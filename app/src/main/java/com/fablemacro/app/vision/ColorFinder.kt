package com.fablemacro.app.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 화면에서 특정 RGB 색을 찾는다.
 *
 * 영역이 1픽셀이면 «그 지점이 이 색인가»를 확인하는 셈이고,
 * 넓은 영역이면 그 안에서 색이 있는 곳을 찾아 위치를 돌려준다.
 */
object ColorFinder {

    /** 색 차이 — 세 채널 중 가장 크게 벗어난 값 (0~255) */
    fun distance(a: Int, b: Int): Int = max(
        abs(Color.red(a) - Color.red(b)),
        max(abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))
    )

    fun matches(pixel: Int, target: Int, tolerance: Int): Boolean =
        distance(pixel, target) <= tolerance.coerceIn(0, 255)

    /** 프레임의 한 점 색 읽기 (범위를 벗어나면 null) */
    fun pixelAt(frame: Bitmap, x: Int, y: Int): Int? {
        if (x < 0 || y < 0 || x >= frame.width || y >= frame.height) return null
        return frame.getPixel(x, y)
    }

    /**
     * 영역 안에서 색이 맞는 지점을 찾는다. 찾으면 맞는 픽셀들의 중심을 Rect로 돌려준다.
     *
     * 넓은 영역을 1픽셀씩 보면 느리므로 성긴 간격으로 훑고, 걸린 자리 주변만 촘촘히 확인한다.
     */
    fun find(frame: Bitmap, target: Int, tolerance: Int, region: IntArray?): Rect? {
        val left: Int; val top: Int; val right: Int; val bottom: Int
        if (region != null && region.size >= 4) {
            left = max(0, min(region[0], region[2]))
            top = max(0, min(region[1], region[3]))
            right = min(frame.width, max(region[0], region[2]) + 1)
            bottom = min(frame.height, max(region[1], region[3]) + 1)
        } else {
            left = 0; top = 0; right = frame.width; bottom = frame.height
        }
        if (right <= left || bottom <= top) return null

        val w = right - left
        val h = bottom - top
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, left, top, w, h)

        // 넓을수록 성기게 훑는다 (작은 영역은 전부 확인)
        val step = when {
            w * h <= 4_096 -> 1
            w * h <= 250_000 -> 2
            else -> 3
        }

        var sumX = 0L
        var sumY = 0L
        var count = 0
        var y = 0
        while (y < h) {
            var x = 0
            val row = y * w
            while (x < w) {
                if (matches(pixels[row + x], target, tolerance)) {
                    sumX += x
                    sumY += y
                    count++
                }
                x += step
            }
            y += step
        }
        if (count == 0) return null

        val cx = left + (sumX / count).toInt()
        val cy = top + (sumY / count).toInt()
        return Rect(cx, cy, cx, cy)
    }
}
