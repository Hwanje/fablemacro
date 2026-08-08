package com.fablemacro.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 전체 화면 픽커 오버레이의 공통 WindowManager 파라미터.
 *
 * MATCH_PARENT로 두면 edge-to-edge 환경에서 창이 상태바 아래로 인셋되어
 * 캔버스 좌표와 화면 좌표가 어긋난다. 실제 디스플레이 크기를 직접 지정하고
 * 컷아웃 영역까지 덮어 화면 전체를 그대로 사용한다.
 */
fun fullscreenPickerParams(context: Context): WindowManager.LayoutParams {
    val wm = context.getSystemService(WindowManager::class.java)
    val w: Int
    val h: Int
    if (Build.VERSION.SDK_INT >= 30) {
        val b = wm.maximumWindowMetrics.bounds
        w = b.width()
        h = b.height()
    } else {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        w = dm.widthPixels
        h = dm.heightPixels
    }
    return WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }
}

private fun makeHintPaint(context: Context) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    textSize = 16f * context.resources.displayMetrics.density
    textAlign = Paint.Align.CENTER
    setShadowLayer(6f, 0f, 2f, Color.BLACK)
}

private fun makeLabelPaint(context: Context) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    textSize = 12f * context.resources.displayMetrics.density
    textAlign = Paint.Align.CENTER
    setShadowLayer(5f, 0f, 1f, Color.BLACK)
}

/**
 * 픽커 공통 베이스.
 *
 * 그리기는 뷰 로컬 좌표(event.x/y)로, 저장/실행에 쓰는 값은 화면 좌표(rawX/rawY)로
 * 분리한다. 창이 인셋되더라도 손가락 위치와 그려지는 도형이 어긋나지 않는다.
 */
@SuppressLint("ViewConstructor")
abstract class BasePickerView(context: Context, private val hint: String) : View(context) {
    protected val dim = Paint().apply { color = Color.argb(60, 0, 0, 0) }
    protected val textPaint = makeHintPaint(context)
    protected val labelPaint = makeLabelPaint(context)
    protected val density: Float = context.resources.displayMetrics.density

    protected fun drawBackdrop(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.drawText(hint, width / 2f, height * 0.12f, textPaint)
    }

    protected fun drawCoordLabel(canvas: Canvas, x: Float, y: Float, label: String) {
        val ty = if (y > 40 * density) y - 18 * density else y + 34 * density
        val tx = x.coerceIn(60 * density, width - 60 * density)
        canvas.drawText(label, tx, ty, labelPaint)
    }
}

/** 단일 지점 탭 픽커 */
@SuppressLint("ViewConstructor")
class PointPickerView(
    context: Context,
    hint: String,
    private val onDone: (Int, Int) -> Unit,
) : BasePickerView(context, hint) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(139, 195, 74)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private var lx = -1f
    private var ly = -1f
    private var rawX = 0
    private var rawY = 0

    override fun onDraw(canvas: Canvas) {
        drawBackdrop(canvas)
        if (lx >= 0) {
            canvas.drawCircle(lx, ly, 22 * density, ringPaint)
            canvas.drawLine(lx - 30 * density, ly, lx + 30 * density, ly, ringPaint)
            canvas.drawLine(lx, ly - 30 * density, lx, ly + 30 * density, ringPaint)
            drawCoordLabel(canvas, lx, ly, "($rawX, $rawY)")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                lx = event.x
                ly = event.y
                rawX = event.rawX.toInt()
                rawY = event.rawY.toInt()
                invalidate()
            }
            MotionEvent.ACTION_UP -> onDone(event.rawX.toInt(), event.rawY.toInt())
        }
        return true
    }
}

/** 드래그로 스와이프 시작→끝 지정. 드래그 시간도 함께 반환 */
@SuppressLint("ViewConstructor")
class SwipePickerView(
    context: Context,
    private val onDone: (x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) -> Unit,
) : BasePickerView(context, "드래그하여 스와이프 경로를 지정하세요") {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(139, 195, 74)
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(139, 195, 74) }
    private var sx = -1f; private var sy = -1f
    private var cx = -1f; private var cy = -1f
    private var downTime = 0L

    override fun onDraw(canvas: Canvas) {
        drawBackdrop(canvas)
        if (sx >= 0 && cx >= 0) {
            canvas.drawLine(sx, sy, cx, cy, linePaint)
            canvas.drawCircle(sx, sy, 10 * density, dotPaint)
            canvas.drawCircle(cx, cy, 14 * density, dotPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                sx = event.x; sy = event.y
                cx = sx; cy = sy
                downTime = System.currentTimeMillis()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                cx = event.x; cy = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val dur = (System.currentTimeMillis() - downTime).coerceIn(100, 5000)
                val startRawX = (sx + (event.rawX - event.x)).toInt()
                val startRawY = (sy + (event.rawY - event.y)).toInt()
                onDone(startRawX, startRawY, event.rawX.toInt(), event.rawY.toInt(), dur)
            }
        }
        return true
    }
}

/** 여러 지점을 탭으로 찍고 상단 완료 버튼으로 종료하는 경로 픽커 */
@SuppressLint("ViewConstructor")
class PathPickerView(
    context: Context,
    private val onDone: (List<IntArray>) -> Unit,
) : BasePickerView(context, "지점을 순서대로 탭하세요") {

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(139, 195, 74) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(139, 195, 74); strokeWidth = 6f; style = Paint.Style.STROKE
    }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(33, 33, 33) }

    /** 화면 좌표(결과용) */
    private val screenPoints = mutableListOf<IntArray>()
    /** 로컬 좌표(그리기용) */
    private val localPoints = mutableListOf<FloatArray>()
    private val doneRect = RectF()

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.drawText(
            "지점을 순서대로 탭하세요 (${screenPoints.size}개)",
            width / 2f, height * 0.12f, textPaint
        )
        doneRect.set(
            width / 2f - 70 * density, height * 0.14f,
            width / 2f + 70 * density, height * 0.14f + 48 * density
        )
        canvas.drawRoundRect(doneRect, 12 * density, 12 * density, btnPaint)
        canvas.drawText("완료", doneRect.centerX(), doneRect.centerY() + 6 * density, textPaint)

        for (i in localPoints.indices) {
            val p = localPoints[i]
            canvas.drawCircle(p[0], p[1], 12 * density, pointPaint)
            canvas.drawText("${i + 1}", p[0], p[1] + 5 * density, labelPaint)
            if (i > 0) {
                val q = localPoints[i - 1]
                canvas.drawLine(q[0], q[1], p[0], p[1], linePaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            if (doneRect.contains(event.x, event.y)) {
                onDone(screenPoints.toList())
            } else {
                screenPoints.add(intArrayOf(event.rawX.toInt(), event.rawY.toInt()))
                localPoints.add(floatArrayOf(event.x, event.y))
                invalidate()
            }
        }
        return true
    }
}

/** 드래그로 사각형 영역 선택 */
@SuppressLint("ViewConstructor")
class RegionPickerView(
    context: Context,
    hint: String,
    private val onDone: (Rect?) -> Unit,
) : BasePickerView(context, hint) {

    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(139, 195, 74); strokeWidth = 5f; style = Paint.Style.STROKE
    }
    private val fillPaint = Paint().apply { color = Color.argb(50, 139, 195, 74) }

    // 로컬 좌표 (그리기용)
    private var sx = -1f; private var sy = -1f
    private var cx = -1f; private var cy = -1f
    // 화면 좌표 (결과용)
    private var sRawX = 0; private var sRawY = 0
    private var cRawX = 0; private var cRawY = 0

    override fun onDraw(canvas: Canvas) {
        drawBackdrop(canvas)
        if (sx >= 0) {
            val r = RectF(minOf(sx, cx), minOf(sy, cy), maxOf(sx, cx), maxOf(sy, cy))
            canvas.drawRect(r, fillPaint)
            canvas.drawRect(r, rectPaint)
            val w = kotlin.math.abs(cRawX - sRawX)
            val h = kotlin.math.abs(cRawY - sRawY)
            drawCoordLabel(canvas, r.centerX(), r.top, "${w} x ${h}")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                sx = event.x; sy = event.y
                cx = sx; cy = sy
                sRawX = event.rawX.toInt(); sRawY = event.rawY.toInt()
                cRawX = sRawX; cRawY = sRawY
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                cx = event.x; cy = event.y
                cRawX = event.rawX.toInt(); cRawY = event.rawY.toInt()
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                cRawX = event.rawX.toInt(); cRawY = event.rawY.toInt()
                val r = Rect(
                    minOf(sRawX, cRawX), minOf(sRawY, cRawY),
                    maxOf(sRawX, cRawX), maxOf(sRawY, cRawY)
                )
                onDone(if (r.width() > 12 && r.height() > 12) r else null)
            }
        }
        return true
    }
}
