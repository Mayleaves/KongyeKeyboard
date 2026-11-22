package com.kongye.keyboard

import android.content.Context

/** Searchable setting names, descriptions and choices, including sections normally collapsed. */
internal class SettingsSearchCatalog(context: Context, summary: (Page) -> String) {
    private val choices = mapOf(
        Page.FUZZY to Prefs.FUZZY_RULES.map { it.second },
        Page.FLICKS to KeyGestures.commands.values.toList(),
        Page.TRANSLATION to Translator.TARGETS.map { it.second },
        Page.RESOURCES to ResourcePacks.catalog(context).flatMap { listOf(it.title, it.summary) },
        Page.LOOK to Themes.all(context).map { it.name },
    )
    private val pages = settingsCopy.map { (page, copy) ->
        page to (listOf(page.title, summary(page)) + copy.split('|').map { it.trim() } + choices[page].orEmpty())
            .filter { it.isNotBlank() }.distinct()
    }

    fun find(query: String): List<Pair<Page, List<String>>> {
        if (query.isBlank()) return emptyList()
        return pages.mapNotNull { (page, fields) ->
            val matches = fields.filter { it.contains(query, ignoreCase = true) }
                .sortedBy { it.length }.take(4)
            matches.takeIf { it.isNotEmpty() }?.let { page to it }
        }.sortedBy { (page, matches) ->
            when {
                page.title.equals(query, ignoreCase = true) -> 0
                matches.any { it.equals(query, ignoreCase = true) } -> 1
                page.title.contains(query, ignoreCase = true) -> 2
                else -> 3
            }
        }
    }

    private companion object {
        val settingsCopy = linkedMapOf(
            Page.READY to "启用输入法|在系统设置中启用空叶键盘|选择输入法|将空叶键盘设为当前输入法|输入法状态与功能权限",
            Page.PERMISSIONS to "麦克风|语音输入使用|接收短信|复制新短信中的验证码|通知|语音输入进行时显示状态和停止按钮|可选",
            Page.INPUT to "拼音、英文、手势、滑行、语音与手写",
            Page.PINYIN to """拼音输入|简拼|例如 zg → 中国|中英混输|中文与英文连续输入，无需切换键盘|选词与符号|标点确认首选|按标点时，先选中候选栏的第一个词|成对符号|输入左括号时补齐右括号，光标停在中间|词库与学习|整句增强词库|基础拼音已可用，可下载整句增强词库|已下载；基础词库已可用|管理|记住选词|将常用选择排在前面|清除选词记录|清除本机学习的选词与自造词|清除""",
            Page.CORRECTION to "邻键纠错|处理误触到相邻按键|顺序纠错|处理相邻字母顺序颠倒|多输纠错|处理多输入的字母|漏输纠错|处理遗漏的字母",
            Page.FUZZY to "近似读音匹配|当前严格区分各组拼音；可按自己的发音习惯开启。|声母|韵母|平翘舌",
            Page.ENGLISH to "单词输入|大小写|英文自动空格|混合输入空格|大写锁定|Caps Lock|Shift|开启后点击大写键持续大写，再点解除；关闭时只大写一个字母|首字母自动大写|英文单词纠错|选词后自动加空格|英文标点后自动加空格|邮箱后缀推荐|中英文之间自动加空格|中文与数字之间自动加空格",
            Page.ASSOC to "识别英文单词|英文候选|github|claude|Anthropic|中文输入时识别 GitHub、Claude、Anthropic 等英文词|词语联想|修改后的推荐|表情与符号推荐|联想词|联想方式|单次联想|连续联想|空格确认联想|推荐 Emoji|推荐表情|推荐符号|推荐颜文字|删除后推荐同音字",
            Page.KEYS to "按键反馈|按键震动|跟随系统|强弱跟随手机的触感设置；想自行调整强弱，选择「启用」|启用|关闭|震动强度|无|弱|较弱|适中|较强|强|按键音|按键音量|仅振动|静音|字母键|长按字母|大小写与符号|输入大写|空格与光标|空格长按|移动光标|连续空格|开关语音|底部滑动移光标|滑动与删除",
            Page.FLICKS to "逗号|句号|空格左右|，|。|启用与方向|选择按键|设置动作|符号文字|启用上下滑|反转上下滑方向|仅反转手势方向，角标位置保持不变|中文|英文|上滑 · 右上角|下滑 · 左下角|下滑 · 右上角|上滑 · 左下角|自定义符号|输入后预览，保存后应用|输入自定义符号或文字|编辑功能|清空中文上下滑|清空英文上下滑|清空当前语言的字母、逗号和句号映射|恢复中文默认|恢复英文默认|保存|清空|重置",
            Page.DELETE to "滑动与清空|联想期间|滑动动作|重置|上滑|下滑|左滑|右滑|不执行动作|清空|撤回|重做|删除|左移光标|右移光标|清空范围|整个输入框|仅光标前|仅当前段落|只清空当前段落|联想时直接删除|关闭后，第一次退格或清空只收起联想词|组字期间|退格还原原始拼音|已选词未上屏时，开启后退格还原拼音；关闭后从末尾逐字母删除|长按清空整段拼音|长按删除键清空当前整段拼音；关闭时连续退格",
            Page.GLIDE to "启用语言|滑行反馈|中文回删|中文滑行|英文滑行|实时显示候选|显示滑行轨迹|按字回删中文滑行|每次删除最后一个汉字对应的编码；关闭后每次删一个字母",
            Page.VOICE to "模型与语言|听写与标点模型|普通话、四川话及语音标点，下载后离线使用|识别语言|普通话|四川话|播放与连续听写|外放音处理|不处理|暂停播放|压低音量|收起后连续听写|收起为听写条，发送后返回输入框自动继续聆听|转写整理|自动标点|阿拉伯数字|去除口头语|清理嗯、啊和重复表达|语句润色",
            Page.POLISH to "润色设置|接口配置|连接测试|整理语序与表达；预览后确认，可恢复原文|润色方式|离线模型|联网接口|离线润色模型|Qwen3 0.6B，约 640 MB，按需下载|联网润色接口|Chat Completions|接口地址|模型名称|API 密钥（可选）|保存配置|清除密钥|测试润色|使用下面的示例测试已保存的配置，不读取聊天或剪贴板内容。|测试已保存的接口|取消测试",
            Page.HAND to "显示带声调读音|单字显示多种读音，词语按词注音|笔迹识别模型|下载后使用神经网络识别；未下载时使用基础备用识别",
            Page.APPEARANCE to "主题、布局、尺寸与候选显示",
            Page.LOOK to "当前效果|明暗模式|背景效果|深色模式|跟随系统|浅色模式|夜间|液态玻璃|键盘透明度|配色方案",
            Page.LAYOUT to "横竖屏布局|横竖屏分别记住布局|竖屏布局|横屏布局|共用布局|中文键位|26 键|14 键|九键|键盘形态|正常键盘|分体键盘|悬浮键盘|悬浮分体键盘|单手使用|单手模式|靠左|靠右|分体选项|悬浮选项|竖屏|横屏|铺满键盘空白区域",
            Page.HEIGHT to "悬浮尺寸|悬浮宽度|实时预览|重置|完整键盘|分体键盘|整体尺寸|键盘高度|左右收进|左侧收进|右侧收进|底部留白|分体设置|分体间距|分体按键尺寸|悬浮键盘|可在键盘调整面板中拖动位置与宽度",
            Page.NUMPAD to "恢复默认|点选描边区域，在下方修改|6 右边|9 右边|0 右边|左侧上下滑动符号列|左列符号|选择功能或符号|空格|自定义字符|设为该键|左列符号顺序|单击选中，再点取消；长按拖动调整位置|删除选中|点灰色符号加入左列，点红色符号移除",
            Page.ARRANGE to "工具栏|菜单|长按拖动调整顺序|排序|显示的功能|选择功能并调整顺序",
            Page.DISPLAY to "输入中的拼音|候选词辅助信息|输入拼音位置|键盘上方气泡|候选栏内|应用输入框内|候选读音|候选翻译|自动|显示|隐藏|显示纠错标记",
            Page.TOOLS to "密码、剪贴板、翻译、统计与备份",
            Page.VAULT to "开始使用|开启密码空间|在空叶文档中允许键盘读取|请先安装对应版本的空叶文档|设置自动填充服务|选择空叶文档，在键盘显示账号候选|登录时点选键盘上的账号|找不到账号时点「更多密码」；未解锁时先验证身份。|更多信息|连接排查|选错账号|关联|隐私与支持范围",
            Page.CLIP to "记录上限|100 条|500 条|1000 条|5000 条|保留时间|一直保留|1 天|7 天|30 天|剪贴板记录|总记录|置顶|图片|只保存在这台手机上|达到上限时，先移除最旧的普通记录|清空历史|保留置顶和自定义分类|清空",
            Page.SMS to "后台复制权限|权限管理|其他权限|通知类短信|写入剪贴板|始终允许|自动复制|权限与设置|已确认|短信验证码自动复制|仅处理新短信，10 分钟后清除，不进入剪贴板历史|自动复制设置流程|短信权限|应用权限",
            Page.TRANSLATION to "目标语言",
            Page.STATS to "今天|近30天|全年|速度|最快|连续天数|累计字数|每日字数|一年前|本周|近一年活跃|单日最高|时段分布|效率|平均码长|首选率|联想上屏|粘贴|各输入方式速度|速度按连续输入时间计算，语音和粘贴不计速度。|分享文字|分享图片|纠错|自动纠错|错键 · 漏键 · 多键 · 颠倒|删除键占比|常见误触|字母误触热图|累计|按键次数|输入时长|折合800字作文|清空统计|删除本机统计记录|记录打字统计|关闭后停止记录，已有数据保留",
            Page.BACKUP to "新建备份|管理备份|删除备份|删除本地备份|删除云端备份|选择文件|查看|备份频率|仅手动|每天|每周|每30天|自动备份|联网且手机空闲时自动上传|最近自动备份|恢复默认设置|重置全部设置|重置全部功能配置|保留个人数据|本地备份|WebDAV 备份|保存备份文件|导出为文件，保存到你选择的位置|从文件恢复|选择备份，校验后确认恢复|备份内容|设置|自造词与选词记录|打字统计|剪贴板|包含文本、图片和自定义分类|离线模型、系统权限和密钥不备份|服务器配置|手动上传与恢复|服务器地址（HTTPS）|账号|密码 / 应用专用密码|保存配置|测试连接|上传备份|按上方选定内容创建新文件，保留已有备份|从 WebDAV 恢复|选择云端备份，校验后确认恢复|清除服务器配置",
            Page.RESOURCES to "下载|删除|取消|继续|已安装|未安装|基础词库和学习记录保留|可随时重新下载",
            Page.ABOUT to "版本检查与更新记录",
            Page.HISTORY to "搜索版本或更新内容|上一页|下一页|详情|Markdown|GitHub|源代码",
            Page.UPDATE to "正在检查更新|无需更新|发现新版本|可以直接安装|需先允许安装|暂无适配本机的安装包|需先下载安装包|点击确认下载更新，下载完成后可继续安装。|取消下载|APK|确认",
        )
    }
}
