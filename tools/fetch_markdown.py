"""Restore pinned native Markdown rendering libraries, checking SHA256."""
import hashlib,json,urllib.request
from pathlib import Path
root=Path(__file__).resolve().parents[1]
for entry in json.loads((root/'app/src/main/assets/markdown-sources.json').read_text()):
    target=root/'app/libs'/entry['file'];target.parent.mkdir(exist_ok=True)
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest()==entry['sha256']:continue
    data=urllib.request.urlopen(entry['url'],timeout=60).read()
    assert hashlib.sha256(data).hexdigest()==entry['sha256']
    target.write_bytes(data)
    print('Verified',target.name)
