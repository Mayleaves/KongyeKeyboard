package com.kongye.keyboard

import android.app.Instrumentation
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object VaultRegression {
    @JvmStatic fun run(inst:Instrumentation) {
        fun ui(block:()->Unit) {
            var failure:Throwable?=null
            inst.runOnMainSync {try {block()} catch(t:Throwable) {failure=t}}
            failure?.let {throw it}
        }
        fun waitFor(label:String, condition:()->Boolean) {
            repeat(300) {inst.waitForIdleSync();var ok=false;ui {ok=condition()};if(ok)return;Thread.sleep(10)}
            error("Timed out: $label")
        }
        fun info(kind:Int,field:Int=1,hint:String="",pkg:String="test.login")=EditorInfo().apply {
            packageName=pkg;fieldId=field;inputType=kind;hintText=hint;initialSelStart=0;initialSelEnd=0
        }
        val password=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        for(variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
            check(VaultFields.sensitive(info(InputType.TYPE_CLASS_TEXT or variation)))
        check(VaultFields.sensitive(info(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)))
        check(VaultFields.kind(info(InputType.TYPE_CLASS_TEXT,1,"用户名"))==VaultFields.Kind.ACCOUNT)
        check(VaultFields.kind(info(password,1,"新密码"))==VaultFields.Kind.NEW_PASSWORD)
        check(VaultFields.kind(info(password,2,"再次输入"))==VaultFields.Kind.CONFIRM)
        check(VaultFields.sensitive(info(password, 1, "密码/验证码")))
        check(VaultFields.kind(info(password, 1, "密码/验证码")) == VaultFields.Kind.OTHER)
        var browserSaved = false
        var enabled=false
        var locked=true
        var backendEnabled=true
        var clock=1000L
        val calls=Collections.synchronizedList(ArrayList<String>())
        val filled=ArrayList<String>()
        var next=0;var unlocks=0;var saved=false
        var nextHook:()->Unit={}
        var hold:CountDownLatch?=null;var entered:CountDownLatch?=null
        val pi=PendingIntent.getActivity(inst.targetContext,901,Intent(inst.targetContext,SettingsActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        fun rows():Bundle=Bundle().apply {
            putString("status","ok")
            putParcelableArrayList("entries",arrayListOf(Bundle().apply {putString("id","fixture");putString("name","Test Login");putString("account","te***@example.test");putString("matchedBy","package")}))
        }
        lateinit var vault:VaultFill
        ui {vault=VaultFill({enabled},{method,extras ->
            calls.add(method)
            if (method == "save" && extras.getString("name").orEmpty().contains("网页密码")) {
                check(!extras.containsKey("package"))
                check(extras.getString("name").orEmpty().contains("请补网址"))
                browserSaved = true
            }
            when(method) {
                "status" -> Bundle().apply {putString("status",if(backendEnabled)"ok" else "disabled");putBoolean("enabled",backendEnabled);putInt("version",1)}
                "match","search" -> rows()
                "reveal" -> {
                    entered?.countDown();hold?.await(5,TimeUnit.SECONDS)
                    Bundle().apply {putString("status",if(locked)"locked" else "ok");if(locked)putParcelable("unlock",pi) else {putString("account","test@example.test");putString("password","Synthetic-Only!9")}}
                }
                "generate" -> Bundle().apply {putString("status","ok");putString("password","Generated-Only!8")}
                "save" -> {saved=extras.getString("password")=="Generated-Only!8";Bundle().apply {putString("status","ok");putString("id","saved");putString("result","created")}}
                else -> Bundle().apply {putString("status","ok")}
            }
        },{unlocks++;true},{filled.add(it);true},{next++;nextHook()},{},{},{},{"Test Login"},clock={clock})}
        ui {vault.focus(info(password))};Thread.sleep(50);check(calls.isEmpty()) {"Disabled feature contacted provider"}
        enabled=true
        ui {vault.focus(info(password))}
        waitFor("matching metadata") {vault.panelEntries().isNotEmpty()}
        ui {vault.choose(vault.panelEntries().first())}
        waitFor("unlock request") {unlocks==1}
        check(filled.isEmpty())
        ui {vault.hide();vault.focus(info(password))}
        Thread.sleep(100);inst.waitForIdleSync();check(unlocks==1 && filled.isEmpty()) {"Canceling unlock reopened authentication"}
        ui {vault.focus(info(password,2));vault.hide()}
        locked=false
        ui {vault.focus(info(password))}
        waitFor("metadata after canceled unlock") {vault.panelEntries().isNotEmpty()}
        ui {vault.choose(vault.panelEntries().first())}
        waitFor("same-field unlock retry") {filled.size==1}
        check(filled[0]=="Synthetic-Only!9")
        ui {vault.focus(info(InputType.TYPE_CLASS_TEXT,10,"账号"))}
        waitFor("account metadata") {vault.panelEntries().isNotEmpty()}
        ui {vault.choose(vault.panelEntries().first())}
        waitFor("account next") {next==1}
        check(filled.last()=="test@example.test")
        ui {vault.focus(info(password,11))}
        waitFor("next-field password") {filled.last()=="Synthetic-Only!9" && filled.size==3}
        ui {vault.search("test") {list ->check(list.size==1);vault.choose(list.first())}}
        waitFor("first search binding") {calls.contains("bind")}
        ui {vault.focus(info(InputType.TYPE_CLASS_TEXT,12,"账号"))}
        waitFor("search account ready") {vault.actions().isNotEmpty()}
        val bindsBeforeNext=calls.count {it=="bind"}
        ui {
            nextHook={vault.focus(info(password,13))}
            vault.choose(VaultFill.Entry("entry","Fixture","te***@example.test","search"))
        }
        waitFor("search binding survives immediate next field") {calls.count {it=="bind"}>bindsBeforeNext}
        waitFor("search account next password") {filled.last()=="Synthetic-Only!9"}
        ui {nextHook={}}
        ui {vault.focus(info(password,20,"新密码"))}
        waitFor("generator action") {vault.actions().any {it.label=="生成强密码"}}
        ui {vault.actions().first {it.label=="生成强密码"}.run()}
        waitFor("generated fill") {filled.last()=="Generated-Only!8"}
        ui {vault.hide();vault.focus(info(password,21,"确认密码"))}
        waitFor("confirm reuse") {vault.actions().any {it.label=="使用刚生成的密码"}}
        ui {vault.actions().first {it.label=="使用刚生成的密码"}.run();vault.submitted()}
        ui {check(vault.actions().any {it.label.startsWith("保存到空叶文档？")});vault.actions().first {it.label.startsWith("保存到空叶文档？")}.run()}
        waitFor("explicit save") {saved}
        ui {check(vault.actions().none {it.label.startsWith("保存到空叶文档？")})}
        ui {vault.focus(info(password,30))}
        waitFor("typed focus") {vault.actions().isNotEmpty()}
        ui {vault.typed("typed-only");vault.invalidateCapture();vault.submitted();check(vault.actions().none {it.label.startsWith("保存到空叶文档？")})}
        ui {vault.focus(info(password,31))}
        waitFor("manual capture status") {vault.actions().isNotEmpty()}
        val savesBefore=calls.count {it=="save"}
        ui {vault.typed("never-save");vault.submitted();check(vault.actions().any {it.label.startsWith("保存到空叶文档？")});vault.discard();check(vault.actions().none {it.label.startsWith("保存到空叶文档？")})}
        check(calls.count {it=="save"}==savesBefore) {"Typing saved without explicit consent"}
        ui {vault.focus(info(password,32,"新密码"))}
        waitFor("second generation") {vault.actions().any {it.label=="生成强密码"}}
        val beforeSecondGeneration=filled.size
        ui {vault.actions().first {it.label=="生成强密码"}.run()}
        waitFor("second generator response") {filled.size>beforeSecondGeneration && filled.last()=="Generated-Only!8"}
        ui {clock+=120001;vault.hide();vault.focus(info(password,33,"确认密码"))}
        waitFor("expired confirmation ready") {vault.actions().isNotEmpty()}
        ui {check(vault.actions().none {it.label=="使用刚生成的密码"});vault.discard()}
        hold=CountDownLatch(1);entered=CountDownLatch(1)
        ui {vault.focus(info(password,40))}
        waitFor("race metadata") {vault.panelEntries().isNotEmpty()}
        val before=filled.size
        ui {vault.choose(vault.panelEntries().first())}
        check(entered!!.await(3,TimeUnit.SECONDS))
        ui {vault.hide();vault.focus(info(InputType.TYPE_CLASS_TEXT,99,pkg="different.app"))}
        hold!!.countDown();hold=null
        Thread.sleep(80);inst.waitForIdleSync();check(filled.size==before) {"Stale reveal filled another editor"}
        hold=CountDownLatch(1);entered=CountDownLatch(1)
        ui {vault.focus(info(password,45))}
        waitFor("same-field race metadata") {vault.panelEntries().isNotEmpty()}
        val beforeTypingRace=filled.size
        ui {vault.choose(vault.panelEntries().first())}
        check(entered!!.await(3,TimeUnit.SECONDS))
        ui {vault.editing();vault.typed("user-changed")}
        hold!!.countDown();hold=null
        Thread.sleep(80);inst.waitForIdleSync();check(filled.size==beforeTypingRace) {"Delayed password replaced newer typing"}
        ui {vault.discard();vault.focus(info(password,46),emptyField=false)}
        waitFor("existing password ready") {vault.actions().isNotEmpty()}
        ui {vault.typed("partial");vault.submitted();check(vault.actions().none {it.label.startsWith("保存到空叶文档？")})}
        val matchesBefore=calls.count {it=="match"}
        ui {vault.focus(info(password,47,pkg="browser.fixture"),browser=true)}
        waitFor("browser search ready") {vault.actions().any {it.label.startsWith("🔑 密码空间")}}
        check(calls.count {it=="match"}==matchesBefore) {"Browser auto-matched without a domain"}
        val browserBinds = calls.count { it == "bind" }
        val beforeBrowserReveal = filled.size
        ui { vault.choose(VaultFill.Entry("fixture", "Fixture", "masked", "search")) }
        waitFor("browser reveal") { filled.size > beforeBrowserReveal }
        // 排队的 status 会在 reveal 的回调完成后执行，确保 bind 检查覆盖异步调用。
        ui { vault.focus(info(password, 48, "新密码", "browser.fixture"), browser=true) }
        waitFor("browser generation ready") { vault.actions().any { it.label == "生成强密码" } }
        check(calls.count { it == "bind" } == browserBinds)
        val beforeBrowserGeneration = filled.size
        ui { vault.actions().first { it.label == "生成强密码" }.run() }
        waitFor("browser generated capture") { filled.size > beforeBrowserGeneration && filled.last() == "Generated-Only!8" }
        ui { vault.submitted(); check(vault.actions().any { it.label.contains("需补网址") }); vault.actions().first { it.label.contains("需补网址") }.run() }
        waitFor("browser save without package") { browserSaved }
        ui { vault.focus(info(password, 49, "密码/验证码")); vault.typed("otp-only"); vault.submitted() }
        ui { check(vault.actions().isEmpty()) }
        backendEnabled=false
        ui {vault.focus(info(password,50))}
        Thread.sleep(80);inst.waitForIdleSync();ui {check(vault.actions().isEmpty())}
        enabled=false
        val callsBefore=calls.size
        ui {vault.reset();vault.focus(info(password));vault.search("ignored") { };vault.submitted()}
        Thread.sleep(60);check(calls.size==callsBefore) {"Disabled reset contacted provider"}
        ui {vault.destroy()}
        v2(inst)
        // A PI created by this test app must never be treated as Docs unlock UI.
        check(!VaultGateway(inst.targetContext).unlock(pi))
        pi.cancel()
    }
    /** 需求文档 v2：签名不同要点两次、按提示文字生成、多字段保存、改密码、系统候选显示时不重复提示。全是假数据。 */
    private fun v2(inst:Instrumentation) {
        fun ui(block:()->Unit) {
            var failure:Throwable?=null
            inst.runOnMainSync {try {block()} catch(t:Throwable) {failure=t}}
            failure?.let {throw it}
        }
        fun waitFor(label:String, condition:()->Boolean) {
            repeat(300) {inst.waitForIdleSync();var ok=false;ui {ok=condition()};if(ok)return;Thread.sleep(10)}
            error("Timed out: $label")
        }
        fun info(kind:Int,field:Int,hint:String="",pkg:String)=EditorInfo().apply {
            packageName=pkg;fieldId=field;inputType=kind;hintText=hint;initialSelStart=0;initialSelEnd=0
        }
        val password=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        // 分类：确认 / 新密码只认密码类型的框（"再次输入手机号"不是确认密码）；原密码是当前密码
        check(VaultFields.kind(info(InputType.TYPE_CLASS_TEXT,1,"再次输入手机号","x"))==VaultFields.Kind.ACCOUNT)
        check(VaultFields.kind(info(password,1,"原密码","x"))==VaultFields.Kind.CURRENT)
        check(VaultFields.accountType(info(InputType.TYPE_CLASS_PHONE,1,"","x"))=="phone")
        check(VaultFields.accountType(info(InputType.TYPE_CLASS_TEXT,1,"邮箱","x"))=="email")

        val calls=Collections.synchronizedList(ArrayList<Bundle>())
        val filled=ArrayList<String>()
        val notices=ArrayList<String>()
        var autofill=false
        val clock=1000L
        lateinit var v:VaultFill
        ui {v=VaultFill({true},{method,extras ->
            calls.add(Bundle(extras).apply {putString("_method",method)})
            when(method) {
                "status" -> Bundle().apply {putString("status","ok");putBoolean("enabled",true);putInt("version",1);putBoolean("autofill",autofill)
                    putStringArrayList("features",arrayListOf("generate.rules","save.fields","match.cert"))}
                "match" -> Bundle().apply {putString("status","ok");putParcelableArrayList("entries",arrayListOf(Bundle().apply {
                    putString("id","fake");putString("name","Fake");putString("account","fa***ke");putString("matchedBy","cert_mismatch")}))}
                "reveal" -> Bundle().apply {putString("status","ok");putString("account","acct");putString("password","Synthetic-Only!9")}
                "generate" -> Bundle().apply {putString("status","ok");putString("password","RuleGen7x");putString("summary","16 位 · 字母+数字");putBoolean("satisfied",true)}
                "save" -> Bundle().apply {putString("status","ok");putString("result","created")}
                else -> Bundle().apply {putString("status","ok")}
            }
        },{true},{filled.add(it);true},{},{},{notices.add(it)},{},{"Shop"},clock={clock})}
        fun last(method:String)=calls.lastOrNull {it.getString("_method")==method}

        // 签名不同：第一次点只提醒，再点才填，并带上 confirmCert；match 声明了 match.cert
        ui {v.focus(info(password,60,pkg="shop.app"))}
        waitFor("mismatch entry") {v.panelEntries().isNotEmpty()}
        @Suppress("DEPRECATION") check(last("match")!!.getStringArrayList("features")?.contains("match.cert")==true)
        check(v.actions().first {it.label.startsWith("🔑 密码空间")}.label=="🔑 密码空间") {"mismatched entries must not count as recommendations"}
        ui {v.choose(v.panelEntries().first())}
        check(last("reveal")==null && notices.last().contains("签名")) {"first tap must only warn"}
        ui {v.choose(v.panelEntries().first())}
        waitFor("confirmed fill") {filled.lastOrNull()=="Synthetic-Only!9"}
        check(last("reveal")!!.getBoolean("confirmCert"))

        // 注册：手机号 → 设置密码（提示文字交给空叶文档解析，手机号不能出现在密码里）→ 确认密码 → 提交后一次保存
        ui {v.focus(info(InputType.TYPE_CLASS_PHONE,61,"手机号",pkg="reg.app"));v.typed("13800001234")}
        ui {v.focus(info(password,62,"设置密码，8-16位字母和数字",pkg="reg.app"))}
        waitFor("generator") {v.actions().any {it.label=="生成强密码"}}
        ui {v.actions().first {it.label=="生成强密码"}.run()}
        waitFor("generated") {filled.lastOrNull()=="RuleGen7x"}
        last("generate")!!.let {g -> @Suppress("DEPRECATION") check(g.getString("ruleText")!!.contains("8-16位") && g.getStringArrayList("avoid")==arrayListOf("13800001234")) {"rule text / avoid"}}
        check(notices.last().contains("16 位"))
        ui {v.focus(info(password,63,"确认密码",pkg="reg.app"))}
        check(v.actions().none {it.label.startsWith("保存到空叶文档？")}) {"must not prompt between new and confirm"}
        waitFor("reuse") {v.actions().any {it.label=="使用刚生成的密码"}}
        ui {v.actions().first {it.label=="使用刚生成的密码"}.run();v.submitted()}
        val prompt=v.actions().first {it.label.startsWith("保存到空叶文档？")}
        check(prompt.label.contains("138****1234") && prompt.label.contains("1 项")) {"prompt label"}
        ui {prompt.run()}
        waitFor("register save") {last("save")!=null}
        last("save")!!.let {s ->
            @Suppress("DEPRECATION") val fields=s.getParcelableArrayList<Bundle>("fields").orEmpty()
            check(s.getString("kind")=="register" && s.getString("account")=="13800001234" && s.getString("password")=="RuleGen7x" && s.getString("package")=="reg.app")
            check(fields.size==1 && fields[0].getString("type")=="phone") {"fields"}
        }

        // 改密码：原密码 → 新密码 → 确认，保存时带 kind=change 和当前密码；两次不一致不提示
        ui {v.focus(info(password,80,"原密码",pkg="chg.app"));v.typed("OldPass1")}
        ui {v.focus(info(password,81,"新密码",pkg="chg.app"));v.typed("NewPass2")}
        ui {v.focus(info(password,82,"确认新密码",pkg="chg.app"));v.typed("NewPass2");v.submitted()}
        waitFor("change prompt") {v.actions().any {it.label.startsWith("保存到空叶文档？")}}
        ui {v.actions().first {it.label.startsWith("保存到空叶文档？")}.run()}
        waitFor("change save") {last("save")?.getString("kind")=="change"}
        check(last("save")!!.getString("currentPassword")=="OldPass1" && last("save")!!.getString("password")=="NewPass2")
        ui {v.focus(info(password,83,"新密码",pkg="mis.app"));v.typed("AAAA1111")}
        ui {v.focus(info(password,84,"确认密码",pkg="mis.app"));v.typed("BBBB2222");v.submitted()}
        ui {check(v.actions().none {it.label.startsWith("保存到空叶文档？")}) {"mismatched confirm must not prompt"}}

        // 系统候选正显示在工具栏（空叶文档是自动填充服务）：不列自己的匹配，也不弹自己的保存提示
        autofill=true
        ui {v.focus(info(password,90,pkg="auto.app"))}
        waitFor("autofill status") {v.panelEntries().isNotEmpty()}
        ui {v.inlineShown("auto.app",true)}
        check(v.panelEntries().isEmpty())
        ui {v.typed("Typed9xx");v.submitted()}
        ui {check(v.actions().none {it.label.startsWith("保存到空叶文档？")}) {"system autofill saves instead"}}
        ui {v.destroy()}
    }

    /** Read-only inter-app checks while Docs' own v3 regression serves its isolated fixture. */
    @JvmStatic fun live(inst:Instrumentation) {
        val context=object:android.content.ContextWrapper(inst.targetContext) {
            override fun getSharedPreferences(name:String,mode:Int)=inst.targetContext.getSharedPreferences("vault-live-"+name,mode)
        }
        val prefs=context.getSharedPreferences("kongye",0)
        prefs.edit().clear().putBoolean(Prefs.VAULT,true).putString(Prefs.VAULT_PACKAGE,"com.kongye.docs.audit").commit()
        val gateway=VaultGateway(context)
        try {
            var ready=false
            for(attempt in 0 until 200) {
                val state=gateway.call("status",Bundle())
                val ok=state.getString("status")=="ok" && state.getBoolean("enabled") && state.getInt("version")==1
                state.clear()
                if(ok) {ready=true;break}
                Thread.sleep(100)
            }
            check(ready) {"Docs isolated fixture was not ready"}
            val match=gateway.call("match",Bundle().apply {putString("package","com.example.mail")})
            check(match.getString("status")=="ok") {"Cross-app match failed"}
            @Suppress("DEPRECATION") val rows=match.getParcelableArrayList<Bundle>("entries").orEmpty()
            val entry=rows.firstOrNull {it.getString("name")=="Example Mail"} ?: error("Isolated fixture entry not present")
            check(entry.getString("account").orEmpty().contains("*")) {"Cross-app account was not masked"}
            check(!entry.containsKey("password")) {"Metadata contained a password"}
            val id=entry.getString("id");match.clear()
            val reveal=gateway.call("reveal",Bundle().apply {putString("id",id);putString("package","com.example.mail")})
            val status=reveal.getString("status")
            check(status=="locked" || status=="ok") {"Cross-app reveal contract failed"}
            if(status=="locked") {
                @Suppress("DEPRECATION") val pending=reveal.getParcelable<PendingIntent>("unlock")
                check(pending?.creatorPackage=="com.kongye.docs.audit") {"Wrong unlock creator"}
                check(!reveal.containsKey("password"))
            } else check(reveal.getString("account")=="alice@example.com") {"Unexpected fixture account"}
            reveal.clear()
            val generated=gateway.call("generate",Bundle())
            check(generated.getString("status")=="ok" && generated.getString("password").orEmpty().length>=12)
            generated.clear()
            val search=gateway.call("search",Bundle().apply {putString("query","Example")})
            check(search.getString("status")=="ok");search.clear()
            prefs.edit().putBoolean(Prefs.VAULT,false).commit()
            val off=gateway.call("status",Bundle());check(off.getString("status")=="disabled");off.clear()
        } finally {prefs.edit().clear().commit()}
    }

}
