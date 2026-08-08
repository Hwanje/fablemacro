package com.fablemacro.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * 설정된 좌표/영역이 화면 어디인지 잠시 보여주는 마커.
 * 터치를 가로채지 않으며(FLAG_NOT_TOUCHABLE), 일정 시간 뒤 서비스가 제거한다.
 */
@SuppressLint("ViewConstructor")
class MarkerView(
    context: Context,
    /** 강조할 지점들 (화면 좌표) — 탭 1개, 스와이프 2개, 경로 N개 */
    private val points: List<IntArray>,
    /** 강조할 영역 (화면 좌표), 없으면 null */
    private val region: Rect?,
    private val label: String,
) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val accent = Color.rgb(139, 195, 74)
    private val loc = IntArray(2)
    private val startedAt = System.currentTimeMillis()

    private val dim = Paint().apply { color = Color.argb(70, 0, 0, 0) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = 4f * density
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 2f * density
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val linkLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; strokeWidth = 5f * density; style = Paint.Style.STROKE
    }
    private val fill = Paint().apply { color = Color.argb(55, 139, 195, 74) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
    }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)

        // 0 → 1 로 반복되는 펄스 진행도
        val elapsed = (System.currentTimeMillis() - startedAt) % 900L
        val t = elapsed / 900f

        region?.let { r ->
            val rf = RectF(
                (r.left - loc[0]).toFloat(), (r.top - loc[1]).toFloat(),
                (r.right - loc[0]).toFloat(), (r.bottom - loc[1]).toFloat()
            )
            canvas.drawRect(rf, fill)
            canvas.drawRect(rf, ring)
        }

        for ((i, p) in points.withIndex()) {
            val x = (p[0] - loc[0]).toFloat()
            val y = (p[1] - loc[1]).toFloat()

            if (i > 0) {
                val q = points[i - 1]
                canvas.drawLine(
                    (q[0] - loc[0]).toFloat(), (q[1] - loc[1]).toFloat(), x, y, linkLine
                )
            }

            // 퍼져나가는 펄스 링
            val pulseRadius = (14f + 26f * t) * density
            ring.alpha = (255 * (1f - t)).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, pulseRadius, ring)
            ring.alpha = 255

            canvas.drawCircle(x, y, 13f * density, dot)
            canvas.drawLine(x - 26 * density, y, x + 26 * density, y, cross)
            canvas.drawLine(x, y - 26 * density, x, y + 26 * density, cross)
            if (points.size > 1) {
                canvas.drawText("${i + 1}", x, y + 4 * density, numPaint)
            }
        }

        // 안내 라벨 — 첫 지점(또는 영역) 근처, 화면 밖으로 나가지 않게 보정
        val anchorX: Float
        val anchorY: Float
        when {
            points.isNotEmpty() -> {
                anchorX = (points[0][0] - loc[0]).toFloat()
                anchorY = (points[0][1] - loc[1]).toFloat()
            }
            region != null -> {
                anchorX = (region.centerX() - loc[0]).toFloat()
                anchorY = (region.top - loc[1]).toFloat()
            }
            else -> {
                anchorX = width / 2f
                anchorY = height / 2f
            }
        }
        val ty = if (anchorY > 70 * density) anchorY - 44 * density else anchorY + 62 * density
        canvas.drawText(label, anchorX.coerceIn(90 * density, width - 90 * density), ty, textPaint)

        postInvalidateOnAnimation()
    }
}
