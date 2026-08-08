package com.fablemacro.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 기반 자동 업데이트.
 *
 * 최신 릴리스의 태그(v1.0.N)와 설치된 versionName을 비교해 새 버전이면
 * APK를 내려받아 시스템 설치 화면을 띄운다.
 * 릴리스 APK는 항상 같은 키로 서명되므로 덮어쓰기 설치가 가능하다.
 */
object UpdateChecker {

    private const val REPO = "Hwanje/fablemacro"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    data class Release(
        val versionName: String,
        val apkUrl: String,
        val apkSize: Long,
        val notes: String,
    )

    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
    }.getOrDefault("1.0.0")

    /** 최신 릴리스 조회. 네트워크 실패/릴리스 없음이면 null */
    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "FableMacro-Updater")
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            try {
                if (conn.responseCode != 200) return@runCatching null
                val json = JsonParser.parseString(conn.inputStream.bufferedReader().readText())
                    .asJsonObject
                val tag = json.get("tag_name")?.asString ?: return@runCatching null
                val notes = json.get("body")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val asset = json.getAsJsonArray("assets")?.firstOrNull { el ->
                    el.asJsonObject.get("name")?.asString?.endsWith(".apk") == true
                }?.asJsonObject ?: return@runCatching null

                Release(
                    versionName = tag.removePrefix("v"),
                    apkUrl = asset.get("browser_download_url").asString,
                    apkSize = asset.get("size")?.asLong ?: 0L,
                    notes = notes.trim(),
                )
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /**
     * 점(.)으로 구분된 숫자 버전을 비교해 remote가 더 새로우면 true.
     * 숫자가 아닌 세그먼트는 0으로 취급한다.
     */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
        val l = local.split('.', '-').mapNotNull { it.toIntOrNull() }
        if (r.isEmpty()) return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    /** APK 다운로드. onProgress(받은 바이트, 전체 바이트) — 전체를 모르면 -1 */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Long, Long) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply {
                mkdirs()
                // 이전 다운로드본 정리
                listFiles()?.forEach { it.delete() }
            }
            val out = File(dir, "FableMacro-${release.versionName}.apk")

            val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "FableMacro-Updater")
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 60_000
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else release.apkSize
                var received = 0L
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            received += n
                            onProgress(received, total)
                        }
                    }
                }
                if (out.length() > 0) out else null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** '출처를 알 수 없는 앱' 설치 권한이 있는지 */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 26) context.packageManager.canRequestPackageInstalls() else true

    /** 설치 권한 설정 화면 */
    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** 시스템 설치 화면 띄우기 */
    fun installApk(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
