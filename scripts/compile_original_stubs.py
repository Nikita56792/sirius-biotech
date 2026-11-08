"""Compile the narrow retained-DEX ABI for javac only; never insert it into APKs."""
from pathlib import Path
import subprocess,os
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path(os.getenv('JAVA_HOME','/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home'))/'bin'
SDK=Path(os.getenv('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')))
out=ROOT/'build/original-abi';out.mkdir(parents=True,exist_ok=True)
subprocess.run([str(JAVA/'javac'),'-source','8','-target','8','-classpath',str(SDK/'platforms/android-36/android.jar'),'-d',str(out),*[str(p) for p in (ROOT/'app/compile-stubs').rglob('*.java')]],check=True)
subprocess.run([str(JAVA/'jar'),'cf',str(ROOT/'build/original-abi.jar'),'-C',str(out),'.'],check=True)
