"""用包含脏拼音的临时词典验证生成器，并检查 APK 内整份词库。"""
import ast
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
source = ast.parse((root / 'tools/build_lexicon.py').read_text(encoding='utf8'))
assignment = next(n for n in source.body if isinstance(n, ast.Assign)
                  and any(isinstance(t, ast.Name) and t.id == 'STANDARD_SYLLABLES' for t in n.targets))
allowed = frozenset(ast.literal_eval(assignment.value.args[0].func.value).split())
bad = {'u', 'uu', 'un', 'wbn', 'ie', 'd', 'dbo', 'shiwa', 'haixun'}
assert not allowed & bad
assert {'nv', 'nve', 'lv', 'lve', 'ju', 'qu', 'xu', 'yu', 'ng'} <= allowed

(root / 'build').mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='ky-lexicon-', dir=root / 'build') as tmp:
    db_path, out_path = Path(tmp) / 'dict.db', Path(tmp) / 'lexicon.txt'
    with sqlite3.connect(db_path) as db:
        db.execute('CREATE TABLE zi (c TEXT, f INTEGER, py TEXT)')
        db.execute('CREATE TABLE ci (w TEXT, f INTEGER, py TEXT)')
        db.executemany('INSERT INTO zi VALUES (?, ?, ?)', [
            ('你', 100, 'nǐ'), ('好', 100, 'hǎo'), ('女', 100, 'nǚ'),
            ('绿', 100, 'lǜ,lù,uu'), ('略', 100, 'lüè'), ('嗯', 100, 'ńg'),
            ('鐢', 999999, 'uu'), ('銓', 999999, 'wbn'), ('囷', 999999, 'un'),
            ('邪', 100, 'ie,xié'),
        ])
        db.executemany('INSERT INTO ci VALUES (?, ?, ?)', [
            ('你好', 1000, 'nǐ hǎo'), ('女绿', 1000, 'nǚ lǜ'),
            ('鐢銓', 999999, 'uu wbn'), ('囷邪', 999999, 'un ie'),
        ])
    db.close()
    subprocess.run([sys.executable, '-X', 'utf8', '-I', str(root / 'tools/build_lexicon.py'),
                    str(db_path), str(out_path), '--wanxiang', '', '--chars', '9000', '--words', '60000'], check=True)
    generated = out_path.read_text(encoding='utf8').splitlines()
    entries = [line.split() for line in generated[1:]]
    assert all(set(row[0].split("'")) <= allowed for row in entries)
    assert {'女', '绿', '略', '嗯', '邪', '你好', '女绿'} <= {row[1] for row in entries}
    assert not {'鐢', '銓', '囷', '鐢銓', '囷邪'} & {row[1] for row in entries}
    assert not set(generated[0].split()) & bad

lines = (root / 'app/src/main/assets/lexicon.txt').read_text(encoding='utf8').splitlines()
assert set(lines[0].split()) <= allowed
for i, line in enumerate(lines[1:], 2):
    syllables, word, score = line.split()
    assert set(syllables.split("'")) <= allowed, (i, line)
    assert len(syllables.split("'")) == len(word), (i, line)
    assert 0 <= int(score) <= 99
print(f'PASS: dirty-reading fixture and all {len(lines) - 1} APK entries')
