"""Build Kongye's mobile Rime schemas. No application vocabulary overrides.

The dictionary and grammar are Wanxiang (CC BY 4.0); Rime performs segmentation,
phrase lookup and sentence decoding. Corrections are edges in Rime's spelling
graph, not replacements of whole input strings. A sentinel prevents composing
several accidental edits within one syllable. Rime's correction tag applies its
native log(0.01) penalty, keeping unmodified spellings ahead of weak corrections.
"""
from string import ascii_lowercase as ABC

GROUPS = {
    "9": ["abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"],
    "14": ["qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m"],
}


def algebra(layout="26", abbreviation=True, corrections=True, fuzzy=()):
    rules = ["derive/^([jqxy])u$/$1v/", "derive/^([nl])ve$/$1ue/"]
    # Established full-pinyin transpositions used by Wanxiang's algebra.
    rules += ["derive/ian$/ain/", "derive/iao$/ioa/", "derive/iang$/aing/",
              "derive/iong$/oing/", "derive/([zcs])h([aeiu])$/$1$2h/"]
    if corrections and layout == "26":
        rules.append("xform/^(.+)$/$1#/")
        for i in range(5):
            rules.append(f"derive/^([a-z]{{{i}}})([a-z])([a-z])([a-z]*)#$/$1$3$2$4/correction")
            # Missing one letter in a syllable, without turning two-letter codes
            # into an unbounded initial-only correction.
            rules.append(f"derive/^(?=[a-z]{{4,}}#)([a-z]{{{i}}})[a-z]([a-z]*)#$/$1$2/correction")
        for c in ABC:
            # Extra key at any boundary; each match still has one sentinel.
            for i in range(7):
                rules.append(f"derive/^([a-z]{{{i}}})([a-z]*)#$/$1{c}$2/correction")
        positions = {c: (i + offset, y) for y, (row, offset) in enumerate(
            [("qwertyuiop", 0), ("asdfghjkl", .25), ("zxcvbnm", .75)]) for i, c in enumerate(row)}
        for c, (x, y) in positions.items():
            for other, (ox, oy) in positions.items():
                if c != other and abs(x-ox) <= 1.25 and abs(y-oy) <= 1:
                    rules.append(f"derive/^([a-z]*){c}([a-z]*)#$/$1{other}$2/correction")
        rules.append("xform/#$//")
    for pair in fuzzy:
        a, b = pair.split("-")
        # Initial and final rules are distinct; never rewrite the middle of codes.
        if pair in ["z-zh", "c-ch", "s-sh", "n-l", "f-h", "r-l"]:
            rules += [f"fuzz/^{a}(?=[aeiouv])/{b}/", f"fuzz/^{b}(?=[aeiouv])/{a}/"]
        else:
            rules += [f"fuzz/{a}$/{b}/", f"fuzz/{b}$/{a}/"]
    if abbreviation:
        rules += ["abbrev/^([zcs]h).+$/$1/", "abbrev/^([a-z]).+$/$1/"]
    if layout in GROUPS:
        groups = GROUPS[layout]
        codes = "23456789" if layout == "9" else "qetuoadgjlzcbm"
        mapping = {c: key for key, group in zip(codes, groups) for c in group}
        rules.append("xlit/" + ABC + "/" + "".join(mapping[c] for c in ABC) + "/")
    return rules


def schema(schema_id, layout="26", abbreviation=True, corrections=True, fuzzy=()):
    rules = "\n".join("    - " + rule for rule in algebra(layout, abbreviation, corrections, fuzzy))
    alphabet = "23456789" if layout == "9" else ABC
    return f'''# Kongye mobile frontend for Rime; vocabulary/model: amzxyz Wanxiang.
schema:
  schema_id: {schema_id}
  name: 空叶拼音
  version: "2.1.0"
menu:
  page_size: {12 if corrections else 40}
engine:
  processors: [speller, selector, navigator, express_editor]
  segmentors: [abc_segmentor, fallback_segmentor]
  translators: [script_translator]
  filters: [uniquifier, "lua_filter@*kongye_meta"]
speller:
  alphabet: {alphabet}
  delimiter: " '"
  algebra:
{rules}
translator:
  dictionary: kongye
  prism: {schema_id}
  user_dict: kongye
  enable_user_dict: true
  enable_sentence: true
  enable_completion: {str(not corrections).lower()}
  enable_correction: false
  spelling_hints: 128
  always_show_comments: true
  contextual_suggestions: false
  max_homophones: 8
  max_homographs: 8
grammar:
  language: wanxiang-lts-zh-hans
  collocation_max_length: 6
  collocation_min_length: 2
  collocation_penalty: -14
  non_collocation_penalty: -6
  weak_collocation_penalty: -100
  rear_penalty: -18
'''
