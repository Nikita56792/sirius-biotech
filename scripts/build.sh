#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
TOOLS="$SDK/build-tools/36.0.0"
cd "$ROOT"
# Opt-in local native verification build; does not replace the published artifact.
if [ "${1:-}" = "--native-verification" ]; then
  exec python3 -I scripts/build_navigator.py --internal-recordings
fi
python3 -I scripts/build_web.py
if [ ! -d android-demo ]; then python3 -I scripts/prepare.py; fi
# Refresh the launcher resource even when the prepared APK tree already exists.
rm -f android-demo/res/drawable/sirius_icon.jpg
cp app/assets/sirius-app-icon.png android-demo/res/drawable/sirius_icon.png
python3 -I -c 'import xml.etree.ElementTree as E; r=E.parse("android-demo/AndroidManifest.xml").getroot(); a="{http://schemas.android.com/apk/res/android}"; app=r.find("application"); assert r.get("package")=="org.sirius.biotech" and all(x.get(a+"name")=="android.permission.INTERNET" for x in r.findall("uses-permission")) and app.get(a+"name") is None and app.find("activity").get(a+"name")=="org.sirius.biotech.MainActivity", "Incomplete or unsafe demo preparation"'
python3 -I -c 'from pathlib import Path;import shutil; src=Path("app/assets");dst=Path("android-demo/assets");shutil.copytree(src,dst,dirs_exist_ok=True)'
python3 -I scripts/configure_network.py
python3 -I scripts/clean_build.py
mkdir -p dist
python3 -I scripts/compile_original_stubs.py
find app/src -name '*.java' > build/java-sources.txt
javac -source 8 -target 8 -encoding UTF-8 -classpath "$SDK/platforms/android-36/android.jar:build/original-abi.jar" -d build/classes @build/java-sources.txt
jar cf build/demo.jar -C build/classes .
"$TOOLS/d8" --min-api 24 --lib "$SDK/platforms/android-36/android.jar" --output build/dex build/demo.jar
cp build/dex/classes.dex android-demo/classes4.dex
java -jar tools/apktool-download/apktool.jar b android-demo -o build/sirius-unsigned.apk
"$TOOLS/zipalign" -f -p 4 build/sirius-unsigned.apk build/sirius-aligned.apk
if [ ! -f build/demo.keystore ]; then
  keytool -genkeypair -keystore build/demo.keystore -storepass android -keypass android -alias sirius-demo -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Sirius Biotech Local Demo'
fi
"$TOOLS/apksigner" sign --ks build/demo.keystore --ks-pass pass:android --key-pass pass:android --out dist/Sirius-Biotech-2027-demo.apk build/sirius-aligned.apk
"$TOOLS/apksigner" verify --verbose dist/Sirius-Biotech-2027-demo.apk
python3 -I scripts/build_navigator.py
printf '\nAPK: %s/dist/Sirius-Biotech-2027-demo.apk\nAndroid 10+ (API 29). Android TTS replaces extracted recordings.\nPublication requires venue-data rights review; see build/navigator-public/asset-substitutions.json.\n' "$ROOT"
