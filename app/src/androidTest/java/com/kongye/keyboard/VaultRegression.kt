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
        },{unlocks++;true},{filled.add(it);true},{next++;nextHook()},{},{},{},{"Test Login"},{clock})}
        ui {vault.focus(info(password))};Thread.sleep(50);check(calls.isEmpty()) {"Disabled feature contacted provider"}
        enabled=true
        ui {vault.focus(info(password))}
        waitFor("matching metadata") {vault.actions().any {it.label.startsWith("🔑")}}
        ui {vault.actions().first {it.label.startsWith("🔑")}.run()}
        waitFor("unlock request") {unlocks==1}
        check(filled.isEmpty())
        ui {vault.hide();vault.focus(info(password))}
        Thread.sleep(100);inst.waitForIdleSync();check(unlocks==1 && filled.isEmpty()) {"Canceling unlock reopened authentication"}
        ui {vault.focus(info(password,2));vault.hide()}
        locked=false
        ui {vault.focus(info(password))}
        waitFor("metadata after canceled unlock") {vault.actions().any {it.label.startsWith("🔑")}}
        ui {vault.actions().first {it.label.startsWith("🔑")}.run()}
        waitFor("same-field unlock retry") {filled.size==1}
        check(filled[0]=="Synthetic-Only!9")
        ui {vault.focus(info(InputType.TYPE_CLASS_TEXT,10,"账号"))}
        waitFor("account metadata") {vault.actions().any {it.label.startsWith("🔑")}}
        ui {vault.actions().first {it.label.startsWith("🔑")}.run()}
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
        ui {check(vault.actions().any {it.label=="存到空叶文档？"});vault.actions().first {it.label=="存到空叶文档？"}.run()}
        waitFor("explicit save") {saved}
        ui {check(vault.actions().none {it.label=="存到空叶文档？"})}
        ui {vault.focus(info(password,30))}
        waitFor("typed focus") {vault.actions().isNotEmpty()}
        ui {vault.typed("typed-only");vault.invalidateCapture();vault.submitted();check(vault.actions().none {it.label=="存到空叶文档？"})}
        ui {vault.focus(info(password,31))}
        waitFor("manual capture status") {vault.actions().isNotEmpty()}
        val savesBefore=calls.count {it=="save"}
        ui {vault.typed("never-save");vault.submitted();check(vault.actions().any {it.label=="存到空叶文档？"});vault.discard();check(vault.actions().none {it.label=="存到空叶文档？"})}
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
        waitFor("race metadata") {vault.actions().any {it.label.startsWith("🔑")}}
        val before=filled.size
        ui {vault.actions().first {it.label.startsWith("🔑")}.run()}
        check(entered!!.await(3,TimeUnit.SECONDS))
        ui {vault.hide();vault.focus(info(InputType.TYPE_CLASS_TEXT,99,pkg="different.app"))}
        hold!!.countDown();hold=null
        Thread.sleep(80);inst.waitForIdleSync();check(filled.size==before) {"Stale reveal filled another editor"}
        hold=CountDownLatch(1);entered=CountDownLatch(1)
        ui {vault.focus(info(password,45))}
        waitFor("same-field race metadata") {vault.actions().any {it.label.startsWith("🔑")}}
        val beforeTypingRace=filled.size
        ui {vault.actions().first {it.label.startsWith("🔑")}.run()}
        check(entered!!.await(3,TimeUnit.SECONDS))
        ui {vault.editing();vault.typed("user-changed")}
        hold!!.countDown();hold=null
        Thread.sleep(80);inst.waitForIdleSync();check(filled.size==beforeTypingRace) {"Delayed password replaced newer typing"}
        ui {vault.discard();vault.focus(info(password,46),emptyField=false)}
        waitFor("existing password ready") {vault.actions().isNotEmpty()}
        ui {vault.typed("partial");vault.submitted();check(vault.actions().none {it.label=="存到空叶文档？"})}
        val matchesBefore=calls.count {it=="match"}
        ui {vault.focus(info(password,47,pkg="browser.fixture"),browser=true)}
        waitFor("browser search ready") {vault.actions().any {it.label=="🔍 搜索密码空间"}}
        check(calls.count {it=="match"}==matchesBefore) {"Browser auto-matched without a domain"}
        backendEnabled=false
        ui {vault.focus(info(password,50))}
        Thread.sleep(80);inst.waitForIdleSync();ui {check(vault.actions().isEmpty())}
        enabled=false
        val callsBefore=calls.size
        ui {vault.reset();vault.focus(info(password));vault.search("ignored") { };vault.submitted()}
        Thread.sleep(60);check(calls.size==callsBefore) {"Disabled reset contacted provider"}
        ui {vault.destroy()}
        // A PI created by this test app must never be treated as Docs unlock UI.
        check(!VaultGateway(inst.targetContext).unlock(pi))
        pi.cancel()
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
