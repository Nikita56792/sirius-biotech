"""Prepare an isolated, offline adaptation; never mutate source inputs."""
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
src = ROOT / 'android-original'
final = ROOT / 'android-demo'
dst = ROOT / 'android-demo-preparing'
if final.exists() or dst.exists():
    raise SystemExit('Preparation target exists; refusing to overwrite')
shutil.copytree(src, dst)
A = '{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android', A[1:-1])
tree = ET.parse(dst / 'AndroidManifest.xml')
m = tree.getroot()
m.set('package', 'org.sirius.biotech')
# No original application, provider, service, receiver or telemetry initializes in demo.
for child in list(m):
    if child.tag not in ('uses-sdk', 'uses-feature'):
        m.remove(child)
app = ET.SubElement(m, 'application', {
    A+'label':'SBS', A+'icon':'@drawable/sirius_icon',
    A+'allowBackup':'false', A+'supportsRtl':'true',
    A+'theme':'@android:style/Theme.Material.Light.NoActionBar',
    A+'usesCleartextTraffic':'false'})
act = ET.SubElement(app, 'activity', {A+'name':'org.sirius.biotech.MainActivity',
    A+'exported':'true', A+'screenOrientation':'portrait', A+'windowSoftInputMode':'adjustResize'})
f = ET.SubElement(act, 'intent-filter')
ET.SubElement(f, 'action', {A+'name':'android.intent.action.MAIN'})
ET.SubElement(f, 'category', {A+'name':'android.intent.category.LAUNCHER'})
tree.write(dst / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
assets = dst / 'assets'
shutil.copy2(ROOT/'source/images/icon.jpg', assets/'sirius_icon.jpg')
shutil.copy2(ROOT/'source/images/theme.jpg.webp', assets/'sirius_theme.webp')
# Canonical sanitized indoor maps survive fresh preparation without navigator installed.
shutil.copytree(ROOT/'app/assets/venue', assets/'venue', dirs_exist_ok=True)
shutil.copy2(ROOT/'app/assets/sirius-app-icon.png', dst/'res/drawable/sirius_icon.png')
# The launcher renders its own completion percentage; suppress the retained
# component's incorrect group-based label while keeping the original resource ID.
import re
for variant in ('values', 'values-ru'):
    strings=dst / 'res' / variant / 'strings.xml'
    content=strings.read_text()
    strings.write_text(re.sub(r'<string name="edit_profile_progress_format">.*?</string>', '<string name="edit_profile_progress_format"> </string>', content))
# Keep resource IDs and original DEX intact. Demo is compiled as an additional DEX.
(dst / '.prepared').write_text('offline-demo-v1\n')
dst.rename(final)
print('Prepared android-demo with original DEX/resources and isolated offline entrypoint')
