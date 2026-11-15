"""Restore the pinned, bundled offline punctuation model."""
import hashlib
import json
from pathlib import Path
import tarfile
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/voice-punctuation'
MANIFEST = json.loads((ASSETS / 'source.json').read_text(encoding='utf-8'))


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


model = ASSETS / 'model.int8.onnx'
if model.is_file() and sha256(model) == MANIFEST['model_sha256']:
    print('Punctuation model already verified')
else:
    archive = ROOT / 'build/voice-punctuation.tar.bz2'
    archive.parent.mkdir(parents=True, exist_ok=True)
    urllib.request.urlretrieve(MANIFEST['url'], archive)
    assert sha256(archive) == MANIFEST['archive_sha256'], 'Unexpected model archive'
    with tarfile.open(archive) as package:
        members = [m for m in package.getmembers() if m.name.endswith('/model.int8.onnx') and m.isfile()]
        assert len(members) == 1
        temporary = model.with_suffix('.part')
        temporary.write_bytes(package.extractfile(members[0]).read())
    assert temporary.stat().st_size == MANIFEST['model_bytes']
    assert sha256(temporary) == MANIFEST['model_sha256']
    temporary.replace(model)
    print('Punctuation model restored and verified')
