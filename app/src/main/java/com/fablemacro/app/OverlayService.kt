package com.fablemacro.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.fablemacro.app.capture.ScreenCapturer
import com.fablemacro.app.engine.MacroEngine
import com.fablemacro.app.model.MacroAction
import com.fablemacro.app.model.MacroScript
import com.fablemacro.app.model.ScriptStore
import com.fablemacro.app.ui.MarkerView
import com.fablemacro.app.ui.OverlayPanel
import com.fablemacro.app.ui.fullscreenPickerParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 포그라운드 오버레이 서비스.
 * MediaProjection 화면 캡처 + 플로팅 버블/패널 + 매크로 엔진을 관리한다.
 */
class OverlayService : Service(), MacroEngine.Listener {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val ACTION_STOP = "com.fablemacro.app.STOP"

        /** 좌표 미리보기 표시 시간 */
        private const val PREVIEW_DURATION_MS = 1800L

        @Volatile
        var isRunning = false
    }

    private lateinit var wm: WindowManager
    lateinit var store: ScriptStore
        private set
    lateinit var engine: MacroEngine
        private set

    /** 엔진 준비 여부 — 준비 전에 engine을 건드리면 예외가 난다 */
    val isEngineReady: Boolean get() = this::engine.isInitialized

    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null

    val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: OverlayPanel? = null
    private var panelAttached = false

    /** 패널이 화면에 붙어 있는지 — 숨은 동안 불필요한 목록 갱신을 피하려고 본다 */
    val isPanelVisible: Boolean get() = panelAttached
    private var picker: View? = null
    private var marker: View? = null

    /** 실행 중인 스크립트의 전체 스텝 수 — 버블에 «3/10» 처럼 보여주기 위해 */
    private var runningTotal = 0
    private var nudgeFlip = false

    /** 캡처 때문에 버블을 숨긴 상태인지 */
    private var bubbleHidden = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        wm = getSystemService(WindowManager::class.java)
        store = ScriptStore(this)

        startAsForeground()

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(resultCode, resultData) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, mainHandler)

        val cap = ScreenCapturer(this)
        cap.start(proj)
        capturer = cap

        engine = MacroEngine(this, cap, store, nudgeScreen = ::nudgeScreen)
        engine.listener = this

        showBubble()
        isRunning = true
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val channelId = "fablemacro"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "FableMacro", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("FableMacro 실행 중")
            .setContentText("버블 탭 = 패널 열기 · 길게 누르기 = 되돌리기")
            .addAction(Notification.Action.Builder(null, "종료", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    // ───────────────────────── 플로팅 버블 ─────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val density = resources.displayMetrics.density
        val size = (52 * density).toInt()
        val b = TextView(this).apply {
            text = "FM"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6212121"))
                setStroke((2 * density).toInt(), Color.parseColor("#FF8BC34A"))
            }
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (8 * density).toInt()
            y = (160 * density).toInt()
        }

        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(b, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (e.eventTime - e.downTime >= 600) onBubbleLongPress() else onBubbleClick()
                    }
                }
            }
            true
        }
        wm.addView(b, params)
        bubble = b
        bubbleParams = params
    }

    /**
     * 길게 누르면 상태를 되돌린다.
     * 캡처 중 멈춤이나 픽커가 남는 등으로 조작 수단이 사라졌을 때의 탈출구.
     */
    private fun onBubbleLongPress() {
        if (isEngineReady && engine.isRunning) runCatching { engine.stop() }
        removePicker()
        removeMarker()
        setBubbleHidden(false)
        setBubbleRunning(false)
        panel?.setRunningState(false)
        setPanelVisible(true)
        panel?.setStatus("길게 눌러 되돌렸습니다")
    }

    private fun onBubbleClick() {
        if (isEngineReady && engine.isRunning) {
            stopMacro()
        } else {
            setPanelVisible(!panelAttached)
        }
    }

    private fun setBubbleRunning(running: Boolean) {
        bubble?.apply {
            text = if (running) "■" else "FM"
            textSize = 18f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (running) Color.parseColor("#E6B71C1C") else Color.parseColor("#E6212121"))
                setStroke(
                    (2 * resources.displayMetrics.density).toInt(),
                    if (running) Color.parseColor("#FFFF8A80") else Color.parseColor("#FF8BC34A")
                )
            }
        }
    }

    /**
     * 실행 중에는 패널이 숨겨져 버블만 보이므로, 지금 몇 번째 스텝인지를 버블에 띄운다.
     * 재시도 중이면 시도 횟수도 함께 보여 멈춘 것인지 도는 중인지 구분된다.
     */
    private fun setBubbleStep(index: Int, attempt: Int) {
        bubble?.apply {
            val step = "${index + 1}/${runningTotal.coerceAtLeast(index + 1)}"
            text = if (attempt > 1) "$step\n↺$attempt" else step
            textSize = if (attempt > 1) 11f else 14f
        }
    }

    // ───────────────────────── 패널 / 픽커 ─────────────────────────

    fun setPanelVisible(visible: Boolean) {
        val p = panel ?: OverlayPanel(this).also { panel = it }
        if (visible && !panelAttached) {
            val density = resources.displayMetrics.density
            // 가로 화면은 세로가 짧으므로 위쪽 여백을 줄여 패널 자리를 더 준다
            val landscape = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
            val params = WindowManager.LayoutParams(
                (330 * density).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = (4 * density).toInt()
                y = ((if (landscape) 8 else 60) * density).toInt()
            }
            wm.addView(p.root, params)
            panelAttached = true
            p.onShown()
        } else if (!visible && panelAttached) {
            wm.removeView(p.root)
            panelAttached = false
        }
    }

    /**
     * 화면을 돌리면 패널 크기·위치 기준이 바뀌므로 붙였다 다시 붙여 새로 재도록 한다.
     * 캡처 쪽 크기는 다음 캡처 때 ScreenCapturer가 알아서 맞춘다.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (panelAttached) {
            setPanelVisible(false)
            mainHandler.post { setPanelVisible(true) }
        }
    }

    fun showPicker(view: View) {
        removePicker()
        picker = view
        wm.addView(view, fullscreenPickerParams(this))
    }

    fun removePicker() {
        picker?.let { runCatching { wm.removeView(it) } }
        picker = null
    }

    /**
     * 액션에 설정된 좌표/영역이 화면 어디인지 잠시 표시한다.
     * 패널이 가릴 수 있으므로 표시하는 동안 패널을 숨겼다가 되돌린다.
     */
    fun previewAction(action: MacroAction) {
        if (!action.canPreview()) {
            panel?.setStatus("표시할 좌표가 없습니다")
            return
        }
        removeMarker()
        val points = action.previewPoints()
        val region = action.previewRegion()?.let {
            Rect(min(it[0], it[2]), min(it[1], it[3]), max(it[0], it[2]), max(it[1], it[3]))
        }
        val label = buildString {
            append(action.displayName())
            when {
                points.size == 1 -> append("  (${points[0][0]}, ${points[0][1]})")
                points.size > 1 -> append("  ${points.size}개 지점")
                region != null -> append("  ${region.width()} x ${region.height()}")
            }
        }

        val wasPanelVisible = panelAttached
        if (wasPanelVisible) setPanelVisible(false)

        val view = MarkerView(this, points, region, label)
        val params = fullscreenPickerParams(this).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        wm.addView(view, params)
        marker = view

        mainHandler.postDelayed({
            removeMarker()
            if (wasPanelVisible) setPanelVisible(true)
        }, PREVIEW_DURATION_MS)
    }

    /** 오버레이(버블 + 패널)를 완전히 종료한다. 실행 중인 매크로가 있으면 먼저 멈춘다. */
    fun shutdownOverlay() {
        if (isEngineReady && engine.isRunning) runCatching { engine.stop() }
        stopSelf()
    }

    private fun removeMarker() {
        marker?.let { runCatching { wm.removeView(it) } }
        marker = null
    }

    // ───────────────────────── 캡처 ─────────────────────────

    /**
     * 화면이 멈춰 있으면 새 캡처 프레임이 오지 않으므로 버블을 아주 조금 흔들어 갱신을 유발한다.
     * 현재 alpha에 더하면 값이 조금씩 흘러내려 버블이 사라지므로, 기준값에서만 흔든다.
     */
    private fun nudgeScreen() {
        mainHandler.post {
            bubble?.let {
                nudgeFlip = !nudgeFlip
                val base = if (bubbleHidden) 0.02f else 1f
                it.alpha = (base - if (nudgeFlip) 0.01f else 0f).coerceIn(0.01f, 1f)
            }
        }
    }

    /** 오버레이를 거의 안 보이게 숨긴 뒤 깨끗한 프레임 캡처 (템플릿 저장용) */
    suspend fun captureClean(): Bitmap? {
        val cap = capturer ?: return null
        return try {
            withContext(Dispatchers.Main + NonCancellable) { setBubbleHidden(true) }
            delay(250)
            cap.capture(onNudge = ::nudgeScreen) // 이전 프레임 소거
            cap.capture(onNudge = ::nudgeScreen)
        } finally {
            // 예외나 취소로 빠져나가도 버블은 반드시 되돌린다.
            // 안 그러면 버블이 거의 투명하게 남아 보이지도, 눌리지도 않는다.
            withContext(Dispatchers.Main + NonCancellable) { setBubbleHidden(false) }
        }
    }

    /** 캡처 동안 버블을 화면에서 지웠다 되돌린다 */
    private fun setBubbleHidden(hidden: Boolean) {
        bubbleHidden = hidden
        bubble?.alpha = if (hidden) 0.02f else 1f
    }

    // ───────────────────────── 매크로 실행 ─────────────────────────

    fun startMacro(script: MacroScript) {
        if (!isEngineReady || engine.isRunning) return
        if (MacroAccessibilityService.instance == null) {
            refuseStart("접근성 서비스가 꺼져 있어 실행할 수 없습니다.\n설정 → 접근성 → FableMacro 를 켜주세요.")
            return
        }
        if (script.actions.isEmpty()) {
            refuseStart("스텝이 없습니다. 아래 Action List에서 액션을 먼저 추가하세요.")
            return
        }
        if (capturer == null) {
            refuseStart("화면 캡처가 준비되지 않았습니다. 오버레이를 종료하고 앱에서 다시 시작해주세요.")
            return
        }
        runningTotal = script.actions.size
        setPanelVisible(false)
        setBubbleRunning(true)
        panel?.setRunningState(true)
        engine.start(script)
    }

    /** 실행을 못 하는 이유는 눈에 띄게 알린다 — 상태줄만 바꾸면 «버튼이 안 눌린다»로 보인다 */
    private fun refuseStart(reason: String) {
        panel?.setStatus("⚠ 실행할 수 없음")
        setPanelVisible(true)
        android.widget.Toast.makeText(this, reason, android.widget.Toast.LENGTH_LONG).show()
    }

    fun stopMacro() {
        if (isEngineReady) engine.stop()
        setBubbleRunning(false)
        panel?.setRunningState(false)
        setPanelVisible(true)
        panel?.setStatus("중지됨")
    }

    override fun onStep(index: Int, action: MacroAction, attempt: Int) {
        panel?.highlight(index)
        setBubbleStep(index, attempt)
        val att = if (attempt > 1) " (시도 $attempt)" else ""
        panel?.setStepCounter(index + 1, runningTotal)
        panel?.setStatus("실행 중: ${index + 1}/${runningTotal}. ${action.displayName()}$att")
    }

    override fun onFinished(message: String) {
        setBubbleRunning(false)
        panel?.setRunningState(false)
        setPanelVisible(true)
        panel?.setStatus(message)
    }

    // ───────────────────────── 종료 ─────────────────────────

    override fun onDestroy() {
        isRunning = false
        if (isEngineReady) runCatching { engine.stop() }
        mainHandler.removeCallbacksAndMessages(null)
        removePicker()
        removeMarker()
        if (panelAttached) runCatching { wm.removeView(panel!!.root) }
        panelAttached = false
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        capturer?.stop()
        capturer = null
        projection?.stop()
        projection = null
        uiScope.cancel()
        super.onDestroy()
    }
}
