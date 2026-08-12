package com.fablemacro.app.online

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import com.fablemacro.app.model.ScriptStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * `fablemacro://import?…` 링크를 눌렀을 때 매크로를 받아 저장하는 화면.
 * 받아온 내용을 먼저 보여주고 확인을 받은 뒤에 저장한다.
 */
class ImportLinkActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.dataString
        if (link.isNullOrBlank()) {
            finish()
            return
        }

        val loading = AlertDialog.Builder(this)
            .setTitle("매크로 가져오는 중…")
            .setMessage(link.take(120))
            .setCancelable(false)
            .create()
        loading.show()

        scope.launch {
            val json = MacroLink.resolve(link)
            loading.dismiss()
            if (json == null) {
                toastAndFinish("링크에서 매크로를 읽지 못했습니다")
                return@launch
            }
            val store = ScriptStore(this@ImportLinkActivity)
            // 미리보기는 저장 전에 보여준다
            val preview = runCatching {
                com.google.gson.Gson().fromJson(json, com.fablemacro.app.model.MacroScript::class.java)
            }.getOrNull()
            if (preview == null || preview.actions.isNullOrEmpty()) {
                toastAndFinish("매크로 내용이 올바르지 않습니다")
                return@launch
            }

            val summary = buildString {
                preview.description?.takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }
                append("스텝 ${preview.actions.size}개\n")
                preview.actions.take(12).forEachIndexed { i, a ->
                    append("\n${i + 1}. ${a.type.emoji} ${a.displayName()}   ${a.summary()}")
                }
                if (preview.actions.size > 12) append("\n… 외 ${preview.actions.size - 12}개")
            }

            AlertDialog.Builder(this@ImportLinkActivity)
                .setTitle(preview.name)
                .setMessage(summary)
                .setPositiveButton("저장") { _, _ ->
                    val saved = store.fromShareJson(json)
                    toastAndFinish(
                        if (saved != null) "저장됨: ${saved.name}\n오버레이 📚 에서 불러오세요"
                        else "저장하지 못했습니다"
                    )
                }
                .setNegativeButton("취소") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    private fun toastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
