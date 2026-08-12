package com.fablemacro.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fablemacro.app.online.MacroLink
import com.fablemacro.app.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 설정/시작 화면: 오버레이 권한 → 접근성 서비스 → 화면 캡처 승인 순서로 안내.
 * 실행 시 GitHub 릴리스에서 새 버전을 자동으로 확인한다.
 */
class MainActivity : Activity() {

    private lateinit var overlayStatus: TextView
    private lateinit var accStatus: TextView
    private lateinit var startBtn: Button
    private lateinit var updateStatus: TextView
    private lateinit var updateBtn: Button

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var autoCheckDone = false
    private var pendingApk: File? = null

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 10)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.parseColor("#FF1A1A1A"))
        }

        root.addView(TextView(this).apply {
            text = "FableMacro"
            textSize = 26f
            setTextColor(Color.parseColor("#FF8BC34A"))
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "이미지 검색 · OCR · 조건 분기 매크로\n다른 앱 위 오버레이로 동작합니다"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, dp(16))
        })

        // 1. 오버레이 권한
        overlayStatus = TextView(this)
        root.addView(card("1. 다른 앱 위에 표시 권한", overlayStatus, "권한 설정") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })

        // 2. 접근성 서비스
        accStatus = TextView(this)
        root.addView(card("2. 접근성 서비스 (제스처 실행)", accStatus, "접근성 설정") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        // 3. 시작
        val startCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#FF262626")
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = marginLp()
        }
        startCard.addView(TextView(this).apply {
            text = "3. 매크로 오버레이 시작"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        startCard.addView(TextView(this).apply {
            text = "화면 캡처를 승인하면 플로팅 버블이 나타납니다.\n버블을 눌러 스크립트를 만들고 ▶ 로 실행하세요."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, dp(10))
        })
        startBtn = Button(this).apply {
            setOnClickListener { onStartStop() }
        }
        startCard.addView(startBtn)
        root.addView(startCard)

        // 4. 업데이트
        updateStatus = TextView(this)
        updateBtn = Button(this).apply {
            text = "업데이트 확인"
            setOnClickListener { checkUpdate(manual = true) }
        }
        val updateCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#FF262626")
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = marginLp()
        }
        updateCard.addView(TextView(this).apply {
            text = "자동 업데이트"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        updateStatus.textSize = 12f
        updateStatus.setPadding(0, dp(4), 0, dp(10))
        updateStatus.setTextColor(Color.LTGRAY)
        updateStatus.text = "현재 버전 ${UpdateChecker.currentVersion(this)}"
        updateCard.addView(updateStatus)
        updateCard.addView(updateBtn)
        root.addView(updateCard)

        // 5. 매크로 카탈로그
        val catalogCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#FF262626")
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = marginLp()
        }
        catalogCard.addView(TextView(this).apply {
            text = "매크로 카탈로그"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        catalogCard.addView(TextView(this).apply {
            text = "웹에서 매크로를 고르고 «앱으로 가져오기»를 누르면 바로 등록됩니다.\n" +
                    "받은 링크는 오버레이 📚 → 링크로 가져오기 에서도 열 수 있습니다."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, dp(10))
        })
        catalogCard.addView(Button(this).apply {
            text = "카탈로그 열기"
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MacroLink.SITE)))
                }.onFailure {
                    Toast.makeText(this@MainActivity, "브라우저를 열지 못했습니다", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(catalogCard)

        root.addView(TextView(this).apply {
            text = "사용 순서: 버블 탭 → Action List에서 액션 추가 → ⚙ 로 이름/순서/재시도(∞)/분기 설정 → ◎ 로 좌표 위치 확인 → ▶ 실행 (실행 중 버블 탭 = 중지)\n" +
                    "📚 저장본 확인·백업 내보내기/가져오기 · ─ 패널 접기 · ✕ 오버레이 종료"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, dp(16), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FF1A1A1A"))
            addView(root)
        })
    }

    private fun rounded(color: String) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(12).toFloat()
    }

    private fun marginLp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(12) }

    private fun card(title: String, status: TextView, buttonText: String, onClick: () -> Unit): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#FF262626")
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = marginLp()
        }
        c.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        status.textSize = 12f
        status.setPadding(0, dp(4), 0, dp(10))
        c.addView(status)
        c.addView(Button(this).apply {
            text = buttonText
            setOnClickListener { onClick() }
        })
        return c
    }

    private fun onStartStop() {
        if (OverlayService.isRunning) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            startBtn.postDelayed({ refresh() }, 300)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "먼저 오버레이 권한을 허용해주세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "먼저 접근성 서비스를 켜주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java)
                .putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(OverlayService.EXTRA_RESULT_DATA, data)
            startForegroundService(i)
            Toast.makeText(this, "플로팅 버블이 나타납니다. 홈으로 이동해 사용하세요.", Toast.LENGTH_LONG).show()
            startBtn.postDelayed({ refresh() }, 500)
        }
    }

    // ───────────────────────── 자동 업데이트 ─────────────────────────

    private fun checkUpdate(manual: Boolean) {
        updateBtn.isEnabled = false
        updateStatus.text = "업데이트 확인 중…"
        scope.launch {
            val local = UpdateChecker.currentVersion(this@MainActivity)
            val release = UpdateChecker.fetchLatest()
            updateBtn.isEnabled = true
            when {
                release == null -> {
                    updateStatus.text = "현재 버전 $local · 확인 실패 (네트워크 또는 릴리스 없음)"
                    if (manual) {
                        Toast.makeText(
                            this@MainActivity,
                            "최신 릴리스를 가져오지 못했습니다",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                UpdateChecker.isNewer(release.versionName, local) -> {
                    updateStatus.text = "새 버전 ${release.versionName} 사용 가능 (현재 $local)"
                    promptUpdate(release, local)
                }

                else -> {
                    updateStatus.text = "현재 버전 $local · 최신입니다"
                    if (manual) {
                        Toast.makeText(this@MainActivity, "최신 버전입니다", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun promptUpdate(release: UpdateChecker.Release, local: String) {
        val message = buildString {
            append("현재 $local → 새 버전 ${release.versionName}\n")
            if (release.apkSize > 0) {
                append("크기 ${"%.1f".format(release.apkSize / 1024.0 / 1024.0)}MB\n")
            }
            if (release.notes.isNotBlank()) {
                append("\n").append(release.notes.take(400))
            }
        }
        AlertDialog.Builder(this)
            .setTitle("업데이트가 있습니다")
            .setMessage(message)
            .setPositiveButton("지금 업데이트") { _, _ -> downloadAndInstall(release) }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun downloadAndInstall(release: UpdateChecker.Release) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        val label = TextView(this).apply {
            text = "다운로드 중…"
            setPadding(dp(24), dp(16), dp(24), dp(4))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(24), 0, dp(24), dp(16)) })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("업데이트 ${release.versionName}")
            .setView(content)
            .setCancelable(false)
            .create()
        dialog.show()

        scope.launch {
            val apk = UpdateChecker.download(this@MainActivity, release) { received, total ->
                scope.launch(Dispatchers.Main) {
                    if (total > 0) {
                        bar.progress = ((received * 100) / total).toInt()
                        label.text = "다운로드 중… ${bar.progress}%"
                    } else {
                        label.text = "다운로드 중… ${received / 1024 / 1024}MB"
                    }
                }
            }
            withContext(Dispatchers.Main) {
                dialog.dismiss()
                if (apk == null) {
                    updateStatus.text = "다운로드 실패 — 릴리스 페이지에서 직접 받을 수 있습니다"
                    Toast.makeText(this@MainActivity, "다운로드에 실패했습니다", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                pendingApk = apk
                startInstall(apk)
            }
        }
    }

    private fun startInstall(apk: File) {
        if (!UpdateChecker.canInstall(this)) {
            AlertDialog.Builder(this)
                .setTitle("설치 권한 필요")
                .setMessage("업데이트를 설치하려면 이 앱의 '알 수 없는 앱 설치' 권한을 허용해주세요.")
                .setPositiveButton("설정 열기") { _, _ ->
                    @Suppress("DEPRECATION")
                    startActivityForResult(UpdateChecker.installPermissionIntent(this), REQ_INSTALL_PERM)
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        runCatching { UpdateChecker.installApk(this, apk) }
            .onFailure {
                Toast.makeText(this, "설치 화면을 열지 못했습니다: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (MacroAccessibilityService.isEnabled) return true
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.startsWith("$packageName/") }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // 설치 권한을 방금 허용하고 돌아온 경우 이어서 설치
        pendingApk?.let { apk ->
            if (UpdateChecker.canInstall(this)) {
                pendingApk = null
                startInstall(apk)
            }
        }
        if (!autoCheckDone) {
            autoCheckDone = true
            checkUpdate(manual = false)
        }
    }

    private fun refresh() {
        val ok = "✅ 허용됨"
        val no = "❌ 필요함"
        val canOverlay = Settings.canDrawOverlays(this)
        overlayStatus.text = if (canOverlay) ok else no
        overlayStatus.setTextColor(
            if (canOverlay) Color.parseColor("#FF8BC34A") else Color.parseColor("#FFFF8A80")
        )
        val acc = isAccessibilityEnabled()
        accStatus.text = if (acc) ok else no
        accStatus.setTextColor(
            if (acc) Color.parseColor("#FF8BC34A") else Color.parseColor("#FFFF8A80")
        )
        startBtn.text = if (OverlayService.isRunning) "오버레이 종료" else "화면 캡처 승인 후 시작"
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val REQ_PROJECTION = 100
        private const val REQ_INSTALL_PERM = 101
    }
}
