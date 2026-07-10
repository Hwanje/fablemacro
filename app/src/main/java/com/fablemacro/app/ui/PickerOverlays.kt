package com.fablemacro.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/** 전체 화면 픽커 오버레이의 공통 WindowManager 파라미터 */
fun fullscreenPickerParams(): WindowManager.LayoutParams =
    WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        android.graphics.PixelFormat.TRANSLUCENT
    )

private fun hintPaint(context: Context) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    textSize = 16f * context.resources.displayMetrics.density
    textAlign = Paint.Align.CENTER
    setShadowLayer(6f, 0f, 2f, Color.BLACK)
}

/** 단일 지점 탭 픽커 */
@SuppressLint("ViewConstructor")
class PointPickerView(
    context: Context,
    private val hint: String,
    private val onDone: (Int, Int) -> Unit,
) : View(context) {
    private val dim = Paint().apply { color = Color.argb(60, 0, 0, 0) }
    private val textPaint = hintPaint(context)

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.drawText(hint, width / 2f, height * 0.12f, textPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            onDone(event.rawX.toInt(), event.rawY.toInt())
        }
        return true
    }
}

/** 드래그로 스와이프 시작→끝 지정. 드래그 시간도 함께 반환 */
@SuppressLint("ViewConstructor")
class SwipePickerView(
    context: Context,
    private val onDone: (x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) -> Unit,
) : View(context) {
    private val dim = Paint().apply { color = Color.argb(60, 0, 0, 0) }
    private val textPaint = hintPaint(context)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(139, 195, 74)
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }
    private var sx = -1f; private var sy = -1f
    private var cx = -1f; private var cy = -1f
    private var downTime = 0L

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.drawText("드래그하여 스와이프 경로를 지정하세요", width / 2f, height * 0.12f, textPaint)
        if (sx >= 0 && cx >= 0) canvas.drawLine(sx, sy, cx, cy, linePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                sx = event.rawX; sy = event.rawY
                cx = sx; cy = sy
                downTime = System.currentTimeMillis()
            }
            MotionEvent.ACTION_MOVE -> {
                cx = event.rawX; cy = event.rawY
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val dur = (System.currentTimeMillis() - downTime).coerceIn(100, 5000)
                onDone(sx.toInt(), sy.toInt(), event.rawX.toInt(), event.rawY.toInt(), dur)
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
) : View(context) {
    private val dim = Paint().apply { color = Color.argb(60, 0, 0, 0) }
    private val textPaint = hintPaint(context)
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(139, 195, 74) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(139, 195, 74); strokeWidth = 6f; style = Paint.Style.STROKE
    }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(33, 33, 33) }
    private val points = mutableListOf<IntArray>()
    private val doneRect = RectF()

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.drawText("지점을 순서대로 탭하세요 (${points.size}개)", width / 2f, height * 0.12f, textPaint)
        val d = resources.displayMetrics.density
        doneRect.set(width / 2f - 70 * d, height * 0.14f, width / 2f + 70 * d, height * 0.14f + 48 * d)
        canvas.drawRoundRect(doneRect, 12 * d, 12 * d, btnPaint)
        canvas.drawText("완료", doneRect.centerX(), doneRect.centerY() + 6 * d, textPaint)
        for (i in points.indices) {
            val p = points[i]
            canvas.drawCircle(p[0].toFloat(), p[1].toFloat(), 12f, pointPaint)
            if (i > 0) {
                val q = points[i - 1]
                canvas.drawLine(q[0].toFloat(), q[1].toFloat(), p[0].toFloat(), p[1].toFloat(), linePaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            if (doneRect.contains(event.rawX, event.rawY)) {
                onDone(points.toList())
            } else {
                points.add(intArrayOf(event.rawX.toInt(), event.rawY.toInt()))
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
    private val hint: String,
    private val onDone: (Rect?) -> Unit,
) : View(context) {
    private val dim = Paint().apply { color = Color.argb(60, 0, 0, 0) }
    private val textPaint = hintPaint(context)
    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(139, 195, 74); strokeWidth = 5f; style = Paint.Style.STROKE
    }
    private val fillPaint = Paint().apply { color = Color.argb(50, 139, 195, 74) }
    private var sx = -1f; private var sy = -1f
    private var cx = -1f; private var cy = -1f

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.drawText(hint, width / 2f, height * 0.12f, textPaint)
        if (sx >= 0) {
            val r = RectF(minOf(sx, cx), minOf(sy, cy), maxOf(sx, cx), maxOf(sy, cy))
            canvas.drawRect(r, fillPaint)
            canvas.drawRect(r, rectPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                sx = event.rawX; sy = event.rawY; cx = sx; cy = sy
            }
            MotionEvent.ACTION_MOVE -> {
                cx = event.rawX; cy = event.rawY
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val r = Rect(
                    minOf(sx, event.rawX).toInt(), minOf(sy, event.rawY).toInt(),
                    maxOf(sx, event.rawX).toInt(), maxOf(sy, event.rawY).toInt()
                )
                onDone(if (r.width() > 12 && r.height() > 12) r else null)
            }
        }
        return true
    }
}
