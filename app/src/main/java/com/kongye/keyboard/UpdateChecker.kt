package com.kongye.keyboard

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 检查更新：读 GitHub 上最新的 Release，比较版本号 */
object UpdateChecker {
    const val REPO = "Mayleaves/KongyeKeyboard"
    const val REPOSITORY = "https://github.com/$REPO"
    const val RELEASES = "https://github.com/$REPO/releases"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    class Release(val tag: String, val notes: String, val apkUrl: String?, val pageUrl: String, val sha256: String? = null, val assetApiUrl:String?=null) {
        val version get() = tag.trimStart('v', 'V')
    }

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    /** 联网读最新版本；要在后台线程调用 */
    fun fetch(): Release {
        val conn = URL(API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "KongyeKeyboard")
            val code = conn.responseCode
            if (code == 404) throw IllegalStateException("仓库里还没有发布版本")
            if (code != 200) throw IllegalStateException("GitHub 返回 $code")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            var digest: String? = null
            var assetApi: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val url = assets.getJSONObject(i).optString("browser_download_url")
                if (url.endsWith(".apk", ignoreCase = true)) {
                    apk = url
                    digest=assets.getJSONObject(i).optString("digest").removePrefix("sha256:").takeIf {it.matches(Regex("[0-9a-fA-F]{64}"))}
                    assetApi=assets.getJSONObject(i).optString("url").takeIf {it.isNotBlank()}
                    break
                }
            }
            return Release(json.optString("tag_name"), json.optString("body"), apk, json.optString("html_url").ifEmpty { RELEASES },digest,assetApi)
        } finally {
            conn.disconnect()
        }
    }

    /** a 比 b 新吗（按点分的数字比较，1.10.0 > 1.9.2） */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }
}
