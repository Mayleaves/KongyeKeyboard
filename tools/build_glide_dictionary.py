"""Extract plain English words from the pinned Rime Ice dictionary already used by Kongye.
Usage: python tools/build_glide_dictionary.py path/to/rime-ice/en_dicts/en.dict.yaml
Rime Ice revision and licensing are recorded in rime-sources.json / THIRD_PARTY_NOTICES.md.
"""
import hashlib,json,re,sys
from pathlib import Path
source=Path(sys.argv[1]);root=Path(__file__).resolve().parents[1]
words={}
for line in source.read_text(encoding="utf-8").splitlines():
    if line.startswith("#"):continue
    fields=line.split("\t")
    if len(fields)<2 or not re.fullmatch("[A-Za-z]{2,24}",fields[0]):continue
    word=fields[0].lower();weight=int(fields[2]) if len(fields)>2 and fields[2].isdigit() else 1
    words[word]=max(weight,words.get(word,0))
out=root/"app/src/main/assets/glide-english.tsv"
out.write_text("".join(f"{w}\t{f}\n" for w,f in sorted(words.items())),encoding="utf-8")
(out.parent/"glide-sources.json").write_text(json.dumps(dict(repository="https://github.com/iDvel/rime-ice",revision="99662ba01e3affa6a0f009174ca530b887bac3b6",path="en_dicts/en.dict.yaml",source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),words=len(words)),indent=2),encoding="utf-8")
print("Glide English dictionary:",len(words),"words")
