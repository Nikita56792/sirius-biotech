#!/usr/bin/env python3
"""Derive floor-two event geometry and routing from the read-only MV assets.

Run with Python -I. Requires numpy, scipy and shapely >= 2. No PDF geometry
or inferred doorways are used. Original SBS binaries are never overwritten.
"""
import argparse
import hashlib
import heapq
import html
import json
import math
import struct
from pathlib import Path

import numpy as np
import shapely
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components
from shapely.geometry import Polygon, LineString
from shapely.ops import unary_union

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path('/Users/nikitasyzdykov/Desktop/mv/navigator/app/app/src/main/assets')
HALLS = {'Зал 1': 'hall-1', 'Зал 3': 'hall-3', 'Зал 5': 'hall-5',
         'Зал 7': 'hall-7', 'Зал 8': 'hall-8', 'Зал «Зима»': 'zima'}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_bin(path):
    raw = path.read_bytes()
    size = struct.unpack_from('<I', raw)[0]
    header = json.loads(raw[4:4+size])
    base = (4+size+3)//4*4
    return {h['k']: np.frombuffer(raw, dtype=np.dtype(h['dtype']).newbyteorder('<'),
            count=h['len']//np.dtype(h['dtype']).itemsize,
            offset=base+h['off']).reshape(h['shape']) for h in header}


def parts(geom):
    if geom.is_empty:
        return []
    if geom.geom_type == 'Polygon':
        return [geom]
    return [p for g in geom.geoms for p in parts(g)] if hasattr(geom, 'geoms') else []


def records(geom, prefix):
    # Keep source float32 coordinates; do not normalize, rotate or quantize.
    return [{'id': f'{prefix}-{i}', 'polygon': list(map(list, p.exterior.coords)),
             'holes': [list(map(list, r.coords)) for r in p.interiors]}
            for i, p in enumerate(parts(geom)) if p.area > 1e-9]


def shape(record):
    return Polygon(record['polygon'], record.get('holes', []))


def grid_cell(grid, x, y):
    r = math.floor((grid['y1']-y)/grid['res'])
    c = math.floor((x-grid['x0'])/grid['res'])
    return r*grid['cols']+c if 0 <= r < grid['rows'] and 0 <= c < grid['cols'] else -1


def xy(grid, cell):
    r, c = divmod(cell, grid['cols'])
    return (grid['x0']+(c+.5)*grid['res'], grid['y1']-(r+.5)*grid['res'])


def decode_grid(data):
    g = data['graph']
    a = np.zeros(g['rows']*g['cols'], dtype=np.uint16)
    for row, runs in enumerate(g['runs']):
        for start, end, room in runs:
            a[row*g['cols']+start:row*g['cols']+end] = room
    return a


def route(data, start, end, grid=None):
    """Strict same-floor MV A*: 8 neighbors, no corner cuts, null on failure.

    Returns original row*cols+col cell ids, including EVERY route vertex.
    No soft-access or nearest-reachable fallback is permitted for closed areas.
    """
    pois = {p['id']: p for p in data['pois']}
    a, b = (pois[k]['entrance']['cell'] for k in (start, end))
    grid = decode_grid(data) if grid is None else grid
    if a is None or b is None or not grid[a] or not grid[b]:
        return None
    cols, rows = data['graph']['cols'], data['graph']['rows']
    br, bc = divmod(b, cols)
    def heuristic(cell):
        r, c = divmod(cell, cols)
        return math.hypot(br-r, bc-c)
    queue, distance, previous = [(heuristic(a), 0., a)], {a: 0.}, {}
    while queue:
        _, cost, cell = heapq.heappop(queue)
        if cost != distance[cell]:
            continue
        if cell == b:
            path = [cell]
            while path[-1] != a:
                path.append(previous[path[-1]])
            return path[::-1]
        r, c = divmod(cell, cols)
        for dr, dc in ((-1,0),(1,0),(0,-1),(0,1),(-1,-1),(-1,1),(1,-1),(1,1)):
            nr, nc = r+dr, c+dc
            if not (0 <= nr < rows and 0 <= nc < cols):
                continue
            nxt = nr*cols+nc
            if not grid[nxt] or (dr and dc and (not grid[r*cols+nc] or not grid[nr*cols+c])):
                continue
            candidate = cost+(math.sqrt(2) if dr and dc else 1)
            if candidate < distance.get(nxt, math.inf):
                distance[nxt], previous[nxt] = candidate, cell
                heapq.heappush(queue, (candidate+heuristic(nxt), candidate, nxt))
    return None


def route_positions(data, cells):
    """Client route contract: absolute model metres, cumulative s, floor 2."""
    result, s = [], 0.
    for cell in cells:
        x, y = xy(data['graph'], cell)
        if result:
            s += math.hypot(x-result[-1]['x'], y-result[-1]['y'])
        result.append({'floor': 2, 'cell': cell, 'x': x, 'y': y, 's': s})
    return {'positions': result, 'length': s, 'time': s/1.2, 'units': 'metre'}


def build(source=SOURCE):
    folder = source/'map'
    meta = json.loads((folder/'meta.json').read_text())
    places = json.loads((folder/'places.json').read_text())
    floor = next(f for f in meta['floors'] if f['n'] == 2)
    center = np.array(meta['center'])
    g = dict(meta['grid'])
    raw_grid = np.fromfile(folder/'nav2.u16', dtype='<u2').reshape(g['rows'], g['cols'])
    mesh = read_bin(folder/'floor2.bin')
    if digest(folder/'floor2.bin') != digest(ROOT/'app/assets/venue/floor2.bin'):
        raise ValueError('SBS floor2.bin is not the expected original MV geometry')
    vertices = mesh['room_xy'].astype(float)+center
    triangles = mesh['room_idx'].reshape(-1, 3)
    # Connected mesh components retain genuine room footprints, including holes.
    i = np.concatenate([triangles[:,0], triangles[:,1], triangles[:,2]])
    j = np.concatenate([triangles[:,1], triangles[:,2], triangles[:,0]])
    _, component = connected_components(coo_matrix((np.ones(len(i)), (i,j)),
                                        shape=(len(vertices),)*2), directed=False)
    groups = {}
    for tri in triangles:
        groups.setdefault(int(component[tri[0]]), []).append(tri)
    room_shapes = {}
    unassigned = []
    for tris in groups.values():
        polys = [Polygon(vertices[t]) for t in tris]
        geom = unary_union(polys)
        counts = {}
        for poly in polys:
            if poly.area <= 1e-9:
                continue
            p = poly.representative_point()
            cell = grid_cell(g, p.x, p.y)
            room = int(raw_grid.flat[cell]) if cell >= 0 else 0
            if room:
                counts[room] = counts.get(room, 0.)+poly.area
        if not counts:
            unassigned.append(geom)
            continue
        room = max(counts, key=counts.get)
        room_shapes.setdefault(room, []).append(geom)
    room_shapes = {k: unary_union(v) for k,v in room_shapes.items()}
    pois, zones, evidence = [], [], []
    for label in floor['labels']:
        if label[2] not in HALLS:
            continue
        x,y = np.array(label[:2])+center
        cell = grid_cell(g, x, y)
        room = int(raw_grid.flat[cell])
        place = next(p for p in places['rooms'] if p['id'] == room and p['floor'] == 2)
        if label[2] not in place['nums'] or room not in room_shapes:
            raise ValueError('Cannot verify hall identity: '+label[2])
        id_ = HALLS[label[2]]
        evidence.append({'poiId': id_, 'roomId': room, 'label': label[2],
                         'localLabel': label[:2], 'absoluteLabel': [x,y],
                         'sourceCell': cell, 'placeNumberMatch': True,
                         'sourceReachable': place['reach']})
        for z in records(room_shapes[room], id_):
            z.update(poiId=id_, category='hall', label=label[2], roomId=room)
            zones.append(z)
        pois.append({'id':id_, 'nameRU':label[2], 'nameEN':None, 'category':'hall',
                     'floor':2, 'roomId':room, 'x':x, 'y':y, 'zoneId':id_,
                     'accessible':None, 'eventAssignment':None,
                     'entrance':{'cell':cell, 'x':x, 'y':y, 'kind':'source-label-in-room'}})
    event_ids = {p['roomId'] for p in pois}
    titles = {r[0]:r[2] for r in meta['rooms'] if r[1] == 2}
    circulation_ids = {k for k,v in titles.items() if any(s in v.lower() for s in
                       ('коридор', 'открытое пространство', 'лифтовой холл', 'фойе', 'холл. стойка'))}
    # The MV model merges some utility spaces into corridor room ids. Keep the
    # actual footprint, not guessed PDF boundaries, and publish that limitation.
    circulation_ids -= event_ids
    event_geom = unary_union([room_shapes[k] for k in event_ids])
    circulation = unary_union([v for k,v in room_shapes.items() if k in circulation_ids])
    wall_vertices = mesh['wall_xy'].astype(float)+center
    wall_triangles = mesh['wall_idx'].reshape(-1,3)
    walls = unary_union([Polygon(wall_vertices[t]) for t in wall_triangles])
    all_rooms = unary_union(list(room_shapes.values())+unassigned)
    footprint = unary_union([all_rooms, walls])
    closed = footprint.difference(unary_union([event_geom, circulation]))
    # Erase enclosed sub-room wall/shaft cutouts in closed masses without moving
    # their exterior boundary or filling a circulation/event footprint.
    closed = unary_union([Polygon(p.exterior, [r.coords for r in p.interiors
                           if Polygon(r).area >= 25]) for p in parts(closed)])
    closed = closed.difference(unary_union([event_geom, circulation]))
    # Dissolving rooms WITH their original wall footprints removes partitions;
    # never draw the raw wall/door/furniture mesh underneath these solid masks.
    blocks = records(closed, 'closed')
    visible_walls = walls.intersection(event_geom.buffer(.5))
    layers = {'circulation': records(circulation, 'circulation'),
              'eventWalls': records(visible_walls, 'wall'), 'eventLines': {}}
    for key in ('door_xy','stair_xy','furn_xy','edit_xy'):
        lines = []
        for seg in mesh[key].reshape(-1,2,2):
            clipped = LineString(seg.astype(float)+center).intersection(event_geom)
            if clipped.geom_type == 'LineString' and not clipped.is_empty:
                lines.append(list(map(list,clipped.coords)))
            elif clipped.geom_type == 'MultiLineString':
                lines.extend(list(map(list,s.coords)) for s in clipped.geoms)
        layers['eventLines'][key] = lines
    allowed = event_ids | circulation_ids
    nav = np.where(np.isin(raw_grid, list(allowed)), raw_grid, 0).astype(np.uint16)
    rr, cc = np.nonzero(nav)
    xx = g['x0']+(cc+.5)*g['res']; yy = g['y1']-(rr+.5)*g['res']
    # Cell squares must fit wholly inside the original traversable footprint;
    # a circumscribed-radius buffer also prevents diagonal wall intersections.
    safety = g['res']/math.sqrt(2)+1e-6
    forbidden = unary_union([closed, walls]).buffer(safety)
    shapely.prepare(forbidden)
    bad = shapely.contains_xy(forbidden, xx, yy)
    nav[rr[bad],cc[bad]] = 0
    # Preserve access restrictions conservatively: no event permission inferred.
    # Closing every source restricted room and every line is stricter than MV's
    # pass-level/one-way policy and cannot open an undocumented event passage.
    restricted = {r['room'] for r in meta['access_rooms'] if r['floor']==2 and r['level']>0}
    nav[np.isin(nav, list(restricted))] = 0
    access_shapes = []
    for line in meta['access']:
        if line['floor'] == 2 and line['level'] > 0 and len(line['pts']) >= 2:
            access_shapes.append(LineString(line['pts']).buffer(.45+safety))
    if access_shapes:
        rr,cc = np.nonzero(nav)
        bad = shapely.contains_xy(unary_union(access_shapes),
                g['x0']+(cc+.5)*g['res'], g['y1']-(rr+.5)*g['res'])
        nav[rr[bad],cc[bad]] = 0
    runs = []
    for row in nav:
        changes = np.flatnonzero(np.diff(np.r_[0,row,0].astype(np.int32)))
        runs.append([[int(a),int(b),int(row[a])] for a,b in zip(changes,changes[1:]) if row[a]])
    for p in pois:
        cell = p['entrance']['cell']
        if not nav.flat[cell]:
            p['entrance']['cell'] = None
            p['entrance']['unavailableReason'] = 'source-label-cell-closed; no invented snapping'
    source_files = ['map/meta.json','map/places.json','map/floor2.bin','map/nav2.u16',
                    'map/stairs.json','voice_variants.json','sounds/durations.json']
    code = 'app/app/src/main/java/com/gmcnav/navi/'
    source_root = source.parents[4]
    files = {name:digest(source/name) for name in source_files}
    for name in ('MapData.kt','NaviMapView.kt','core/Router.kt','core/Guidance.kt'):
        path = source_root/code/name
        if path.exists():
            files[name] = digest(path)
    return {'version':2, 'id':'mv-gmc-floor2-event', 'floor':2,
            'titleRU':'НТУ «Сириус» · этаж 2',
            'source':{'geometry':'original-mv-mesh', 'root':str(source), 'sha256':files,
                      'assignmentStatus':'event-names-unverified', 'hallEvidence':evidence,
                      'transformation':'absolute = original local float32 + center; no PDF transform',
                      'unassignedMeshComponents':len(unassigned)},
            'coordinates':{'space':'mv-absolute-plan','units':'metre','axisY':'up',
                           'center':meta['center'], 'metresPerUnit':1,
                           'bounds':list(footprint.bounds)},
            'style':{'surface':'#ffffff','blocked':'#dcefea','highlight':'#1baf7a',
                     'route':'#2a78d6','ink':'#102d2c','wall':'#51645d'},
            'blocked':blocks,'zones':zones,'pois':pois,'geometry':layers,
            'graph':dict(g, type='grid-rle-u16', floor=2, directed=False,
                         weightUnit='metre', runs=runs, diagonalCornerCut=False),
            'navigation':{'algorithm':'astar-8-no-corner-cut','walkingSpeedMps':1.2,
                          'startMode':'manual-poi-or-verified-position','locationTracking':False,
                          'distanceDisplay':'metres-model-not-survey','wallClearanceMetres':safety,
                          'accessPolicy':'all-source-restrictions-closed',
                          'accessibilityVerified':False, 'noRouteFallback':True},
            'gui':{'floorSelector':False,'searchPOIsOnly':True,
                   'navigatorReference':'NaviMapView.kt + core/Guidance.kt; clients must implement v2'},
            'uncertainties':['Event/PDF hall aliases and service assignments are NOT verified; none are rendered.',
               'Original merged corridor room ids may also contain utility spaces; no fabricated subdivision is applied.',
               'Five numbered hall labels have reach=false in source places.json. Disconnected routes stay unavailable.',
               'Conservative wall and access masks can remove additional routes; no inferred door openings.',
               'Metres are source model units, not an on-site safety or accessibility survey.',
               'No GPS, inertial positioning or moving route arrow is simulated.']}


def svg(data):
    x0,y0,x1,y1 = data['coordinates']['bounds']
    def paths(items, fill):
        output = []
        for item in items:
            d = ' '.join('M '+' L '.join(f'{x},{y}' for x,y in ring)+' Z'
                         for ring in [item['polygon']]+item.get('holes',[]))
            output.append(f'<path d="{d}" fill="{fill}" fill-rule="evenodd"/>')
        return output
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{x0} {-y1} {x1-x0} {y1-y0}" role="img" aria-labelledby="title desc">',
           '<title id="title">Original MV map · floor 2</title>',
           '<desc id="desc">Original model geometry. Six verified hall labels highlighted; solid closed areas. Event aliases unverified.</desc>',
           '<rect x="-1000" y="-1000" width="3000" height="3000" fill="white"/>',
           '<g transform="scale(1,-1)">']
    out += paths(data['geometry']['circulation'], '#f4f6f5')
    out += paths(data['blocked'], data['style']['blocked'])
    out += paths(data['zones'], data['style']['highlight'])
    out += paths(data['geometry']['eventWalls'],data['style']['wall'])
    for lines in data['geometry']['eventLines'].values():
        for seg in lines:
            out.append('<polyline points="'+' '.join(f'{x},{y}' for x,y in seg)+'" fill="none" stroke="#51645d" stroke-width="0.08"/>')
    out.append('</g>')
    for p in data['pois']:
        out.append(f'<text x="{p["x"]}" y="{-p["y"]}" text-anchor="middle" font-family="sans-serif" font-size="2.5" fill="#102d2c">{html.escape(p["nameRU"])}</text>')
    out.append('</svg>')
    return '\n'.join(out)+'\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=SOURCE)
    parser.add_argument('--output',type=Path,default=ROOT/'app/assets/venue/event-map.json')
    parser.add_argument('--svg',type=Path,default=ROOT/'app/assets/venue/event-map.svg')
    args = parser.parse_args()
    data = build(args.source)
    args.output.write_text(json.dumps(data, ensure_ascii=False, separators=(',',':'))+'\n')
    args.svg.write_text(svg(data))
    print(f'{len(data["pois"])} verified halls; {len(data["zones"])} zone polygons; '
          f'{len(data["blocked"])} dissolved closed footprints; '
          f'{np.count_nonzero(decode_grid(data))} original routing cells retained')


if __name__ == '__main__':
    main()
