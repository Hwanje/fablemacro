package com.fablemacro.app.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 액션 종류 (스크린샷의 Action List 대응) */
enum class ActionType(val label: String, val emoji: String) {
    TAP("탭", "👆"),
    SWIPE("스와이프", "👉"),
    PATH("경로", "〰️"),
    DELAY("딜레이", "⏱"),
    SEARCH_IMAGE("이미지 검색", "🖼"),
    SEARCH_TEXT("텍스트 검색", "🔎"),
    OCR_COPY("텍스트 OCR 복사", "📋"),
    PASTE_TEXT("텍스트 붙여넣기", "📝"),
    OPEN_APP("앱 실행", "📱"),
    BACK("뒤로가기", "◀"),
    HOME("홈", "⌂"),
    RECENTS("최근 앱", "▤"),
    RANDOM_TAP("랜덤 탭", "🎲");
}

/** 분기 상수: onSuccessGoto / onFailureGoto 값 */
object Goto {
    const val NEXT = -1   // 다음 스텝으로
    const val STOP = -2   // 스크립트 중지
    // 0 이상 = 해당 인덱스 스텝으로 점프
}

private val ID_CHARS = ('a'..'z') + ('A'..'Z') + ('0'..'9')
fun randomId(): String = (1..8).map { ID_CHARS.random() }.joinToString("")

/**
 * 매크로 스텝 하나. 타입별로 사용하는 필드만 채워진다.
 * Gson 직렬화를 위해 모든 필드에 기본값을 둔 단일 클래스로 유지.
 */
data class MacroAction(
    var id: String = randomId(),
    var type: ActionType = ActionType.TAP,
    // 사용자가 지정한 액션 이름 (비어 있으면 타입 이름으로 표시)
    var name: String? = null,
    // 좌표 (TAP / SWIPE 시작점)
    var x: Int = 0,
    var y: Int = 0,
    // SWIPE 끝점
    var x2: Int = 0,
    var y2: Int = 0,
    // PATH 포인트 목록 [x, y]
    var points: MutableList<IntArray> = mutableListOf(),
    // 제스처 지속시간
    var durationMs: Long = 120,
    // DELAY 시간
    var delayMs: Long = 1000,
    // SEARCH_IMAGE 템플릿 이미지 파일명 (images/ 내)
    var imageFile: String? = null,
    // 이미지 매칭 임계값 0.0 ~ 1.0
    var threshold: Double = 0.85,
    // SEARCH_TEXT 대상 텍스트 / PASTE_TEXT 내용
    var text: String? = null,
    // 검색/랜덤탭 영역 [left, top, right, bottom], null = 전체 화면
    var region: IntArray? = null,
    // OPEN_APP 패키지명
    var packageName: String? = null,
    var appLabel: String? = null,
    // 검색 성공 시 찾은 위치 클릭 여부
    var clickOnFound: Boolean = true,
    // 검색 재시도: -1 = 무한(∞), n = 최대 n회 시도
    var maxAttempts: Int = -1,
    var retryIntervalMs: Long = 1000,
    // 실행 후 대기시간 (모든 액션 공통)
    var postDelayMs: Long = 300,
    // 분기: 성공 시 / 실패 시 이동 (Goto 상수 또는 스텝 인덱스)
    var onSuccessGoto: Int = Goto.NEXT,
    var onFailureGoto: Int = Goto.STOP,
    // TAP 반복 횟수
    var repeatCount: Int = 1,
    // 링크/웹으로 주고받을 때만 쓰는 템플릿 이미지 본문 (base64 PNG).
    // 기기에 저장할 때는 imageFile로 풀어 쓰고 이 값은 비운다.
    var imageData: String? = null,
) {
    /** 리스트 제목에 쓸 이름 — 지정한 이름이 없으면 타입 이름 */
    fun displayName(): String = name?.takeIf { it.isNotBlank() } ?: type.label

    /** 이름을 따로 지정한 경우에만 타입 이름을 부제로 노출 */
    fun hasCustomName(): Boolean = !name.isNullOrBlank()

    /** 화면에서 위치를 미리 보여줄 수 있는 액션인지 */
    fun previewPoints(): List<IntArray> = when (type) {
        ActionType.TAP -> listOf(intArrayOf(x, y))
        ActionType.SWIPE -> listOf(intArrayOf(x, y), intArrayOf(x2, y2))
        ActionType.PATH -> points.toList()
        else -> emptyList()
    }

    fun previewRegion(): IntArray? = when (type) {
        ActionType.TAP, ActionType.SWIPE, ActionType.PATH -> null
        else -> region
    }

    fun canPreview(): Boolean = previewPoints().isNotEmpty() || previewRegion() != null

    /** 리스트에 표시할 파라미터 요약 */
    fun summary(): String = when (type) {
        ActionType.TAP -> "($x, $y) x$repeatCount"
        ActionType.SWIPE -> "($x,$y)→($x2,$y2) ${durationMs}ms"
        ActionType.PATH -> "${points.size}개 지점 ${durationMs}ms"
        ActionType.DELAY -> "time : ${delayMs}ms"
        ActionType.SEARCH_IMAGE -> "image:${imageFile?.removeSuffix(".png") ?: "?"} th:${threshold}" +
                (if (maxAttempts < 0) " ↺∞" else " ↺$maxAttempts")
        ActionType.SEARCH_TEXT -> "\"${text ?: ""}\"" + (if (maxAttempts < 0) " ↺∞" else " ↺$maxAttempts")
        ActionType.OCR_COPY -> "영역 OCR → 클립보드"
        ActionType.PASTE_TEXT -> if (text.isNullOrEmpty()) "클립보드 내용" else "\"$text\""
        ActionType.OPEN_APP -> appLabel ?: packageName ?: "?"
        ActionType.BACK, ActionType.HOME, ActionType.RECENTS -> ""
        ActionType.RANDOM_TAP -> region?.let { "[${it[0]},${it[1]}~${it[2]},${it[3]}]" } ?: ""
    }
}

data class MacroScript(
    var name: String = "새 스크립트",
    var actions: MutableList<MacroAction> = mutableListOf(),
    // 카탈로그/웹에서 쓰는 설명과 태그 (앱 동작에는 영향 없음)
    var description: String? = null,
    var tags: MutableList<String> = mutableListOf(),
)

/** 스크립트(JSON)와 템플릿 이미지(PNG) 저장소 */
class ScriptStore(private val context: Context) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val scriptDir: File get() = File(context.filesDir, "scripts").apply { mkdirs() }
    private val imageDir: File get() = File(context.filesDir, "images").apply { mkdirs() }

    fun listNames(): List<String> =
        scriptDir.listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    fun save(script: MacroScript) {
        val safe = script.name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "script" }
        script.name = safe
        File(scriptDir, "$safe.json").writeText(gson.toJson(script))
    }

    fun load(name: String): MacroScript? {
        val f = File(scriptDir, "$name.json")
        if (!f.exists()) return null
        return runCatching { gson.fromJson(f.readText(), MacroScript::class.java) }.getOrNull()
    }

    fun delete(name: String) {
        File(scriptDir, "$name.json").delete()
    }

    fun saveImage(bitmap: Bitmap): String {
        val name = "${randomId()}.png"
        File(imageDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return name
    }

    fun loadImage(name: String): Bitmap? {
        val f = File(imageDir, name)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    /** 저장 목록에서 보여줄 요약 정보 */
    data class ScriptInfo(
        val name: String,
        val steps: Int,
        val templates: Int,
        val missingTemplates: Int,
        val modifiedAt: Long,
        val bytes: Long,
    )

    fun info(name: String): ScriptInfo? {
        val f = File(scriptDir, "$name.json")
        if (!f.exists()) return null
        val s = load(name) ?: return ScriptInfo(name, 0, 0, 0, f.lastModified(), f.length())
        val templates = s.actions.mapNotNull { it.imageFile }.distinct()
        val missing = templates.count { !File(imageDir, it).exists() }
        return ScriptInfo(name, s.actions.size, templates.size, missing, f.lastModified(), f.length())
    }

    /**
     * 스크립트 + 참조하는 템플릿 이미지를 ZIP 한 개로 묶어 내보낸다.
     * 이미지까지 함께 담기므로 다른 기기에서도 그대로 복원된다.
     */
    fun exportTo(name: String, out: OutputStream): Boolean {
        val script = load(name) ?: return false
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("script.json"))
            zip.write(gson.toJson(script).toByteArray())
            zip.closeEntry()

            for (img in script.actions.mapNotNull { it.imageFile }.distinct()) {
                val f = File(imageDir, img)
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry("images/$img"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return true
    }

    /**
     * exportTo로 만든 ZIP을 되돌린다. 이미 같은 이름이 있으면 «이름 (2)» 처럼 번호를 붙여
     * 기존 저장본을 덮어쓰지 않는다. 복원된 스크립트 이름을 반환.
     */
    fun importFrom(input: InputStream): String? {
        var json: String? = null
        val images = mutableMapOf<String, ByteArray>()

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // 경로 탈출(zip slip) 방지 — 파일명만 사용한다
                val entryName = File(entry.name).name
                when {
                    entry.isDirectory -> {}
                    entry.name == "script.json" -> json = zip.readBytes().decodeToString()
                    entry.name.startsWith("images/") && entryName.isNotBlank() ->
                        images[entryName] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }

        val script = json?.let {
            runCatching { gson.fromJson(it, MacroScript::class.java) }.getOrNull()
        } ?: return null

        // 템플릿 이미지 파일명이 기존 것과 겹치면 새 이름으로 복사하고 참조를 바꾼다
        val renamed = mutableMapOf<String, String>()
        for ((origName, bytes) in images) {
            val target = if (File(imageDir, origName).exists()) "${randomId()}.png" else origName
            File(imageDir, target).writeBytes(bytes)
            renamed[origName] = target
        }
        for (a in script.actions) {
            a.imageFile?.let { a.imageFile = renamed[it] ?: it }
        }

        script.name = uniqueName(script.name.ifBlank { "가져온 스크립트" })
        save(script)
        return script.name
    }

    /** 기존 저장본을 덮어쓰지 않는 이름 만들기 */
    fun uniqueName(base: String): String {
        val safe = base.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "script" }
        if (!File(scriptDir, "$safe.json").exists()) return safe
        var n = 2
        while (File(scriptDir, "$safe ($n).json").exists()) n++
        return "$safe ($n)"
    }

    fun exists(name: String): Boolean = File(scriptDir, "$name.json").exists()

    /** 저장하지 않은 변경이 있는지 비교하기 위한 스냅샷 */
    fun snapshot(script: MacroScript): String = gson.toJson(script)

    // ─────────────── 링크/웹 공유용 JSON ───────────────

    /**
     * 링크나 웹으로 넘길 JSON. 템플릿 이미지를 base64로 본문에 담아
     * 받는 쪽에 이미지 파일이 없어도 그대로 동작하게 한다.
     */
    fun toShareJson(script: MacroScript): String {
        val copy = gson.fromJson(gson.toJson(script), MacroScript::class.java)
        for (a in copy.actions) {
            val img = a.imageFile ?: continue
            val f = File(imageDir, img)
            if (f.exists()) {
                a.imageData = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            }
        }
        return gson.toJson(copy)
    }

    /**
     * 링크/웹에서 받은 JSON을 저장본으로 만든다.
     * 항상 새 이름으로 저장하므로 기존 저장본을 덮어쓰지 않는다.
     * 외부에서 온 값이라 타입이 빠졌거나 목록이 없는 경우까지 걸러낸다.
     */
    @Suppress("SENSELESS_COMPARISON")
    fun fromShareJson(json: String): MacroScript? {
        val script = runCatching { gson.fromJson(json, MacroScript::class.java) }.getOrNull()
            ?: return null
        if (script.actions == null) script.actions = mutableListOf()
        if (script.tags == null) script.tags = mutableListOf()
        // 타입이 없는 항목은 실행할 수 없으므로 제외
        script.actions = script.actions.filter { it != null && it.type != null }.toMutableList()
        if (script.actions.isEmpty()) return null

        for (a in script.actions) {
            if (a.id.isNullOrBlank()) a.id = randomId()
            if (a.points == null) a.points = mutableListOf()
            // 외부에서 온 파일명은 경로로 해석되지 않게 이름만 남긴다
            a.imageFile = a.imageFile?.let { File(it).name }?.takeIf { it.isNotBlank() }
            val data = a.imageData
            a.imageData = null
            if (data.isNullOrBlank()) continue
            val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() ?: continue
            val target = "${randomId()}.png"
            runCatching {
                File(imageDir, target).writeBytes(bytes)
                a.imageFile = target
            }
        }

        script.name = uniqueName(script.name?.ifBlank { null } ?: "가져온 매크로")
        save(script)
        return script
    }
}
