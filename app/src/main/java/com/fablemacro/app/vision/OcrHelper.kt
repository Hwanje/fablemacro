package com.fablemacro.app.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** ML Kit 온디바이스 OCR (한국어 + 라틴) */
object OcrHelper {

    private val recognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(bitmap: Bitmap): Text? = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }

    /** 화면에서 텍스트를 찾아 해당 라인의 중심 좌표(Rect)를 반환 */
    suspend fun findText(bitmap: Bitmap, query: String): Rect? {
        val result = recognize(bitmap) ?: return null
        val q = normalize(query)
        if (q.isEmpty()) return null
        for (block in result.textBlocks) {
            for (line in block.lines) {
                if (normalize(line.text).contains(q)) return line.boundingBox
            }
            if (normalize(block.text).contains(q)) return block.boundingBox
        }
        return null
    }

    suspend fun readAll(bitmap: Bitmap): String = recognize(bitmap)?.text ?: ""

    private fun normalize(s: String) = s.lowercase().replace(Regex("\\s+"), "")
}
