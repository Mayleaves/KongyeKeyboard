"""
从空叶阅读的离线词典 dict.db 生成空叶键盘的拼音词库。

用法：
  展示页：python -I tools/build_lexicon.py <dict.db> design/lexicon.js
  APK：   python -I tools/build_lexicon.py <dict.db> app/src/main/assets/lexicon.txt --chars 9000 --words 60000

输出扩展名决定格式：
  .js   window.KY_SYL / window.KY_LEX 两个全局变量，给展示页用
  .txt  第一行是音节表（空格分隔），之后一行一条，给 APK 用
每条：拼音（音节用 ' 连接，无声调，ü 写作 v）  字或词  分数（log2 词频 × 4，取整）
音节表按总字频从高到低排列，用于切分拼音。
"""
import argparse
import math
import sqlite3
import unicodedata
from pathlib import Path

from pypinyin import lazy_pinyin

ap = argparse.ArgumentParser()
ap.add_argument('db')
ap.add_argument('out')
ap.add_argument('--chars', type=int, default=4200, help='取字频最高的这么多字')
ap.add_argument('--wanxiang', default=str(Path(__file__).with_name('data') / 'wanxiang-common.tsv'), help='CC BY 4.0 万象精选词库，空字符串禁用')
ap.add_argument('--words', type=int, default=9000, help='取词频最高的这么多 2～4 字词')
args = ap.parse_args()

db = sqlite3.connect(args.db)


def toneless(s):
    s = unicodedata.normalize('NFD', s.strip().lower())
    s = s.replace('ü', 'v').replace('ü', 'v')
    return ''.join(ch for ch in s if not unicodedata.combining(ch))


def is_han(ch):
    return '一' <= ch <= '鿿'


# 普通话完整音节白名单，不能用“任意字母串”判断拼音。
# ü 用 v；j/q/x/y 后按拼音正字法写 u。保留嗯、呣等语气词的独立鼻音。
STANDARD_SYLLABLES = frozenset('''
a ai an ang ao
ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu
pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu
ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu
fa fan fang fei fen feng fo fou fu
da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo
ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo
na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nuo nv nve
la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lv lve
ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo
ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo
ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo
ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun
qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun
xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun
zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo
cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo
sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo
ran rang rao re ren reng ri rong rou ru ruan rui run ruo
za zai zan zang zao ze zei zen zeng zi zong zou zu zuan zui zun zuo
ca cai can cang cao ce cen ceng ci cong cou cu cuan cui cun cuo
sa sai san sang sao se sen seng si song sou su suan sui sun suo
e ei en eng er o ou
ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun
wa wai wan wang wei wen weng wo wu
m n ng hm hng
'''.split())
score = lambda f: max(0, min(99, round(math.log2(f + 1) * 4)))

# —— 单字 ——
# jieba 的单字词频只数“单独成词”的次数，“现”大多出现在“现在”“现代”里，会被严重低估。
# 输入法要的是字的整体使用频率：单字词频 + 所有含这个字的词的词频。
char_total = {}
for c, f in db.execute('SELECT c, f FROM zi WHERE f > 0'):
    char_total[c] = f
for w, f in db.execute('SELECT w, f FROM ci WHERE f > 0'):
    for ch in set(w):
        if ch in char_total:
            char_total[ch] += f

chars, syl_freq, seen_chars = [], {}, 0
rows = [(c, char_total.get(c, f), py) for c, f, py in db.execute('SELECT c, f, py FROM zi WHERE f > 1')]
rows.sort(key=lambda r: -r[1])
for c, f, py in rows:
    if seen_chars >= args.chars:
        break
    if not is_han(c) or not py:
        continue
    readings = []
    for r in py.split(','):
        t = toneless(r)
        if t in STANDARD_SYLLABLES and t not in readings:
            readings.append(t)
    if not readings:
        continue
    seen_chars += 1
    for i, t in enumerate(readings):
        # 多音字：第一个读音用原词频，其余读音打一折
        ff = f if i == 0 else max(1, f // 10)
        chars.append((t, c, score(ff)))
        syl_freq[t] = syl_freq.get(t, 0) + ff

valid_syl = STANDARD_SYLLABLES

# —— 词 ——
COMMON_READINGS = {
    '重庆': "chong'qing", '银行': "yin'hang", '行为': "xing'wei", '行走': "xing'zou",
    '音乐': "yin'yue", '快乐': "kuai'le", '长大': "zhang'da", '长城': "chang'cheng", '长春': "chang'chun",
    '重复': "chong'fu", '重新': "chong'xin", '重要': "zhong'yao", '重量': "zhong'liang",
    '调节': "tiao'jie", '调整': "tiao'zheng", '调度': "diao'du", '了解': "liao'jie", '觉得': "jue'de",
}
words, have, word_keys = [], {}, set()
for w, f, py in db.execute('SELECT w, f, py FROM ci WHERE f > 0 ORDER BY f DESC'):
    if len(words) >= args.words:
        break
    if not (2 <= len(w) <= 8) or not all(is_han(ch) for ch in w) or not py:
        continue
    parts = COMMON_READINGS[w].split("'") if w in COMMON_READINGS else [toneless(p) for p in py.split()]
    if len(parts) != len(w) or not all(p in valid_syl for p in parts):
        continue
    key=(w,"'".join(parts))
    if key in word_keys:
        continue
    word_keys.add(key)
    have.setdefault(w,len(words))
    words.append([key[1], w, score(f)])

words_by_key = {(row[1], row[0]): [row] for row in words}

# —— GitHub 万象精选：采用已校对读音和现代语料词频，保留独立署名与快照清单 ——
upstream_count = 0
if args.wanxiang:
    path = Path(args.wanxiang)
    if not path.is_file():
        raise SystemExit(f'Missing pinned dictionary: {path}')
    for line in path.read_text(encoding='utf-8').splitlines():
        if line.startswith('#'):
            continue
        w, p, frequency = line.split('\t')
        parts = p.split("'")
        if len(parts) != len(w) or not all(x in valid_syl for x in parts):
            continue
        p = COMMON_READINGS.get(w, p)
        key = w, p
        sc = score(int(frequency))
        if key in word_keys:
            for row in words_by_key.get(key, []):
                row[2] = max(row[2], sc)
        else:
            row = [p, w, sc]
            have.setdefault(w, len(words))
            words.append(row)
            word_keys.add(key)
            words_by_key[key] = [row]
        upstream_count += 1

# —— 聊天常用词与键盘相关词：jieba 词频偏新闻语料，这些词排不进前列，单独补上 ——
EXTRA = {
    72: '空叶 空叶键盘 空叶阅读 空叶拼音',
    62: ('谢谢 晚安 拜拜 没事 知道了 好久 你好 你好呀 你们好 键盘 输入法 拼音 皮肤 我爱你 早安 早上好 晚上好 '
         '谢谢你 不客气 没关系 对不起 好的 好吧 好呀 哈哈 哈哈哈 嗯嗯 可爱 好看 美观 喜欢 开心 想你 在吗 在干嘛 '
         '干嘛 吃饭了吗 睡觉 加油 辛苦了 收到 明天见 周末 一起 今天 明天 晚上 下班 上班 回家 等你 好久不见 生日快乐'),
    56: ('九宫格 十八键 水墨 宣纸 青竹 机械 极光 玻璃 像素 掌机 霓虹 雾蓝 素笺 石墨 晴空 墨夜 夜岩 看板猫 小猫 '
         '猫咪 落叶 红叶 一叶知秋 打字 手写 语音 表情 符号 数字 换行 发送 搜索 剪贴板 主题 布局 试试 试一下 '
         '还不错 太好了 真好看 换一套 选这套 好喜欢 键帽 分体'),
}
EXTRA[62] += (' 不好意思 没问题 怎么回事 没想到 已经好了 应该可以 什么时候 怎么了 可以吗 没事的 等一下 '
    '稍等 马上回来 一会儿再说 晚点再说 等会儿 有空吗 一会儿 好了吗 真棒 太棒了 真的嘛 真的假的 当然可以 '
    '你说得对 我知道了 没听清楚 再说一遍 明白了 记住了 不知道 不确定 看一下 看到了 找到了 '
    '还没有 已经可以了 没有了 有时候 没什么 这么回事 为什么 怎么办 怎么样 还有吗 还有什么 '
    '什么时候回来 你在哪里 要不要 需不需要 可不可以 能不能 应不应该 没有问题 不用谢 不用了 不需要 '
    '一路平安 注意安全 路上小心 身体健康 早点休息 好好休息 新年快乐 节日快乐 周末愉快')
EXTRA[56] += (' 自动纠错 自动更新 自定义 收藏 撤回 撤销 重做 取消 确认 删除 置顶 分词 文档 文件 文件夹 '
    '照片 视频 音频 图片 链接 网页 光标 段落 段首 段尾 留白 调节 调整 高度 宽度 透明度 '
    '流畅 不流畅 卡住 会卡住 没反应 没有反应 闪烁 抖动 拖动 滑动 选择 全选 输入框 候选词 '
    '翻译 普通话 四川话 识别 麦克风 离线 中文 英文 拼音 音节 词库 模糊音 连续删除 长按删除 '
    '工作 学习 开会 快递 外卖 地铁 公交 高铁 火车 飞机 打车 付款 支付 转账 订单 地址 电话 '
    '上午 下午 中午 晚饭 午饭 早饭 早餐 午餐 晚餐 今天晚上 明天早上 后天 昨天 刚才 现在 以后 '
    '银行 行为 音乐 快乐 长大 长城 重庆 重复 重新 重要 重量 调节 调整 调度 了解 觉得')
for sc, s in EXTRA.items():
    for w in s.split():
        if w in have:
            row = words[have[w]]
            row[2] = max(row[2], sc)
            continue
        parts = COMMON_READINGS[w].split("'") if w in COMMON_READINGS else lazy_pinyin(w)
        if all(p in valid_syl for p in parts):
            have[w]=len(words)
            words.append(["'".join(parts), w, sc])

syls = sorted(syl_freq, key=lambda s: -syl_freq[s])
lines = [f'{p} {w} {s}' for p, w, s in chars + words]

with open(args.out, 'w', encoding='utf8', newline='\n') as fp:
    if args.out.endswith('.js'):
        fp.write('/* 空叶键盘展示页词库：由 tools/build_lexicon.py 从空叶阅读 dict.db 生成，'
                 f'{len(chars)} 个字音、{len(words)} 个词。 */\n')
        fp.write('window.KY_SYL="' + ' '.join(syls) + '";\n')
        fp.write('window.KY_LEX=`' + '\n'.join(lines) + '`;\n')
    else:
        fp.write(' '.join(syls) + '\n')
        fp.write('\n'.join(lines) + '\n')

print('syllables', len(syls), 'char readings', len(chars), 'words', len(words), 'upstream', upstream_count)
