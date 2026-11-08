"""Build native integration with unchanged original recordings and drawable assets."""
from pathlib import Path
import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile
import struct
import sys
import json
import hashlib

# Original recordings and geometry are required for this candidate.
INTERNAL = True
if any(a != '--internal-recordings' for a in sys.argv[1:]):
    raise SystemExit('Usage: build_navigator.py [--internal-recordings]')

ROOT = Path(__file__).resolve().parent.parent
SDK = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
JAVA = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home')) / 'bin'
TOOLS = SDK / 'build-tools/36.0.0'
ANDROID = SDK / 'platforms/android-36/android.jar'
MODULE = ROOT / 'navigator/src/main'
OUT = ROOT / ('build/navigator-internal' if INTERNAL else 'build/navigator-public')
STAGE = OUT / 'apk'
A = '{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android', A[1:-1])

def run(*args):
    subprocess.run([str(a) for a in args], check=True, cwd=ROOT)

def jar(group, artifact, version):
    files = list((Path.home() / '.gradle/caches/modules-2/files-2.1' / group / artifact / version).glob('*/*.jar'))
    if len(files) != 1:
        raise RuntimeError(f'Missing or ambiguous cached dependency: {artifact}:{version}')
    return files[0]

def descriptors(data):
    ns, so = struct.unpack_from('<II', data, 56)
    nt, to = struct.unpack_from('<II', data, 64)
    nc, co = struct.unpack_from('<II', data, 96)
    def string(i):
        off = struct.unpack_from('<I', data, so + 4*i)[0]
        while data[off] & 128:
            off += 1
        off += 1
        return data[off:data.index(0, off)].decode()
    types = [string(struct.unpack_from('<I', data, to + 4*i)[0]) for i in range(nt)]
    return {types[struct.unpack_from('<I', data, co + 32*i)[0]] for i in range(nc)}

compiler = jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '1.9.24')
stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.24')
cp = [compiler, stdlib, jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '1.9.24'),
      jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
      jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'), jar('org.jetbrains', 'annotations', '13.0')]
if OUT.exists():
    shutil.rmtree(OUT)
OUT.mkdir(parents=True)
shutil.copytree(ROOT / 'android-demo', STAGE, ignore=shutil.ignore_patterns('build', '.DS_Store'))
# All verification changes are staged, preserving the known working build.
for f in STAGE.glob('classes*.dex'):
    if f.name not in ('classes.dex', 'classes2.dex', 'classes3.dex'):
        f.unlink()
for f in (MODULE / 'res').rglob('*'):
    if f.is_file():
        rel = f.relative_to(MODULE / 'res')
        dest = STAGE / 'res' / rel.parent / ('sbs_nav_' + rel.name)
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(f.read_bytes())
shutil.copytree(ROOT / 'app/assets', STAGE / 'assets', dirs_exist_ok=True)
shutil.copytree(MODULE / 'assets', STAGE / 'assets', dirs_exist_ok=True)
for marker in ('sbs-public-voice', 'native-asset-substitutions.json'):
    (STAGE / 'assets' / marker).unlink(missing_ok=True)
(STAGE / 'assets/venue').mkdir(exist_ok=True)
shutil.copy2(ROOT / 'app/assets/venue/event-map.json', STAGE / 'assets/venue/event-map.json')
tree = ET.parse(STAGE / 'AndroidManifest.xml')
m = tree.getroot()
permissions = {'INTERNET', 'ACCESS_WIFI_STATE', 'CHANGE_WIFI_STATE', 'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'NEARBY_WIFI_DEVICES', 'FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_MEDIA_PLAYBACK', 'POST_NOTIFICATIONS'}
for p in list(m.findall('uses-permission')):
    m.remove(p)
for permission in sorted(permissions):
    ET.SubElement(m, 'uses-permission', {A+'name': 'android.permission.'+permission})
ET.SubElement(m, 'uses-feature', {A+'glEsVersion': '0x00030000', A+'required': 'true'})
for feature in ('wifi', 'sensor.barometer'):
    ET.SubElement(m, 'uses-feature', {A+'name':'android.hardware.'+feature, A+'required':'false'})
app = m.find('application')
assert app.get(A+'name') is None
assert not any(app.findall(tag) for tag in ('service', 'receiver', 'provider'))
ET.SubElement(app, 'service', {A+'name': 'com.gmcnav.navi.NavigationService', A+'exported': 'false', A+'foregroundServiceType': 'mediaPlayback'})
app.set(A+'largeHeap', 'true')
ET.SubElement(app, 'activity', {A+'name':'com.gmcnav.navi.MainActivity', A+'exported':'false',
    A+'theme':'@style/SbsNavAppTheme', A+'screenOrientation':'user', A+'windowSoftInputMode':'adjustResize',
    A+'configChanges':'orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden'})
tree.write(STAGE / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
yml = STAGE / 'apktool.yml'
yml.write_text(re.sub(r'minSdkVersion: \d+', 'minSdkVersion: 29', yml.read_text()))
run(JAVA/'java', '-jar', ROOT/'tools/apktool-download/apktool.jar', 'b', STAGE, '-o', OUT/'resources.apk')
dump = subprocess.check_output([str(TOOLS/'aapt2'), 'dump', 'resources', str(OUT/'resources.apk')], text=True)
symbols = {}
for ident, typ, name in re.findall(r'resource (0x[0-9a-f]+) (\w+)/([\w.]+)', dump):
    symbols.setdefault(typ, {})[name.removeprefix('sbs_nav_').replace('.', '_')] = ident
r = OUT/'r/com/gmcnav/navi/R.java'
r.parent.mkdir(parents=True)
r.write_text('package com.gmcnav.navi; public final class R {\n' + ''.join(
    'public static final class '+typ+' {\n'+''.join('public static final int '+name+' = '+ident+';\n' for name, ident in names.items())+'}\n'
    for typ, names in symbols.items())+'}\n')
classes = OUT/'classes'
classes.mkdir()
run(JAVA/'javac', '-source', '17', '-target', '17', '-d', classes, r)
run(JAVA/'java', '-Xmx3g', '-cp', os.pathsep.join(map(str, cp)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', os.pathsep.join(map(str, [ANDROID, stdlib, classes])),
    '-d', classes, *sorted((MODULE/'java').rglob('*.kt')))
run(JAVA/'jar', 'cf', OUT/'native.jar', '-C', classes, '.')
run(JAVA/'javac', '-cp', compiler, '-d', OUT, ROOT/'scripts/RelocateNavigator.java')
run(JAVA/'java', '-cp', os.pathsep.join(map(str, [OUT, compiler])), 'RelocateNavigator', OUT/'isolated.jar', OUT/'native.jar', stdlib)
dex = OUT/'dex'
dex.mkdir()
run(TOOLS/'d8', '--min-api', '29', '--lib', ANDROID, '--output', dex, OUT/'isolated.jar')
run(sys.executable, '-I', ROOT/'scripts/compile_original_stubs.py')
# Compile the SBS launcher against a staged source substitution, leaving release unchanged.
launcher = OUT/'launcher'
shutil.copytree(ROOT/'app/src', launcher)
main = launcher/'org/sirius/biotech/MainActivity.java'
s = main.read_text()
old = 'setContentView(VenueMaps.create(this));'
assert s.count(old) == 1
main.write_text(s.replace(old, 'startActivity(new Intent().setClassName(this,"com.gmcnav.navi.MainActivity").addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));'))
jclasses = OUT/'java-classes'
jclasses.mkdir()
run(JAVA/'javac', '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-classpath', str(ANDROID)+os.pathsep+str(ROOT/'build/original-abi.jar'), '-d', jclasses, *sorted(launcher.rglob('*.java')))
run(JAVA/'jar', 'cf', OUT/'launcher.jar', '-C', jclasses, '.')
jdex = OUT/'java-dex'
jdex.mkdir()
run(TOOLS/'d8', '--min-api', '29', '--lib', ANDROID, '--output', jdex, OUT/'launcher.jar')
# Freeze resources after R generation: inject DEX only, store MP3 for openFd.
unsigned = OUT/'unsigned.apk'
with zipfile.ZipFile(OUT/'resources.apk') as src, zipfile.ZipFile(unsigned, 'w') as dst:
    for info in src.infolist():
        if info.filename.startswith('META-INF/'):
            continue
        data = src.read(info.filename)
        if info.filename.endswith('.mp3'):
            info.compress_type = zipfile.ZIP_STORED
        dst.writestr(info, data)
    dst.write(jdex/'classes.dex', 'classes4.dex')
    for i, f in enumerate(sorted(dex.glob('classes*.dex')), 5):
        dst.write(f, f'classes{i}.dex')
with zipfile.ZipFile(unsigned) as z:
    seen = set()
    for name in z.namelist():
        if re.fullmatch(r'classes\d*\.dex', name):
            defs = descriptors(z.read(name))
            assert not seen.intersection(defs), f'Duplicate DEX descriptors in {name}'
            seen.update(defs)
    assert 'Lcom/gmcnav/navi/MainActivity;' in seen
    assert all(i.compress_type == zipfile.ZIP_STORED for i in z.infolist() if i.filename.endswith('.mp3'))
run(TOOLS/'zipalign', '-f', '-p', '4', unsigned, OUT/'aligned.apk')
run(TOOLS/'apksigner', 'sign', '--ks', ROOT/'build/demo.keystore', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', OUT/'SBS-native-verification.apk', OUT/'aligned.apk')
run(TOOLS/'apksigner', 'verify', '--verbose', OUT/'SBS-native-verification.apk')
artifact = OUT/'SBS-native-verification.apk'
if INTERNAL:
    target = ROOT/'dist/Sirius-Biotech-2027-demo.apk'
    target.parent.mkdir(exist_ok=True)
    shutil.copy2(artifact, target)
    artifact = target
print('ORIGINAL RECORDINGS NATIVE CANDIDATE (rights review still required):', artifact)
print('SHA256:', hashlib.sha256(artifact.read_bytes()).hexdigest())
