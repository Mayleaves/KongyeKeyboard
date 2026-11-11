"""Fetch pinned Rime/Wanxiang dependencies and compile portable Rime dictionaries.

Run on 64-bit Windows: python tools/fetch_rime.py
Only independent librime.so/libc++ and JNA are imported, not KeyTao's frontend.
The source URLs/hashes are recorded; no user input is used to build the data.
"""
import concurrent.futures
import argparse
import hashlib
import json
import shutil
import subprocess
import unicodedata
import zipfile
from pathlib import Path
from fetch_offline_voice import fetch
from rime_config import schema
from rime_native import Rime

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'build/rime'
DEPS = {
    'keytao.apk': ('https://github.com/xkinput/keytao-app/releases/download/v1.2.4/keytao-app-1.2.4-android-arm64-release.apk', 'ff2712b2fa4d8ef9f641d95e15cba0841a488456ca8aa0e336e5dfd15e922c7e'),
    'keytao-x86_64.apk': ('https://github.com/xkinput/keytao-app/releases/download/v1.2.4/keytao-app-1.2.4-android-x86_64-release.apk', '8a3b04f906e0b6c0f1b137303cfc419c56628031a8c28a332c05b80c9732b0dd'),
    'rime-ice.zip': ('https://codeload.github.com/iDvel/rime-ice/zip/99662ba01e3affa6a0f009174ca530b887bac3b6', '459eb85d0555f29888452114b48914e7b9b9407db765731629d322ba8a648aaa'),
    'jna.aar': ('https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.17.0/jna-5.17.0.aar', '4dbeffffa665d97ad5aa7eee297531d3c841a86716ab7f774fd6956422b3cf38'),
    'wanxiang.zip': ('https://github.com/amzxyz/rime-wanxiang/releases/download/v18.1.11/rime-wanxiang-base.zip', '6f3b50a3cbf66227b8f8c490aefc6715a92243eec0d3eab6b920741cd2c07cd6'),
    'wanxiang-lts-zh-hans.gram': ('https://github.com/amzxyz/rime-wanxiang-data/releases/download/LTS/wanxiang-lts-zh-hans.gram', '3457b69f5fe0fc4f5d211c878bbfa2e2361b90639952c9845c8e31e9d047cde6'),
    'rime.7z': ('https://github.com/rime/librime/releases/download/1.17.0/rime-33e7814-Windows-clang-x64.7z', 'f850177bb2d256574348258f221730dbf3d358a53dbeca4bd49cdc338d927438'),
    'rime-deps.7z': ('https://github.com/rime/librime/releases/download/1.17.0/rime-deps-33e7814-Windows-clang-x64.7z', '4a494d253b886bcba6eb2fde22eae74940b75f25db7612e6f99a53317995c952'),
    'rime_api.h': ('https://raw.githubusercontent.com/rime/librime/1.17.0/src/rime_api.h', '6eb5629162c44761e7b878f1b122f464de5c8685bd3cd5ac1e8519badc6d6424'),
}


def normalize(code):
    for c in 'üǖǘǚǜ': code = code.replace(c, 'v')
    return ''.join(c for c in unicodedata.normalize('NFD', code) if not unicodedata.combining(c))


def from_apk(path):
    """Recover the pinned data from this version's APK if upstream LTS changes.
    This path also works without the Windows dictionary compiler.
    """
    manifest = json.loads((ROOT / 'tools/rime/sources.json').read_text(encoding='utf8'))
    expected = manifest.get('native_binaries') or {'arm64-v8a/librime.so': 'f6a2517ed852a03dc7d7e7ced5eae68bb9232d62f59bb151ab4f5a557db6567f',
                'arm64-v8a/libc++_shared.so': '92ee8da641b969254cc8723f64aa115d36c8ffa87115e38bc9cb89e3eefee839'}
    libs = ROOT / 'app/libs'; libs.mkdir(exist_ok=True)
    url, sha = DEPS['jna.aar']; fetch(url, libs / 'jna-5.17.0.aar', sha)
    with zipfile.ZipFile(path) as source:
        data = source.read('assets/rime-data.zip')
        if hashlib.sha256(data).hexdigest() != manifest['assets_sha256']: raise ValueError('Wrong Rime data version')
        (ROOT / 'app/src/main/assets/rime-data.zip').write_bytes(data)
        with zipfile.ZipFile(libs / 'rime-1.17.0.aar', 'w', zipfile.ZIP_DEFLATED) as out:
            out.writestr('AndroidManifest.xml', '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.rime.core"/>')
            for name, sha in expected.items():
                binary = source.read('lib/' + name)
                if hashlib.sha256(binary).hexdigest() != sha: raise ValueError('Wrong Rime runtime')
                out.writestr('jni/' + name, binary)
    print('Pinned Rime data and runtime restored from release APK')


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for job in [pool.submit(fetch, url, WORK / name, sha or None) for name, (url, sha) in DEPS.items()]: job.result()
    win = WORK / 'windows'; win.mkdir(exist_ok=True)
    for name in ['rime.7z', 'rime-deps.7z']:
        subprocess.run(['tar', '-xf', str(WORK / name), '-C', str(win)], check=True)
    shared = WORK / 'shared'; shared.mkdir(exist_ok=True)
    source = WORK / 'wanxiang'
    with zipfile.ZipFile(WORK / 'wanxiang.zip') as z: z.extractall(source)
    ice = WORK / 'ice'
    with zipfile.ZipFile(WORK / 'rime-ice.zip') as z: z.extractall(ice)
    ice_source = next(ice.glob('rime-ice-*'))
    shutil.copytree(ice_source / 'cn_dicts', shared / 'cn_dicts', dirs_exist_ok=True)
    readings = {}
    count = 0
    with (shared / 'kongye.dict.yaml').open('w', encoding='utf8', newline='\n') as out:
        out.write('---\nname: kongye\nversion: "ice-99662ba"\nsort: by_weight\nuse_preset_vocabulary: false\nimport_tables:\n  - cn_dicts/8105\n  - cn_dicts/base\n  - cn_dicts/ext\n  - cn_dicts/tencent\n  - cn_dicts/others\n...\n')
        for name in ['zi', 'jichu', 'cuoyin', 'duoyin', 'diming', 'mingren', 'yiren']:
            for line in (source / 'dicts' / (name + '.dict.yaml')).read_text(encoding='utf-8-sig').splitlines():
                a = line.split('\t')
                if len(a) < 3: continue
                py = normalize(a[1])
                if not py or any(c not in 'abcdefghijklmnopqrstuvwxyz ' for c in py): continue
                if any(s in ['m', 'n', 'ng', 'hm', 'hng'] for s in py.split()): continue
                # Wanxiang remains the pronunciation helper and grammar source.
                # Decode words/weights come from unmodified Rime Ice tables.
                if len(a[0]) == 1:
                    weight = int(a[2])
                    if a[0] not in readings or weight > readings[a[0]][1]: readings[a[0]] = (py, weight)
    (shared / 'lua').mkdir(exist_ok=True)
    shutil.copyfile(ROOT / 'tools/rime/kongye_meta.lua', shared / 'lua/kongye_meta.lua')
    model = shared / 'wanxiang-lts-zh-hans.gram'
    if not model.exists(): shutil.copyfile(WORK / model.name, model)
    (shared / 'default.yaml').write_text('config_version: "2.1.0"\nschema_list:\n  - schema: kongye_26\nmenu:\n  page_size: 40\n', encoding='utf8')
    schemas = {}
    for layout in ['26', '14', '9']:
        for abbr in [True, False]:
            sid = 'kongye_' + layout + ('' if abbr else '_full')
            schemas[sid] = schema(sid, layout, abbreviation=abbr, corrections=False)
    schemas['kongye_correction'] = schema('kongye_correction', abbreviation=False, corrections=True)
    schemas['kongye_neighbor'] = schema('kongye_neighbor', abbreviation=False, corrections=True).replace(
        'enable_correction: false', 'enable_correction: true')
    user = WORK / 'compile-user'; user.mkdir(exist_ok=True)
    rime = Rime(win / 'dist/lib/rime.dll', WORK / 'rime_api.h', shared, user)
    try:
        for sid, content in schemas.items():
            path = shared / (sid + '.schema.yaml'); path.write_text(content, encoding='utf8')
            rime.deploy(path); print('Deployed ' + sid, flush=True)
        # The native library must include both sentence grammar and Lua modules.
        _, c = rime.query('kongye_26', 'wobzdzmb')
        if not c or c[0]['text'] != '我不知道怎么办': raise RuntimeError('Rime data smoke check failed')
    finally: rime.close()
    data = ROOT / 'app/src/main/assets/rime-data.zip'
    with zipfile.ZipFile(data, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for path in [shared / 'default.yaml', shared / 'lua/kongye_meta.lua', model]:
            z.write(path, path.relative_to(shared).as_posix())
        for path in sorted((user / 'build').iterdir()):
            if path.suffix in ['.bin', '.yaml']: z.write(path, 'build/' + path.name)
        z.writestr('readings.tsv', '\n'.join(c + '\t' + py for c, (py, _) in sorted(readings.items())))
    libs = ROOT / 'app/libs'; libs.mkdir(exist_ok=True)
    shutil.copyfile(WORK / 'jna.aar', libs / 'jna-5.17.0.aar')
    # Package the independent C core in an AAR, without introducing JNI app code.
    native_binaries = {}
    with zipfile.ZipFile(libs / 'rime-1.17.0.aar', 'w', zipfile.ZIP_DEFLATED) as out:
        out.writestr('AndroidManifest.xml', '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.rime.core"/>')
        for abi, apk in [('arm64-v8a', 'keytao.apk'), ('x86_64', 'keytao-x86_64.apk')]:
            with zipfile.ZipFile(WORK / apk) as source:
                for name in ['librime.so', 'libc++_shared.so']:
                    binary = source.read(f'lib/{abi}/{name}')
                    native_binaries[f'{abi}/{name}'] = hashlib.sha256(binary).hexdigest()
                    out.writestr(f'jni/{abi}/{name}', binary)
    count = sum(1 for name in ['8105', 'base', 'ext', 'tencent', 'others'] for line in
                (shared / 'cn_dicts' / (name + '.dict.yaml')).read_text(encoding='utf8').splitlines() if '\t' in line and not line.startswith('#'))
    shutil.copyfile(ice_source / 'LICENSE', ROOT / 'app/src/main/assets/licenses/RimeIce-GPL-3.0.txt')
    manifest = {'rime': '1.17.0', 'wanxiang': '18.1.11', 'dictionary': 'Rime Ice 99662ba01e3affa6a0f009174ca530b887bac3b6', 'entries': count,
                'native_binaries': native_binaries,
                'dependencies': {name: {'url': url, 'sha256': hashlib.sha256((WORK / name).read_bytes()).hexdigest()} for name, (url, _) in DEPS.items()},
                'assets_sha256': hashlib.sha256(data.read_bytes()).hexdigest()}
    (WORK / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf8')
    print('Rime offline assets ready:', count, 'dictionary entries;', data.stat().st_size, 'bytes')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--from-apk', type=Path, help='Restore pinned artifacts from the matching release APK (all host platforms)')
    args = parser.parse_args()
    if args.from_apk: from_apk(args.from_apk)
    else: main()
