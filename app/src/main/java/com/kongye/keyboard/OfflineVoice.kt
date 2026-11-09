package com.kongye.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.sqrt
import kotlin.concurrent.withLock

/** 普通话和川渝专用模型在本机运行，不依赖系统识别服务，也不上传录音。 */
@Keep
object OfflineVoice {
    private data class Pack(val id: String, val name: String, val repo: String, val revision: String,
                            val bytes: Long, val modelHash: String, val tokensBytes: Long, val tokensHash: String)
    private val mandarin = Pack("mandarin", "普通话", "csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09",
        "63ddc3cd0f2810b68289a7b3876e62ef5d53d6df", 81828675,
        "3ef6c19369b912f7caf3cef8e545c5ccd1a33d9d7ec792a46668dc41c4b229ec", 75352,
        "4b2d964e18b9cf139b473003b6698fb2ed9a2a5ec55b93daa677b28f578897aa")
    private val sichuan = Pack("sichuan", "四川话", "csukuangfj/sherpa-onnx-paraformer-zh-int8-2025-10-07",
        "52f0cdb5bdde9533c62f2a49c9ec073ab8139820", 238429929,
        "53813ee1d41722cc6370a571c887e6d0b391d25b8312cf714a31af85ea603812", 75756,
        "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6")
    private val main = Handler(Looper.getMainLooper())
    private val engineLock = java.util.concurrent.locks.ReentrantLock()
    private val installs = HashMap<String, MutableList<(Boolean, String) -> Unit>>()
    private fun pack(dialect: Int) = if (dialect == 1) sichuan else mandarin
    fun name(dialect: Int) = pack(dialect).name
    fun directory(ctx: Context, dialect: Int): File = File(checkNotNull(ctx.getExternalFilesDir("offline-voice")) { "离线资源存储不可用" }, pack(dialect).id)
    private fun signature(p: Pack) = "${p.modelHash}\n${p.tokensHash}"

    fun ready(ctx: Context, dialect: Int): Boolean {
        val p = pack(dialect)
        val dir = directory(ctx, dialect)
        return File(dir, "model.int8.onnx").length() == p.bytes && File(dir, "tokens.txt").length() == p.tokensBytes &&
            runCatching { File(dir, ".verified").readText() == signature(p) }.getOrDefault(false)
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** 安装后先校验哈希，两个文件都完整才能启用离线识别。 */
    fun install(ctx: Context, dialect: Int, cb: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        val p = pack(dialect)
        if (ready(app, dialect)) { cb(true, "${p.name}离线包已安装，断网可用"); return }
        synchronized(installs) {
            installs[p.id]?.let { it.add(cb); return }
            installs[p.id] = mutableListOf(cb)
        }
        fun report(ok: Boolean, message: String, finish: Boolean = false) {
            val callbacks = synchronized(installs) {
                val copy = installs[p.id]?.toList().orEmpty()
                if (finish) installs.remove(p.id)
                copy
            }
            main.post { callbacks.forEach { it(ok, message) } }
        }
        Thread({
            runCatching {
                val dir = directory(app, dialect).apply { check(mkdirs() || isDirectory) { "离线资源目录不可用" } }
                report(false, "正在校验${p.name}离线包…")
                for ((fileName, hash) in listOf("model.int8.onnx" to p.modelHash, "tokens.txt" to p.tokensHash)) {
                    val dest = File(dir, fileName)
                    if (dest.isFile && sha(dest) == hash) continue
                    val temporary = File(dir, "$fileName.part")
                    val connection = URL("https://huggingface.co/${p.repo}/resolve/${p.revision}/$fileName?download=true").openConnection() as HttpURLConnection
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 30_000
                    connection.setRequestProperty("User-Agent", "KongyeKeyboard")
                    try {
                        check(connection.responseCode in 200..299) { "下载服务返回 ${connection.responseCode}" }
                        val total = if (fileName == "model.int8.onnx") p.bytes else p.tokensBytes
                        var count = 0L
                        var shown = -1
                        connection.inputStream.buffered().use { input -> temporary.outputStream().buffered().use { out ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); count += n
                                check(count <= total) { "下载内容大小异常" }
                                val percent = (count * 100 / total).toInt()
                                if (shown != percent) { shown = percent; report(false, "正在下载${p.name}离线包：$percent%") }
                            }
                        } }
                        check(temporary.length() == total && sha(temporary) == hash) { "文件校验失败，请重试" }
                        check(temporary.renameTo(dest)) { "保存离线包失败" }
                    } finally { connection.disconnect() }
                }
                File(dir, ".verified").writeText(signature(p))
                report(true, "${p.name}离线包已安装，断网可用", true)
            }.onFailure { report(false, "${p.name}离线包安装失败：${it.message ?: "请检查网络重试"}", true) }
        }, "KongyeVoiceInstall-${p.id}").start()
    }

    private fun recognizer(ctx: Context, dialect: Int): OfflineRecognizer {
        check(ready(ctx, dialect)) { "请先安装${name(dialect)}离线包" }
        val dir = directory(ctx, dialect)
        return OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            paraformer = OfflineParaformerModelConfig(model = File(dir, "model.int8.onnx").absolutePath),
            tokens = File(dir, "tokens.txt").absolutePath, numThreads = 2, debug = false, provider = "cpu")))
    }

    private fun decode(r: OfflineRecognizer, samples: FloatArray, sampleRate: Int = 16000): String {
        val stream = r.createStream()
        return try { stream.acceptWaveform(samples, sampleRate); r.decode(stream); r.getResult(stream).text.trim() }
        finally { stream.release() }
    }

    @Volatile private var cachedDialect = -1
    private var cachedEngine: OfflineRecognizer? = null
    private val warmRequests = HashSet<Int>()
    @Volatile private var preferredDialect = 0

    /** 必须持有 engineLock；复用已加载的所选模型，不在每次暂停后销毁。 */
    private fun engine(ctx: Context, dialect: Int): OfflineRecognizer {
        cachedEngine?.takeIf { cachedDialect == dialect }?.let { return it }
        cachedDialect=-1
        cachedEngine?.release(); cachedEngine=null
        return recognizer(ctx,dialect).also { cachedEngine=it; cachedDialect=dialect }
    }

    /** 只预热模型，不打开麦克风。 */
    fun prewarm(ctx: Context, dialect: Int) {
        preferredDialect=dialect
        if(!ready(ctx,dialect) || cachedDialect==dialect) return
        synchronized(warmRequests) { if(!warmRequests.add(dialect)) return }
        Thread({
            try { engineLock.withLock { if(preferredDialect==dialect) runCatching { engine(ctx.applicationContext,dialect) } } }
            finally { synchronized(warmRequests) { warmRequests.remove(dialect) } }
        },"KongyeVoiceWarmup").start()
    }

    fun releaseCache() {
        preferredDialect=-1
        Thread({ engineLock.withLock {
            cachedDialect=-1; cachedEngine?.release(); cachedEngine=null
        } },"KongyeVoiceRelease").start()
    }

    /** 公开样本验证与真实录音共享同一个缓存引擎。 */
    fun recognizeSamples(ctx: Context, dialect: Int, samples: FloatArray, sampleRate: Int): String = engineLock.withLock {
        decode(engine(ctx,dialect), samples, sampleRate)
    }

    @Keep
    interface Listener {
        fun status(text: String)
        fun partial(text: String)
        fun result(text: String)
        fun level(value: Float)
        fun spectrum(values: FloatArray) {}
        fun stopped(error: String?)
    }

    @Keep
    class Session(private val ctx: Context, private val dialect: Int, private val listener: Listener) {
        @Volatile private var recording = false
        @Volatile private var cancelled = false
        @Volatile private var finished = false
        @Volatile private var activeRecorder: AudioRecord? = null
        fun isFinished() = finished
        fun stop() { recording = false; runCatching { activeRecorder?.stop() } }
        fun cancel() { cancelled = true; stop() }
        private fun post(action: () -> Unit) { main.post { if (!cancelled) action() } }

        @SuppressLint("MissingPermission")
        fun start() {
            recording = true
            Thread({
                var audio: AudioRecord? = null
                var capture: Thread? = null
                val captureError=java.util.concurrent.atomic.AtomicReference<String?>(null)
                val captureDone=java.util.concurrent.atomic.AtomicBoolean(false)
                val chunks=java.util.concurrent.ArrayBlockingQueue<Pair<ShortArray,Float>>(800)
                var error:String?=null
                try {
                    // 先开麦接住开头，再等模型锁及首次加载；冷启动也不会丢第一句话。
                    val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                    check(minimum>0) { "麦克风不支持 16kHz 录音" }
                    val source=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,
                        AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum*4,64000))
                    audio=source
                    activeRecorder=source
                    check(source.state==AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
                    if(!recording) return@Thread
                    source.startRecording()
                    if(!recording) return@Thread
                    post { listener.status("正在聆听...") }
                    capture=Thread({
                        try {
                            val buffer=ShortArray(512)
                            val spectrumWindow=ShortArray(1024)
                            while(recording) {
                                val n=source.read(buffer,0,buffer.size)
                                check(n>=0 || !recording) { "录音读取失败（$n）" }
                                if(n<=0) continue
                                var sum=0.0
                                for(i in 0 until n) { val value=buffer[i]/32768f; sum+=value*value }
                                val rms=sqrt(sum/n).toFloat()
                                val frame=buffer.copyOf(n)
                                spectrumWindow.copyInto(spectrumWindow,0,n,spectrumWindow.size)
                                frame.copyInto(spectrumWindow,spectrumWindow.size-n)
                                val bands=VoiceSpectrum.analyze(spectrumWindow,16000)
                                post {
                                    listener.level(if(rms<.006f) 0f else (rms*300).coerceIn(0f,10f))
                                    listener.spectrum(bands)
                                }
                                check(chunks.offer(frame to rms)) { "设备识别速度不足，请分短句说话" }
                            }
                        } catch(e:Throwable) {
                            if(recording) captureError.set(e.message ?: "录音失败")
                            recording=false
                        } finally { captureDone.set(true) }
                    },"KongyeVoiceCapture").also { it.start() }
                    var locked=false
                    while(!cancelled && !locked) locked=engineLock.tryLock(40,java.util.concurrent.TimeUnit.MILLISECONDS)
                    if(cancelled) { if(locked) engineLock.unlock(); return@Thread }
                    try {
                        val loaded=engine(ctx,dialect)
                        val samples=FloatArray(16000*12)
                        var length=0
                        var spoken=false
                        var silence=0
                        var idle=0
                        var partialAt=0
                        var lastPartial=""
                        fun flush() {
                            if(spoken && length>3200) {
                                val result=decode(loaded,samples.copyOf(length))
                                if(result.isNotBlank()) post { listener.result(result) }
                            }
                            length=0; spoken=false; silence=0; partialAt=0; lastPartial=""
                        }
                        while(!cancelled && (!captureDone.get() || chunks.isNotEmpty())) {
                            val chunk=chunks.poll(150,java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                            val buffer=chunk.first; val n=buffer.size; val rms=chunk.second
                            if(length+n>samples.size) flush()
                            for(i in 0 until n) samples[length++]=buffer[i]/32768f
                            if(rms>.009f) { spoken=true; silence=0; idle=0 } else { silence+=n; idle+=n }
                            if(!spoken && length>4800) {
                                samples.copyInto(samples,0,length-3200,length); length=3200
                            }
                            if(spoken && silence>=11200) flush()
                            else if(spoken && length-partialAt>=24000) {
                                partialAt=length
                                val partial=decode(loaded,samples.copyOf(length))
                                if(partial.isNotBlank() && partial!=lastPartial) {
                                    lastPartial=partial; post { listener.partial(partial) }
                                }
                            }
                            if(idle>=16000*20) { recording=false; break }
                        }
                        captureError.get()?.let { error=it }
                        if(!cancelled) flush()
                    } finally { engineLock.unlock() }
                } catch(e:Throwable) { error=e.message ?: "离线识别失败" }
                finally {
                    recording=false
                    runCatching { audio?.stop() }
                    capture?.join(1000)
                    audio?.release()
                    activeRecorder=null
                    finished=true
                    val failure=error
                    post { listener.stopped(failure) }
                }
            },"KongyeOfflineVoice").start()
        }
    }
}
