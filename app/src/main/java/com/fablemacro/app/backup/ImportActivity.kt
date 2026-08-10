package com.fablemacro.app.backup

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.fablemacro.app.model.ScriptStore

/**
 * 백업 ZIP을 고르기 위한 투명 액티비티.
 * 서비스에서는 파일 선택 결과를 받을 수 없어 이 화면을 잠깐 띄운다.
 * 가져온 스크립트는 항상 새 이름으로 저장되므로 기존 저장본을 덮어쓰지 않는다.
 */
class ImportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
            }
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQ_PICK)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            finish()
            return
        }
        val name = runCatching {
            contentResolver.openInputStream(uri)?.use { ScriptStore(this).importFrom(it) }
        }.getOrNull()

        Toast.makeText(
            this,
            if (name != null) "가져왔습니다: $name" else "백업 파일을 읽지 못했습니다",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    companion object {
        private const val REQ_PICK = 200
    }
}
