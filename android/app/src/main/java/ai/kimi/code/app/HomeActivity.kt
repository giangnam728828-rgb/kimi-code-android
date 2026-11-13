package ai.kimi.code.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DSH-Go 风格的原生首页。
 *
 * 三个页签：会话 / 运行 / 设置；抽屉里放全部会话、筛选、文件夹（按工作区）
 * 和「原生 / 网页版」切换。聊天本身仍然交给官方 dist-web，通过 MainActivity
 * 打开，所以工具卡、审批、diff、技能、定时任务一个都不少。
 *
 * 布局全部代码构建：项目没有 androidx / Material，XML 只有一份老布局，
 * 新界面不去动它，避免把已经稳定的启动链改坏。
 */
class HomeActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var pages: FrameLayout
    private lateinit var navBar: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View
    private lateinit var drawerList: LinearLayout

    private var tab = 0
    private var filter = 0
    private var query = ""
    private var snapshot: SessionRepository.Snapshot? = null
    private var loading = false
    private var loadRetries = 0
    private var drawerOpen = false
    private var lastPhase: Runtime.Phase? = null
    private var sessionList: LinearLayout? = null

    private val navItems = mutableListOf<Triple<FrameLayout, TextView, LinearLayout>>()
    private val listener: () -> Unit = { runOnUiThread { renderChrome() } }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)

    /** 上次启动崩掉的堆栈，构建首页时贴一条红条出来。 */
    private var lastCrash: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        lastCrash = CrashGuard.consume(this)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.parseColor(DshUi.BG_BOTTOM)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        root = FrameLayout(this).apply { setBackground(DshUi.background()) }
        setContentView(root)

        pages = FrameLayout(this)
        root.addView(pages, FrameLayout.LayoutParams(-1, -1))

        // 首页是手搓的原生布局，任何一处抛异常都会让用户只看到白屏。
        // 兜住：把堆栈记下来，直接退到网页版工作台，至少功能是完整的。
        try {
            buildNavBar()
            buildDrawer()
            Runtime.addListener(listener)
            selectTab(0)
            maybeStartEngine()
        } catch (t: Throwable) {
            CrashGuard.record(this, t)
            fallbackToWeb(t)
            return
        }

        // 手机操控：起本地桥，并把 MCP 脚本与配置写进容器。放在后台线程，
        // 不拖慢首页第一帧；引擎没装时这些目录也只是空写一次。
        Thread {
            runCatching {
                PhoneAccess.ensureBridge(this)
                PhoneAccess.installIntoContainer(this)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun fallbackToWeb(error: Throwable) {
        runCatching {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_WEB, true)
            }
            startActivity(intent)
            Toast.makeText(this, "原生首页加载失败，已切到网页版（详情见剪贴板）", Toast.LENGTH_LONG).show()
            lastCrash = lastCrash ?: error.stackTraceToString()
            lastCrash?.let { CrashGuard.copyToClipboard(this, it) }
        }
        finish()
    }

    override fun onResume() {
        super.onResume()
        Runtime.addListener(listener)
        if (Runtime.phase == Runtime.Phase.READY) loadSessions()
        renderChrome()
    }

    override fun onPause() {
        Runtime.removeListener(listener)
        super.onPause()
    }

    override fun onBackPressed() {
        if (drawerOpen) {
            closeDrawer()
            return
        }
        if (tab != 0) {
            selectTab(0)
            return
        }
        super.onBackPressed()
    }

    // ---------------------------------------------------------------- chrome

    private fun buildNavBar() {
        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#F2FFFFFF"), Color.parseColor("#F7FFFFFF")),
            ).apply {
                setStroke(1, Color.parseColor("#EDEDF5"))
                cornerRadii = floatArrayOf(
                    dpf(22f), dpf(22f), dpf(22f), dpf(22f), 0f, 0f, 0f, 0f,
                )
            }
            elevation = dp(10f).toFloat()
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
        val lp = FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM }
        root.addView(navBar, lp)

        val items = listOf(
            Triple(DshIcon.CHAT, "会话", 0),
            Triple(DshIcon.RUN, "运行", 1),
            Triple(DshIcon.SETTINGS, "设置", 2),
        )
        items.forEach { (icon, label, index) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(6f), 0, dp(4f))
                isClickable = true
                setOnClickListener { selectTab(index) }
            }
            val iconBox = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28f), dp(24f))
            }
            val glyph = DshIcon.view(this, icon, Color.parseColor(DshUi.MUTED), 22, 1.7f)
            iconBox.addView(glyph, DshUi.centerParams())
            iconBox.tag = icon
            val textView = DshUi.text(this, label, 11f, DshUi.MUTED)
            item.addView(iconBox)
            item.addView(DshUi.spacer(this, 3f))
            item.addView(textView)
            navBar.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
            navItems.add(Triple(iconBox, textView, item))
        }
    }

    private fun renderChrome() {
        val active = when (tab) {
            0 -> "会话"
            1 -> "运行"
            else -> "设置"
        }
        navItems.forEachIndexed { index, (iconBox, label, item) ->
            val on = index == tab
            val color = if (on) DshUi.PRIMARY else DshUi.MUTED
            label.setTextColor(Color.parseColor(color))
            label.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            val type = iconBox.tag as? String
            if (type != null) {
                iconBox.removeAllViews()
                val glyph = DshIcon.view(this, type, Color.parseColor(color), 22, if (on) 1.9f else 1.7f)
                iconBox.addView(glyph, DshUi.centerParams())
            }
            item.background = if (on) DshUi.fill(DshUi.PRIMARY_SOFT, 16f) else null
        }
        if (lastPhase != Runtime.phase) {
            lastPhase = Runtime.phase
            if (Runtime.phase == Runtime.Phase.READY && snapshot == null) loadSessions()
        }
    }

    // ---------------------------------------------------------------- pages

    private fun selectTab(index: Int) {
        tab = index
        CrashGuard.route = when (index) {
            0 -> "首页/会话"
            1 -> "首页/运行"
            else -> "首页/设置"
        }
        pages.removeAllViews()
        val page = when (index) {
            0 -> buildChatsPage()
            1 -> buildRunPage()
            else -> buildSettingsPage()
        }
        pages.addView(page, FrameLayout.LayoutParams(-1, -1))
        // Leave room for the floating bar.
        page.setPadding(0, 0, 0, dp(72f))
        renderChrome()
    }

    // ---- 会话 --------------------------------------------------------------

    private fun buildChatsPage(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), statusBarHeight() + dp(10f), dp(16f), dp(24f))
        }
        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))

        body.addView(buildTopBar("会话", subtitle(), onMenu = { openDrawer() }))
        lastCrash?.let { crash ->
            body.addView(DshUi.spacer(this, 12f))
            body.addView(crashBanner(crash))
        }
        body.addView(DshUi.spacer(this, 14f))

        val search = DshUi.searchField(this, "搜索会话") { value ->
            query = value
            renderSessionList()
        }
        search.setText(query)
        body.addView(DshUi.withSearchIcon(this, search), LinearLayout.LayoutParams(-1, -2))
        body.addView(DshUi.spacer(this, 12f))

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("会话", "运行中", "待确认", "未读").forEachIndexed { index, label ->
            if (index > 0) chips.addView(DshUi.gap(this, 8f))
            val chip = DshUi.chip(this, label, filter == index) {
                filter = index
                selectTab(0)
            }
            chips.addView(chip)
        }
        body.addView(chips)
        body.addView(DshUi.spacer(this, 12f))

        val listCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = DshUi.fill("#E6FFFFFF", 20f, "#F1F1F8")
            setPadding(dp(5f), dp(5f), dp(5f), dp(5f))
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sessionList = list
        listCard.addView(list, LinearLayout.LayoutParams(-1, -2))
        body.addView(listCard, LinearLayout.LayoutParams(-1, -2))
        renderSessionList()
        return scroll
    }

    private fun subtitle(): String {
        val ready = Runtime.phase == Runtime.Phase.READY
        return if (ready) "本机 · 运行中" else Runtime.title
    }

    /** The DSH top bar: round menu key, the title card, round new-chat key. */
    private fun buildTopBar(title: String, subtitle: String, onMenu: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(DshUi.circleButton(this, DshIcon.MENU, 42f, tint = DshUi.TEXT) { onMenu() })
        row.addView(DshUi.gap(this, 10f))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = DshUi.fill("#FCFFFFFF", 21f, "#F0F0F7")
            elevation = dp(1f).toFloat()
            setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
        }
        card.addView(DshUi.text(this, title, 15f, DshUi.TEXT, true))
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(3f), 0, 0)
        }
        line.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6f), dp(6f))
            background = DshUi.fillInt(
                Color.parseColor(if (Runtime.phase == Runtime.Phase.READY) DshUi.GREEN else DshUi.AMBER),
                3f,
            )
        })
        line.addView(DshUi.gap(this, 5f))
        line.addView(DshUi.text(this, subtitle, 11f, DshUi.MUTED))
        card.addView(line)
        row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(4f); rightMargin = dp(10f)
        })

        row.addView(DshUi.circleButton(this, DshIcon.EDIT, 42f, tint = DshUi.TEXT) { openWeb(null, null) })
        return row
    }

    private fun renderSessionList() {
        val holder = sessionList ?: return
        holder.removeAllViews()

        val snap = snapshot
        if (snap == null) {
            holder.addView(emptyCard(
                if (Runtime.phase == Runtime.Phase.READY) "正在读取会话" else "引擎还没启动",
                if (Runtime.phase == Runtime.Phase.READY) "稍等一下" else "到「运行」页启动，启动后这里会列出手机上的全部会话",
            ))
            return
        }

        var items = snap.sessions
        items = when (filter) {
            1 -> items.filter { it.busy }
            2 -> items.filter { it.pending }
            3 -> items.filter { it.busy || it.pending }
            else -> items
        }
        if (query.isNotBlank()) {
            items = items.filter { it.title.contains(query, ignoreCase = true) }
        }

        if (items.isEmpty()) {
            holder.addView(emptyCard("还没有会话", "点右上角的笔，或在抽屉里点「新对话」"))
            return
        }

        val names = snap.workspaces.associate { it.id to it.name }
        var lastBucket = ""
        items.forEach { session ->
            val bucket = SessionRepository.bucket(session.updatedAt)
            if (bucket != lastBucket) {
                lastBucket = bucket
                holder.addView(DshUi.text(this, bucket, 12f, DshUi.MUTED, true).apply {
                    setPadding(dp(6f), dp(14f), 0, dp(6f))
                })
            }
            val where = names[session.workspaceId] ?: session.cwd.substringAfterLast('/')
            val sub = buildString {
                if (where.isNotBlank()) append(where).append(" · ")
                append(SessionRepository.relative(session.updatedAt))
                if (session.messages > 0) append(" · ").append(session.messages).append(" 条")
            }
            holder.addView(
                DshUi.sessionRow(
                    this,
                    session.title,
                    sub,
                    running = session.busy,
                    pending = session.pending,
                    selected = false,
                    onClick = { openWeb(session.id, session.title) },
                    onLongClick = { showSessionActions(session) },
                ),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2f) },
            )
        }
    }

    private fun emptyCard(title: String, detail: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = DshUi.fill("#B3FFFFFF", 20f, "#F1F1F8")
        setPadding(dp(20f), dp(34f), dp(20f), dp(34f))
        addView(DshUi.text(this@HomeActivity, title, 15f, DshUi.SUB, true))
        addView(DshUi.spacer(this@HomeActivity, 6f))
        addView(DshUi.text(this@HomeActivity, detail, 12.5f, DshUi.MUTED, lines = 2).apply {
            gravity = Gravity.CENTER
        })
    }

    /**
     * 上次崩溃的红条：点一下整段堆栈进剪贴板，再点一次就消失。
     * 用户不用连电脑，粘出来发一条消息就能定位。
     */
    private fun crashBanner(text: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = DshUi.fill("#FFF4F2", 16f, "#F3D6D2")
        setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        isClickable = true
        addView(DshUi.text(this@HomeActivity, "上次启动崩了一次 · 点这里复制日志", 13.5f, "#B4372E", true))
        addView(DshUi.spacer(this@HomeActivity, 6f))
        addView(DshUi.text(this@HomeActivity, CrashGuard.summary(text), 11f, "#8A5A55", lines = 3).apply {
            typeface = Typeface.MONOSPACE
        })
        setOnClickListener {
            CrashGuard.copyToClipboard(this@HomeActivity, text)
            lastCrash = null
            Toast.makeText(this@HomeActivity, "日志已复制，粘贴出来即可", Toast.LENGTH_SHORT).show()
            selectTab(0)
        }
    }

    private fun showSessionActions(session: SessionRepository.Session) {
        picker(session.title, session.cwd, listOf("打开", "复制标题", "删除会话")) { index ->
            when (index) {
                0 -> openWeb(session.id, session.title)
                1 -> {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("session", session.title))
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
                }
                2 -> {
                    Thread {
                        runCatching {
                            val conn = java.net.URL("http://127.0.0.1:${Runtime.port}/api/v1/sessions/${session.id}")
                                .openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "DELETE"
                            conn.connectTimeout = 4000
                            conn.responseCode
                            conn.disconnect()
                        }
                        runOnUiThread { loadSessions() }
                    }.start()
                }
            }
        }
    }

    /** A DSH-shaped choice sheet: message, tappable rows, one cancel key. */
    private fun picker(titleText: String, message: String, options: List<String>, onPick: (Int) -> Unit) {
        EtaSheet.show(this, titleText, tall = false) { sheet ->
            if (message.isNotBlank()) {
                sheet.body.addView(DshUi.text(this, message, 13f, DshUi.SUB, lines = 6).apply {
                    setPadding(0, dp(6f), 0, dp(12f))
                })
            }
            options.forEachIndexed { index, label ->
                val row = DshUi.text(this, label, 15.5f, DshUi.TEXT).apply {
                    setPadding(dp(4f), dp(14f), dp(4f), dp(14f))
                    isClickable = true
                    setOnClickListener { sheet.dismiss(); onPick(index) }
                }
                sheet.body.addView(row, LinearLayout.LayoutParams(-1, -2))
                if (index < options.size - 1) sheet.body.addView(EtaUi.divider(this))
            }
            sheet.footer.addView(
                EtaSheet.button(this, "取消", filled = false) { sheet.dismiss() },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
    }

    // ---- 运行 --------------------------------------------------------------

    private fun buildRunPage(): View {
        val (scroll, body) = DshUi.page(this)
        body.setPadding(dp(16f), statusBarHeight() + dp(10f), dp(16f), dp(30f))

        body.addView(buildTopBar("运行", subtitle(), onMenu = { openDrawer() }))
        body.addView(DshUi.spacer(this, 16f))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = DshUi.fill("#FCFFFFFF", 22f, "#F0F0F7")
            elevation = dp(1.5f).toFloat()
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(9f), dp(9f))
            background = DshUi.fillInt(
                Color.parseColor(
                    when (Runtime.phase) {
                        Runtime.Phase.READY -> DshUi.GREEN
                        Runtime.Phase.STARTING, Runtime.Phase.INSTALLING -> DshUi.AMBER
                        else -> "#C9C9D6"
                    }
                ),
                5f,
            )
        })
        head.addView(DshUi.gap(this, 8f))
        head.addView(DshUi.text(this, Runtime.title, 17f, DshUi.TEXT, true))
        hero.addView(head)
        if (Runtime.detail.isNotBlank()) {
            hero.addView(DshUi.text(this, Runtime.detail, 12.5f, DshUi.MUTED, lines = 3).apply {
                setPadding(0, dp(6f), 0, 0)
            })
        }
        if (Runtime.phase == Runtime.Phase.READY) {
            val facts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(14f), 0, 0)
            }
            facts.addView(factRow("端口", Runtime.port.toString()))
            facts.addView(factRow("已运行", uptime()))
            facts.addView(factRow("工作目录", ProotRuntime.currentWorkdir(this@HomeActivity)))
            hero.addView(facts)
        }
        body.addView(hero)
        body.addView(DshUi.spacer(this, 12f))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (Runtime.phase == Runtime.Phase.READY) {
            actions.addView(DshUi.pillButton(this, "打开工作台", true, DshIcon.CHAT) { openWeb(null, null) })
            actions.addView(DshUi.gap(this, 8f))
            actions.addView(DshUi.pillButton(this, "重启", false, DshIcon.REFRESH) {
                RuntimeService.restart(this); selectTab(1)
            })
            actions.addView(DshUi.gap(this, 8f))
            actions.addView(DshUi.pillButton(this, "停止", false, DshIcon.STOP) {
                RuntimeService.stop(this); selectTab(1)
            })
        } else {
            actions.addView(DshUi.pillButton(this, "启动引擎", true, DshIcon.RUN) {
                RuntimeService.start(this); selectTab(1)
            })
        }
        body.addView(actions, LinearLayout.LayoutParams(-1, -2))

        body.addView(DshUi.sectionLabel(this, "日志"))
        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = DshUi.fill("#F7F7FB", 16f, "#EFEFF6")
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        val lines = Runtime.snapshotLogs().takeLast(18)
        logCard.addView(DshUi.text(
            this,
            if (lines.isEmpty()) "还没有日志" else lines.joinToString("\n"),
            10.5f, DshUi.SUB, lines = 18,
        ).apply {
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(3f).toFloat(), 1f)
        })
        body.addView(logCard, LinearLayout.LayoutParams(-1, -2))
        return scroll
    }

    private fun factRow(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4f), 0, dp(4f))
        addView(DshUi.text(this@HomeActivity, label, 12.5f, DshUi.MUTED))
        addView(DshUi.gap(this@HomeActivity, 12f))
        addView(DshUi.text(this@HomeActivity, value, 12.5f, DshUi.SUB).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun uptime(): String {
        val start = Runtime.readyAt
        if (start <= 0) return "—"
        val seconds = ((System.currentTimeMillis() - start).coerceAtLeast(0L) / 1000L)
        return when {
            seconds < 60 -> "$seconds 秒"
            seconds < 3600 -> "${seconds / 60} 分钟"
            else -> "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分"
        }
    }

    // ---- 设置 --------------------------------------------------------------

    private fun buildSettingsPage(): View {
        val (scroll, body) = DshUi.page(this)
        body.setPadding(dp(16f), statusBarHeight() + dp(10f), dp(16f), dp(30f))

        body.addView(buildTopBar("设置", "本机 · ${versionName()}", onMenu = { openDrawer() }))
        body.addView(DshUi.spacer(this, 14f))

        // 概览：账户 + 引擎
        val overview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = DshUi.fill("#FCFFFFFF", 22f, "#F0F0F7")
            elevation = dp(1.5f).toFloat()
        }
        val account = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(16f), dp(14f), dp(14f))
        }
        account.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42f), dp(42f))
            background = DshUi.fillInt(Color.parseColor("#E8EEFB"), 21f)
            val g = DshIcon.view(this@HomeActivity, DshIcon.PERSON, Color.parseColor("#3B6BD6"), 22, 1.6f)
            addView(g, DshUi.centerParams())
        })
        account.addView(DshUi.gap(this, 12f))
        val who = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        who.addView(DshUi.text(this, "Kimi Code", 15.5f, DshUi.TEXT, true))
        who.addView(DshUi.text(this, "本机运行 · ${versionName()}", 12f, DshUi.MUTED).apply {
            setPadding(0, dp(3f), 0, 0)
        })
        account.addView(who, LinearLayout.LayoutParams(0, -2, 1f))
        account.addView(DshUi.text(this, "本机", 12f, DshUi.MUTED))
        account.addView(DshIcon.view(this, DshIcon.CHEVRON, Color.parseColor("#C6C6D2"), 15, 1.6f))
        overview.addView(account)

        overview.addView(DshUi.divider(this, 16f))
        val engine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(12f), dp(14f), dp(12f))
            isClickable = true
            setOnClickListener { selectTab(1) }
        }
        engine.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7f), dp(7f))
            background = DshUi.fillInt(
                Color.parseColor(if (Runtime.phase == Runtime.Phase.READY) DshUi.GREEN else DshUi.MUTED), 4f,
            )
        })
        engine.addView(DshUi.gap(this, 9f))
        engine.addView(DshUi.text(
            this,
            "本机引擎 · ${if (Runtime.phase == Runtime.Phase.READY) "运行中" else Runtime.title}",
            12.5f, DshUi.SUB,
        ).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        engine.addView(DshUi.text(this, "端口 ${Runtime.port}", 12f, DshUi.MUTED))
        engine.addView(DshIcon.view(this, DshIcon.CHEVRON, Color.parseColor("#C6C6D2"), 15, 1.6f))
        overview.addView(engine)
        body.addView(overview)

        // AI
        body.addView(DshUi.sectionLabel(this, "AI"))
        val ai = DshUi.group(this)
        ai.addView(DshUi.settingsRow(this, DshIcon.MODEL, 0, "模型", modelSummary()) {
            openProviderPage()
        })
        ai.addView(DshUi.divider(this))
        ai.addView(DshUi.settingsRow(this, DshIcon.SHIELD, 1, "权限", permissionLabel()) {
            showPermissionSheet()
        })
        ai.addView(DshUi.divider(this))
        ai.addView(DshUi.settingsRow(this, DshIcon.PERSON, 2, "个性化", workspaceName()) {
            promptWorkspace()
        })
        body.addView(ai)
        body.addView(DshUi.spacer(this, 8f))

        // 能力
        body.addView(DshUi.sectionLabel(this, "能力"))
        val ability = DshUi.group(this)
        ability.addView(DshUi.settingsRow(
            this, DshIcon.PHONE, 4, "手机操控",
            if (PhoneAccess.isEnabled(this)) "已开启" else "未开启",
        ) { openPhoneControl() })
        ability.addView(DshUi.divider(this))
        ability.addView(DshUi.settingsRow(this, DshIcon.GLOBE, 3, "联网搜索", "模型自带") {
            Toast.makeText(this, "搜索工具由模型供应商提供，无需单独配置", Toast.LENGTH_SHORT).show()
        })
        ability.addView(DshUi.divider(this))
        ability.addView(DshUi.settingsRow(this, DshIcon.IMAGE, 5, "图像与视频", "读取相册 / 拍照") {
            Toast.makeText(this, "在对话输入框点「附件」即可发图片", Toast.LENGTH_SHORT).show()
        })
        body.addView(ability)
        body.addView(DshUi.spacer(this, 8f))

        // 通用
        body.addView(DshUi.sectionLabel(this, "通用"))
        val general = DshUi.group(this)
        general.addView(DshUi.settingsRow(this, DshIcon.SWAP, 6, "界面", "原生") {
            showUiModeSheet()
        })
        general.addView(DshUi.divider(this))
        general.addView(DshUi.settingsRow(this, DshIcon.DATA, 3, "数据与备份", "") {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PAGE, MainActivity.PAGE_DATA))
        })
        general.addView(DshUi.divider(this))
        general.addView(DshUi.settingsRow(this, DshIcon.TERMINAL, 1, "终端", "") {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PAGE, MainActivity.PAGE_TERMINAL))
        })
        general.addView(DshUi.divider(this))
        general.addView(DshUi.settingsRow(this, DshIcon.PLUGIN, 2, "插件", "") {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PAGE, MainActivity.PAGE_PLUGINS))
        })
        body.addView(general)
        body.addView(DshUi.spacer(this, 8f))

        body.addView(DshUi.sectionLabel(this, "关于"))
        val about = DshUi.group(this)
        about.addView(DshUi.settingsRow(this, DshIcon.CHAT, 0, "网页版工作台", "完整界面") {
            openWeb(null, null)
        })
        about.addView(DshUi.divider(this))
        about.addView(DshUi.settingsRow(this, DshIcon.SETTINGS, 6, "版本", versionName()))
        body.addView(about)

        body.addView(DshUi.text(
            this,
            "界面参考 DSH Go 的原生版布局；聊天、工具卡、审批仍由官方 Kimi Code 工作台渲染。",
            11.5f, DshUi.MUTED, lines = 3,
        ).apply { setPadding(dp(6f), dp(18f), dp(6f), 0) })
        return scroll
    }

    private fun modelSummary(): String {
        val count = Prefs.load(this).count { it.isConfigured }
        return if (count == 0) "未配置" else "$count 个供应商"
    }

    private fun permissionLabel(): String =
        if (Prefs.autonomy(this)) "完全权限" else "默认"

    private fun workspaceName(): String =
        ProotRuntime.currentWorkdir(this).substringAfterLast('/').ifBlank { "未设置" }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
    }.getOrDefault("—")

    private fun showPermissionSheet() {
        val autonomy = Prefs.autonomy(this)
        picker(
            "权限",
            "完全权限：工具调用、命令执行、文件改写全部自动通过，不再逐条询问。\n默认：涉及写入与命令时先弹确认。",
            listOf(
                (if (autonomy) "✓  " else "") + "完全权限（推荐）",
                (if (!autonomy) "✓  " else "") + "默认（危险操作先问）",
            ),
        ) { index ->
            Prefs.setAutonomy(this, index == 0)
            Toast.makeText(this, if (index == 0) "已切换为完全权限" else "已切回默认", Toast.LENGTH_SHORT).show()
            selectTab(2)
        }
    }


    private fun showUiModeSheet() {
        picker(
            "界面",
            "原生：会话、抽屉、设置是原生界面，聊天页仍是官方工作台。\n网页版：直接进官方工作台，全屏。",
            listOf("原生界面（当前）", "网页版工作台"),
        ) { index ->
            if (index == 1) openWeb(null, null)
        }
    }


    private fun openPhoneControl() {
        startActivity(Intent(this, PhoneControlActivity::class.java))
    }

    private fun openProviderPage() {
        startActivity(Intent(this, ProviderActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.eta_slide_in, 0)
    }

    private fun promptWorkspace() {
        val current = ProotRuntime.currentWorkdir(this)
        picker(
            "工作目录",
            "当前：$current\n\nAgent 能读写这个目录，以及 /storage/emulated/0 下的其他位置。",
            listOf("使用手机存储 /KimiCode", "保持当前目录"),
        ) { index ->
            if (index == 0) {
                Prefs.setWorkspace(this, "/storage/emulated/0/KimiCode")
                Toast.makeText(this, "下次启动生效", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun buildDrawer() {
        scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#66101018"))
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = DshUi.fill("#FDFDFF", 0f).apply {
                cornerRadii = floatArrayOf(0f, 0f, dpf(26f), dpf(26f), dpf(26f), dpf(26f), 0f, 0f)
            }
            elevation = dp(18f).toFloat()
            setPadding(dp(14f), statusBarHeight() + dp(12f), dp(14f), dp(16f))
            translationX = -dp(360f).toFloat()
            visibility = View.GONE
        }
        val width = (resources.displayMetrics.widthPixels * 0.86f).toInt().coerceAtMost(dp(340f))
        root.addView(drawer, FrameLayout.LayoutParams(width, -1))

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), 0, 0, dp(14f))
        }
        val mark = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34f), dp(34f))
            background = DshUi.fillInt(Color.parseColor(DshUi.PRIMARY), 11f)
            addView(DshUi.text(this@HomeActivity, "K", 17f, "#FFFFFF", true).apply {
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        brand.addView(mark)
        brand.addView(DshUi.gap(this, 10f))
        brand.addView(DshUi.text(this, "Kimi Code", 19f, DshUi.TEXT, true))
        brand.addView(LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        brand.addView(DshUi.circleButton(this, DshIcon.SWAP, 36f, tint = DshUi.PRIMARY) { openWeb(null, null) })
        brand.addView(DshUi.gap(this, 8f))
        brand.addView(DshUi.circleButton(this, DshIcon.CLOSE, 36f) { closeDrawer() })
        drawer.addView(brand)

        drawer.addView(
            DshUi.pillButton(this, "新对话", true, DshIcon.PLUS) { openWeb(null, null) },
            LinearLayout.LayoutParams(-1, dp(38f)),
        )
        drawer.addView(DshUi.spacer(this, 12f))

        val search = DshUi.searchField(this, "搜索对话") { value ->
            query = value
            renderSessionList()
        }
        drawer.addView(DshUi.withSearchIcon(this, search), LinearLayout.LayoutParams(-1, -2))
        drawer.addView(DshUi.spacer(this, 10f))

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("全部", "运行中", "待确认", "未读").forEachIndexed { index, label ->
            if (index > 0) chips.addView(DshUi.gap(this, 6f))
            chips.addView(DshUi.chip(this, label, filter == index) {
                filter = index
                selectTab(0)
                buildDrawerList()
            })
        }
        drawer.addView(chips)
        drawer.addView(DshUi.spacer(this, 8f))

        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        drawerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(drawerList, ViewGroup.LayoutParams(-1, -2))
        drawer.addView(scroll)

        drawer.addView(DshUi.divider(this, 4f))
        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), dp(10f), dp(2f), 0)
        }
        foot.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7f), dp(7f))
            background = DshUi.fillInt(
                Color.parseColor(if (Runtime.phase == Runtime.Phase.READY) DshUi.GREEN else DshUi.MUTED), 4f,
            )
        })
        foot.addView(DshUi.gap(this, 8f))
        val statusColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        statusColumn.addView(DshUi.text(this, "本机", 13.5f, DshUi.TEXT, true))
        statusColumn.addView(DshUi.text(this, if (Runtime.phase == Runtime.Phase.READY) "运行中" else Runtime.title, 11f, DshUi.MUTED))
        foot.addView(statusColumn)
        foot.addView(DshUi.circleButton(this, DshIcon.PLUGIN, 34f) {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PAGE, MainActivity.PAGE_PLUGINS))
        })
        foot.addView(DshUi.gap(this, 6f))
        foot.addView(DshUi.circleButton(this, DshIcon.SETTINGS, 34f) {
            closeDrawer(); selectTab(2)
        })
        drawer.addView(foot)
        buildDrawerList()
    }

    private fun buildDrawerList() {
        if (this::drawerList.isInitialized.not()) return
        drawerList.removeAllViews()
        val snap = snapshot
        if (snap == null) {
            drawerList.addView(DshUi.text(this, "还没有会话", 13f, DshUi.MUTED).apply {
                setPadding(dp(8f), dp(14f), 0, 0)
            })
            return
        }
        val names = snap.workspaces.associate { it.id to it.name }
        val byWorkspace = snap.sessions.groupBy { names[it.workspaceId] ?: "工作区" }
        byWorkspace.forEach { (workspace, sessions) ->
            val folder = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f), dp(10f), 0, dp(4f))
            }
            folder.addView(DshIcon.view(this, DshIcon.FOLDER, Color.parseColor("#B08A4F"), 16, 1.6f))
            folder.addView(DshUi.gap(this, 8f))
            folder.addView(DshUi.text(this, workspace, 12.5f, DshUi.MUTED, true))
            folder.addView(LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            folder.addView(DshUi.text(this, sessions.size.toString(), 11.5f, DshUi.MUTED))
            drawerList.addView(folder)
            sessions.take(8).forEach { session ->
                drawerList.addView(
                    DshUi.sessionRow(
                        this, session.title,
                        SessionRepository.relative(session.updatedAt),
                        session.busy, session.pending, false,
                        onClick = { closeDrawer(); openWeb(session.id, session.title) },
                    ),
                    LinearLayout.LayoutParams(-1, -2),
                )
            }
        }
    }

    private fun openDrawer() {
        drawerOpen = true
        scrim.visibility = View.VISIBLE
        drawer.visibility = View.VISIBLE
        drawer.animate().translationX(0f).setDuration(220).start()
        scrim.animate().alpha(1f).setDuration(220).start()
        buildDrawerList()
    }

    private fun closeDrawer() {
        drawerOpen = false
        drawer.animate().translationX(-drawer.width.toFloat()).setDuration(190).withEndAction {
            drawer.visibility = View.GONE
        }.start()
        scrim.animate().alpha(0f).setDuration(190).withEndAction {
            scrim.visibility = View.GONE
        }.start()
    }

    // ---------------------------------------------------------------- engine

    private fun maybeStartEngine() {
        if (Paths.isRuntimeInstalled(this) && !ProotRuntime.isRunning() && Runtime.phase == Runtime.Phase.IDLE) {
            RuntimeService.start(this)
        } else if (Runtime.phase == Runtime.Phase.READY) {
            loadSessions()
        }
    }

    private fun loadSessions() {
        if (loading) return
        if (Runtime.phase != Runtime.Phase.READY || Runtime.port == 0) return
        loading = true
        SessionRepository.load(Runtime.port, ProotRuntime.readToken(this)) { snap ->
            loading = false
            if (snap != null) {
                snapshot = snap
                loadRetries = 0
            } else if (loadRetries < 3) {
                // 服务刚 READY 时 token 文件可能还没落盘，隔 1.5 秒再试。
                loadRetries++
                pages.postDelayed({ loadSessions() }, 1500)
            }
            renderSessionList()
            buildDrawerList()
        }
    }

    private fun openWeb(sessionId: String?, sessionTitle: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_WEB, true)
            sessionId?.let { putExtra(MainActivity.EXTRA_SESSION_ID, it) }
            sessionTitle?.let { putExtra(MainActivity.EXTRA_SESSION_TITLE, it) }
        }
        startActivity(intent)
    }

    @Suppress("unused")
    internal fun selectTabForTest(index: Int) = selectTab(index)

    @Suppress("unused")
    internal fun openDrawerForTest() = openDrawer()

    private fun navigationBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun statusBarHeight(): Int = DshUi.statusBarHeight(this)

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
    private fun dpf(value: Float): Float = value * resources.displayMetrics.density
}
