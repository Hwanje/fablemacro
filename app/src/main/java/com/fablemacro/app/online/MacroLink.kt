package com.fablemacro.app.online

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 매크로 링크 해석기.
 *
 * 받아들이는 형태:
 *  - `fablemacro://import?u=<주소>`      다른 주소를 가리키는 앱 링크
 *  - `fablemacro://import?p=<페이로드>`  링크 안에 매크로가 통째로 들어 있는 형태
 *  - `fablemacro://import?m=<id>`       카탈로그에 올라간 매크로 id
 *  - `https://…/#/m/<id>`               웹사이트 주소 (카탈로그 id로 해석)
 *  - `https://…/#p=<페이로드>`          웹 편집기가 만든 자기완결 링크
 *  - `https://…/….json`                 매크로 JSON 주소
 *  - `{ … }`                            매크로 JSON 본문 그대로 붙여넣기
 *
 * 페이로드는 base64(url-safe 허용)이며, gzip으로 압축돼 있으면 알아서 푼다.
 */
object MacroLink {

    private const val OWNER = "Hwanje"
    private const val REPO = "fablemacro"

    /** 웹 카탈로그 (GitHub Pages) */
    const val SITE = "https://$OWNER.github.io/$REPO/"

    /** Pages 설정과 무관하게 항상 접근 가능한 원본 경로 */
    private const val RAW_BASE =
        "https://raw.githubusercontent.com/$OWNER/$REPO/main/docs/macros/"

    const val CATALOG_URL = RAW_BASE + "index.json"

    /** 카탈로그 목록 항목 */
    data class Entry(
        val id: String = "",
        val name: String = "",
        val description: String? = null,
        val steps: Int = 0,
        val tags: List<String> = emptyList(),
    )

    fun macroUrl(id: String): String = RAW_BASE + id + ".json"

    fun appLink(id: String): String = "fablemacro://import?m=$id"

    /**
     * 입력값을 매크로 JSON 문자열로 바꾼다. 네트워크가 필요하면 받아온다.
     * 해석하거나 받아오지 못하면 null.
     */
    suspend fun resolve(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null

        // JSON 본문을 그대로 붙여넣은 경우
        if (raw.startsWith("{")) return raw

        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null

        when (uri.scheme?.lowercase()) {
            "fablemacro" -> {
                param(uri, "p")?.let { return decodePayload(it) }
                param(uri, "m")?.let { return fetch(macroUrl(it)) }
                param(uri, "u")?.let { return resolve(it) }
                return null
            }

            "http", "https" -> {
                // #p=… 또는 #/m/<id> 형태를 먼저 본다
                uri.fragment?.let { frag ->
                    Regex("(?:^|[?&#])p=([^&]+)").find(frag)?.let {
                        return decodePayload(it.groupValues[1])
                    }
                    Regex("/m/([A-Za-z0-9_-]+)").find(frag)?.let {
                        return fetch(macroUrl(it.groupValues[1]))
                    }
                }
                return fetch(raw)
            }
        }
        return null
    }

    private fun param(uri: Uri, key: String): String? =
        runCatching { uri.getQueryParameter(key) }.getOrNull()?.takeIf { it.isNotBlank() }

    /** base64(+gzip) 페이로드를 JSON으로 되돌린다 */
    private fun decodePayload(payload: String): String? {
        val cleaned = payload.trim().replace("\n", "")
        val bytes = listOf(
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.DEFAULT,
        ).firstNotNullOfOrNull { flags ->
            runCatching { Base64.decode(cleaned, flags) }.getOrNull()
        } ?: return null

        // gzip 매직 넘버면 압축을 푼다
        val text = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().decodeToString() }
            }.getOrNull()
        } else {
            runCatching { bytes.decodeToString() }.getOrNull()
        }
        return text?.takeIf { it.trimStart().startsWith("{") }
    }

    /** 카탈로그 목록 받아오기 */
    suspend fun fetchCatalog(): List<Entry>? = withContext(Dispatchers.IO) {
        val json = fetch(CATALOG_URL) ?: return@withContext null
        runCatching {
            val gson = com.google.gson.Gson()
            val root = com.google.gson.JsonParser.parseString(json)
            val array = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject && root.asJsonObject.has("macros") ->
                    root.asJsonObject.getAsJsonArray("macros")
                else -> return@runCatching null
            }
            array.map { gson.fromJson(it, Entry::class.java) }
        }.getOrNull()
    }

    suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "FableMacro")
                setRequestProperty("Accept", "application/json, text/plain, */*")
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 20_000
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                // 지나치게 큰 응답은 받지 않는다
                conn.inputStream.bufferedReader().use { it.readText() }.take(8 * 1024 * 1024)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
