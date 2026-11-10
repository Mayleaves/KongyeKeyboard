"""Create the matching-source archive for the redistributed Android Rime core.
Every source revision follows KeyTao v1.2.4's source-build script and the runtime
metadata in its APK. Original sources/patches/build scripts are kept verbatim.
"""
import concurrent.futures
import hashlib
import io
import json
import tarfile
import zipfile
from pathlib import Path
from fetch_offline_voice import fetch

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'build/native-sources'
SOURCES = {
    'librime': ('rime/librime', '33e78140250125871856cdc5b42ddc6a5fcd3cd4', 'librime'),
    'glog': ('google/glog', '7b134a5c82c0c0b5698bb6bf7a835b230c5638e4', 'librime/deps/glog'),
    'leveldb': ('google/leveldb', '99b3c03b3284f5886f9ef9a4ef703d57373e61be', 'librime/deps/leveldb'),
    'marisa': ('s-yata/marisa-trie', '3e87d53b78e15f2f43783d5e376561a8c9722051', 'librime/deps/marisa-trie'),
    'opencc': ('BYVoid/OpenCC', '556ed22496d650bd0b13b6c163be9814637970ae', 'librime/deps/opencc'),
    'yaml-cpp': ('jbeder/yaml-cpp', '2f86d13775d119edbb69af52e5f566fd65c6953b', 'librime/deps/yaml-cpp'),
    'rime-lua': ('hchunhui/librime-lua', '6da5d6cedb853380ed1b5b9b6979cb1a9c028353', 'librime/plugins/lua'),
    'lua': ('hchunhui/librime-lua', '9e5bb71db1544913f8005dadc3df8439c00d08b7', 'librime/plugins/lua/thirdparty'),
    'octagram': ('lotem/librime-octagram', 'dfcc15115788c828d9dd7b4bff68067d3ce2ffb8', 'librime/plugins/octagram'),
    'predict': ('rime/librime-predict', '920bd41ebf6f9bf6855d14fbe80212e54e749791', 'librime/plugins/predict'),
    'keytao': ('xkinput/keytao-app', 'v1.2.4', 'keytao'),
    'hanzi_lookup': ('gugray/hanzi_lookup', '01f90c3ab99a8fadf0696c28e5eb097223c500db', 'hanzi_lookup'),
}


def download(name, spec):
    repo, rev, _ = spec
    path = fetch(f'https://codeload.github.com/{repo}/zip/{rev}', WORK / (name + '.zip'))
    return name, hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        digests = dict(pool.map(lambda item: download(*item), SOURCES.items()))
    boost = fetch('https://archives.boost.io/release/1.84.0/source/boost_1_84_0.tar.bz2', WORK / 'boost.tar.bz2')
    digests['boost'] = hashlib.sha256(boost.read_bytes()).hexdigest()
    release = ROOT / 'build/release'; release.mkdir(exist_ok=True)
    target = release / 'KongyeKeyboard-2.1.0-native-sources.zip'
    manifest = {'runtime': 'KeyTao v1.2.4 arm64, source-built librime 1.17.0',
                'rime_source': '33e78140250125871856cdc5b42ddc6a5fcd3cd4',
                'ndk': '27.0.12077973', 'boost': '1.84.0',
                'sources': {name: {'repository': repo, 'revision': rev, 'path': path, 'archive_sha256': digests[name]}
                            for name, (repo, rev, path) in SOURCES.items()},
                'boost_archive_sha256': digests['boost']}
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as out:
        for name, (_, _, prefix) in SOURCES.items():
            with zipfile.ZipFile(WORK / (name + '.zip')) as source:
                for entry in source.infolist():
                    path = entry.filename.split('/', 1)
                    if entry.is_dir() or len(path) < 2 or not path[1]: continue
                    # Only build scripts, patches and notices are involved in the
                    # KeyTao runtime; its unrelated UI is not distributed here.
                    if name == 'keytao' and not path[1].startswith(('scripts/', 'LICENSE', 'README')): continue
                    out.writestr(prefix + '/' + path[1], source.read(entry))
        with tarfile.open(boost, 'r:bz2') as source:
            for entry in source:
                if not entry.isfile(): continue
                path = entry.name.split('/', 1)
                if len(path) < 2 or not path[1].startswith(('boost/', 'LICENSE')): continue
                out.writestr('boost_1_84_0/' + path[1], source.extractfile(entry).read())
        out.writestr('sources.json', json.dumps(manifest, indent=2))
        out.write(ROOT / 'docs/Rime-原生构建.md', 'BUILD.md')
    (WORK / 'sources.json').write_text(json.dumps(manifest, indent=2), encoding='utf8')
    print('Matching source archive:', target, target.stat().st_size, 'bytes')


if __name__ == '__main__': main()
