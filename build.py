import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parent
SDK_PATH = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
SDK = Path(SDK_PATH) if SDK_PATH else Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk'
BUILD_TOOLS = SDK / 'build-tools/36.0.0'
ANDROID_JAR = SDK / 'platforms/android-35/android.jar'
JAVA_SETTINGS = subprocess.run(
    ['java', '-XshowSettings:properties', '-version'], capture_output=True, text=True, check=True)
JAVA_HOME = next(line.split('=', 1)[1].strip()
                 for line in JAVA_SETTINGS.stderr.splitlines() if 'java.home =' in line)
JAVA_BIN = Path(JAVA_HOME) / 'bin'
BUILD = ROOT / 'build'
DIST = ROOT / 'dist'
API = ROOT / 'tools/xposed-api-82.jar'


def run(*args):
    command = [str(arg) for arg in args]
    print('+', ' '.join(command), flush=True)
    subprocess.run(command, cwd=ROOT, check=True)


def main():
    key = ROOT / 'keys/mipad.p12'
    if not key.is_file():
        raise FileNotFoundError(f'Signing keystore required: {key}. See README.md.')
    for name in ['MIPAD_KEYSTORE_PASSWORD', 'MIPAD_KEY_PASSWORD']:
        if not os.environ.get(name):
            raise ValueError(f'Signing environment variable required: {name}')

    run(sys.executable, '-m', 'unittest', 'discover', '-s', 'tests', '-p', 'test_*.py')

    if BUILD.exists():
        try:
            shutil.rmtree(BUILD)
        except OSError as error:
            raise RuntimeError(f'Failed to clear build directory: {BUILD}') from error
    for directory in [BUILD / 'classes', BUILD / 'tests', BUILD / 'dex', DIST]:
        directory.mkdir(parents=True, exist_ok=True)

    sources = sorted((ROOT / 'app/src').rglob('*.java'))
    run(JAVA_BIN / 'javac.exe', '--release', '8', '-Xlint:all', '-cp',
        f'{ANDROID_JAR}{os.pathsep}{API}', '-d', BUILD / 'classes', *sources)
    run(JAVA_BIN / 'javac.exe', '--release', '8', '-Xlint:all', '-d', BUILD / 'tests',
        ROOT / 'app/src/dev/mipad/guidedaccess/VolumeChord.java',
        ROOT / 'app/src/dev/mipad/guidedaccess/AccessState.java',
        *sorted((ROOT / 'tests').rglob('*.java')))
    for name in ['VolumeChordTest', 'AccessStateTest']:
        run(JAVA_BIN / 'java.exe', '-cp', BUILD / 'tests', f'dev.mipad.guidedaccess.{name}')

    classes = BUILD / 'classes.jar'
    with zipfile.ZipFile(classes, 'w', zipfile.ZIP_DEFLATED) as archive:
        for source in sorted((BUILD / 'classes').rglob('*.class')):
            archive.write(source, source.relative_to(BUILD / 'classes').as_posix())
    run(JAVA_BIN / 'java.exe', '-cp', BUILD_TOOLS / 'lib/d8.jar', 'com.android.tools.r8.D8',
        '--lib', ANDROID_JAR, '--classpath', API, '--min-api', '34',
        '--output', BUILD / 'dex', classes)
    run(BUILD_TOOLS / 'aapt2.exe', 'compile', '--dir', ROOT / 'app/res',
        '-o', BUILD / 'resources.zip')
    unsigned = BUILD / 'unsigned.apk'
    run(BUILD_TOOLS / 'aapt2.exe', 'link', '-I', ANDROID_JAR,
        '--manifest', ROOT / 'app/AndroidManifest.xml', '-A', ROOT / 'app/assets',
        '-o', unsigned, BUILD / 'resources.zip')
    with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as archive:
        archive.write(ROOT / 'LICENSE', 'assets/LICENSE')
        for dex in sorted((BUILD / 'dex').glob('*.dex')):
            archive.write(dex, dex.name)
    run(BUILD_TOOLS / 'zipalign.exe', '-f', '4', unsigned, BUILD / 'aligned.apk')

    apk = DIST / 'guided-access.apk'
    run(JAVA_BIN / 'java.exe', '-jar', BUILD_TOOLS / 'lib/apksigner.jar', 'sign',
        '--ks', key, '--ks-key-alias', 'mipad',
        '--ks-pass', 'env:MIPAD_KEYSTORE_PASSWORD', '--key-pass', 'env:MIPAD_KEY_PASSWORD',
        '--out', apk, BUILD / 'aligned.apk')
    run(JAVA_BIN / 'java.exe', '-jar', BUILD_TOOLS / 'lib/apksigner.jar', 'verify',
        '--verbose', apk)

    version = ElementTree.parse(ROOT / 'app/AndroidManifest.xml').getroot().attrib[
        '{http://schemas.android.com/apk/res/android}versionName']
    module_zip = DIST / f'mipad-guided-access-{version}.zip'
    with zipfile.ZipFile(module_zip, 'w', zipfile.ZIP_DEFLATED) as archive:
        for source in sorted((ROOT / 'module').rglob('*')):
            if source.is_file():
                archive.write(source, source.relative_to(ROOT / 'module').as_posix())
        archive.write(apk, 'guided-access.apk')
        for name in ['LICENSE', 'README.md', 'THIRD_PARTY_NOTICES.md', 'LICENSES/Magisk-GPL-3.0.txt']:
            archive.write(ROOT / name, name)
        archive.write(ROOT / 'tools/module_installer.sh', 'META-INF/com/google/android/update-binary')
        archive.writestr('META-INF/com/google/android/updater-script', '#MAGISK\n')
    print(f'Built {module_zip}', flush=True)


if __name__ == '__main__':
    main()
