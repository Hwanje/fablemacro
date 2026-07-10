package com.fablemacro.app.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import com.fablemacro.app.MacroAccessibilityService
import com.fablemacro.app.capture.ScreenCapturer
import com.fablemacro.app.model.ActionType
import com.fablemacro.app.model.Goto
import com.fablemacro.app.model.MacroAction
import com.fablemacro.app.model.MacroScript
import com.fablemacro.app.model.ScriptStore
import com.fablemacro.app.vision.OcrHelper
import com.fablemacro.app.vision.TemplateMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 스크립트 실행 엔진.
 * 각 스텝을 실행하고 성공/실패에 따라 다음 스텝 또는 지정 스텝으로 분기한다.
 * 검색 계열 액션은 maxAttempts(-1 = ∞) / retryIntervalMs 로 재시도 루프를 돈다.
 */
class MacroEngine(
    private val context: Context,
    private val capturer: ScreenCapturer,
    private val store: ScriptStore,
    private val nudgeScreen: () -> Unit,
) {
    interface Listener {
        fun onStep(index: Int, action: MacroAction, attempt: Int)
        fun onFinished(message: String)
    }

    var listener: Listener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start(script: MacroScript) {
        if (isRunning) return
        job = scope.launch {
            var message = "완료"
            try {
                message = runScript(script)
            } catch (e: kotlinx.coroutines.CancellationException) {
                message = "중지됨"
            } catch (e: Exception) {
                message = "오류: ${e.message}"
            } finally {
                notifyFinished(message)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun notifyFinished(message: String) {
        withContext(Dispatchers.Main + NonCancellable) { listener?.onFinished(message) }
    }

    private suspend fun notifyStep(index: Int, action: MacroAction, attempt: Int) {
        withContext(Dispatchers.Main) { listener?.onStep(index, action, attempt) }
    }

    private suspend fun runScript(script: MacroScript): String {
        var index = 0
        var executed = 0
        while (coroutineContext.isActive && index in script.actions.indices) {
            val action = script.actions[index]
            notifyStep(index, action, 0)
            val success = execute(index, action)
            if (action.postDelayMs > 0) delay(action.postDelayMs)
            executed++

            index = if (success) {
                when (action.onSuccessGoto) {
                    Goto.NEXT -> index + 1
                    Goto.STOP -> return "완료 (${executed}스텝 실행)"
                    else -> action.onSuccessGoto
                }
            } else {
                when (action.onFailureGoto) {
                    Goto.NEXT -> index + 1
                    Goto.STOP -> return "스텝 ${index + 1} 실패로 중지"
                    else -> action.onFailureGoto
                }
            }
        }
        return "완료 (${executed}스텝 실행)"
    }

    private fun acc(): MacroAccessibilityService? = MacroAccessibilityService.instance

    private suspend fun execute(index: Int, a: MacroAction): Boolean {
        return when (a.type) {
        ActionType.TAP -> {
            val svc = acc() ?: return false
            var ok = true
            repeat(max(1, a.repeatCount)) {
                ok = svc.tap(a.x, a.y, a.durationMs) && ok
                if (a.repeatCount > 1) delay(80)
            }
            ok
        }

        ActionType.SWIPE -> acc()?.swipe(a.x, a.y, a.x2, a.y2, a.durationMs) ?: false

        ActionType.PATH -> acc()?.path(a.points, a.durationMs) ?: false

        ActionType.DELAY -> {
            delay(a.delayMs)
            true
        }

        ActionType.SEARCH_IMAGE -> {
            val template = a.imageFile?.let { store.loadImage(it) }
            if (template == null) false
            else searchLoop(index, a) { frame ->
                TemplateMatcher.find(frame, template, a.region, a.threshold)
                    ?.let { Rect(it.cx, it.cy, it.cx, it.cy) }
            }
        }

        ActionType.SEARCH_TEXT -> {
            val query = a.text
            if (query.isNullOrBlank()) false
            else searchLoop(index, a) { frame ->
                val (cropped, offX, offY) = cropRegion(frame, a.region)
                OcrHelper.findText(cropped, query)?.also { it.offset(offX, offY) }
            }
        }

        ActionType.OCR_COPY -> {
            val frame = capturer.capture(onNudge = nudgeScreen)
            if (frame == null) false
            else {
                val (cropped, _, _) = cropRegion(frame, a.region)
                val text = OcrHelper.readAll(cropped)
                if (text.isBlank()) false
                else {
                    withContext(Dispatchers.Main) {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("fablemacro", text))
                    }
                    true
                }
            }
        }

        ActionType.PASTE_TEXT -> {
            val svc = acc() ?: return false
            val text = if (!a.text.isNullOrEmpty()) a.text!!
            else withContext(Dispatchers.Main) {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
            }
            if (text.isEmpty()) false else svc.setTextToFocused(text)
        }

        ActionType.OPEN_APP -> {
            val pkg = a.packageName
            if (pkg == null) false
            else {
                val li = context.packageManager.getLaunchIntentForPackage(pkg)
                if (li == null) false
                else {
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(li)
                    true
                }
            }
        }

        ActionType.BACK -> acc()?.globalBack() ?: false
        ActionType.HOME -> acc()?.globalHome() ?: false
        ActionType.RECENTS -> acc()?.globalRecents() ?: false

        ActionType.RANDOM_TAP -> {
            val svc = acc() ?: return false
            val r = a.region
            if (r == null) false
            else {
                val left = min(r[0], r[2])
                val right = max(r[0], r[2])
                val top = min(r[1], r[3])
                val bottom = max(r[1], r[3])
                val x = if (right > left) Random.nextInt(left, right) else left
                val y = if (bottom > top) Random.nextInt(top, bottom) else top
                svc.tap(x, y, a.durationMs)
            }
        }
        }
    }

    /**
     * 검색 재시도 루프. 찾으면 (옵션에 따라) 클릭 후 true,
     * maxAttempts 소진 시 false. maxAttempts < 0 이면 무한 재시도(∞).
     */
    private suspend fun searchLoop(
        index: Int,
        a: MacroAction,
        finder: suspend (Bitmap) -> Rect?,
    ): Boolean {
        var attempt = 0
        while (coroutineContext.isActive) {
            attempt++
            notifyStep(index, a, attempt)
            val frame = capturer.capture(onNudge = nudgeScreen)
            if (frame != null) {
                val found = finder(frame)
                if (found != null) {
                    if (a.clickOnFound) {
                        acc()?.tap(found.centerX(), found.centerY(), a.durationMs)
                    }
                    return true
                }
            }
            if (a.maxAttempts in 1..attempt) return false
            delay(a.retryIntervalMs.coerceAtLeast(100))
        }
        return false
    }

    private fun cropRegion(frame: Bitmap, region: IntArray?): Triple<Bitmap, Int, Int> {
        if (region == null) return Triple(frame, 0, 0)
        val left = max(0, min(region[0], region[2]))
        val top = max(0, min(region[1], region[3]))
        val right = min(frame.width, max(region[0], region[2]))
        val bottom = min(frame.height, max(region[1], region[3]))
        if (right - left < 8 || bottom - top < 8) return Triple(frame, 0, 0)
        return Triple(Bitmap.createBitmap(frame, left, top, right - left, bottom - top), left, top)
    }
}
