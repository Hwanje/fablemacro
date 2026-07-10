package com.fablemacro.app

import android.app.Activity
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 설정/시작 화면: 오버레이 권한 → 접근성 서비스 → 화면 캡처 승인 순서로 안내.
 */
class MainActivity : Activity() {

    private lateinit var overlayStatus: TextView
    private lateinit var accStatus: TextView
    private lateinit var startBtn: Button

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
        root.addView(startCard, marginLp())

        root.addView(TextView(this).apply {
            text = "사용 순서: 버블 탭 → Action List에서 액션 추가 → ⚙ 로 재시도(∞)/분기 설정 → ▶ 실행 (실행 중 버블 탭 = 중지)"
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
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        c.layoutParams = lp
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
    }

    private fun refresh() {
        val ok = "✅ 허용됨"
        val no = "❌ 필요함"
        overlayStatus.text = if (Settings.canDrawOverlays(this)) ok else no
        overlayStatus.setTextColor(if (Settings.canDrawOverlays(this)) Color.parseColor("#FF8BC34A") else Color.parseColor("#FFFF8A80"))
        val acc = isAccessibilityEnabled()
        accStatus.text = if (acc) ok else no
        accStatus.setTextColor(if (acc) Color.parseColor("#FF8BC34A") else Color.parseColor("#FFFF8A80"))
        startBtn.text = if (OverlayService.isRunning) "오버레이 종료" else "화면 캡처 승인 후 시작"
    }

    companion object {
        private const val REQ_PROJECTION = 100
    }
}
