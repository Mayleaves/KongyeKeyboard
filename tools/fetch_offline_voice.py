"""下载固定版本的普通话/四川话离线语音资源；不把大型模型提交进 Git。"""
import concurrent.futures
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/offline-voice'
MODELS = {
    'mandarin': ('csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09', '63ddc3cd0f2810b68289a7b3876e62ef5d53d6df'),
    'sichuan': ('csukuangfj/sherpa-onnx-paraformer-zh-int8-2025-10-07', '52f0cdb5bdde9533c62f2a49c9ec073ab8139820'),
}
HASHES = {
    'mandarin': {
        'model.int8.onnx': '3ef6c19369b912f7caf3cef8e545c5ccd1a33d9d7ec792a46668dc41c4b229ec',
        'tokens.txt': '4b2d964e18b9cf139b473003b6698fb2ed9a2a5ec55b93daa677b28f578897aa',
    },
    'sichuan': {
        'model.int8.onnx': '53813ee1d41722cc6370a571c887e6d0b391d25b8312cf714a31af85ea603812',
        'tokens.txt': '59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6',
    },
}


def fetch(url, dest, expected=None):
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and (not expected or hashlib.sha256(dest.read_bytes()).hexdigest() == expected):
        return dest
    partial = dest.with_suffix(dest.suffix + '.part')
    request = urllib.request.Request(url, headers={'User-Agent': 'KongyeKeyboard-offline-resources', 'Cache-Control': 'no-cache'})
    digest = hashlib.sha256()
    print('Downloading ' + dest.name, flush=True)
    with urllib.request.urlopen(request, timeout=90) as response, partial.open('wb') as output:
        linked_hash = response.headers.get('x-linked-etag', '').strip('"')
        while chunk := response.read(1024 * 1024):
            output.write(chunk)
            digest.update(chunk)
    wanted = expected or (linked_hash if len(linked_hash) == 64 else None)
    if wanted and digest.hexdigest() != wanted:
        raise ValueError('SHA256 mismatch: ' + str(dest))
    partial.replace(dest)
    print('Ready ' + str(dest.relative_to(ROOT)) + ' (' + str(dest.stat().st_size) + ' bytes)', flush=True)
    return dest


def model(name, spec):
    repo, revision = spec
    base = f'https://huggingface.co/{repo}/resolve/{revision}/'
    directory = OUT / name
    for filename in ('model.int8.onnx', 'tokens.txt', 'README.md'):
        fetch(base + filename + '?download=true', directory / filename, HASHES[name].get(filename))
    wave = '0.wav' if name == 'mandarin' else '1.wav'
    fetch(base + 'test_wavs/' + wave + '?download=true', OUT / 'test' / (name + '.wav'))
    manifest = {f: {'bytes': (directory / f).stat().st_size, 'sha256': hashlib.sha256((directory / f).read_bytes()).hexdigest()}
                for f in ('model.int8.onnx', 'tokens.txt')}
    (directory / 'manifest.json').write_text(json.dumps({'source': repo, 'revision': revision, 'files': manifest}, indent=2), encoding='utf8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--engine-only', action='store_true', help='只获取构建所需 AAR，不下载语音模型')
    args = parser.parse_args()
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        jobs = [] if args.engine_only else [pool.submit(model, name, spec) for name, spec in MODELS.items()]
        jobs.append(pool.submit(fetch,
            'https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar',
            ROOT / 'app/libs/sherpa-onnx-2.0.1.aar',
            'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7'))
        for job in jobs:
            job.result()
