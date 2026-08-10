package com.fablemacro.app.backup

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.fablemacro.app.model.ScriptStore
import java.io.File

/**
 * 저장된 매크로를 ZIP 백업으로 뽑아내고 되돌리는 기능.
 *
 * 백업에는 스크립트 JSON과 참조하는 템플릿 이미지가 함께 들어가므로
 * 다른 기기에서도 그대로 복원된다. 내보낼 때는 다운로드 폴더에 사본을 남기고
 * 공유 시트도 함께 띄워 원하는 곳으로 보낼 수 있게 한다.
 */
object BackupManager {

    data class ExportResult(val file: File, val savedTo: String?)

    private fun safeFileName(name: String) =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "script" }

    /**
     * 스크립트를 ZIP으로 내보낸다.
     * 앱 캐시에 만든 뒤 다운로드 폴더로 복사하며, 복사한 위치를 함께 돌려준다.
     */
    fun export(context: Context, store: ScriptStore, scriptName: String): ExportResult? {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val fileName = "FableMacro-${safeFileName(scriptName)}.zip"
        val file = File(dir, fileName)

        val ok = runCatching {
            file.outputStream().use { store.exportTo(scriptName, it) }
        }.getOrDefault(false)
        if (!ok) return null

        return ExportResult(file, copyToDownloads(context, file, fileName))
    }

    /** 다운로드 폴더에 사본을 남기고 사용자에게 보여줄 경로 문자열을 반환 */
    private fun copyToDownloads(context: Context, source: File, fileName: String): String? =
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                resolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                } ?: return@runCatching null
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "다운로드/$fileName"
            } else {
                // 구버전은 권한 없이 쓸 수 있는 앱 전용 외부 저장소로
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: return@runCatching null
                val target = File(dir, fileName)
                source.copyTo(target, overwrite = true)
                target.absolutePath
            }
        }.getOrNull()

    /** 공유 시트로 백업 파일 보내기 */
    fun share(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "매크로 백업 내보내기")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 파일 선택 화면을 띄워 백업을 되돌린다 (ImportActivity가 처리) */
    fun startImport(context: Context) {
        context.startActivity(
            Intent(context, ImportActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
