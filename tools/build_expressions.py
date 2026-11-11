"""Generate exact-pinyin expression associations from pinned Rime Ice data.
The upstream dictionary and its phrase frequencies are never modified.
"""
import json
from collections import defaultdict
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]


def main():
    source=next((ROOT/'build/rime/ice').glob('rime-ice-*'))
    words=json.loads((ROOT/'tools/data/expression-overrides.json').read_text(encoding='utf8'))
    for line in (source/'opencc/emoji.txt').read_text(encoding='utf8').splitlines():
        fields=line.split('\t')
        if len(fields)!=2: continue
        values=fields[1].split(' ')
        if values and values[0]==fields[0]: values=values[1:]
        target=words.setdefault(fields[0],[])
        for value in values:
            if value and value not in target: target.append(value)
    codes=defaultdict(list)
    for name in ['8105','base','ext']:
        for line in (source/'cn_dicts'/f'{name}.dict.yaml').read_text(encoding='utf8').splitlines():
            fields=line.split('\t')
            if len(fields)<2 or fields[0] not in words: continue
            code=fields[1].replace(' ','')
            if code and all(c in 'abcdefghijklmnopqrstuvwxyz' for c in code) and code not in codes[fields[0]]:
                codes[fields[0]].append(code)
    rows=[]
    for word,values in words.items():
        if codes[word] and values:
            rows.append('\t'.join([word,','.join(codes[word]),json.dumps(values[:8],ensure_ascii=False,separators=(',',':'))]))
    (ROOT/'app/src/main/assets/pinyin-expressions.tsv').write_text('\n'.join(rows)+'\n',encoding='utf8')
    print(f'Expression keywords: {len(rows)}; source Rime Ice 99662ba; own symbol/kaomoji overrides')


if __name__=='__main__':main()
