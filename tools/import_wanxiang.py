"""Create a bounded, attributed snapshot from downloaded Wanxiang source dictionaries."""
import argparse
import hashlib
import json
import unicodedata
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument('source', type=Path)
ap.add_argument('--limit', type=int, default=100000)
ap.add_argument('--out', type=Path, default=Path('tools/data/wanxiang-common.tsv'))
args = ap.parse_args()

def toneless(value):
    value = unicodedata.normalize('NFD', value.lower()).replace('u\u0308', 'v')
    return ''.join(c for c in value if not unicodedata.combining(c))

rows = {}
sources = {}
for name in ['jichu.dict.yaml', 'duoyin.dict.yaml']:
    path = args.source / name
    sources[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    for line in path.open(encoding='utf-8'):
        fields = line.rstrip().split('\t')
        if len(fields) != 3 or not fields[2].isdigit():
            continue
        word, reading, frequency = fields
        parts = toneless(reading).split()
        if not 2 <= len(word) <= 8 or not all('一' <= c <= '鿿' for c in word) or len(parts) != len(word):
            continue
        if not all(p.isascii() and p.isalpha() for p in parts):
            continue
        key = word, "'".join(parts)
        rows[key] = max(rows.get(key, 0), int(frequency))
selected = sorted(rows.items(), key=lambda x: (-x[1], x[0]))[:args.limit]
args.out.parent.mkdir(parents=True, exist_ok=True)
with args.out.open('w', encoding='utf-8', newline='\n') as output:
    output.write('# Adapted from amzxyz/rime-wanxiang, CC BY 4.0; see wanxiang-source.json\n')
    for (word, reading), frequency in selected:
        output.write(f'{word}\t{reading}\t{frequency}\n')
manifest = {
    'author': 'amzxyz', 'project': '万象拼音 / rime-wanxiang',
    'repository': 'https://github.com/amzxyz/rime-wanxiang',
    'commit': (args.source / 'commit.txt').read_text().strip(),
    'license': 'CC-BY-4.0', 'license_url': 'https://creativecommons.org/licenses/by/4.0/',
    'sources_sha256': sources, 'entries': len(selected),
    'changes': 'Merge basic/polyphonic dictionaries; remove tones; keep 2–8 Han characters; deduplicate; select highest-frequency entries. APK builder validates syllables and converts frequencies to log scores.',
    'output_sha256': hashlib.sha256(args.out.read_bytes()).hexdigest(),
}
args.out.with_name('wanxiang-source.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
args.out.with_name('wanxiang-LICENSE.txt').write_bytes((args.source/'LICENSE').read_bytes())
print('selected', len(selected), 'entries;', args.out.stat().st_size, 'bytes')
