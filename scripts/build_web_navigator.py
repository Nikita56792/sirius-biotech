#!/usr/bin/env python3
"""Compile the Android navigator core to browser JavaScript (no rewritten router).

Only JVM platform facilities are adapted in a disposable staging directory.
The navigation/search/planner/maneuver/voice algorithms remain canonical Kotlin.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import urllib.request
import gzip
import wave
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / 'navigator/src/main/java/com/gmcnav/navi/core'
BRIDGE = ROOT / 'web/navigator'
OUT = ROOT / 'build/web-navigator'
DEST = ROOT / 'web/dist/navigator'
VERSION = '1.9.24'
STDLIB_SHA256 = 'a5502b8c40c3f85c805a87403a85e825e8736c69cce471e83704cbe22c4dd72b'
SOURCES = ['Building', 'Router', 'Planner', 'Places', 'Maneuvers', 'Guidance', 'Json', 'Vertical', 'Categories', 'StepDetector']


def cached(group, artifact, version):
    files = list((Path.home() / '.gradle/caches/modules-2/files-2.1' / group / artifact / version).glob('*/*.jar'))
    if len(files) != 1:
        raise RuntimeError(f'Missing cached compiler dependency: {artifact}:{version}')
    return files[0]


def platform_source(name):
    text = (CORE / f'{name}.kt').read_text()
    text = re.sub(r'^import java\..*\n', '', text, flags=re.M)
    text = text.replace('@Volatile ', '').replace('@Synchronized', '')
    step = re.search(r'const val STEP_LEN = ([\d.]+)', (CORE / 'Positioning.kt').read_text()).group(1)
    text = text.replace('PositionEngine.STEP_LEN', step)
    text = re.sub(r'synchronized\((?:router|this)\)\s*\{', 'run {', text)
    text = text.replace('.bufferedReader(Charsets.UTF_8).readText()', '.readText()')
    text = text.replace('String.format("\\\\u%04x", c.code)', '"\\\\u" + c.code.toString(16).padStart(4, \'0\')')
    if name == 'Building':
        text = re.sub(r'fun interface AssetSource.*?(?=class FloorInfo)', '', text, flags=re.S)
        old = '''val bytes = src.open("map/nav${floors[fi].n}.u16").use { it.readBytes() }
            val sa = ShortArray(rows * cols)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sa)
            sa'''
        assert old in text
        text = text.replace(old, 'src.shorts("map/nav${floors[fi].n}.u16")')
    if name == 'Vertical':
        text = text[:text.index('class VerticalTracker')]
    if name == 'Router':
        # Identical rank bitset, using the browser's native 32-bit bitwise word.
        # Avoid boxed Kotlin Long allocations for every expanded grid neighbour.
        substitutions = {
            '(nCells + 63) / 64': '(nCells + 31) / 32',
            'LongArray(words)': 'IntArray(words)',
            'var word = 0L': 'var word = 0',
            'val c0 = w * 64': 'val c0 = w * 32',
            'c0 + 64': 'c0 + 32',
            '(1L shl (c - c0))': '(1 shl (c - c0))',
            'java.lang.Long.bitCount(word)': 'word.countOneBits()',
            'cell ushr 6': 'cell ushr 5',
            '1L shl (cell and 63)': '1 shl (cell and 31)',
            'word and bit == 0L': 'word and bit == 0',
            'java.lang.Long.bitCount(word and (bit - 1))': '(word and (bit - 1)).countOneBits()',
        }
        for old, new in substitutions.items():
            assert old in text, f'Router platform adapter no longer matches: {old}'
            text = text.replace(old, new)
    return text


def build():
    stage = OUT / 'src'
    stage.mkdir(parents=True, exist_ok=True)
    DEST.mkdir(parents=True, exist_ok=True)
    manifest = {'compiler': VERSION, 'sources': {}, 'platform': 'Kotlin/JS IR, browser worker'}
    for name in SOURCES:
        source = CORE / f'{name}.kt'
        manifest['sources'][str(source.relative_to(ROOT))] = hashlib.sha256(source.read_bytes()).hexdigest()
        (stage / source.name).write_text(platform_source(name))
    for source in [CORE / 'Positioning.kt', *sorted(BRIDGE.glob('*.kt'))]:
        manifest['sources'][str(source.relative_to(ROOT))] = hashlib.sha256(source.read_bytes()).hexdigest()
    positioning = (CORE / 'Positioning.kt').read_text()
    positioning = positioning[positioning.index('class Fix('):positioning.index('/**\n * Floor transitions')]
    step = re.search(r'const val STEP_LEN = ([\d.]+)', (CORE / 'Positioning.kt').read_text()).group(1)
    positioning = positioning.replace('PositionEngine.STEP_LEN', step)
    (stage / 'Fix.kt').write_text('package com.gmcnav.navi.core\nimport kotlin.math.*\n' + positioning)
    dependencies = [cached('org.jetbrains.kotlin', a, v) for a, v in [
        ('kotlin-compiler-embeddable', VERSION), ('kotlin-stdlib', VERSION),
        ('kotlin-script-runtime', VERSION), ('kotlin-reflect', '1.6.10')]]
    dependencies += [cached('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'), cached('org.jetbrains', 'annotations', '13.0')]
    library = ROOT / f'tools/kotlin-js/kotlin-stdlib-js-{VERSION}.klib'
    if not library.exists():
        library.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(f'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-js/{VERSION}/kotlin-stdlib-js-{VERSION}.klib', library)
    manifest['stdlibSha256'] = hashlib.sha256(library.read_bytes()).hexdigest()
    manifest['compilerSha256'] = hashlib.sha256(dependencies[0].read_bytes()).hexdigest()
    if manifest['stdlibSha256'] != STDLIB_SHA256:
        raise RuntimeError('Kotlin/JS stdlib checksum mismatch')
    java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home')) / 'bin/java'
    compiled = OUT / 'compiled'
    compiled.mkdir(exist_ok=True)
    subprocess.run([str(java), '-Xmx3g', '-cp', os.pathsep.join(map(str, dependencies)),
                    'org.jetbrains.kotlin.cli.js.K2JSCompiler', '-Xir-produce-js', '-Xir-dce',
                    '-libraries', str(library), '-ir-output-dir', str(compiled), '-ir-output-name', 'core',
                    '-module-kind', 'es', '-main', 'noCall',
                    *map(str, sorted(stage.glob('*.kt'))), *map(str, sorted(BRIDGE.glob('*.kt')))], check=True)
    shutil.copy2(compiled / 'core.mjs', DEST / 'core.js')
    (DEST / 'core.mjs').unlink(missing_ok=True)
    assets = ROOT / 'navigator/src/main/assets'
    target = DEST / 'map'
    target.mkdir(exist_ok=True)
    # The exact same floor geometry and routing inputs as the shipped native APK.
    for source in (assets / 'map').iterdir():
        if source.is_file():
            shutil.copy2(source, target / source.name)
            if source.suffix == '.u16':
                (target / (source.name + '.gz')).write_bytes(gzip.compress(source.read_bytes(), mtime=0))
    voice = DEST / 'sounds'
    shutil.copytree(assets / 'sounds', voice, dirs_exist_ok=True)
    shutil.copy2(assets / 'voice_variants.json', DEST / 'voice_variants.json')
    icons = DEST / 'icons'
    icons.mkdir(exist_ok=True)
    A = '{http://schemas.android.com/apk/res/android}'
    def color(value):
        if len(value) == 9:
            return '#' + value[3:] + value[1:3]
        return value
    for source in (ROOT / 'navigator/src/main/res/drawable').glob('*.xml'):
        original = ET.parse(source).getroot()
        if original.tag != 'vector':
            continue
        for white in (False, True):
            svg = ET.Element('svg', {'xmlns': 'http://www.w3.org/2000/svg', 'viewBox': f'0 0 {original.get(A+"viewportWidth")} {original.get(A+"viewportHeight")}', 'fill': 'none'})
            def append(original_node, svg_parent):
                if original_node.tag == 'group':
                    get = lambda name, default: original_node.get(A+name, str(default))
                    px, py = float(get('pivotX', 0)), float(get('pivotY', 0))
                    tx, ty = float(get('translateX', 0)), float(get('translateY', 0))
                    group = ET.SubElement(svg_parent, 'g', {'transform': f"translate({tx+px} {ty+py}) rotate({get('rotation',0)}) scale({get('scaleX',1)} {get('scaleY',1)}) translate({-px} {-py})"})
                    for child in original_node: append(child, group)
                    return
                if original_node.tag != 'path':
                    raise ValueError(f'Unsupported navigator vector node: {original_node.tag}')
                p = original_node
                attrs = {'d': p.get(A+'pathData')}
                attrs['fill-rule'] = 'evenodd' if p.get(A+'fillType') == 'evenOdd' else 'nonzero'
                for src, dest in [('fillColor','fill'), ('strokeColor','stroke')]:
                    v = p.get(A+src)
                    if v:
                        full_color = source.stem in ('ya_compass', 'ya_no_entry') and not white
                        attrs[dest] = color(v) if full_color else ('#fff' if white else '#5a626e' if source.stem == 'ya_lock' else '#4d4d4d') if v not in ('#00000000', '@android:color/transparent') else 'none'
                        if not full_color and v.startswith('#') and len(v) == 9:
                            attrs[dest+'-opacity'] = str(int(v[1:3],16)/255 * float(p.get(A+('fillAlpha' if dest=='fill' else 'strokeAlpha'), '1')))
                for src, dest in [('strokeWidth','stroke-width'),('strokeLineCap','stroke-linecap'),('strokeLineJoin','stroke-linejoin'),('fillAlpha','fill-opacity'),('strokeAlpha','stroke-opacity')]:
                    if p.get(A+src) is not None and dest not in attrs: attrs[dest] = p.get(A+src)
                ET.SubElement(svg_parent, 'path', attrs)
            for child in original: append(child, svg)
            (icons / (source.stem + ('_white' if white else '') + '.svg')).write_text(ET.tostring(svg, encoding='unicode'))
    orange = ET.parse(icons / 'ya_lock.svg').getroot()
    for p in orange.iter():
        for key in ('fill','stroke'):
            if p.get(key) not in (None,'none'): p.set(key,'#e65100')
    (icons / 'ya_lock_orange.svg').write_text(ET.tostring(orange,encoding='unicode'))
    shutil.copy2(ROOT / 'tools/fonts/Roboto.ttf', DEST / 'Roboto.ttf')
    shutil.copy2(ROOT / 'tools/fonts/LICENSE', DEST / 'FONT-LICENSE.txt')
    shutil.copy2(ROOT / 'tools/fonts/NOTICE', DEST / 'FONT-NOTICE.txt')
    with wave.open(str(DEST / 'silence.wav'), 'wb') as audio:
        audio.setnchannels(1); audio.setsampwidth(2); audio.setframerate(8000); audio.writeframes(b'\0' * 1600)
    manifest['assets'] = {str(p.relative_to(assets)): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in sorted(assets.rglob('*')) if p.is_file() and p.parts[-2] != 'radio'
                          and (p.is_relative_to(assets / 'map') or p.is_relative_to(assets / 'sounds') or p.name == 'voice_variants.json')}
    (DEST / 'provenance.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    public_files = ['/app/' + p.relative_to(ROOT / 'web/dist').as_posix() for p in sorted(DEST.rglob('*'))
                    if p.is_file() and p.suffix != '.u16']
    (DEST / 'precache.json').write_text(json.dumps(public_files))
    print('Compiled Android navigator core:', DEST / 'core.js')


if __name__ == '__main__':
    build()
