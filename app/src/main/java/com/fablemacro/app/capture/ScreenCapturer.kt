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
 *
 * 화면을 돌리면 실제 화면 크기가 바뀌므로, 캡처할 때마다 크기를 확인해
 * 어긋났으면 캡처 대상을 다시 맞춘다. 이렇게 하지 않으면 가로/세로 전환 후
 * 세로 크기 그대로 캡처돼 이미지 검색과 좌표가 전부 어긋난다.
 */
class ScreenCapturer(private val context: Context) {

    var width = 0; private set
    var height = 0; private set
    private var densityDpi = 0

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val thread = HandlerThread("fm-capture").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var lastBitmap: Bitmap? = null
    @Volatile private var pending: CompletableDeferred<Bitmap>? = null

    fun start(projection: MediaProjection) {
        this.projection = projection
        configure()
    }

    /** 현재 실제 화면 크기 (회전 상태 반영) */
    private fun currentSize(): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    /** 지금 화면 크기에 맞춰 ImageReader와 VirtualDisplay를 준비한다 */
    private fun configure() {
        val (w, h) = currentSize()
        width = w
        height = h
        densityDpi = context.resources.configuration.densityDpi

        val previous = reader
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

        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = projection?.createVirtualDisplay(
                "fablemacro-capture", width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
        } else {
            // 새 표면으로 먼저 갈아끼운 뒤에 이전 것을 닫는다
            vd.resize(width, height, densityDpi)
            vd.surface = r.surface
        }
        previous?.close()
    }

    /**
     * 화면 크기가 바뀌었으면 캡처 대상을 다시 맞춘다.
     * 바뀐 경우 true — 이때는 첫 프레임이 올 때까지 조금 더 기다려야 한다.
     */
    private fun ensureSize(): Boolean {
        if (projection == null) return false
        val (w, h) = currentSize()
        if (w == width && h == height) return false
        lastBitmap = null   // 이전 방향의 프레임을 돌려주면 안 된다
        configure()
        return true
    }

    /**
     * 다음 프레임을 캡처. 화면이 정적이면 새 프레임이 안 올 수 있으므로
     * 타임아웃 시 마지막 프레임으로 폴백한다.
     * onNudge: 화면 갱신을 유발하기 위한 콜백 (오버레이 invalidate 등)
     */
    suspend fun capture(timeoutMs: Long = 1200, onNudge: (() -> Unit)? = null): Bitmap? {
        val resized = runCatching { ensureSize() }.getOrDefault(false)
        val d = CompletableDeferred<Bitmap>()
        pending = d
        onNudge?.invoke()
        // 방향이 막 바뀌었으면 첫 프레임이 늦게 온다
        val wait = if (resized) timeoutMs + 1000 else timeoutMs
        val result = withTimeoutOrNull(wait) { d.await() }
        pending = null
        return result ?: lastBitmap
    }

    /** 프레임 자체 크기로 변환한다 — 회전 직후 크기가 어긋나도 깨지지 않게 */
    private fun toBitmap(image: Image): Bitmap {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * w
        val bmp = Bitmap.createBitmap(
            w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, w, h)
    }

    fun stop() {
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        projection = null
        thread.quitSafely()
    }
}
