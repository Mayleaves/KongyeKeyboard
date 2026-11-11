package com.kongye.keyboard

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** 检查更新：读 GitHub 上最新的 Release，比较版本号 */
object UpdateChecker {
    const val REPO = "Mayleaves/KongyeKeyboard"
    const val REPOSITORY = "https://github.com/$REPO"
    const val RELEASES = "https://github.com/$REPO/releases"
    // The GitHub "latest" label remains on the small updater compatibility
    // release, so old clients with a 200MB limit can upgrade in two local steps.
    // New clients select the highest stable version from the actual releases.
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=20"

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
            return parseReleases(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    fun parseReleases(body: String, supportedAbis: List<String> = android.os.Build.SUPPORTED_ABIS.toList()): Release {
            val entries = JSONArray(body)
            var selected: JSONObject? = null
            for(i in 0 until entries.length()) {
                val candidate=entries.getJSONObject(i)
                if(candidate.optBoolean("draft") || candidate.optBoolean("prerelease")) continue
                val candidateVersion=candidate.optString("tag_name").trimStart('v','V')
                if(!candidateVersion.matches(Regex("[0-9]+([.][0-9]+)+"))) continue
                if(selected==null || newer(candidateVersion,selected.optString("tag_name").trimStart('v','V'))) selected=candidate
            }
            val json = selected ?: error("仓库里还没有发布版本")
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            var digest: String? = null
            var assetApi: String? = null
            var best = -1
            if (assets != null) for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val url = asset.optString("browser_download_url")
                if (url.endsWith(".apk", ignoreCase = true)) {
                    val name = asset.optString("name").ifEmpty { url.substringAfterLast('/') }.lowercase()
                    val aliases = mapOf("arm64-v8a" to listOf("arm64-v8a", "arm64"), "x86_64" to listOf("x86_64", "x64"),
                        "armeabi-v7a" to listOf("armeabi-v7a", "armv7"), "x86" to listOf("-x86."))
                    val matched = aliases.entries.firstOrNull { (_, names) -> names.any { name.contains(it) } }?.key
                    val score = when {
                        matched != null && matched in supportedAbis -> 1000 - supportedAbis.indexOf(matched)
                        matched != null -> -1
                        "universal" in name -> 100
                        else -> 0 // Previous single-APK releases have no ABI suffix.
                    }
                    if (score <= best) continue
                    best = score
                    apk = url
                    digest=asset.optString("digest").removePrefix("sha256:").takeIf {it.matches(Regex("[0-9a-fA-F]{64}"))}
                    assetApi=asset.optString("url").takeIf {it.isNotBlank()}
                }
            }
            return Release(json.optString("tag_name"), json.optString("body"), apk, json.optString("html_url").ifEmpty { RELEASES },digest,assetApi)
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
