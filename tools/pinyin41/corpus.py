"""Author and audit synthetic 4.1 candidate corpora. --check never rewrites fixtures."""
from pathlib import Path
import argparse
import hashlib
import json

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'app/src/androidTest/assets/pinyin41'
# All spelling variants of a phrase family stay in one split. Previously inspected
# words are development/regression material, never advertised as unseen benchmarks.
BASE = {
    'train': [
        ('ni hao', '你好'), ('jue de', '觉得'), ('huang gua', '黄瓜'),
        ('bei jing', '北京/背景'), ('shan xi', '山西/陕西'), ('ping an', '平安'),
        ('zhang ai', '障碍'), ('xian', '先/线/现'), ('shi shi', '实施/事实/试试'),
        ('yin hang', '银行'), ('xie xie', '谢谢'), ('zai jian', '再见'),
        ('jin tian', '今天'), ('shu ru fa', '输入法'), ('peng you', '朋友'),
        ('shou ji', '手机'), ('dian hua', '电话'), ('shi jian', '时间'),
        ('xue xi', '学习'), ('gong zuo', '工作'), ('ran liao', '燃料'), ('lv se', '绿色'),
    ],
    'validation': [
        ('tian qi', '天气'), ('bao gao', '报告'), ('kuai le', '快乐'),
        ('fang fa', '方法'), ('wen jian', '文件'), ('xi huan', '喜欢'),
        ('yin wei', '因为'), ('shu ju', '数据'), ('que ding', '确定'),
        ('hui jia', '回家'), ('zhang dan', '账单'), ('chan pin', '产品'), ('lan tian', '蓝天'),
    ],
    'test': [
        ('bing xiang', '冰箱'), ('yu san', '雨伞'), ('yu mao qiu', '羽毛球'),
        ('kao hong shu', '烤红薯'), ('tu shu guan', '图书馆'), ('xi yi ji', '洗衣机'),
        ('kuai di gui', '快递柜'), ('hang ban', '航班'), ('di tie', '地铁'),
        ('can ting', '餐厅'), ('fen bi', '粉笔'), ('xin qing', '心情'),
        ('liang shui', '凉水'), ('guan li', '管理'),
    ],
}
FUZZY = {
    'train': [('shou ji', 'souji', 's-sh'), ('ran liao', 'lanliao', 'r-l'),
              ('shan xi', 'shangxi', 'an-ang'), ('lv se', 'luse', 'v-u')],
    'validation': [('zhang dan', 'zangdan', 'z-zh'), ('chan pin', 'canpin', 'c-ch'),
                   ('lan tian', 'nantian', 'n-l'), ('fang fa', 'hangfa', 'f-h')],
    'test': [('fen bi', 'fengbi', 'en-eng'), ('xin qing', 'xingqing', 'in-ing'),
             ('liang shui', 'lianshui', 'ian-iang'), ('guan li', 'guangli', 'uan-uang')],
}
TYPOS = {
    'train': [('ni hao', 'nuhao', 1), ('jue de', 'jeude', 2),
              ('ni hao', 'nihaoo', 4), ('ni hao', 'niho', 8)],
    'validation': [('shu ju', 'shyju', 1), ('kuai le', 'kuiale', 2),
                   ('wen jian', 'wenjiann', 4), ('xi huan', 'xihun', 8)],
    'test': [('xi yi ji', 'xuyiji', 1), ('bing xiang', 'bingxaing', 2),
             ('yu san', 'yusann', 4), ('tu shu guan', 'tushugan', 8)],
}
LONG = {
    'train': ('wo zhen de bu zhi dao ni zai shuo shen me', '我真的不知道你在说什么'),
    'validation': ('zhou mo qu gong yuan san bu', '周末去公园散步'),
    'test': ('chu men ji de dai shang yu san', '出门记得带上雨伞'),
}


def make(split):
    cases = []
    def add(family, category, raw, words=(), required=(), **extra):
        cases.append(dict(id=f'{split[:2].upper()}-{len(cases)+1:03d}', family=family,
                          category=category, input=raw, expectedTop3=list(words)[:3], mustInclude=list(required),
                          reason=extra.pop('reason', '按完整消费检查词语覆盖；前三排名只作参考，不强制唯一顺序。'), **extra))
    for reading, words in BASE[split]:
        syllables = reading.split()
        targets = words.split('/')
        family = reading.replace(' ', '')
        add(family, '标准全拼', family, targets, targets)
        add(family, '声母简拼', ''.join(s[0] for s in syllables), targets, targets)
        if len(syllables) > 1:
            add(family, '全拼加简拼', ''.join(syllables[:-1]) + syllables[-1][0], targets, targets)
            add(family, '手动隔音符', "'".join(syllables), targets, targets)
    by_reading = dict(BASE[split])
    for reading, raw, rule in FUZZY[split]:
        targets = by_reading[reading].split('/')
        family = reading.replace(' ', '')
        add(family, '模糊音关闭', raw, (), (), forbiddenFull=targets,
            reason=f'关闭纠错与 {rule} 时，不得通过该映射完整匹配目标词。')
        add(family, '模糊音开启', raw, targets, targets, fuzzy=[rule],
            reason=f'只开启 {rule}，目标词可被找到；关闭后再次检查映射是否泄漏。')
    for reading, raw, flag in TYPOS[split]:
        targets = by_reading[reading].split('/')
        family = reading.replace(' ', '')
        add(family, '纠错关闭', raw, (), (), forbiddenFull=targets,
            reason='关闭纠错时不得仍以该错拼完整消费目标词；其他合法简拼及短候选可以保留。')
        add(family, '单类纠错开启', raw, targets, targets, correction=flag, offAbsent=targets,
            reason=f'只开启纠错位 {flag}，检查目标覆盖和关闭后的对照；不能因一个预期调整整批词频。')
    reading, word = LONG[split]
    add(reading.replace(' ', ''), '连续长句', reading.replace(' ', ''), [word], [word], walk=reading.split())
    noise = {'train': 'z'*32, 'validation': 'k'*32, 'test': 'qv'*16}[split]
    add('noise-'+split, '无语义边界', noise, reason='无唯一语义答案；检查有界查询、消费长度和取消，不要求生成整句。')
    add('single-'+split, '单字母边界', {'train':'d', 'validation':'r', 'test':'t'}[split],
        reason='允许合法单字与未完成音节，检查有界候选；不从一两个首字母推断任意个人长词。')
    return {'version':1, 'targetVersion':'4.1.0', 'split':split, 'synthetic':True, 'cases':cases}


def sha(data): return hashlib.sha256(data).hexdigest()


def check():
    manifest = json.loads((ASSETS/'manifest.json').read_text('utf8'))
    for split, edits in TYPOS.items():
        for reading, typed, flag in edits:
            expected = reading.replace(' ', '')
            if flag == 1:
                differences = [(a,b) for a,b in zip(typed, expected) if a != b]
                valid = len(typed) == len(expected) and len(differences) == 1 and any(
                    all(ch in row for ch in differences[0]) and abs(row.index(differences[0][0])-row.index(differences[0][1])) == 1
                    for row in ('qwertyuiop','asdfghjkl','zxcvbnm'))
            elif flag == 2:
                valid = any(typed == expected[:n]+expected[n+1]+expected[n]+expected[n+2:] for n in range(len(expected)-1))
            elif flag == 4:
                valid = len(typed) == len(expected)+1 and any(typed[:n]+typed[n+1:] == expected for n in range(len(typed)))
            else:
                valid = len(expected) == len(typed)+1 and any(expected[:n]+expected[n+1:] == typed for n in range(len(expected)))
            assert valid, f'Wrong synthetic error label: {split} {typed} bit={flag}'
    seen_families, seen_inputs = {}, {}
    for split in ('train', 'validation', 'test'):
        raw = (ASSETS/f'{split}.json').read_bytes()
        assert sha(raw) == manifest['splits'][split]['sha256'], f'Frozen fixture changed: {split}'
        data = json.loads(raw)
        cases = data['cases']
        assert data['split'] == split and len(cases) == manifest['splits'][split]['cases']
        assert len({c['id'] for c in cases}) == len(cases)
        for case in cases:
            family = case['family']
            assert seen_families.get(family, split) == split, f'Family leaks between splits: {family}'
            seen_families[family] = split
            assert case['input'] and all(c in "abcdefghijklmnopqrstuvwxyz'" for c in case['input'])
            # Exact full spellings with/without a separator are intentionally related.
            # Short abbreviation collisions are language ambiguity, not the same target family.
            signature = case['input'].replace("'", '')
            if len(signature) >= 4:
                assert seen_inputs.get(signature, split) == split, f'Input leaks between splits: {signature}'
                seen_inputs[signature] = split
        print(f'{split}: {len(cases)} cases, {len({c["family"] for c in cases})} families; SHA256 verified')
    print('PASS: family separation, full-input separation, schema and frozen fixture hashes')


def write():
    ASSETS.mkdir(parents=True, exist_ok=True)
    assert not (ASSETS/'manifest.json').exists(), 'Do not silently regenerate frozen corpora; create a new revision'
    manifest = {'version':1, 'targetVersion':'4.1.0', 'authoredDate':'2026-11-26',
                'provenance':'Manually authored synthetic development corpus; not a public benchmark or private typing history.',
                'splits':{}}
    for split in ('train', 'validation', 'test'):
        data = make(split)
        raw = (json.dumps(data, ensure_ascii=False, indent=2)+'\n').encode('utf8')
        (ASSETS/f'{split}.json').write_bytes(raw)
        manifest['splits'][split] = {'cases':len(data['cases']), 'families':len({c['family'] for c in data['cases']}), 'sha256':sha(raw)}
    (ASSETS/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf8')
    check()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true', help='Initial authoring only; refuses existing frozen corpus')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    write() if args.write else check()
