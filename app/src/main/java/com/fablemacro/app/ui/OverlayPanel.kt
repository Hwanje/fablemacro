package com.fablemacro.app.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.format.DateFormat
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
import com.fablemacro.app.backup.BackupManager
import com.fablemacro.app.model.ActionType
import com.fablemacro.app.model.Goto
import com.fablemacro.app.model.MacroAction
import com.fablemacro.app.model.MacroScript
import com.fablemacro.app.model.ScriptStore
import com.fablemacro.app.online.MacroLink
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

    /** 마지막으로 저장/불러온 시점의 내용 — 변경 여부 판단용 */
    private var savedSnapshot: String = ""

    /** 열려 있는 스텝 설정 창 — 이미지 다시 지정할 때 닫기 위해 들고 있는다 */
    private var settingsDialog: AlertDialog? = null

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
        header.addView(iconButton("📚") { showScriptManager() })
        header.addView(iconButton("✚") { onNew() })
        header.addView(iconButton("─") { service.setPanelVisible(false) })
        header.addView(iconButton("✕", Color.parseColor("#FFFF8A80")) { confirmShutdown() })
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

        markSaved()
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
        // 저장하지 않은 변경은 제목 옆 • 로 알린다
        titleView.text = if (isDirty()) "${script.name} •" else script.name
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
            info.addView(text("${i + 1}. ${a.type.emoji} ${a.displayName()}", 11f, bold = true))
            val extra = buildString {
                if (a.hasCustomName()) append("${a.type.label} · ")
                append(a.summary())
                branchSummary(a)?.let { append("  $it") }
            }
            if (extra.isNotBlank()) info.addView(text(extra, 10f, Color.LTGRAY))
            row.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (a.canPreview()) row.addView(iconButton("◎") { service.previewAction(a) })
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
        keepingBranchTargets {
            val tmp = script.actions[i]
            script.actions[i] = script.actions[j]
            script.actions[j] = tmp
        }
        refreshList()
    }

    /** 스텝을 지정한 위치로 옮긴다 (설정 다이얼로그의 «순서» 입력) */
    private fun reorder(from: Int, to: Int) {
        if (from == to || from !in script.actions.indices) return
        val target = to.coerceIn(0, script.actions.size - 1)
        keepingBranchTargets {
            val item = script.actions.removeAt(from)
            script.actions.add(target, item)
        }
    }

    /**
     * 순서를 바꿔도 분기(성공/실패 시 이동)가 원래 가리키던 스텝을 계속 가리키게 한다.
     * 분기 값은 인덱스라서 재배치 후 그대로 두면 엉뚱한 스텝으로 점프한다.
     */
    private fun keepingBranchTargets(block: () -> Unit) {
        val before = script.actions.toList()
        fun refOf(goto: Int) = if (goto >= 0) before.getOrNull(goto)?.id else null
        val successRefs = before.map { refOf(it.onSuccessGoto) }
        val failureRefs = before.map { refOf(it.onFailureGoto) }

        block()

        val indexById = script.actions.withIndex().associate { (i, a) -> a.id to i }
        for ((k, a) in before.withIndex()) {
            successRefs[k]?.let { id ->
                // 가리키던 스텝이 사라졌으면 다음 스텝으로 이어가게 한다
                a.onSuccessGoto = indexById[id] ?: Goto.NEXT
            }
            failureRefs[k]?.let { id ->
                a.onFailureGoto = indexById[id] ?: Goto.NEXT
            }
        }
    }

    private fun confirmDelete(i: Int) {
        dialog("스텝 ${i + 1} 삭제", null, onOk = {
            keepingBranchTargets { script.actions.removeAt(i) }
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
        markSaved()
        refreshList()
        setStatus("저장됨: ${script.name}")
    }

    private fun onNew() {
        confirmDiscard("새 스크립트") {
            script = MacroScript()
            highlighted = -1
            markSaved()
            refreshList()
            setStatus("새 스크립트")
        }
    }

    /** 오버레이 종료 — 저장하지 않은 내용이 있으면 먼저 알린다 */
    private fun confirmShutdown() {
        val msg = if (isDirty()) {
            "오버레이를 종료할까요?\n«${script.name}»에 저장하지 않은 변경이 있습니다."
        } else {
            "오버레이를 종료할까요?"
        }
        dialog(msg, null, onOk = { service.shutdownOverlay() })
    }

    // ───────────────────────── 저장 상태 추적 ─────────────────────────

    private fun markSaved() {
        savedSnapshot = service.store.snapshot(script)
    }

    private fun isDirty(): Boolean = service.store.snapshot(script) != savedSnapshot

    /** 저장하지 않은 변경이 있으면 확인을 받고 진행 */
    private fun confirmDiscard(what: String, onProceed: () -> Unit) {
        if (!isDirty()) {
            onProceed()
            return
        }
        val b = AlertDialog.Builder(ctx)
        b.setTitle("$what — 저장하지 않은 변경이 있습니다")
        b.setMessage("«${script.name}»의 변경 내용을 어떻게 할까요?")
        b.setPositiveButton("저장하고 계속") { _, _ ->
            service.store.save(script)
            markSaved()
            onProceed()
        }
        b.setNeutralButton("버리고 계속") { _, _ -> onProceed() }
        b.setNegativeButton("취소", null)
        showOverlayDialog(b.create())
    }

    // ───────────────────────── 저장된 매크로 관리 ─────────────────────────

    /** 저장본 목록 — 내용 확인 / 불러오기 / 백업 내보내기 / 복제 / 삭제 */
    @SuppressLint("SetTextI18n")
    private fun showScriptManager() {
        val names = service.store.listNames()

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        // 항목을 고르면 목록은 닫고 상세로 넘어간다
        var managerDialog: AlertDialog? = null

        if (names.isEmpty()) {
            body.addView(TextView(ctx).apply {
                text = "저장된 매크로가 없습니다.\n💾 로 현재 스크립트를 저장하거나, 백업 파일을 가져올 수 있습니다."
                textSize = 12f
            })
        } else {
            for (name in names) {
                val info = service.store.info(name)
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    setOnClickListener {
                        managerDialog?.dismiss()
                        showScriptDetail(name)
                    }
                }
                row.addView(TextView(ctx).apply {
                    text = if (name == script.name) "$name  (현재 편집 중)" else name
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                })
                row.addView(TextView(ctx).apply {
                    text = info?.let { describe(it) } ?: "읽을 수 없는 파일"
                    textSize = 11f
                })
                body.addView(row)
                body.addView(View(ctx).apply {
                    setBackgroundColor(Color.parseColor("#22000000"))
                    layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
                })
            }
        }

        val b = AlertDialog.Builder(ctx)
        b.setTitle("저장된 매크로 (${names.size}개)")
        b.setView(ScrollView(ctx).apply { addView(body) })
        b.setPositiveButton("링크로 가져오기") { _, _ -> showLinkImport() }
        b.setNeutralButton("백업 파일") { _, _ -> showBackupOptions() }
        b.setNegativeButton("닫기", null)
        val created = b.create()
        managerDialog = created
        showOverlayDialog(created)
    }

    private fun showBackupOptions() {
        val b = AlertDialog.Builder(ctx)
        b.setTitle("백업 파일")
        b.setItems(arrayOf("백업 ZIP 가져오기", "웹 카탈로그 열기")) { _, which ->
            service.setPanelVisible(false)
            when (which) {
                0 -> {
                    BackupManager.startImport(service)
                    setStatus("백업 파일을 선택하세요")
                }
                1 -> openCatalogSite()
            }
        }
        b.setNegativeButton("취소", null)
        showOverlayDialog(b.create())
    }

    private fun openCatalogSite() {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(MacroLink.SITE)
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { service.startActivity(intent) }
            .onFailure { setStatus("브라우저를 열지 못했습니다") }
    }

    // ───────────────────────── 링크로 매크로 가져오기 ─────────────────────────

    /** 매크로 링크 / JSON 주소 / JSON 본문을 받아 저장한다 */
    private fun showLinkImport() {
        val input = EditText(ctx).apply {
            hint = "fablemacro://… 또는 https://… 링크 붙여넣기"
            setText(clipboardLink().orEmpty())
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(TextView(ctx).apply {
                text = "받은 매크로 링크를 붙여넣으세요.\n매크로 JSON 본문을 그대로 붙여넣어도 됩니다."
                textSize = 12f
            })
            addView(input)
        }

        val b = AlertDialog.Builder(ctx)
        b.setTitle("링크로 매크로 가져오기")
        b.setView(layout)
        b.setPositiveButton("가져오기") { _, _ -> importFromLink(input.text.toString()) }
        b.setNeutralButton("카탈로그에서 고르기") { _, _ -> showCatalogPicker() }
        b.setNegativeButton("취소", null)
        showOverlayDialog(b.create())
    }

    /** 공개 카탈로그 목록을 받아 바로 고를 수 있게 한다 */
    private fun showCatalogPicker() {
        setStatus("카탈로그를 받아오는 중…")
        service.uiScope.launch {
            val entries = MacroLink.fetchCatalog()
            if (entries.isNullOrEmpty()) {
                setStatus("카탈로그를 가져오지 못했습니다")
                return@launch
            }
            setStatus("카탈로그 ${entries.size}개")
            val labels = entries.map { e ->
                buildString {
                    append(e.name.ifBlank { e.id })
                    append("  (${e.steps}스텝)")
                    e.description?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
                }
            }.toTypedArray()

            val b = AlertDialog.Builder(ctx)
            b.setTitle("매크로 카탈로그")
            b.setItems(labels) { _, which ->
                importFromLink(MacroLink.macroUrl(entries[which].id))
            }
            b.setNeutralButton("웹에서 보기") { _, _ ->
                service.setPanelVisible(false)
                openCatalogSite()
            }
            b.setNegativeButton("닫기", null)
            showOverlayDialog(b.create())
        }
    }

    /** 클립보드에 링크처럼 보이는 값이 있으면 미리 채워준다 */
    private fun clipboardLink(): String? {
        val cm = service.getSystemService(android.content.ClipboardManager::class.java) ?: return null
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(service)?.toString()?.trim() ?: return null
        return text.takeIf {
            it.startsWith("fablemacro://") || it.startsWith("http://") ||
                    it.startsWith("https://") || it.startsWith("{")
        }
    }

    private fun importFromLink(input: String) {
        if (input.isBlank()) {
            setStatus("링크를 입력해주세요")
            return
        }
        setStatus("매크로를 받아오는 중…")
        service.uiScope.launch {
            val json = MacroLink.resolve(input)
            if (json == null) {
                setStatus("링크에서 매크로를 읽지 못했습니다")
                return@launch
            }
            val saved = service.store.fromShareJson(json)
            if (saved == null) {
                setStatus("매크로 내용이 올바르지 않습니다")
                return@launch
            }
            setStatus("저장됨: ${saved.name}")
            confirmLoadImported(saved)
        }
    }

    private fun confirmLoadImported(saved: MacroScript) {
        val b = AlertDialog.Builder(ctx)
        b.setTitle("가져왔습니다: ${saved.name}")
        b.setMessage(
            buildString {
                saved.description?.takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }
                append("스텝 ${saved.actions.size}개\n")
                saved.actions.take(10).forEachIndexed { i, a ->
                    append("\n${i + 1}. ${a.type.emoji} ${a.displayName()}   ${a.summary()}")
                }
                if (saved.actions.size > 10) append("\n… 외 ${saved.actions.size - 10}개")
                append("\n\n지금 편집기로 불러올까요?")
            }
        )
        b.setPositiveButton("불러오기") { _, _ ->
            confirmDiscard("불러오기") {
                script = saved
                highlighted = -1
                markSaved()
                refreshList()
                setStatus("불러옴: ${saved.name}")
            }
        }
        b.setNegativeButton("나중에", null)
        showOverlayDialog(b.create())
    }

    /** 현재 편집 중인 스크립트를 공유 링크로 만든다 */
    private fun shareAsLink(name: String) {
        val saved = service.store.load(name) ?: run {
            setStatus("«$name» 을 읽을 수 없습니다")
            return
        }
        val json = service.store.toShareJson(saved)
        val payload = android.util.Base64.encodeToString(
            json.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
        )
        val link = "fablemacro://import?p=$payload"

        val b = AlertDialog.Builder(ctx)
        b.setTitle("공유 링크")
        b.setMessage(
            if (link.length > 3000) {
                "이 매크로는 템플릿 이미지가 커서 링크가 매우 깁니다 (${link.length / 1024}KB).\n" +
                        "메신저로 보낼 때는 백업 ZIP 내보내기를 쓰는 편이 안전합니다."
            } else {
                "링크를 복사해 다른 기기의 FableMacro에서 열면 그대로 가져옵니다."
            }
        )
        b.setPositiveButton("복사") { _, _ ->
            val cm = service.getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("FableMacro", link))
            setStatus("링크를 복사했습니다")
        }
        b.setNegativeButton("닫기", null)
        showOverlayDialog(b.create())
    }

    private fun describe(i: ScriptStore.ScriptInfo): String {
        val date = DateFormat.format("yyyy-MM-dd HH:mm", i.modifiedAt)
        val size = if (i.bytes < 1024) "${i.bytes}B" else "${i.bytes / 1024}KB"
        return buildString {
            append("${i.steps}스텝")
            if (i.templates > 0) append(" · 이미지 ${i.templates}개")
            if (i.missingTemplates > 0) append(" ⚠️이미지 ${i.missingTemplates}개 없음")
            append(" · $date · $size")
        }
    }

    /** 저장본을 불러오지 않고 내용만 미리 확인 + 관리 동작 */
    @SuppressLint("SetTextI18n")
    private fun showScriptDetail(name: String) {
        val saved = service.store.load(name)
        if (saved == null) {
            setStatus("«$name» 을 읽을 수 없습니다")
            return
        }
        val info = service.store.info(name)

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        info?.let {
            body.addView(TextView(ctx).apply { text = describe(it); textSize = 11f })
        }
        if (info != null && info.missingTemplates > 0) {
            body.addView(TextView(ctx).apply {
                text = "⚠️ 템플릿 이미지 일부가 없어 이미지 검색이 실패할 수 있습니다."
                textSize = 11f
                setTextColor(Color.parseColor("#FFD32F2F"))
            })
        }
        body.addView(TextView(ctx).apply {
            text = "\n스텝 ${saved.actions.size}개"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
        })
        for ((i, a) in saved.actions.withIndex()) {
            body.addView(TextView(ctx).apply {
                text = "${i + 1}. ${a.type.emoji} ${a.displayName()}   ${a.summary()}"
                textSize = 11f
                setPadding(0, dp(3), 0, dp(3))
            })
        }

        val b = AlertDialog.Builder(ctx)
        b.setTitle(name)
        b.setView(ScrollView(ctx).apply { addView(body) })
        b.setPositiveButton("불러오기") { _, _ ->
            confirmDiscard("불러오기") {
                service.store.load(name)?.let {
                    script = it
                    highlighted = -1
                    markSaved()
                    refreshList()
                    setStatus("불러옴: ${it.name}")
                }
            }
        }
        b.setNeutralButton("내보내기") { _, _ -> exportScript(name) }
        b.setNegativeButton("더보기") { _, _ -> showScriptActions(name) }
        showOverlayDialog(b.create())
    }

    private fun showScriptActions(name: String) {
        val b = AlertDialog.Builder(ctx)
        b.setTitle(name)
        b.setItems(arrayOf("백업 내보내기", "공유 링크 만들기", "복제", "삭제")) { _, which ->
            when (which) {
                0 -> exportScript(name)
                1 -> shareAsLink(name)
                2 -> duplicateScript(name)
                3 -> confirmDeleteScript(name)
            }
        }
        b.setNegativeButton("취소", null)
        showOverlayDialog(b.create())
    }

    /** 스크립트 + 템플릿 이미지를 ZIP으로 추출 */
    private fun exportScript(name: String) {
        val result = BackupManager.export(service, service.store, name)
        if (result == null) {
            setStatus("«$name» 내보내기에 실패했습니다")
            return
        }
        setStatus(result.savedTo?.let { "저장됨: $it" } ?: "백업 생성됨: ${result.file.name}")

        val b = AlertDialog.Builder(ctx)
        b.setTitle("백업을 만들었습니다")
        b.setMessage(
            buildString {
                append("${result.file.name}\n")
                result.savedTo?.let { append("\n기기에 저장됨: $it\n") }
                append("\n스크립트와 템플릿 이미지가 모두 들어 있어 다른 기기에서도 복원됩니다.")
            }
        )
        b.setPositiveButton("공유") { _, _ ->
            service.setPanelVisible(false)
            runCatching { BackupManager.share(service, result.file) }
                .onFailure { setStatus("공유할 앱을 찾지 못했습니다") }
        }
        b.setNegativeButton("닫기", null)
        showOverlayDialog(b.create())
    }

    private fun duplicateScript(name: String) {
        val src = service.store.load(name)
        if (src == null) {
            setStatus("«$name» 을 읽을 수 없습니다")
            return
        }
        src.name = service.store.uniqueName(name)
        service.store.save(src)
        setStatus("복제됨: ${src.name}")
    }

    private fun confirmDeleteScript(name: String) {
        val b = AlertDialog.Builder(ctx)
        b.setTitle("«$name» 삭제")
        b.setMessage("저장본이 지워집니다. 되돌릴 수 없으니 필요하면 먼저 내보내기로 백업하세요.")
        b.setPositiveButton("삭제") { _, _ ->
            service.store.delete(name)
            setStatus("삭제됨: $name")
        }
        b.setNeutralButton("내보내고 삭제") { _, _ ->
            BackupManager.export(service, service.store, name)
            service.store.delete(name)
            setStatus("백업 후 삭제됨: $name")
        }
        b.setNegativeButton("취소", null)
        showOverlayDialog(b.create())
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
        captureTemplateImage(r) { fileName ->
            addAction(MacroAction(type = ActionType.SEARCH_IMAGE, imageFile = fileName))
        }
    }

    /** 화면에서 영역을 잘라 템플릿 이미지로 저장하고 파일명을 넘겨준다 */
    private fun captureTemplateImage(r: Rect, onSaved: (String) -> Unit) {
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
            onSaved(service.store.saveImage(cropped))
        }
    }

    /**
     * 이미 있는 이미지 검색 스텝의 템플릿을 새로 지정한다.
     * 링크로 받은 스켈레톤 매크로처럼 이미지가 비어 있는 스텝을 채울 때 쓴다.
     */
    private fun recaptureTemplate(index: Int) {
        val a = script.actions.getOrNull(index) ?: return
        pickRegion("«${a.displayName()}» 에 쓸 이미지 영역을 드래그로 지정하세요") { r ->
            captureTemplateImage(r) { fileName ->
                a.imageFile = fileName
                refreshList()
                setStatus("이미지 지정됨: ${a.displayName()}")
                showSettings(index)
            }
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

        // 액션 이름 — 모든 타입 공통, 비워두면 타입 이름으로 표시된다
        field("name", "액션 이름 (비우면 «${a.type.label}»)", a.name ?: "", numeric = false)
        // 순서 — 번호를 바꾸면 그 자리로 이동한다 (분기 대상은 따라간다)
        field("order", "순서 (1 ~ ${script.actions.size})", (index + 1).toString())

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
            ActionType.SEARCH_IMAGE -> {
                // 링크로 받은 스켈레톤처럼 이미지가 비어 있는 스텝을 여기서 채울 수 있다
                layout.addView(TextView(ctx).apply {
                    text = if (a.imageFile == null) "⚠ 찾을 이미지가 아직 없습니다" else "찾을 이미지: ${a.imageFile}"
                    textSize = 12f
                })
                layout.addView(android.widget.Button(ctx).apply {
                    text = if (a.imageFile == null) "이미지 지정" else "이미지 다시 지정"
                    setOnClickListener {
                        settingsDialog?.dismiss()
                        recaptureTemplate(index)
                    }
                })
                field("th", "매칭 임계값 (0.5~0.99)", a.threshold.toString())
            }
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
        settingsDialog = dialog("스텝 ${index + 1}: ${a.displayName()} 설정", scroll, onOk = {
            fun num(key: String, def: Long) = fields[key]?.text?.toString()?.trim()?.toLongOrNull() ?: def
            fields["name"]?.let { a.name = it.text.toString().trim().ifBlank { null } }
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

            // 분기 값을 먼저 확정한 뒤 이동시켜야 대상이 어긋나지 않는다
            val newPos = num("order", (index + 1).toLong()).toInt() - 1
            if (newPos != index) {
                reorder(index, newPos)
                setStatus("스텝 ${index + 1} → ${newPos.coerceIn(0, script.actions.size - 1) + 1} 로 이동")
            }
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

    private fun numberInput(initial: String) = EditText(ctx).apply {
        setText(initial)
        inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun dialog(title: String, content: View?, onOk: () -> Unit): AlertDialog {
        val b = AlertDialog.Builder(ctx)
        b.setTitle(title)
        content?.let { b.setView(it) }
        b.setPositiveButton("확인") { _, _ -> onOk() }
        b.setNegativeButton("취소", null)
        val d = b.create()
        showOverlayDialog(d)
        return d
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
