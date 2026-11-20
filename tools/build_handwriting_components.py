"""Build axis-aligned IDS lookup data; no recognition answers are hard-coded.

Source: Andrew West's BabelStone IDS database. Its header explicitly permits
personal/commercial reuse and waives rights in the presentation format.
"""
from pathlib import Path
import hashlib,json,re,urllib.request

ROOT=Path(__file__).resolve().parents[1]
SOURCE='https://www.babelstone.co.uk/CJK/IDS.TXT'
EXPECTED_SHA256='cc2a0a97e6a51ed6ebe59870ef2af66e83f3c3c27387d79b4441bb2d791d78b1'
WORK=ROOT/'build/handwriting-components'
WORK.mkdir(parents=True,exist_ok=True)
source=WORK/'IDS.TXT'
if not source.exists():
    with urllib.request.urlopen(SOURCE,timeout=60) as r: source.write_bytes(r.read())
assert hashlib.sha256(source.read_bytes()).hexdigest()==EXPECTED_SHA256, 'IDS source version changed'
data=source.read_text('utf-8-sig')
assert 'waive any copyright claims' in data
arities={'⿰':2,'⿱':2,'⿲':3,'⿳':3}
def valid(pattern):
    at=0;leaves=0
    def node(depth=0):
        nonlocal at,leaves
        if at>=len(pattern) or depth>3:return False
        c=pattern[at];at+=1
        if c in arities:return all(node(depth+1) for _ in range(arities[c]))
        if '\u2ff0'<=c<='\u2fff' or c in '？{}[]()^$':return False
        if not ('\u3400'<=c<='\u9fff' or 0x20000<=ord(c)<=0x323ff):return False
        leaves+=1;return leaves<=6
    return node() and at==len(pattern) and 2<=leaves<=6
rows=set()
for line in data.splitlines():
    fields=line.split('\t')
    if len(fields)<3 or not fields[0].startswith('U+'):continue
    character=fields[1]
    if len(character)!=1:continue
    for field in fields[2:]:
        match=re.search(r'\^([^$]+)\$',field)
        if match and valid(match[1]):rows.add((match[1],character))
target=ROOT/'app/src/main/assets/handwriting-components.tsv'
target.write_text(''.join(pattern+'\t'+char+'\n' for pattern,char in sorted(rows)),encoding='utf8')
notice='\n'.join(line for line in data.splitlines()[:44] if line.startswith('#'))+'\n'
(ROOT/'app/src/main/assets/licenses/BabelStone-IDS-notice.txt').write_text(notice,encoding='utf8')
meta={'url':SOURCE,'sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'output_sha256':hashlib.sha256(target.read_bytes()).hexdigest(),
      'transform':'Axis-aligned binary/ternary IDS trees, depth <= 3 and 2-6 leaves. Full source retained in build/handwriting-components.','rows':len(rows)}
(ROOT/'app/src/main/assets/handwriting-components-sources.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print('Generated',len(rows),'general component patterns;',meta['sha256'])
