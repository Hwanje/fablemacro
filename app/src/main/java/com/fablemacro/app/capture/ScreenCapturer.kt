package com.fablemacro.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MediaProjection 기반 화면 캡처.
 * 프레임을 매번 변환하지 않고, 요청이 있을 때만 최신 이미지를 Bitmap으로 변환한다.
 */
class ScreenCapturer(private val context: Context) {

    var width = 0; private set
    var height = 0; private set
    private var densityDpi = 0

    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val thread = HandlerThread("fm-capture").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var lastBitmap: Bitmap? = null
    @Volatile private var pending: CompletableDeferred<Bitmap>? = null

    fun start(projection: MediaProjection) {
        val wm = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            width = b.width()
            height = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            width = dm.widthPixels
            height = dm.heightPixels
        }
        densityDpi = context.resources.configuration.densityDpi

        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val p = pending
                if (p != null && !p.isCompleted) {
                    val bmp = toBitmap(img)
                    lastBitmap = bmp
                    p.complete(bmp)
                }
            } finally {
                img.close()
            }
        }, handler)
        reader = r

        virtualDisplay = projection.createVirtualDisplay(
            "fablemacro-capture", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
    }

    /**
     * 다음 프레임을 캡처. 화면이 정적이면 새 프레임이 안 올 수 있으므로
     * 타임아웃 시 마지막 프레임으로 폴백한다.
     * onNudge: 화면 갱신을 유발하기 위한 콜백 (오버레이 invalidate 등)
     */
    suspend fun capture(timeoutMs: Long = 1200, onNudge: (() -> Unit)? = null): Bitmap? {
        val d = CompletableDeferred<Bitmap>()
        pending = d
        onNudge?.invoke()
        val result = withTimeoutOrNull(timeoutMs) { d.await() }
        pending = null
        return result ?: lastBitmap
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * width
        val bmp = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, width, height)
    }

    fun stop() {
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        thread.quitSafely()
    }
}
