"""Install unmodified HanziLookup and reproducible tone/polyphone resources."""
import hashlib
import json
import shutil
import unicodedata
from pathlib import Path
from fetch_offline_voice import fetch

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'build/handwriting'
ASSETS = ROOT / 'app/src/main/assets'
HANZI_REV = '01f90c3ab99a8fadf0696c28e5eb097223c500db'
PINYIN_REV = 'fe29ad32bdf352b31a02b9936e537f575432e9a6'
FILES = {
    'hanzi_lookup.js': (f'https://raw.githubusercontent.com/gugray/hanzi_lookup/{HANZI_REV}/web_demo/hanzi_lookup.js', 'fe6f70c1b4172951727a1b313313cff1dc1c663ff0c5209a7ccb42ae3e0a71ef'),
    'hanzi_lookup_bg.wasm': (f'https://raw.githubusercontent.com/gugray/hanzi_lookup/{HANZI_REV}/web_demo/hanzi_lookup_bg.wasm', '8d2affdbfffe651d62534fb6a4ad192d57cf3559631860a7aadb7cd48ffa7463'),
    'hanzi-LGPL-3.0.txt': (f'https://raw.githubusercontent.com/gugray/hanzi_lookup/{HANZI_REV}/LICENSE', 'e3a994d82e644b03a792a930f574002658412f62407f5fee083f2555c5f23118'),
    'hanzi-Arphic.txt': (f'https://raw.githubusercontent.com/gugray/hanzi_lookup/{HANZI_REV}/LICENSE-APL', '3a5e90c0957524a89e48203febcd4492ca4393678abaa7e5b4d70f3ff32b386d'),
    'pinyin.txt': (f'https://raw.githubusercontent.com/mozillazg/pinyin-data/{PINYIN_REV}/pinyin.txt', '2342a6f98c85dbc3c8d26aef31744876a36326c6466e51e9a0f16898c36c2011'),
    'pinyin-data-MIT.txt': (f'https://raw.githubusercontent.com/mozillazg/pinyin-data/{PINYIN_REV}/LICENSE', '9c048697be2502a16e8bcb282d5d465a07295b2def0ffb05a269c5d39dbe1586'),
}


def main():
    target = ASSETS / 'handwriting'; target.mkdir(exist_ok=True)
    for name, (url, sha) in FILES.items():
        source = fetch(url, WORK / name, sha)
        if name in ['hanzi_lookup.js', 'hanzi_lookup_bg.wasm']: shutil.copyfile(source, target / name)
        elif name.endswith('.txt') and name != 'pinyin.txt': shutil.copyfile(source, ASSETS / 'licenses' / name)
    rows = []
    for line in (WORK / 'pinyin.txt').read_text(encoding='utf8').splitlines():
        if not line.startswith('U+'): continue
        code, data = line.split(':', 1)
        readings = list(dict.fromkeys(unicodedata.normalize('NFC', x.strip()) for x in data.split('#')[0].strip().split(',')))
        rows.append(chr(int(code[2:], 16)) + '\t' + ' / '.join(readings))
    (ASSETS / 'handwriting-readings.tsv').write_text('\n'.join(rows) + '\n', encoding='utf8')
    phrases = {}
    for name in ['jichu', 'duoyin', 'diming']:
        path = ROOT / 'build/rime/wanxiang/dicts' / (name + '.dict.yaml')
        if not path.exists(): raise RuntimeError('Run tools/fetch_rime.py first')
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            fields = line.split('\t')
            if len(fields) < 3 or not 2 <= len(fields[0]) <= 6: continue
            try: weight = int(fields[2])
            except ValueError: continue
            old = phrases.get(fields[0])
            if old is None or weight > old[1]: phrases[fields[0]] = (unicodedata.normalize('NFC', fields[1]), weight)
    phrases = sorted(phrases.items(), key=lambda e: -e[1][1])[:60000]
    (ASSETS / 'handwriting-phrases.tsv').write_text('\n'.join(word + '\t' + pinyin for word, (pinyin, _) in phrases) + '\n', encoding='utf8')
    (target / 'sources.json').write_text(json.dumps({'HanziLookup': HANZI_REV, 'pinyin-data': PINYIN_REV,
        'files': {name: {'url': url, 'sha256': sha} for name, (url, sha) in FILES.items()},
        'changes': 'HanziLookup JS/WASM unmodified. Codepoint and phrase pronunciation TSV generated; phrase readings from Wanxiang 18.1.11, top 60000 by frequency.'}, indent=2), encoding='utf8')
    print('Offline handwriting module, 44440 character readings and 60000 phrase readings ready')


if __name__ == '__main__': main()
