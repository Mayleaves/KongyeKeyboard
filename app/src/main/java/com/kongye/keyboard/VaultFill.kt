package com.kongye.keyboard

import android.app.PendingIntent
import android.app.ActivityOptions
import android.os.Build
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.util.concurrent.Executors

/**
 * 输入框分类（需求文档 v2 第 5.1 节里键盘能用的部分：只看得到输入类型和提示文字）。
 * Only metadata may be shown. Every plaintext result is consumed then its Bundle cleared.
 */
@androidx.annotation.Keep
object VaultFields {
    enum class Kind { OTHER, ACCOUNT, PASSWORD, NEW_PASSWORD, CONFIRM, CURRENT }
    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val OTP = re("""验证码|校验码|动态码|动态密码|短信码|captcha|\botp\b|verif(?:y|ication)?\s*code""")
    private val CONFIRM = re("""确认|再次|重复|再输|confirm|repeat|again|re-?enter|retype""")
    private val CURRENT = re("""原密码|旧密码|当前密码|current\s*-?password|old\s*-?password""")
    private val NEW = re("""新密码|设置.{0,4}密码|设定密码|创建密码|new\s*-?password|create\s*-?password|set\s*-?password""")
    private val PHONE = re("""手机|电话|mobile|phone""")
    private val EMAIL = re("""邮箱|e-?mail""")
    private val USER = re("""账号|帐号|账户|帐户|用户名|登录名|会员名|user|account|login""")

    /** 提示文字、标签和字段名拼在一起 */
    fun label(info: EditorInfo): String = listOfNotNull(info.hintText?.toString(), info.label?.toString(), info.fieldName).joinToString(" ")

    fun kind(info: EditorInfo): Kind {
        val label = label(info)
        if (OTP.containsMatchIn(label)) return Kind.OTHER
        val password = passwordType(info)
        if (password && CONFIRM.containsMatchIn(label)) return Kind.CONFIRM
        if (password && CURRENT.containsMatchIn(label)) return Kind.CURRENT
        if (password && NEW.containsMatchIn(label)) return Kind.NEW_PASSWORD
        if (password) return Kind.PASSWORD
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
            || cls == InputType.TYPE_CLASS_PHONE || PHONE.containsMatchIn(label) || EMAIL.containsMatchIn(label) || USER.containsMatchIn(label)) return Kind.ACCOUNT
        return Kind.OTHER
    }

    /** 账号框是哪一种：phone / email / username（保存时分别存成字段） */
    fun accountType(info: EditorInfo): String {
        val label = label(info)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when {
            cls == InputType.TYPE_CLASS_PHONE || PHONE.containsMatchIn(label) -> "phone"
            cls == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) || EMAIL.containsMatchIn(label) -> "email"
            else -> "username"
        }
    }

    val SECRET = setOf(Kind.PASSWORD, Kind.NEW_PASSWORD, Kind.CONFIRM, Kind.CURRENT)
    /** 填已存的密码：普通密码框和改密码页的当前密码框 */
    val LOGIN = setOf(Kind.PASSWORD, Kind.CURRENT)
    fun passwordType(info: EditorInfo): Boolean {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) || cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
    fun sensitive(info: EditorInfo) = passwordType(info) || kind(info) in SECRET

    /** 打码：邮箱留前 2 位和域名，11 位手机号留前 3 后 4，其余留首尾（与空叶文档一致） */
    fun mask(account: String): String {
        val a = account.trim()
        if (a.isEmpty()) return ""
        val at = a.indexOf('@')
        if (at > 0) return a.take(minOf(2, at)) + "****" + a.substring(at)
        if (a.length == 11 && a.all { it.isDigit() }) return a.take(3) + "****" + a.takeLast(4)
        return when {
            a.length <= 2 -> a.take(1) + "*"
            a.length <= 5 -> a.take(1) + "***" + a.takeLast(1)
            else -> a.take(2) + "****" + a.takeLast(2)
        }
    }
}

/** Fixed authorities, package ownership and signature checked before every cross-app call. */
@androidx.annotation.Keep
class VaultGateway(private val context: Context) {
    @Volatile var lastFailure = ""
        private set
    companion object {
        val PACKAGES = listOf("com.kongye.docs", "com.kongye.docs.audit", "com.kongye.docs.docktest", "com.kongye.docs.upgradefixture")
    }
    fun owner() = Prefs.of(context).getString(Prefs.VAULT_PACKAGE, PACKAGES[0]).takeIf { it in PACKAGES } ?: PACKAGES[0]
    fun call(method: String, extras: Bundle): Bundle {
        lastFailure = ""
        if (!Prefs.of(context).getBoolean(Prefs.VAULT, false)) return Bundle().apply { putString("status", "disabled") }
        val owner = owner()
        val provider = context.packageManager.resolveContentProvider("$owner.vault", 0)
            ?: return Bundle().apply { putString("status", "not_found") }
        if (provider.packageName != owner || context.packageManager.checkSignatures(context.packageName, owner) != PackageManager.SIGNATURE_MATCH)
            return Bundle().apply { putString("status", "denied") }
        // Use the application attribution, not an IME window/display context.
        return try { context.applicationContext.contentResolver.call(Uri.parse("content://$owner.vault"), method, null, extras)
            ?: Bundle().apply { putString("status", "error") }
        } catch (e: Exception) {
            lastFailure=e.javaClass.simpleName
            Bundle().apply { putString("status", "error") }
        }
    }
    fun unlock(intent: PendingIntent): Boolean {
        if(!Prefs.of(context).getBoolean(Prefs.VAULT,false)) return false
        if (intent.creatorPackage != owner() || context.packageManager.checkSignatures(context.packageName, owner()) != PackageManager.SIGNATURE_MATCH) return false
        return runCatching {
            val options = if(Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(if(Build.VERSION.SDK_INT >= 36)
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                else @Suppress("DEPRECATION") ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle() else null
            intent.send(context,0,null,null,null,null,options)
            true
        }.getOrDefault(false)
    }
}

/**
 * 键盘直连密码空间（路线 B，需求文档 v2 第 9.2 节）。
 * No preferences, clipboard, logging or learning for secrets. Main-looper state, serialized IPC.
 */
@androidx.annotation.Keep
class VaultFill(
    private val enabled: () -> Boolean,
    private val call: (String, Bundle) -> Bundle,
    private val unlock: (PendingIntent) -> Boolean,
    private val fill: (String) -> Boolean,
    private val next: () -> Unit,
    private val changed: () -> Unit,
    private val notice: (String) -> Unit,
    private val searchUi: () -> Unit,
    private val appName: (String) -> String,
    private val panelUi: () -> Unit = searchUi,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    data class Entry(val id: String, val name: String, val account: String, val matchedBy: String) {
        val certMismatch get() = matchedBy == "cert_mismatch"
    }
    data class Action(val label: String, val run: () -> Unit)
    private data class Target(val pkg: String, val field: Int, val kind: VaultFields.Kind, val hint: String, val inputType: Int, val browser: Boolean, val accountType: String = "username")
    private data class Pending(val id: String, val target: Target, val until: Long, val bind: Boolean, val confirmCert: Boolean = false)

    /** 同一个 App 的一页（10 分钟内、没换过 App）：账号类字段、页面类型、当前密码、还没确认的新密码 */
    private class PageState(val pkg: String, val since: Long) {
        val fields = LinkedHashMap<String, CharArray>()
        var register = false
        var change = false
        var current = CharArray(0)
        var heldNew = CharArray(0)
        fun wipe() { fields.values.forEach { it.fill('\u0000') }; fields.clear(); current.fill('\u0000'); current = CharArray(0); heldNew.fill('\u0000'); heldNew = CharArray(0) }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var epoch = 0
    @Volatile private var resetEpoch = 0
    private var secretEpoch = 0
    private var queryEpoch = 0
    private var target: Target? = null
    private var active = false
    private var available = false
    private var dismissed = false
    private var entries = emptyList<Entry>()
    private var pendingUnlock: Pending? = null
    private var pendingNext: Pending? = null
    private var account = CharArray(0)
    private var accountPkg = ""
    private var typed = CharArray(0)
    private var capture = false
    private var page: PageState? = null
    private var savePassword = CharArray(0)
    private var savePkg = ""
    private var saveBrowser = false
    private var saveFields = LinkedHashMap<String, CharArray>()
    private var saveKind = "login"
    private var saveCurrent = CharArray(0)
    private var generated = CharArray(0)
    private var generatedPkg = ""
    private var generatedAt = 0L
    private var saveUntil = 0L
    /** 空叶文档的能力（接口 v1.1 的 features）和是否是系统自动填充服务 */
    private var features = emptySet<String>()
    private var docsAutofill = false
    /** 系统推送的自动填充候选正显示在工具栏里（路线 A）：这个 App 不再弹键盘自己的匹配和保存提示 */
    private var inlinePkg = ""
    /** 签名不同的条目：第一次点只提醒，10 秒内再点才填 */
    private var armedCert = ""
    private var armedUntil = 0L
    /** 同一次保存 60 秒内不重复提示（第 8 节第 4 条） */
    private var lastPrompt = ""
    private var lastPromptAt = 0L
    private val expireSave = Runnable { wipeSave(); changed() }
    private val expireGenerated = Runnable { generated.fill('\u0000'); generated = CharArray(0); changed() }
    private val expireUnlock = Runnable { pendingUnlock = null }

    /** Diagnostic flags only: never include entry metadata, input or secrets. */
    fun diagnosticState() = "kind=${target?.kind} active=$active available=$available dismissed=$dismissed enabled=${enabled()} features=${features.size} autofill=$docsAutofill inline=${inlinePkg.isNotEmpty()}"

    private fun request(method: String, extras: Bundle = Bundle(), done: (Bundle) -> Unit) {
        val version = epoch
        val boundary = resetEpoch
        worker.execute {
            // Explicit save/bind survives account → password focus, but never a reset
            // caused by locking the screen or changing the selected password source.
            val confirmedWrite = (method=="save" || method=="bind") && boundary==resetEpoch
            val result = try { if (enabled() && (version==epoch || confirmedWrite)) call(method, extras) else Bundle().apply { putString("status", "disabled") } }
                catch (_: Exception) { Bundle().apply { putString("status", "error") } }
            extras.clear()
            main.post {
                try { if (version == epoch && enabled()) done(result) }
                finally { result.clear() }
            }
        }
    }

    private fun autofillActive(pkg: String) = docsAutofill && inlinePkg.isNotEmpty() && inlinePkg == pkg

    /** 账号框里打的字记进这一页 */
    private fun commitAccount(t: Target?) {
        val p = page ?: return
        if (t == null || t.kind != VaultFields.Kind.ACCOUNT || account.isEmpty() || accountPkg != p.pkg) return
        p.fields[t.accountType]?.fill('\u0000')
        p.fields[t.accountType] = account.copyOf()
    }

    /** 离开一个密码框：按框的类型决定是提示保存、先记着，还是只当作当前密码 */
    private fun retireTyped(nextKind: VaultFields.Kind?, promoteHeld: Boolean = true) {
        val t = target
        val p = page
        if (typed.isNotEmpty() && t != null && p != null && p.pkg == t.pkg) {
            when (t.kind) {
                VaultFields.Kind.CURRENT -> { p.current.fill('\u0000'); p.current = typed.copyOf() }
                VaultFields.Kind.NEW_PASSWORD -> { p.heldNew.fill('\u0000'); p.heldNew = typed.copyOf() }
                VaultFields.Kind.CONFIRM -> {
                    // 两次不一样：用户自己输错了，不提示
                    if (p.heldNew.isEmpty() || p.heldNew.contentEquals(typed)) prompt(typed)
                    p.heldNew.fill('\u0000'); p.heldNew = CharArray(0)
                }
                else -> prompt(typed)
            }
        }
        typed.fill('\u0000'); typed = CharArray(0); capture = false
        // 新密码已经输好、但没有接着去确认框：现在提示
        if (promoteHeld && p != null && p.heldNew.isNotEmpty() && nextKind != VaultFields.Kind.CONFIRM && nextKind != VaultFields.Kind.NEW_PASSWORD) {
            prompt(p.heldNew); p.heldNew.fill('\u0000'); p.heldNew = CharArray(0)
        }
    }

    private fun prompt(password: CharArray) {
        val t = target ?: return
        val p = page ?: return
        if (autofillActive(t.pkg)) return // 系统自动填充会提示保存
        val key = t.pkg + "\u0000" + String(password).hashCode()
        if (key == lastPrompt && clock() - lastPromptAt < 60000) return
        wipeSave()
        savePassword = password.copyOf(); savePkg = t.pkg; saveBrowser = t.browser
        p.fields.forEach { (k, v) -> saveFields[k] = v.copyOf() }
        saveKind = if (p.change) "change" else if (p.register) "register" else "login"
        saveCurrent = p.current.copyOf()
        saveUntil = clock() + 30000
        lastPrompt = key; lastPromptAt = clock()
        main.postDelayed(expireSave, 30000)
    }

    fun focus(info: EditorInfo, browser: Boolean = false, emptyField: Boolean = true) {
        val kind = VaultFields.kind(info)
        val fresh = Target(info.packageName.orEmpty(), info.fieldId, kind, info.hintText?.toString().orEmpty(), info.inputType, browser,
            if (kind == VaultFields.Kind.ACCOUNT) VaultFields.accountType(info) else "username")
        if (fresh != target) { secretEpoch++; commitAccount(target); retireTyped(fresh.kind) }
        if (target?.pkg != fresh.pkg) {
            wipeSave(); account.fill('\u0000'); account = CharArray(0); accountPkg = ""
            if (generatedPkg != fresh.pkg) { generated.fill('\u0000'); generated = CharArray(0) }
            if (inlinePkg != fresh.pkg) inlinePkg = ""
        }
        if (page?.pkg != fresh.pkg || clock() - (page?.since ?: 0L) > 600000) { page?.wipe(); page = PageState(fresh.pkg, clock()) }
        page?.let { p ->
            if (kind == VaultFields.Kind.NEW_PASSWORD || kind == VaultFields.Kind.CONFIRM) p.register = true
            if (kind == VaultFields.Kind.CURRENT) p.change = true
        }
        if(fresh.kind==VaultFields.Kind.ACCOUNT && fresh!=target) {account.fill('\u0000');account=CharArray(0);accountPkg=fresh.pkg}
        target = fresh; active = true; epoch++; entries = emptyList(); available = false; dismissed = false
        capture = fresh.kind in VaultFields.SECRET && (typed.isNotEmpty() || emptyField) && info.initialSelStart == typed.size && info.initialSelEnd == typed.size
        if (!enabled()) { reset(); return }
        val returning = pendingUnlock?.takeIf { it.target == fresh && clock() < it.until }
        if (returning != null) { pendingUnlock = null; reveal(returning.id, returning.bind, allowUnlock=false, confirmCert = returning.confirmCert); return }
        val nextPassword = pendingNext?.takeIf { it.target.pkg == fresh.pkg && fresh.kind in VaultFields.LOGIN && clock() < it.until }
        pendingNext = null
        if (nextPassword != null) { reveal(nextPassword.id, false); return }
        if (fresh.kind == VaultFields.Kind.OTHER && savePassword.isEmpty()) return
        request("status") { state ->
            available = state.getString("status") == "ok" && state.getBoolean("enabled") && state.getInt("version") == 1
            @Suppress("DEPRECATION")
            features = state.getStringArrayList("features").orEmpty().toSet()
            docsAutofill = state.getBoolean("autofill")
            if (!available) { invalidateCapture();wipeSave(); changed(); return@request }
            changed()
            if (!browser && fresh.kind != VaultFields.Kind.OTHER) request("match", Bundle().apply {
                putString("package", fresh.pkg)
                if ("match.cert" in features) putStringArrayList("features", arrayListOf("match.cert"))
            }) { data -> entries = metadata(data); changed() }
        }
    }
    private fun metadata(data: Bundle): List<Entry> {
        if (data.getString("status") != "ok") return emptyList()
        @Suppress("DEPRECATION") val rows = data.getParcelableArrayList<Bundle>("entries").orEmpty()
        return rows.take(40).mapNotNull { row ->
            val id = row.getString("id") ?: return@mapNotNull null
            Entry(id, row.getString("name").orEmpty().take(80), row.getString("account").orEmpty().take(80), row.getString("matchedBy").orEmpty())
        }
    }

    /** 系统推送的候选显示 / 撤下（键盘在工具栏里显示它们时，不再重复显示自己的匹配） */
    fun inlineShown(pkg: String, shown: Boolean) {
        inlinePkg = if (shown) pkg else if (inlinePkg == pkg) "" else inlinePkg
        changed()
    }

    /** 「密码空间」面板：注册 / 改密码页最上面是生成；系统候选在显示时不再列自己的匹配 */
    val canGenerate: Boolean get() = target?.kind.let { it == VaultFields.Kind.NEW_PASSWORD || it == VaultFields.Kind.CONFIRM }
    fun panelEntries(): List<Entry> = if (target?.let { autofillActive(it.pkg) } == true) emptyList() else entries

    fun actions(): List<Action> {
        if (!enabled() || !active || !available || dismissed) return emptyList()
        if(savePassword.isNotEmpty() && clock()>=saveUntil) wipeSave()
        if (savePassword.isNotEmpty()) {
            val account = saveAccountValue()
            val count = 1 + saveFields.count { (_, v) -> v.isNotEmpty() && String(v) != account }
            val who = appName(savePkg)
            val label = buildString {
                append("保存到空叶文档？").append(who)
                if (account.isNotEmpty()) append(" · ").append(VaultFields.mask(account))
                append(" · ").append(count).append(" 项")
                if (saveBrowser) append("（需补网址）")
            }
            return listOf(Action(label, ::save), Action("不保存", ::discard))
        }
        val t = target ?: return emptyList()
        if (t.kind == VaultFields.Kind.OTHER) return emptyList()
        val result = ArrayList<Action>()
        if (t.kind == VaultFields.Kind.CONFIRM && generatedPkg == t.pkg && generated.isNotEmpty() && clock()-generatedAt < 120000)
            result.add(Action("使用刚生成的密码") { useGenerated() })
        if (t.kind == VaultFields.Kind.NEW_PASSWORD || t.kind == VaultFields.Kind.CONFIRM) result.add(Action("生成强密码", ::generate))
        val n = panelEntries().count { !it.certMismatch }
        result.add(Action(if (n > 0) "🔑 密码空间 · $n" else "🔑 密码空间", panelUi))
        return result
    }
    fun search(query: String, result: (List<Entry>) -> Unit) {
        if (!available || !enabled()) { result(emptyList()); return }
        val version=++queryEpoch
        request("search", Bundle().apply { putString("query", query.take(100)) }) { if(version==queryEpoch) result(metadata(it)) }
    }

    /** 面板或搜索里选中一条；签名不同的要再点一次 */
    fun choose(entry: Entry) {
        if (entry.certMismatch && !(armedCert == entry.id && clock() < armedUntil)) { armCert(entry.id, entry.name); return }
        val confirm = entry.certMismatch || (armedCert == entry.id && clock() < armedUntil)
        armedCert = ""
        // 关联一次：搜索来的记住这个 App；已关联的补记签名证书（空叶文档 v1.1）
        reveal(entry.id, bind = true, confirmCert = confirm)
    }
    private fun armCert(id: String, name: String) {
        armedCert = id; armedUntil = clock() + 10000
        notice("「${appName(target?.pkg.orEmpty())}」的签名和上次关联「$name」时不同，可能是仿冒 App。10 秒内再点一次仍然填入")
    }

    private fun reveal(id: String, bind: Boolean, allowUnlock: Boolean = true, confirmCert: Boolean = false) {
        val t = target ?: return
        if (!active || !enabled()) return
        val operation=++secretEpoch
        request("reveal", Bundle().apply { putString("id", id); putString("package", t.pkg); if (confirmCert) putBoolean("confirmCert", true) }) { data ->
            if(operation!=secretEpoch) return@request
            when (data.getString("status")) {
                "locked" -> {
                    if(!allowUnlock) { notice("密码空间未解锁，点候选可重试"); return@request }
                    @Suppress("DEPRECATION") val intent = data.getParcelable<PendingIntent>("unlock")
                    pendingUnlock = Pending(id, t, clock()+30000, bind, confirmCert)
                    main.removeCallbacks(expireUnlock); main.postDelayed(expireUnlock, 30000)
                    if (intent == null || !unlock(intent)) { pendingUnlock = null; notice("无法打开密码空间解锁") }
                }
                "cert_mismatch" -> armCert(id, entries.firstOrNull { it.id == id }?.name ?: "这一条")
                "ok" -> {
                    if (!active || target != t) return@request
                    available = true
                    val key = if (t.kind == VaultFields.Kind.ACCOUNT) "account" else "password"
                    val value = data.getString(key) ?: return@request
                    if (value.isEmpty() || !fill(value)) { notice("这里填不了"); return@request }
                    invalidateCapture()
                    if (bind && !t.browser) request("bind", Bundle().apply { putString("id",id); putString("package",t.pkg) }) { }
                    if (t.kind == VaultFields.Kind.ACCOUNT) {
                        account.fill('\u0000'); account = value.toCharArray(); accountPkg = t.pkg
                        commitAccount(t)
                        pendingNext = Pending(id,t,clock()+10000,false)
                        next()
                    }
                    changed()
                }
                else -> notice("密码空间暂不可用")
            }
        }
    }
    /** 面板里的「生成强密码」 */
    fun generateFromPanel() { if (canGenerate) generate() }
    private fun generate() {
        val t = target ?: return
        val operation=++secretEpoch
        // 接口 v1.1：把提示文字交给空叶文档解析密码要求（"8-16 位，须包含字母和数字"）
        val extras = Bundle().apply {
            if ("generate.rules" in features) {
                if (t.hint.isNotBlank()) putString("ruleText", t.hint.take(300))
                if ((t.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER) putStringArrayList("allowed", arrayListOf("digit"))
                val avoid = page?.fields?.values?.map { String(it) }?.filter { it.length >= 3 }.orEmpty()
                if (avoid.isNotEmpty()) putStringArrayList("avoid", ArrayList(avoid))
            }
        }
        request("generate", extras) { data ->
            if(operation!=secretEpoch) return@request
            if (data.getString("status") != "ok" || target != t || !active) { notice("暂时无法生成密码"); return@request }
            val password = data.getString("password") ?: return@request
            generated.fill('\u0000'); generated = password.toCharArray(); generatedPkg = t.pkg; generatedAt = clock()
            main.removeCallbacks(expireGenerated); main.postDelayed(expireGenerated,120000)
            useGenerated()
            data.getString("summary")?.let { s -> notice(if (data.getBoolean("satisfied", true)) "已生成：$s" else "已生成：$s（页面要求互相矛盾，请留意提示）") }
        }
    }
    private fun useGenerated() {
        if (!active || target?.pkg != generatedPkg || generated.isEmpty() || clock()-generatedAt >= 120000) return
        if (fill(String(generated))) { typed.fill('\u0000'); typed = generated.copyOf(); capture = true }
    }
    fun editing() {secretEpoch++;pendingUnlock=null;pendingNext=null}
    fun typed(text: String) {
        secretEpoch++;pendingNext=null;if(pendingUnlock?.target==target) pendingUnlock=null
        if (!enabled() || !active) return
        val t = target ?: return
        if (t.kind == VaultFields.Kind.ACCOUNT) {
            if (accountPkg != t.pkg) { account.fill('\u0000'); account = CharArray(0) }
            if(account.size+text.length>1024) {account.fill('\u0000');account=CharArray(0);return}
            val newer = account + text.toCharArray(); account.fill('\u0000'); account = newer; accountPkg = t.pkg
        } else if (capture && t.kind != VaultFields.Kind.OTHER) {
            if(typed.size+text.length>1024) {invalidateCapture();return}
            val newer = typed + text.toCharArray(); typed.fill('\u0000'); typed = newer
        }
    }
    fun pasted(text:String) {
        secretEpoch++;pendingNext=null
        invalidateCapture()
        if(target?.kind==VaultFields.Kind.ACCOUNT && text.length<=1024) {
            account.fill('\u0000');account=text.toCharArray();accountPkg=target?.pkg.orEmpty()
        }
    }
    fun backspace() {
        secretEpoch++;pendingNext=null
        if (typed.isNotEmpty()) { val n = Character.offsetByCodePoints(typed,0,typed.size,typed.size,-1); val newer = typed.copyOf(n); typed.fill('\u0000'); typed = newer }
        else if (target?.kind == VaultFields.Kind.ACCOUNT && account.isNotEmpty()) { val newer = account.copyOf(account.size-1); account.fill('\u0000'); account = newer }
    }
    fun selection(start:Int,end:Int) {
        if(capture && (start!=end || start!=typed.size)) {secretEpoch++;invalidateCapture()}
    }
    fun invalidateCapture() { typed.fill('\u0000'); typed = CharArray(0); capture = false }
    fun submitted() { commitAccount(target); retireTyped(null); changed() }
    /** 键盘收起：新密码先记着（多半是去确认框），提交或换到别的框时再提示 */
    fun hide() { commitAccount(target); retireTyped(null, promoteHeld = false); active = false; epoch++;secretEpoch++ }
    fun dismiss() { dismissed = true; discard() }
    fun discard() { secretEpoch++;pendingUnlock=null;pendingNext=null;invalidateCapture();wipeSave();account.fill('\u0000');account=CharArray(0);generated.fill('\u0000');generated=CharArray(0);page?.wipe();changed() }
    private fun wipeSave() {
        main.removeCallbacks(expireSave); savePassword.fill('\u0000'); savePassword = CharArray(0)
        saveFields.values.forEach { it.fill('\u0000') }; saveFields = LinkedHashMap()
        saveCurrent.fill('\u0000'); saveCurrent = CharArray(0); savePkg = ""; saveBrowser = false; saveKind = "login"
    }
    /** 账号：用户名 → 手机号 → 邮箱 */
    private fun saveAccountValue(): String = listOf("username", "phone", "email").firstNotNullOfOrNull { k -> saveFields[k]?.takeIf { it.isNotEmpty() }?.let { String(it) } }.orEmpty()
    private fun save() {
        val pkg = savePkg
        if (savePassword.isEmpty() || pkg.isEmpty()) return
        val account = saveAccountValue()
        val extras = Bundle().apply {
            putString("name", if (saveBrowser) "${appName(pkg)} 网页密码（请补网址）" else appName(pkg))
            if (!saveBrowser) putString("package",pkg)
            putString("account", account); putString("password",String(savePassword))
            if ("save.fields" in features) {
                putString("kind", saveKind)
                putParcelableArrayList("fields", ArrayList(saveFields.filter { it.value.isNotEmpty() }.map { (k, v) -> Bundle().apply { putString("type", k); putString("value", String(v)) } }))
                if (saveKind == "change" && saveCurrent.isNotEmpty()) putString("currentPassword", String(saveCurrent))
            }
        }
        wipeSave(); generated.fill('\u0000'); generated = CharArray(0); this.account.fill('\u0000'); this.account = CharArray(0); page?.wipe()
        request("save", extras) { notice(if (it.getString("status")=="ok") { if (it.getString("result")=="updated") "已更新密码，旧密码保留在条目里" else "已存到空叶文档" } else "保存失败，请在密码空间中确认") }
        changed()
    }
    fun reset() {
        epoch++;resetEpoch++;secretEpoch++;queryEpoch++; active=false; available=false; entries=emptyList(); pendingUnlock=null;pendingNext=null
        main.removeCallbacks(expireUnlock);main.removeCallbacks(expireGenerated)
        invalidateCapture();wipeSave();account.fill('\u0000');account=CharArray(0);generated.fill('\u0000');generated=CharArray(0)
        page?.wipe(); page = null; inlinePkg = ""
        changed()
    }
    fun destroy() { reset(); worker.shutdownNow() }
}
