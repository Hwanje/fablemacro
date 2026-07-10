package com.fablemacro.app.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fablemacro.app.OverlayService
import com.fablemacro.app.model.ActionType
import com.fablemacro.app.model.Goto
import com.fablemacro.app.model.MacroAction
import com.fablemacro.app.model.MacroScript
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * 플로팅 매크로 편집 패널.
 * 스크립트 리스트(스텝별 설정/삭제/이동/단일 실행) + 액션 팔레트 + 재생/저장/불러오기.
 */
class OverlayPanel(private val service: OverlayService) {

    private val ctx = ContextThemeWrapper(service, android.R.style.Theme_Material_Light_Dialog)
    private val density = service.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    var script = MacroScript()

    val root: LinearLayout
    private val titleView: TextView
    private val playBtn: TextView
    private val listContainer: LinearLayout
    private val statusView: TextView
    private var highlighted = -1

    private val darkBg = Color.parseColor("#F2242424")
    private val rowBg = Color.parseColor("#FF333333")
    private val accent = Color.parseColor("#FF8BC34A")

    init {
        root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(darkBg, 14)
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        // ── 헤더: 스크립트 이름 / 재생 / 저장 / 열기 / 새로 만들기 / 접기 ──
        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = text(script.name, 14f, bold = true).apply {
            setSingleLine()
            setOnClickListener { renameDialog() }
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
        playBtn = iconButton("▶", accent) { onPlay() }
        header.addView(playBtn)
        header.addView(iconButton("💾") { onSave() })
        header.addView(iconButton("📂") { onLoad() })
        header.addView(iconButton("✚") { onNew() })
        header.addView(iconButton("─") { service.setPanelVisible(false) })
        root.addView(header)

        root.addView(divider())

        // ── 스크립트 리스트 ──
        root.addView(text("Script List", 11f, Color.LTGRAY))
        listContainer = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(service).apply { addView(listContainer) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, dp(210)))

        root.addView(divider())

        // ── 액션 팔레트 ──
        root.addView(text("Action List", 11f, Color.LTGRAY))
        val grid = GridLayout(service).apply { columnCount = 4 }
        for (type in ActionType.entries) {
            val b = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(rowBg, 10)
                setPadding(0, dp(6), 0, dp(6))
                addView(text(type.emoji, 16f).apply { gravity = Gravity.CENTER })
                addView(text(type.label, 9f).apply { gravity = Gravity.CENTER })
                setOnClickListener { onPalette(type) }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = dp(68); height = WRAP
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            grid.addView(b, lp)
        }
        root.addView(grid)

        statusView = text("준비됨", 11f, Color.LTGRAY)
        root.addView(statusView)

        refreshList()
    }

    // ───────────────────────── UI 헬퍼 ─────────────────────────

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun text(s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(service).apply {
            this.text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun iconButton(label: String, color: Int = Color.WHITE, onClick: () -> Unit) =
        TextView(service).apply {
            text = label
            textSize = 15f
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(4), dp(7), dp(4))
            setOnClickListener { onClick() }
        }

    private fun divider() = View(service).apply {
        setBackgroundColor(Color.parseColor("#44FFFFFF"))
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
            topMargin = dp(5); bottomMargin = dp(5)
        }
    }

    fun setStatus(s: String) {
        statusView.text = s
    }

    fun setRunningState(running: Boolean) {
        playBtn.text = if (running) "⏹" else "▶"
        playBtn.setTextColor(if (running) Color.parseColor("#FFF44336") else accent)
        if (!running) {
            highlighted = -1
            refreshList()
        }
    }

    fun highlight(index: Int) {
        if (highlighted == index) return
        highlighted = index
        refreshList()
    }

    // ───────────────────────── 스크립트 리스트 ─────────────────────────

    @SuppressLint("SetTextI18n")
    fun refreshList() {
        titleView.text = script.name
        listContainer.removeAllViews()
        if (script.actions.isEmpty()) {
            listContainer.addView(text("아래 Action List에서 액션을 추가하세요", 11f, Color.GRAY).apply {
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }
        for ((i, a) in script.actions.withIndex()) {
            val row = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(if (i == highlighted) Color.parseColor("#FF4A6b2A") else rowBg, 8)
                setPadding(dp(7), dp(5), dp(4), dp(5))
            }
            val info = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text("${i + 1}. ${a.type.emoji} ${a.type.label}  (${a.id})", 11f, bold = true))
            val extra = buildString {
                append(a.summary())
                branchSummary(a)?.let { append("  $it") }
            }
            if (extra.isNotBlank()) info.addView(text(extra, 10f, Color.LTGRAY))
            row.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(iconButton("▷") { runSingle(a) })
            row.addView(iconButton("⚙") { showSettings(i) })
            row.addView(iconButton("↑") { move(i, -1) })
            row.addView(iconButton("↓") { move(i, +1) })
            row.addView(iconButton("🗑") { confirmDelete(i) })
            val lp = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) }
            listContainer.addView(row, lp)
        }
    }

    private fun branchSummary(a: MacroAction): String? {
        fun label(v: Int) = when (v) {
            Goto.NEXT -> null
            Goto.STOP -> "중지"
            else -> "→${v + 1}"
        }
        val s = label(a.onSuccessGoto)?.let { "성공:$it" }
        val f = when (a.type) {
            ActionType.SEARCH_IMAGE, ActionType.SEARCH_TEXT -> label(a.onFailureGoto)?.let { "실패:$it" }
            else -> null
        }
        return listOfNotNull(s, f).joinToString(" ").ifBlank { null }
    }

    private fun move(i: Int, delta: Int) {
        val j = i + delta
        if (j !in script.actions.indices) return
        val tmp = script.actions[i]
        script.actions[i] = script.actions[j]
        script.actions[j] = tmp
        refreshList()
    }

    private fun confirmDelete(i: Int) {
        dialog("스텝 ${i + 1} 삭제", null, onOk = {
            script.actions.removeAt(i)
            refreshList()
        })
    }

    private fun runSingle(a: MacroAction) {
        val single = MacroScript(name = "single", actions = mutableListOf(a.copy(onSuccessGoto = Goto.STOP, onFailureGoto = Goto.STOP)))
        service.startMacro(single)
    }

    // ───────────────────────── 헤더 동작 ─────────────────────────

    private fun onPlay() {
        if (service.engine.isRunning) {
            service.stopMacro()
        } else {
            service.startMacro(script)
        }
    }

    private fun onSave() {
        service.store.save(script)
        setStatus("저장됨: ${script.name}")
    }

    private fun onLoad() {
        val names = service.store.listNames()
        if (names.isEmpty()) {
            setStatus("저장된 스크립트가 없습니다")
            return
        }
        val b = AlertDialog.Builder(ctx)
        b.setTitle("스크립트 불러오기")
        b.setItems(names.toTypedArray()) { _, which ->
            service.store.load(names[which])?.let {
                script = it
                highlighted = -1
                refreshList()
                setStatus("불러옴: ${it.name}")
            }
        }
        b.setNegativeButton("취소", null)
        showOverlayDialog(b.create())
    }

    private fun onNew() {
        dialog("새 스크립트를 만들까요? (저장하지 않은 내용은 사라집니다)", null, onOk = {
            script = MacroScript()
            highlighted = -1
            refreshList()
            setStatus("새 스크립트")
        })
    }

    private fun renameDialog() {
        val input = EditText(ctx).apply { setText(script.name) }
        dialog("스크립트 이름", input, onOk = {
            val n = input.text.toString().trim()
            if (n.isNotEmpty()) {
                script.name = n
                refreshList()
            }
        })
    }

    // ───────────────────────── 액션 추가 ─────────────────────────

    private fun onPalette(type: ActionType) {
        when (type) {
            ActionType.TAP -> pickPoint("탭할 위치를 터치하세요") { x, y ->
                addAction(MacroAction(type = type, x = x, y = y))
            }

            ActionType.SWIPE -> {
                service.setPanelVisible(false)
                service.showPicker(SwipePickerView(service) { x1, y1, x2, y2, dur ->
                    service.removePicker()
                    service.setPanelVisible(true)
                    addAction(MacroAction(type = type, x = x1, y = y1, x2 = x2, y2 = y2, durationMs = dur))
                })
            }

            ActionType.PATH -> {
                service.setPanelVisible(false)
                service.showPicker(PathPickerView(service) { pts ->
                    service.removePicker()
                    service.setPanelVisible(true)
                    if (pts.size >= 2) {
                        addAction(
                            MacroAction(
                                type = type,
                                points = pts.toMutableList(),
                                durationMs = (pts.size - 1) * 250L
                            )
                        )
                    } else setStatus("경로는 2개 이상 지점이 필요합니다")
                })
            }

            ActionType.DELAY -> {
                val input = numberInput("1000")
                dialog("딜레이 시간 (ms)", input, onOk = {
                    addAction(MacroAction(type = type, delayMs = input.text.toString().toLongOrNull() ?: 1000L))
                })
            }

            ActionType.SEARCH_IMAGE -> pickRegion("찾을 이미지 영역을 드래그로 지정하세요") { r ->
                captureTemplate(r)
            }

            ActionType.SEARCH_TEXT -> {
                val input = EditText(ctx).apply { hint = "찾을 텍스트" }
                dialog("텍스트 검색 (OCR 후 클릭)", input, onOk = {
                    val t = input.text.toString().trim()
                    if (t.isNotEmpty()) addAction(MacroAction(type = type, text = t))
                })
            }

            ActionType.OCR_COPY -> pickRegion("OCR로 읽을 영역을 드래그로 지정하세요") { r ->
                addAction(MacroAction(type = ActionType.OCR_COPY, region = intArrayOf(r.left, r.top, r.right, r.bottom)))
            }

            ActionType.PASTE_TEXT -> {
                val input = EditText(ctx).apply { hint = "비워두면 클립보드 내용 사용" }
                dialog("입력할 텍스트", input, onOk = {
                    addAction(MacroAction(type = type, text = input.text.toString()))
                })
            }

            ActionType.OPEN_APP -> pickApp()

            ActionType.BACK, ActionType.HOME, ActionType.RECENTS ->
                addAction(MacroAction(type = type))

            ActionType.RANDOM_TAP -> pickRegion("랜덤 탭 영역을 드래그로 지정하세요") { r ->
                addAction(MacroAction(type = type, region = intArrayOf(r.left, r.top, r.right, r.bottom)))
            }
        }
    }

    private fun addAction(a: MacroAction) {
        script.actions.add(a)
        refreshList()
        setStatus("추가됨: ${a.type.label}")
    }

    private fun pickPoint(hint: String, onDone: (Int, Int) -> Unit) {
        service.setPanelVisible(false)
        service.showPicker(PointPickerView(service, hint) { x, y ->
            service.removePicker()
            service.setPanelVisible(true)
            onDone(x, y)
        })
    }

    private fun pickRegion(hint: String, onDone: (Rect) -> Unit) {
        service.setPanelVisible(false)
        service.showPicker(RegionPickerView(service, hint) { r ->
            service.removePicker()
            service.setPanelVisible(true)
            if (r != null) onDone(r) else setStatus("영역이 너무 작습니다")
        })
    }

    /** 화면에서 템플릿 이미지를 잘라 저장하고 SEARCH_IMAGE 액션 추가 */
    private fun captureTemplate(r: Rect) {
        service.setPanelVisible(false)
        service.uiScope.launch {
            val frame = service.captureClean()
            service.setPanelVisible(true)
            if (frame == null) {
                setStatus("화면 캡처 실패")
                return@launch
            }
            val left = max(0, r.left)
            val top = max(0, r.top)
            val right = min(frame.width, r.right)
            val bottom = min(frame.height, r.bottom)
            if (right - left < 8 || bottom - top < 8) {
                setStatus("영역이 너무 작습니다")
                return@launch
            }
            val cropped = android.graphics.Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
            val fileName = service.store.saveImage(cropped)
            addAction(MacroAction(type = ActionType.SEARCH_IMAGE, imageFile = fileName))
        }
    }

    private fun pickApp() {
        val pm = service.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) {
            setStatus("실행 가능한 앱이 없습니다")
            return
        }
        val labels = apps.map { it.second }.toTypedArray()
        val b = AlertDialog.Builder(ctx)
        b.setTitle("실행할 앱 선택")
        b.setItems(labels) { _, which ->
            addAction(
                MacroAction(
                    type = ActionType.OPEN_APP,
                    packageName = apps[which].first,
                    appLabel = apps[which].second
                )
            )
        }
        b.setNegativeButton("취소", null)
        showOverlayDialog(b.create())
    }

    // ───────────────────────── 스텝 설정 다이얼로그 ─────────────────────────

    @SuppressLint("SetTextI18n")
    private fun showSettings(index: Int) {
        val a = script.actions[index]
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val fields = mutableMapOf<String, EditText>()

        fun field(key: String, label: String, value: String, numeric: Boolean = true) {
            layout.addView(TextView(ctx).apply { text = label; textSize = 12f })
            val e = EditText(ctx).apply {
                setText(value)
                if (numeric) inputType =
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            layout.addView(e)
            fields[key] = e
        }

        val isSearch = a.type == ActionType.SEARCH_IMAGE || a.type == ActionType.SEARCH_TEXT
        var clickBox: CheckBox? = null

        when (a.type) {
            ActionType.TAP -> {
                field("x", "X 좌표", a.x.toString())
                field("y", "Y 좌표", a.y.toString())
                field("repeat", "반복 횟수", a.repeatCount.toString())
                field("dur", "탭 지속시간 (ms)", a.durationMs.toString())
            }
            ActionType.SWIPE -> {
                field("x", "시작 X", a.x.toString()); field("y", "시작 Y", a.y.toString())
                field("x2", "끝 X", a.x2.toString()); field("y2", "끝 Y", a.y2.toString())
                field("dur", "지속시간 (ms)", a.durationMs.toString())
            }
            ActionType.PATH -> field("dur", "전체 지속시간 (ms)", a.durationMs.toString())
            ActionType.DELAY -> field("delay", "딜레이 (ms)", a.delayMs.toString())
            ActionType.SEARCH_IMAGE -> field("th", "매칭 임계값 (0.5~0.99)", a.threshold.toString())
            ActionType.SEARCH_TEXT -> field("text", "찾을 텍스트", a.text ?: "", numeric = false)
            ActionType.PASTE_TEXT -> field("text", "텍스트 (비우면 클립보드)", a.text ?: "", numeric = false)
            ActionType.RANDOM_TAP -> field("dur", "탭 지속시간 (ms)", a.durationMs.toString())
            else -> {}
        }

        if (isSearch) {
            field("attempts", "재시도 횟수 (0 = 무한 ∞)", if (a.maxAttempts < 0) "0" else a.maxAttempts.toString())
            field("interval", "재시도 간격 (ms)", a.retryIntervalMs.toString())
            clickBox = CheckBox(ctx).apply {
                text = "찾은 위치 클릭"
                isChecked = a.clickOnFound
            }
            layout.addView(clickBox)
        }

        // 분기: 0 = 다음 스텝, -1 = 중지, n = n번 스텝으로 점프
        field("onOk", "성공 시 이동 (0=다음, -1=중지, n=스텝번호)", gotoToUi(a.onSuccessGoto))
        if (isSearch) {
            field("onFail", "실패 시 이동 (0=다음, -1=중지, n=스텝번호)", gotoToUi(a.onFailureGoto))
        }
        field("post", "실행 후 대기 (ms)", a.postDelayMs.toString())

        val scroll = ScrollView(ctx).apply { addView(layout) }
        dialog("스텝 ${index + 1}: ${a.type.label} 설정", scroll, onOk = {
            fun num(key: String, def: Long) = fields[key]?.text?.toString()?.trim()?.toLongOrNull() ?: def
            fields["x"]?.let { a.x = num("x", a.x.toLong()).toInt() }
            fields["y"]?.let { a.y = num("y", a.y.toLong()).toInt() }
            fields["x2"]?.let { a.x2 = num("x2", a.x2.toLong()).toInt() }
            fields["y2"]?.let { a.y2 = num("y2", a.y2.toLong()).toInt() }
            fields["repeat"]?.let { a.repeatCount = num("repeat", a.repeatCount.toLong()).toInt().coerceAtLeast(1) }
            fields["dur"]?.let { a.durationMs = num("dur", a.durationMs).coerceAtLeast(1) }
            fields["delay"]?.let { a.delayMs = num("delay", a.delayMs).coerceAtLeast(0) }
            fields["th"]?.let {
                a.threshold = (it.text.toString().toDoubleOrNull() ?: a.threshold).coerceIn(0.3, 0.99)
            }
            fields["text"]?.let { a.text = it.text.toString() }
            fields["attempts"]?.let {
                val v = num("attempts", 0)
                a.maxAttempts = if (v <= 0) -1 else v.toInt()
            }
            fields["interval"]?.let { a.retryIntervalMs = num("interval", a.retryIntervalMs).coerceAtLeast(100) }
            fields["onOk"]?.let { a.onSuccessGoto = uiToGoto(num("onOk", 0).toInt()) }
            fields["onFail"]?.let { a.onFailureGoto = uiToGoto(num("onFail", -1).toInt()) }
            fields["post"]?.let { a.postDelayMs = num("post", a.postDelayMs).coerceAtLeast(0) }
            clickBox?.let { a.clickOnFound = it.isChecked }
            refreshList()
        })
    }

    private fun gotoToUi(v: Int) = when (v) {
        Goto.NEXT -> "0"
        Goto.STOP -> "-1"
        else -> (v + 1).toString()
    }

    private fun uiToGoto(v: Int) = when {
        v == 0 -> Goto.NEXT
        v < 0 -> Goto.STOP
        else -> v - 1
    }

    // ───────────────────────── 다이얼로그 헬퍼 ─────────────────────────

    private fun dialog(title: String, content: View?, onOk: () -> Unit) {
        val b = AlertDialog.Builder(ctx)
        b.setTitle(title)
        content?.let { b.setView(it) }
        b.setPositiveButton("확인") { _, _ -> onOk() }
        b.setNegativeButton("취소", null)
        showOverlayDialog(b.create())
    }

    private fun showOverlayDialog(d: AlertDialog) {
        d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        d.show()
    }

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
