package com.fablemacro.app.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

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
) {
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
}
