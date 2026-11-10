package com.kongye.keyboard;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.k2fsa.sherpa.onnx.WaveData;
import com.k2fsa.sherpa.onnx.WaveReader;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kotlin.Unit;

/** Release 设备验证；voice-start 短暂开麦检查启动时间，其他模式使用公开样本或独立输入框。 */
public final class OfflineResourcesCheck extends Instrumentation {
    private String mode = "all";
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        if (args != null) mode = args.getString("mode", "all");
        start();
    }
    private void report(String message) {
        Bundle bundle = new Bundle(); bundle.putString("stream", message + "\n"); sendStatus(0, bundle);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static <T> T await(Task<T> task, int seconds) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        task.addOnCompleteListener(Runnable::run, complete -> latch.countDown());
        require(latch.await(seconds, TimeUnit.SECONDS), "Task timed out");
        if (!task.isSuccessful()) throw new Exception("Task failed", task.getException());
        return task.getResult();
    }
    @Override public void onStart() {
        JSONObject result = new JSONObject();
        try {
            Context ctx = getTargetContext();
            if ("engine".equals(mode)) {
                long start=android.os.SystemClock.elapsedRealtime();
                Lexicon.INSTANCE.loadFrom(ctx.getAssets().open("lexicon.txt"),null);
                report("PASS engine cold load ms: "+(android.os.SystemClock.elapsedRealtime()-start));
                for(String[] sample:new String[][]{{"nain","年"},{"sih","是"},{"zhrmbang","真棒"},{"zhenbng","真棒"},{"zhenbgang","真棒"},{"nihoa","你好"},{"mihao","你好"},{"nhao","你好"},{"nihaoo","你好"}}) {
                    long query=System.nanoTime();
                    java.util.List<Cand> found=Lexicon.INSTANCE.candidates(sample[0],true);
                    require(!found.isEmpty() && found.get(0).getW().equals(sample[1]),"Wrong correction: "+sample[0]);
                    require(found.get(0).getN()==sample[0].length(),"Wrong consumed input length");
                    require(found.get(0).getMask()!=null,"Missing typo highlight");
                    report("PASS "+sample[0]+" → "+sample[1]+" in "+((System.nanoTime()-query)/1000)+" us");
                }
                require(Lexicon.INSTANCE.candidates("u",true).get(0).getW().equals("u"),"Invalid syllable returned Han characters");
                Lexicon.INSTANCE.setFuzzyRules(new java.util.HashSet<>(java.util.Arrays.asList(ctx.getSharedPreferences("kongye",Context.MODE_PRIVATE).getString("fuzzy","").split(","))));
                require(Lexicon.INSTANCE.candidates("nain",true).get(0).getW().equals("年"),"Fuzzy completion displaced transpose correction");
                report("PASS nain → 年 with the phone's fuzzy preferences");
                report("ALL DEVICE ENGINE CHECKS PASSED");
                finish(Activity.RESULT_OK,new Bundle());return;
            }
            if ("voice-start".equals(mode)) {
                checkVoiceStart(ctx);
                report("ALL VOICE STARTUP CHECKS PASSED");
                finish(Activity.RESULT_OK, new Bundle());
                return;
            }
            if ("voice-ui".equals(mode)) {
                checkVoiceUi(ctx);
                report("ALL VOICE UI REGRESSIONS PASSED");
                finish(Activity.RESULT_OK, new Bundle());
                return;
            }
            if ("clipboard".equals(mode)) {
                checkClipboard(ctx);
                report("ALL CLIPBOARD REGRESSIONS PASSED");
                finish(Activity.RESULT_OK, new Bundle());
                return;
            }
            if (!"translation".equals(mode)) for (int dialect = 0; dialect <= 1; dialect++) {
                CountDownLatch latch = new CountDownLatch(1);
                boolean[] installed = {false}; String[] state = {""};
                OfflineVoice.INSTANCE.install(ctx, dialect, (ok, status) -> {
                    state[0] = status;
                    if (ok || status.indexOf("失败") >= 0) { installed[0] = ok; latch.countDown(); }
                    return Unit.INSTANCE;
                });
                require(latch.await(60, TimeUnit.SECONDS) && installed[0], "Voice install failed: " + state[0]);
                String id = dialect == 0 ? "mandarin" : "sichuan";
                File file = new File(ctx.getExternalFilesDir("offline-voice"), "test/" + id + ".wav");
                WaveData wave = WaveReader.Companion.readWave(file.getAbsolutePath());
                String text = OfflineVoice.INSTANCE.recognizeSamples(ctx, dialect, wave.getSamples(), wave.getSampleRate());
                require(!text.isEmpty(), "Empty offline result: " + id);
                if (dialect == 0) require(text.indexOf("介绍") >= 0 && text.indexOf("研究") >= 0, text);
                else require(text.indexOf("哥哥") >= 0 && text.indexOf("伴奏") >= 0, text);
                result.put(id, text); report("PASS offline " + id + ": " + text);
            }
            if (!"voice".equals(mode)) {
                RemoteModelManager manager = RemoteModelManager.getInstance();
                for (String language : new String[]{"zh", "ja", "ko"}) {
                    TranslateRemoteModel model = new TranslateRemoteModel.Builder(language).build();
                    if (!await(manager.isModelDownloaded(model), 30)) {
                        boolean paused;
                        try (Cursor cursor = ctx.getSystemService(DownloadManager.class).query(new DownloadManager.Query())) {
                            paused = cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_PAUSED;
                        }
                        if (paused) await(manager.deleteDownloadedModel(model), 30);
                        report("Installing translation " + language);
                        await(manager.download(model, new DownloadConditions.Builder().build()), 240);
                    }
                    require(await(manager.isModelDownloaded(model), 30), "Missing model " + language);
                    report("PASS installed translation " + language);
                }
                // 直接 translate，不调用 downloadModelIfNeeded，验证本地模型。
                for (String[] entry : new String[][]{{"zh","en","你好"},{"zh","ja","你好"},{"zh","ko","你好"},{"ja","zh","こんにちは"},{"ko","zh","안녕하세요"}}) {
                    Translator client = Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(entry[0]).setTargetLanguage(entry[1]).build());
                    String translated;
                    try { translated = await(client.translate(entry[2]), 30); } finally { client.close(); }
                    require(!translated.isEmpty(), "Empty translation");
                    result.put(entry[0] + "-" + entry[1], translated);
                    report("PASS local translation " + entry[0] + "-" + entry[1] + ": " + translated);
                }
            }
            int count = -1; boolean hello = false; boolean keyboard = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(ctx.getAssets().open("lexicon.txt"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    count++;
                    if (line.startsWith("ni'hao 你好 ")) hello = true;
                    if (line.startsWith("jian'pan 键盘 ")) keyboard = true;
                }
            }
            require(count >= 69000 && hello && keyboard, "Missing common lexicon");
            result.put("lexicon_entries", count);
            if (!"translation".equals(mode)) require(ctx.getSharedPreferences("kongye", Context.MODE_PRIVATE).edit().putBoolean("voice_offline", true).commit(), "Preference save failed");
            Files.write(new File(ctx.getExternalFilesDir("offline-voice"), "verification-" + mode + ".json").toPath(), result.toString(2).getBytes(StandardCharsets.UTF_8));
            report("PASS lexicon: " + count + " entries");
            Bundle bundle = new Bundle(); bundle.putString("stream", "ALL OFFLINE RESOURCE CHECKS PASSED\n" + result + "\n"); finish(Activity.RESULT_OK, bundle);
        } catch (Throwable error) {
            Bundle bundle = new Bundle(); bundle.putString("stream", "FAILED: " + android.util.Log.getStackTraceString(error) + "\n"); finish(Activity.RESULT_CANCELED, bundle);
        }
    }

    private void checkVoiceStart(Context ctx) throws Exception {
        java.lang.reflect.Field mutex = OfflineVoice.class.getDeclaredField("engineLock");
        mutex.setAccessible(true);
        java.util.concurrent.locks.Lock lock = (java.util.concurrent.locks.Lock)mutex.get(null);
        CountDownLatch captured = new CountDownLatch(1);
        OfflineVoice.Session session = new OfflineVoice.Session(ctx,0,new OfflineVoice.Listener() {
            @Override public void status(String value) {}
            @Override public void partial(String value) {}
            @Override public void result(String value) {}
            @Override public void level(float value) { captured.countDown(); }
            @Override public void spectrum(float[] values) {}
            @Override public void stopped(String error) {}
        });
        long start=android.os.SystemClock.elapsedRealtime();
        lock.lock();
        try {
            session.start();
            try { require(captured.await(3,TimeUnit.SECONDS),"Microphone waited for model lock"); }
            finally { session.cancel(); }
            long cancelled=android.os.SystemClock.elapsedRealtime()+2000;
            while(!session.isFinished() && android.os.SystemClock.elapsedRealtime()<cancelled) Thread.sleep(20);
            require(session.isFinished(),"Cancellation waited for the model lock");
        } finally { lock.unlock(); }
        report("PASS microphone sampling before model lock: " + (android.os.SystemClock.elapsedRealtime()-start) + " ms");
        long deadline=android.os.SystemClock.elapsedRealtime()+4000;
        while(!session.isFinished() && android.os.SystemClock.elapsedRealtime()<deadline) Thread.sleep(20);
        require(session.isFinished(),"Cancelled capture did not finish");
        File file=new File(ctx.getExternalFilesDir("offline-voice"),"test/mandarin.wav");
        WaveData wave=WaveReader.Companion.readWave(file.getAbsolutePath());
        long first=android.os.SystemClock.elapsedRealtime();
        String a=OfflineVoice.INSTANCE.recognizeSamples(ctx,0,wave.getSamples(),wave.getSampleRate());
        long firstMs=android.os.SystemClock.elapsedRealtime()-first;
        java.lang.reflect.Field cache=OfflineVoice.class.getDeclaredField("cachedEngine");
        cache.setAccessible(true); Object loaded=cache.get(null);
        long second=android.os.SystemClock.elapsedRealtime();
        String b=OfflineVoice.INSTANCE.recognizeSamples(ctx,0,wave.getSamples(),wave.getSampleRate());
        require(loaded!=null && loaded==cache.get(null),"Recognizer was recreated instead of reused");
        require(a.equals(b) && !b.isEmpty(),"Reused model result differs");
        report("PASS recognizer reused, cold+decode="+firstMs+" ms, warm decode="+(android.os.SystemClock.elapsedRealtime()-second)+" ms");
    }

    private void checkVoiceUi(Context ctx) throws Exception {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(() -> {
            try {
                android.widget.EditText editor = new android.widget.EditText(ctx);
                editor.setText("abXYZcd"); editor.setSelection(2, 5);
                android.view.inputmethod.InputConnection input = editor.onCreateInputConnection(new android.view.inputmethod.EditorInfo());
                require(input != null, "Missing editable input connection");
                VoiceDraft cancelled = new VoiceDraft(input);
                require(cancelled.preview("你") && cancelled.preview("你好"), "Preview rejected");
                require(editor.getText().toString().equals("ab你好cd"), "Preview duplicated or replaced surrounding text");
                cancelled.cancel();
                require(editor.getText().toString().equals("abXYZcd"), "Cancel did not restore selected text");
                require(editor.getSelectionStart() == 2 && editor.getSelectionEnd() == 5, "Cancel did not restore selection");
                editor.setText("前后"); editor.setSelection(1);
                VoiceDraft confirmed = new VoiceDraft(input);
                confirmed.preview("测试"); confirmed.preview("测试通过"); confirmed.confirm(); confirmed.cancel();
                require(editor.getText().toString().equals("前测试通过后"), "Confirm duplicated or removed preview");
                require(android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.getText()) == -1, "Confirm left composing span");
                editor.setText("前后"); editor.setSelection(1);
                VoiceDraft moved = new VoiceDraft(input);
                moved.preview("语音"); editor.setSelection(0); moved.preview("语音预览"); moved.cancel();
                require(editor.getText().toString().equals("前后"), "Cursor movement broke cancellation");
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new Exception("Voice draft regression", failure.get());
        report("PASS composing preview, selected-text rollback, confirm once and cursor movement");
        int[] peaks = new int[2];
        int pass = 0;
        for (int frequency : new int[]{250, 2500}) {
            short[] pcm = new short[1600];
            for (int i=0;i<pcm.length;i++) pcm[i]=(short)(Math.sin(2*Math.PI*frequency*i/16000)*2000);
            float[] bands = VoiceSpectrum.INSTANCE.analyze(pcm,16000);
            int peak=0;
            for (int i=1;i<bands.length;i++) if(bands[i]>bands[peak]) peak=i;
            peaks[pass++]=peak;
        }
        require(peaks[0] < peaks[1], "Frequency bins do not distinguish low and high tones");
        float[] silent = VoiceSpectrum.INSTANCE.analyze(new short[1600],16000);
        for(float value:silent) require(value == 0, "Silence must not animate");
        float[] ascending = new float[17];
        for(int i=0;i<ascending.length;i++) ascending[i]=i/16f;
        float[] mirrored = VoiceSpectrum.INSTANCE.mirrored(ascending);
        for(int i=0;i<mirrored.length;i++) require(mirrored[i] == mirrored[mirrored.length-1-i], "Wave must respond equally on both sides");
        for(float value:VoiceSpectrum.INSTANCE.mirrored(silent)) require(value == 0, "Mirrored silence must stay flat");
        report("PASS fixed spectrum bands: low=" + peaks[0] + ", high=" + peaks[1] + ", silence=0");
        report("PASS mirrored display has equal left/right energy and quiet silence");
    }

    /** 独立缓存目录与独立首选项，不读取或改写用户剪贴板。 */
    private void checkClipboard(Context target) throws Exception {
        File directory = new File(target.getCacheDir(), "clipboard-regression");
        require(directory.isDirectory() || directory.mkdirs(), "Cannot create isolated test directory");
        Context isolated = new android.content.ContextWrapper(target) {
            @Override public File getFilesDir() { return directory; }
            @Override public Context getApplicationContext() { return this; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int flags) {
                return target.getSharedPreferences("clipboard-regression-" + name, flags);
            }
        };
        File saved = new File(directory, "clipboard.json");
        saved.delete();
        ClipStore store = ClipStore.INSTANCE;
        java.lang.reflect.Field storeFile = ClipStore.class.getDeclaredField("file");
        storeFile.setAccessible(true);
        require(storeFile.get(null) == null, "Run clipboard regression in a fresh instrumentation process");
        store.load(isolated);
        require(saved.equals(storeFile.get(null)), "Refusing to touch a non-test clipboard store");
        store.getItems().clear();
        store.add("pin regression");
        Clip stale = store.getItems().get(0);
        store.add("pin regression"); // 面板仍持有旧对象，系统复制已经生成了新记录。
        store.togglePin(stale);
        require(store.list("置顶", "").size() == 1, "Stale card pin was lost");
        require(store.getItems().get(0).getPinned(), "Stored record must be pinned");
        require(!store.tabs().contains("图片"), "Image category must be hidden");
        report("PASS stale-card pin and pinned category");
        for (int n = 0; n < 80; n++) store.togglePin(store.getItems().get(0));
        java.lang.reflect.Field field = ClipStore.class.getDeclaredField("writer");
        field.setAccessible(true);
        java.util.concurrent.ExecutorService writer = (java.util.concurrent.ExecutorService)field.get(null);
        writer.submit(() -> {}).get(10, TimeUnit.SECONDS);
        org.json.JSONArray json = new org.json.JSONArray(new String(Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8));
        require(json.length() == 1 && json.getJSONObject(0).getBoolean("p"), "Latest pin state was overwritten by an older save");
        report("PASS 80 queued atomic writes preserve final pinned state");
        store.getItems().clear();
        saved.delete();
        new File(directory, "clips").delete();
        directory.delete();
    }
}
