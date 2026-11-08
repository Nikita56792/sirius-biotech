#!/usr/bin/env python3
"""Build the attendee PWA and compile its shared Android navigation core."""
from pathlib import Path
import json
import hashlib
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
for destination in (ROOT/'web/src/photo-upload.js',ROOT/'server/static/photo-upload.js'):
 shutil.copy2(ROOT/'shared/photo-upload.js',destination)
subprocess.run([sys.executable,'-I',str(ROOT/'web/build.py')],check=True)
out=ROOT/'web/dist';assets=out/'assets';assets.mkdir(parents=True,exist_ok=True)
shutil.copytree(ROOT/'app/assets',assets,dirs_exist_ok=True)
subprocess.run([sys.executable,'-I',str(ROOT/'scripts/build_web_navigator.py')],check=True)
config=json.loads((ROOT/'shared/app.json').read_text())
A='{http://schemas.android.com/apk/res/android}'
S='{http://www.w3.org/2000/svg}'
ET.register_namespace('',S[1:-1])
for section in config['sections']:
 source=ROOT/'android-original/res/drawable'/ (section['drawable']+'.xml')
 vector=ET.parse(source).getroot()
 svg=ET.Element(S+'svg',{'viewBox':'0 0 '+vector.get(A+'viewportWidth','24')+' '+vector.get(A+'viewportHeight','24'),'fill':'none'})
 for path in vector.iter('path'):
  attributes={'d':path.get(A+'pathData','')}
  fill=path.get(A+'fillColor')
  if fill and fill not in ('#00000000','@android:color/transparent'):attributes['fill']='#2c333b'
  if path.get(A+'strokeColor'):
   attributes.update(stroke='#2c333b',**{'stroke-width':path.get(A+'strokeWidth','1.5')})
  ET.SubElement(svg,S+'path',attributes)
 icon=section['id']+'.svg';ET.ElementTree(svg).write(assets/icon,encoding='utf-8',xml_declaration=True)
 section['icon']='/app/assets/'+icon
(out/'shared.json').write_text(json.dumps(config,ensure_ascii=False,indent=2))
for size,name,source in ((192,'icon-192.png','sirius-app-icon.png'),(512,'icon-512.png','sirius-app-icon.png'),(180,'apple-touch-icon.png','sirius-apple-touch-icon.png')):
 subprocess.run(['sips','-s','format','png','-z',str(size),str(size),str(ROOT/'app/assets'/source),'--out',str(assets/name)],check=True,stdout=subprocess.DEVNULL)
shutil.copy2(ROOT/'shared/app.json',ROOT/'app/assets/shared.json')
subprocess.run([sys.executable,'-I',str(ROOT/'scripts/export_android_ui.py')],check=True)
build_hash=hashlib.sha256()
for path in sorted(out.rglob('*')):
 if path.is_file() and path.name!='sw.js':
  build_hash.update(path.relative_to(out).as_posix().encode());build_hash.update(path.read_bytes())
(out/'sw.js').write_text((out/'sw.js').read_text().replace('__BUILD__',build_hash.hexdigest()[:16]).replace('__SPEAKER_PHOTOS__',json.dumps(list(json.loads((ROOT/'server/fixtures/speaker-photos.json').read_text()).values()))))
print('PWA built:',out)
