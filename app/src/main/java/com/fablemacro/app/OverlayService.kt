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
import com.fablemacro.app.ui.OverlayPanel
import com.fablemacro.app.ui.fullscreenPickerParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 포그라운드 오버레이 서비스.
 * MediaProjection 화면 캡처 + 플로팅 버블/패널 + 매크로 엔진을 관리한다.
 */
class OverlayService : Service(), MacroEngine.Listener {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val ACTION_STOP = "com.fablemacro.app.STOP"

        @Volatile
        var isRunning = false
    }

    private lateinit var wm: WindowManager
    lateinit var store: ScriptStore
        private set
    lateinit var engine: MacroEngine
        private set

    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null

    val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: OverlayPanel? = null
    private var panelAttached = false
    private var picker: View? = null
    private var nudgeFlip = false

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
            .setContentText("플로팅 버블을 눌러 매크로 패널을 여세요")
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
                    if (!moved) onBubbleClick()
                }
            }
            true
        }
        wm.addView(b, params)
        bubble = b
        bubbleParams = params
    }

    private fun onBubbleClick() {
        if (engine.isRunning) {
            stopMacro()
        } else {
            setPanelVisible(!panelAttached)
        }
    }

    private fun setBubbleRunning(running: Boolean) {
        bubble?.apply {
            text = if (running) "■" else "FM"
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

    // ───────────────────────── 패널 / 픽커 ─────────────────────────

    fun setPanelVisible(visible: Boolean) {
        val p = panel ?: OverlayPanel(this).also { panel = it }
        if (visible && !panelAttached) {
            val density = resources.displayMetrics.density
            val params = WindowManager.LayoutParams(
                (330 * density).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = (4 * density).toInt()
                y = (60 * density).toInt()
            }
            wm.addView(p.root, params)
            panelAttached = true
        } else if (!visible && panelAttached) {
            wm.removeView(p.root)
            panelAttached = false
        }
    }

    fun showPicker(view: View) {
        removePicker()
        picker = view
        wm.addView(view, fullscreenPickerParams())
    }

    fun removePicker() {
        picker?.let { runCatching { wm.removeView(it) } }
        picker = null
    }

    // ───────────────────────── 캡처 ─────────────────────────

    /** 화면 갱신 유발: 버블 알파를 미세하게 흔들어 새 프레임 생성 */
    private fun nudgeScreen() {
        mainHandler.post {
            bubble?.let {
                nudgeFlip = !nudgeFlip
                it.alpha = it.alpha + if (nudgeFlip) -0.01f else 0.01f
            }
        }
    }

    /** 오버레이를 거의 안 보이게 숨긴 뒤 깨끗한 프레임 캡처 (템플릿 저장용) */
    suspend fun captureClean(): Bitmap? {
        val cap = capturer ?: return null
        withContext(Dispatchers.Main) { bubble?.alpha = 0.02f }
        delay(250)
        cap.capture(onNudge = ::nudgeScreen) // 이전 프레임 소거
        val frame = cap.capture(onNudge = ::nudgeScreen)
        withContext(Dispatchers.Main) { bubble?.alpha = 1f }
        return frame
    }

    // ───────────────────────── 매크로 실행 ─────────────────────────

    fun startMacro(script: MacroScript) {
        if (engine.isRunning) return
        if (MacroAccessibilityService.instance == null) {
            panel?.setStatus("⚠ 접근성 서비스를 먼저 켜주세요")
            return
        }
        if (script.actions.isEmpty()) {
            panel?.setStatus("스크립트가 비어 있습니다")
            return
        }
        setPanelVisible(false)
        setBubbleRunning(true)
        panel?.setRunningState(true)
        engine.start(script)
    }

    fun stopMacro() {
        engine.stop()
        setBubbleRunning(false)
        panel?.setRunningState(false)
        setPanelVisible(true)
        panel?.setStatus("중지됨")
    }

    override fun onStep(index: Int, action: MacroAction, attempt: Int) {
        panel?.highlight(index)
        val att = if (attempt > 1) " (시도 $attempt)" else ""
        panel?.setStatus("실행 중: ${index + 1}. ${action.type.label}$att")
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
        runCatching { engine.stop() }
        removePicker()
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
