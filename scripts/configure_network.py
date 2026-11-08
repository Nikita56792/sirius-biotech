"""Enable only the app-owned API transport; keep original services unregistered."""
from pathlib import Path
import xml.etree.ElementTree as ET
import shutil
ROOT=Path(__file__).resolve().parent.parent
A='{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android',A[1:-1])
p=ROOT/'android-demo/AndroidManifest.xml'
t=ET.parse(p);r=t.getroot();app=r.find('application')
assert r.get('package')=='org.sirius.biotech' and app.get(A+'name') is None
if not any(x.get(A+'name')=='android.permission.INTERNET' for x in r.findall('uses-permission')):
    r.insert(0,ET.Element('uses-permission',{A+'name':'android.permission.INTERNET'}))
app.set(A+'label','SBS')
app.set(A+'networkSecurityConfig','@xml/sirius_api_network')
app.set(A+'usesCleartextTraffic','false')
app.find('activity').set(A+'theme','@style/SbsLaunchTheme')
app.find('activity').set(A+'screenOrientation','user')
shutil.copytree(ROOT/'app/res',ROOT/'android-demo/res',dirs_exist_ok=True)
t.write(p,encoding='utf-8',xml_declaration=True)
xml=ROOT/'android-demo/res/xml/sirius_api_network.xml'
(ROOT/'android-demo/res/raw').mkdir(exist_ok=True)
shutil.copy2(ROOT/'app/network/school-ca.crt',ROOT/'android-demo/res/raw/sirius_school_ca.crt')
xml.write_text('''<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
  <base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system" /></trust-anchors></base-config>
  <domain-config cleartextTrafficPermitted="false"><domain>10.82.10.8</domain><trust-anchors><certificates src="@raw/sirius_school_ca" /></trust-anchors></domain-config>
  <domain-config cleartextTrafficPermitted="true"><domain>127.0.0.1</domain><domain>localhost</domain></domain-config>
</network-security-config>
''')
