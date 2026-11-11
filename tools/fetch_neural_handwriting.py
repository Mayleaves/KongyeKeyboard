"""Pinned ochwpro handwriting model and Microsoft's Android Java binding.

Sherpa's runtime hides the public ORT C API, so it cannot serve Microsoft's
Java binding. Rename the official runtime's SONAME and the JNI DT_NEEDED string
to keep the two independent engines from shadowing one another. Equal-length
ELF string substitution leaves offsets/code intact. Both are loaded on demand.
"""
import hashlib
import json
import shutil
import zipfile
from pathlib import Path
from fetch_offline_voice import fetch

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'build/handwriting-neural'
REV = '54c326d33d86cc0754dbea5a36c1448682c8202c'
FILES = {
    'ochwpro.onnx': ('https://www.modelscope.cn/models/bikeand/ochwpro/resolve/master/ochwpro.onnx', 'eb04d62a314c7d7bac4e34d6ce0c24137474d30ff94b929115a621385420ce13'),
    'char_index.json': ('https://www.modelscope.cn/models/bikeand/ochwpro/resolve/master/char_index.json', '171cf2ac23731428ac9dd28f64be82dece5f66a0ca3d674ffb3b567b51532176'),
    'onnxruntime-android-1.28.0.aar': ('https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.28.0/onnxruntime-android-1.28.0.aar', 'f351a0638696f54b35184290dbc001d66daae17281ad0b548d2c70347d53b8a9'),
}


def main():
    target = ROOT / 'app/src/main/assets/handwriting-neural'
    target.mkdir(parents=True, exist_ok=True)
    for name, (url, sha) in FILES.items():
        source = fetch(url, WORK / name, sha)
        if not name.endswith('.aar'):
            shutil.copyfile(source, target / name)
    with zipfile.ZipFile(WORK / 'onnxruntime-android-1.28.0.aar') as source, zipfile.ZipFile(
            ROOT / 'app/libs/onnxruntime-java-1.28.0.aar', 'w', zipfile.ZIP_DEFLATED) as out:
        for entry in source.infolist():
            if entry.is_dir():
                continue
            name = entry.filename
            data = source.read(entry)
            if name.endswith(('/libonnxruntime.so', '/libonnxruntime4j_jni.so')):
                original = b'libonnxruntime.so\x00'
                replacement = b'libkongye_ort_.so\x00'
                assert len(original) == len(replacement) and original in data
                data = data.replace(original, replacement)
                name = name.replace('/libonnxruntime.so', '/libkongye_ort_.so')
            out.writestr(name, data)
    for name, url in {
        'ochwpro-MIT.txt': f'https://raw.githubusercontent.com/ximeiorg/ochwpro/{REV}/LICENSE',
        'ONNXRuntime-MIT.txt': 'https://raw.githubusercontent.com/microsoft/onnxruntime/v1.28.0/LICENSE',
    }.items():
        shutil.copyfile(fetch(url, WORK / name), ROOT / 'app/src/main/assets/licenses' / name)
    (target / 'sources.json').write_text(json.dumps({
        'source': 'https://github.com/ximeiorg/ochwpro', 'revision': REV,
        'files': {n: {'url': u, 'sha256': s} for n, (u, s) in FILES.items()},
        'runtime': 'Official ONNX Runtime 1.28.0; ELF SONAME/DT_NEEDED renamed to libkongye_ort_.so to coexist with sherpa',
    }, indent=2), encoding='utf8')
    print('Neural Chinese handwriting model ready (7356 classes)')


if __name__ == '__main__':
    main()
