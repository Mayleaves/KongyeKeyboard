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

/** Only metadata may be shown. Every plaintext result is consumed then its Bundle cleared. */
@androidx.annotation.Keep
object VaultFields {
    enum class Kind { OTHER, ACCOUNT, PASSWORD, NEW_PASSWORD, CONFIRM }
    fun kind(info: EditorInfo): Kind {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val hint = info.hintText?.toString().orEmpty()
        if(hint.contains("验证码")) return Kind.OTHER
        val password = passwordType(info)
        if (hint.contains("确认密码") || hint.contains("再次输入")) return Kind.CONFIRM
        if (hint.contains("设置密码") || hint.contains("新密码")) return Kind.NEW_PASSWORD
        if (password) return Kind.PASSWORD
        if (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
            || listOf("账号", "帐号", "手机", "邮箱", "用户名", "登录名").any(hint::contains)) return Kind.ACCOUNT
        return Kind.OTHER
    }
    val SECRET = setOf(Kind.PASSWORD, Kind.NEW_PASSWORD, Kind.CONFIRM)
    fun passwordType(info: EditorInfo): Boolean {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) || cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
    fun sensitive(info: EditorInfo) = passwordType(info) || kind(info) in SECRET
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

/** No preferences, clipboard, logging or learning for secrets. Main-looper state, serialized IPC. */
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
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    data class Entry(val id: String, val name: String, val account: String, val matchedBy: String)
    data class Action(val label: String, val run: () -> Unit)
    private data class Target(val pkg: String, val field: Int, val kind: VaultFields.Kind, val hint: String, val inputType: Int, val browser: Boolean)
    private data class Pending(val id: String, val target: Target, val until: Long, val bind: Boolean)
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
    private var savePassword = CharArray(0)
    private var savePkg = ""
    private var saveBrowser = false
    private var saveAccount = CharArray(0)
    private var generated = CharArray(0)
    private var generatedPkg = ""
    private var generatedAt = 0L
    private var saveUntil = 0L
    private val expireSave = Runnable { wipeSave(); changed() }
    private val expireGenerated = Runnable { generated.fill('\u0000'); generated = CharArray(0); changed() }
    private val expireUnlock = Runnable { pendingUnlock = null }

    /** Diagnostic flags only: never include entry metadata, input or secrets. */
    fun diagnosticState() = "kind=${target?.kind} active=$active available=$available dismissed=$dismissed enabled=${enabled()}"

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
    private fun retireTyped() {
        if (typed.isNotEmpty()) {
            wipeSave(); savePassword = typed.copyOf(); savePkg = target?.pkg.orEmpty(); saveBrowser = target?.browser == true
            saveAccount=if(accountPkg==savePkg) account.copyOf() else CharArray(0)
            saveUntil=clock()+30000
            main.postDelayed(expireSave, 30000)
        }
        typed.fill('\u0000'); typed = CharArray(0); capture = false
    }
    fun focus(info: EditorInfo, browser: Boolean = false, emptyField: Boolean = true) {
        val fresh = Target(info.packageName.orEmpty(), info.fieldId, VaultFields.kind(info), info.hintText?.toString().orEmpty(), info.inputType, browser)
        if(fresh != target) secretEpoch++
        if (fresh != target) retireTyped()
        if (target?.pkg != fresh.pkg) {
            wipeSave(); account.fill('\u0000'); account = CharArray(0); accountPkg = ""
            if (generatedPkg != fresh.pkg) { generated.fill('\u0000'); generated = CharArray(0) }
        }
        if(fresh.kind==VaultFields.Kind.CONFIRM && generatedPkg==fresh.pkg && clock()-generatedAt<120000) wipeSave()
        if(fresh.kind==VaultFields.Kind.ACCOUNT && fresh!=target) {account.fill('\u0000');account=CharArray(0);accountPkg=fresh.pkg}
        target = fresh; active = true; epoch++; entries = emptyList(); available = false; dismissed = false
        capture = fresh.kind in VaultFields.SECRET && (typed.isNotEmpty() || emptyField) && info.initialSelStart == typed.size && info.initialSelEnd == typed.size
        if (!enabled()) { reset(); return }
        val returning = pendingUnlock?.takeIf { it.target == fresh && clock() < it.until }
        if (returning != null) { pendingUnlock = null; reveal(returning.id, returning.bind, allowUnlock=false); return }
        val nextPassword = pendingNext?.takeIf { it.target.pkg == fresh.pkg && fresh.kind == VaultFields.Kind.PASSWORD && clock() < it.until }
        pendingNext = null
        if (nextPassword != null) { reveal(nextPassword.id, false); return }
        if (fresh.kind == VaultFields.Kind.OTHER && savePassword.isEmpty()) return
        request("status") { state ->
            available = state.getString("status") == "ok" && state.getBoolean("enabled") && state.getInt("version") == 1
            if (!available) { invalidateCapture();wipeSave(); changed(); return@request }
            changed()
            if (!browser && fresh.kind != VaultFields.Kind.OTHER) request("match", Bundle().apply { putString("package", fresh.pkg) }) { data ->
                entries = metadata(data); changed()
            }
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
    fun actions(): List<Action> {
        if (!enabled() || !active || !available || dismissed) return emptyList()
        if(savePassword.isNotEmpty() && clock()>=saveUntil) wipeSave()
        if (savePassword.isNotEmpty()) return listOf(Action(if (saveBrowser) "存到空叶文档（需补网址）？" else "存到空叶文档？", ::save), Action("不保存", ::discard))
        val t = target ?: return emptyList()
        if (t.kind == VaultFields.Kind.OTHER) return emptyList()
        val result = ArrayList<Action>()
        if (t.kind == VaultFields.Kind.CONFIRM && generatedPkg == t.pkg && generated.isNotEmpty() && clock()-generatedAt < 120000)
            result.add(Action("使用刚生成的密码") { useGenerated() })
        if (t.kind == VaultFields.Kind.NEW_PASSWORD || t.kind == VaultFields.Kind.CONFIRM) result.add(Action("生成强密码", ::generate))
        entries.forEach { e -> result.add(Action("🔑 ${e.name} · ${e.account}") { reveal(e.id, e.matchedBy == "search") }) }
        result.add(Action("🔍 搜索密码空间", searchUi))
        return result
    }
    fun search(query: String, result: (List<Entry>) -> Unit) {
        if (!available || !enabled()) { result(emptyList()); return }
        val version=++queryEpoch
        request("search", Bundle().apply { putString("query", query.take(100)) }) { if(version==queryEpoch) result(metadata(it)) }
    }
    fun choose(entry: Entry) { reveal(entry.id, true) }
    private fun reveal(id: String, bind: Boolean, allowUnlock: Boolean = true) {
        val t = target ?: return
        if (!active || !enabled()) return
        val operation=++secretEpoch
        request("reveal", Bundle().apply { putString("id", id); putString("package", t.pkg) }) { data ->
            if(operation!=secretEpoch) return@request
            when (data.getString("status")) {
                "locked" -> {
                    if(!allowUnlock) { notice("密码空间未解锁，点候选可重试"); return@request }
                    @Suppress("DEPRECATION") val intent = data.getParcelable<PendingIntent>("unlock")
                    pendingUnlock = Pending(id, t, clock()+30000, bind)
                    main.removeCallbacks(expireUnlock); main.postDelayed(expireUnlock, 30000)
                    if (intent == null || !unlock(intent)) { pendingUnlock = null; notice("无法打开密码空间解锁") }
                }
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
                        pendingNext = Pending(id,t,clock()+10000,false)
                        next()
                    }
                    changed()
                }
                else -> notice("密码空间暂不可用")
            }
        }
    }
    private fun generate() {
        val t = target ?: return
        val operation=++secretEpoch
        request("generate") { data ->
            if(operation!=secretEpoch) return@request
            if (data.getString("status") != "ok" || target != t || !active) { notice("暂时无法生成密码"); return@request }
            val password = data.getString("password") ?: return@request
            generated.fill('\u0000'); generated = password.toCharArray(); generatedPkg = t.pkg; generatedAt = clock()
            main.removeCallbacks(expireGenerated); main.postDelayed(expireGenerated,120000)
            useGenerated()
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
    fun submitted() { retireTyped(); changed() }
    fun hide() { retireTyped(); active = false; epoch++;secretEpoch++ }
    fun dismiss() { dismissed = true; discard() }
    fun discard() { secretEpoch++;pendingUnlock=null;pendingNext=null;invalidateCapture();wipeSave();account.fill('\u0000');account=CharArray(0);generated.fill('\u0000');generated=CharArray(0);changed() }
    private fun wipeSave() { main.removeCallbacks(expireSave); savePassword.fill('\u0000'); savePassword = CharArray(0); saveAccount.fill('\u0000'); saveAccount=CharArray(0); savePkg = ""; saveBrowser = false }
    private fun save() {
        val pkg = savePkg
        if (savePassword.isEmpty() || pkg.isEmpty()) return
        val extras = Bundle().apply { putString("name", if (saveBrowser) "${appName(pkg)} 网页密码（请补网址）" else appName(pkg)); if (!saveBrowser) putString("package",pkg); putString("account", String(saveAccount)); putString("password",String(savePassword)) }
        wipeSave(); generated.fill('\u0000'); generated = CharArray(0); account.fill('\u0000'); account = CharArray(0)
        request("save", extras) { notice(if (it.getString("status")=="ok") { if (it.getString("result")=="updated") "已更新密码，旧密码保留在条目里" else "已存到空叶文档" } else "保存失败，请在密码空间中确认") }
        changed()
    }
    fun reset() {
        epoch++;resetEpoch++;secretEpoch++;queryEpoch++; active=false; available=false; entries=emptyList(); pendingUnlock=null;pendingNext=null
        main.removeCallbacks(expireUnlock);main.removeCallbacks(expireGenerated)
        invalidateCapture();wipeSave();account.fill('\u0000');account=CharArray(0);generated.fill('\u0000');generated=CharArray(0)
        changed()
    }
    fun destroy() { reset(); worker.shutdownNow() }
}
