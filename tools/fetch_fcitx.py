"""Reproduce the pinned official Fcitx5 Android native runtime and libime model.
Only JNI adapters and assets are used, not the upstream app UI.
"""
import hashlib
import json
from pathlib import Path
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parents[1]
META=ROOT/'app/src/main/assets/fcitx-sources.json'
WORK=ROOT/'build/fcitx-upstream'

def fetch(name,spec):
    path=WORK/name
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=spec['sha256']:
        with urllib.request.urlopen(urllib.request.Request(spec['url'],headers={'User-Agent':'KongyeKeyboard-build'}),timeout=180) as r:
            path.write_bytes(r.read())
    assert hashlib.sha256(path.read_bytes()).hexdigest()==spec['sha256'],name
    return path

def main():
    WORK.mkdir(parents=True,exist_ok=True)
    meta=json.loads(META.read_text(encoding='utf-8'))
    files={name:fetch(name,spec) for name,spec in meta['artifacts'].items()}
    libs=ROOT/'app/libs';libs.mkdir(exist_ok=True)
    with zipfile.ZipFile(libs/'fcitx5-0.1.3.aar','w',zipfile.ZIP_DEFLATED) as out:
        out.writestr('AndroidManifest.xml','<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.fcitx.core"/>')
        for abi,name in [('arm64-v8a','fcitx-arm64.apk'),('x86_64','fcitx-x86_64.apk')]:
            with zipfile.ZipFile(files[name]) as source:
                for entry in source.namelist():
                    if entry.startswith('lib/'+abi+'/') and entry.endswith('.so'):
                        # Isolate upstream libc++ from the existing Rime runtime.
                        # SONAME / DT_NEEDED string lengths remain identical;
                        # no code, decoder, frequencies or model weights change.
                        data=source.read(entry).replace(b'libc++_shared.so',b'libfcx_shared.so')
                        name=Path(entry).name.replace('libc++_shared.so','libfcx_shared.so')
                        out.writestr('jni/'+abi+'/'+name,data)
    with zipfile.ZipFile(ROOT/'app/src/main/assets/fcitx-data.zip','w',zipfile.ZIP_DEFLATED,compresslevel=6) as out:
        with zipfile.ZipFile(files['fcitx-arm64.apk']) as source:
            for name in source.namelist():
                if not name.startswith('assets/usr/'):continue
                tail=name[len('assets/'):]
                replacement=files.get(Path(tail).name) if tail.startswith('usr/share/libime/') else None
                out.writestr(tail,replacement.read_bytes() if replacement else source.read(name))
    print('Official Fcitx5 runtime and libime model ready')

if __name__=='__main__':main()
