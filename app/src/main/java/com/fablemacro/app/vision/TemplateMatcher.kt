package com.fablemacro.app.vision

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 순수 Kotlin 템플릿 매칭 (OpenCV 없이).
 * 화면과 템플릿을 같은 비율로 축소한 그레이스케일에서
 * 2단계(코스 → 정밀) SAD 탐색으로 가장 유사한 위치를 찾는다.
 */
object TemplateMatcher {

    data class Match(val cx: Int, val cy: Int, val score: Double)

    fun find(screen: Bitmap, template: Bitmap, region: IntArray?, threshold: Double): Match? {
        var offX = 0
        var offY = 0
        var src = screen
        if (region != null) {
            val r = Rect(
                max(0, min(region[0], region[2])),
                max(0, min(region[1], region[3])),
                min(screen.width, max(region[0], region[2])),
                min(screen.height, max(region[1], region[3]))
            )
            if (r.width() >= template.width && r.height() >= template.height) {
                src = Bitmap.createBitmap(screen, r.left, r.top, r.width(), r.height())
                offX = r.left
                offY = r.top
            }
        }
        if (template.width > src.width || template.height > src.height) return null

        val scale = min(1.0, 480.0 / src.width)
        val sw = max(1, (src.width * scale).toInt())
        val sh = max(1, (src.height * scale).toInt())
        val tw = max(1, (template.width * scale).toInt())
        val th = max(1, (template.height * scale).toInt())
        if (tw < 2 || th < 2 || tw > sw || th > sh) return null

        val s = gray(if (sw == src.width) src else Bitmap.createScaledBitmap(src, sw, sh, true), sw, sh)
        val t = gray(
            if (tw == template.width) template else Bitmap.createScaledBitmap(template, tw, th, true),
            tw, th
        )

        // 1단계: 위치 2px 간격, 템플릿 픽셀 2px 샘플링
        var bestX = 0
        var bestY = 0
        var bestScore = -1.0
        val sampleCount = ((tw + 1) / 2) * ((th + 1) / 2)
        var y = 0
        while (y <= sh - th) {
            var x = 0
            while (x <= sw - tw) {
                var sad = 0L
                var ty = 0
                while (ty < th) {
                    val srow = (y + ty) * sw + x
                    val trow = ty * tw
                    var tx = 0
                    while (tx < tw) {
                        sad += abs(s[srow + tx] - t[trow + tx])
                        tx += 2
                    }
                    ty += 2
                }
                val score = 1.0 - sad.toDouble() / (255.0 * sampleCount)
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
                x += 2
            }
            y += 2
        }

        // 2단계: 최고 지점 주변 ±2px 전체 픽셀 정밀 탐색
        var rBestScore = -1.0
        var rx = bestX
        var ry = bestY
        val fullCount = tw * th
        for (yy in max(0, bestY - 2)..min(sh - th, bestY + 2)) {
            for (xx in max(0, bestX - 2)..min(sw - tw, bestX + 2)) {
                var sad = 0L
                for (ty in 0 until th) {
                    val srow = (yy + ty) * sw + xx
                    val trow = ty * tw
                    for (tx in 0 until tw) {
                        sad += abs(s[srow + tx] - t[trow + tx])
                    }
                }
                val score = 1.0 - sad.toDouble() / (255.0 * fullCount)
                if (score > rBestScore) {
                    rBestScore = score
                    rx = xx
                    ry = yy
                }
            }
        }

        if (rBestScore < threshold) return null
        val cx = ((rx + tw / 2.0) / scale).toInt() + offX
        val cy = ((ry + th / 2.0) / scale).toInt() + offY
        return Match(cx, cy, rBestScore)
    }

    private fun gray(b: Bitmap, w: Int, h: Int): IntArray {
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            px[i] = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        return px
    }
}
