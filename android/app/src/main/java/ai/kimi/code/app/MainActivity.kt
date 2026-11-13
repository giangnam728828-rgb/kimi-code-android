package ai.kimi.code.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Four bottom tabs (home / plugins / settings / terminal) plus the workspace,
 * which is a full-screen WebView carrying the bearer token so the web UI never
 * asks for it. Returning from the workspace is a horizontal swipe from the left
 * edge or the hardware back key — no floating button.
 */
class MainActivity : Activity() {

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnPrimary: Button
    private lateinit var btnRestart: Button
    private lateinit var btnStop: Button
    private lateinit var valueAddress: TextView
    private lateinit var valueLan: TextView
    private lateinit var valueLanToggle: TextView
    private lateinit var valueModel: TextView
    private lateinit var valueWorkspace: TextView
    private lateinit var logContainer: ScrollView
    private lateinit var logText: TextView
    private lateinit var pageTitle: TextView
    private lateinit var btnOpenWeb: TextView

    private lateinit var pageHome: View
    private lateinit var pagePlugins: View
    private lateinit var pageSettings: View
    private lateinit var pageTerminal: View
    private lateinit var pageData: View
    private lateinit var shell: View
    private lateinit var pluginList: LinearLayout
    private lateinit var termOutput: TextView
    private lateinit var termScroll: ScrollView
    private lateinit var termInput: EditText

    private lateinit var webContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var webLoading: ProgressBar
    private lateinit var webErrorBox: View
    private lateinit var webErrorTitle: TextView
    private lateinit var webErrorDetail: TextView

    private lateinit var navItems: List<Pair<View, Pair<TextView, TextView>>>

    private var loadedUrl: String? = null
    private var workspaceVisible = false
    /** True right after WebView.restoreState: an extra loadUrl would discard the page (and its DOM). */
    private var webViewRestored = false
    private var currentPage = R.id.pageHome
    private var lastExternalUrl: String? = null
    private var lastExternalAt = 0L

    // --- rendering caches: keep render() cheap when proot floods the log ---
    private var lastRenderedLogVersion = -1
    private var cachedLocalIp: String? = null
    private var lastKeepAwake: Boolean? = null
    private var cachedDataSize: Long? = null
    private var dataSizeInFlight = false
    /** Popup probe WebView from window.open; reused so it never accumulates. */
    private var probeWebView: WebView? = null

    // --- 2.18.0 附件：网页里的 <input type=file> 走这里 -------------------
    /** 网页发起的文件选择，结果必须回给它；为空表示当前没有进行中的选择。 */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    /** 拍照时相机的输出 URI，onActivityResult 里回传给网页。 */
    private var cameraOutputUri: Uri? = null
    /** 等 CAMERA 权限期间挂起的回调，授权后继续拍照。 */
    private var pendingCaptureCallback: ValueCallback<Array<Uri>>? = null
    /** 长按图片「保存到相册」时解码出的临时位图，避免重复下载。 */
    private var lastImageSaveUrl: String? = null

    private val listener: () -> Unit = { runOnUiThread { render() } }

    private companion object {
        const val REQUEST_PICK_BACKUP = 3001
        const val REQUEST_FILE_CHOOSER = 3002
        const val REQUEST_CAPTURE_IMAGE = 3003
        const val REQUEST_CAMERA_PERMISSION = 1002
        const val KEY_WEB_STATE = "web_view_state"

        val IMAGE_EXTENSIONS = setOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic", ".heif", ".bmp")

        /**
         * WebView state must also survive a user-initiated finish() + relaunch,
         * where onSaveInstanceState is not delivered. Process-local by design:
         * if the process itself dies, localStorage on the pinned origin is the
         * fallback.
         */
        var savedWebViewState: Bundle? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupWebView()
        restoreWebViewState(savedInstanceState)
        setupNavigation()
        setupSettingsPage()
        setupTerminal()
        setupPlugins()
        setupDataPage()
        requestStoragePermission()

        Runtime.addListener(listener)
        render()

        if (Paths.isRuntimeInstalled(this) && !ProotRuntime.isRunning() && Runtime.phase == Runtime.Phase.IDLE) {
            RuntimeService.start(this)
        }
    }

    private fun bindViews() {
        statusDot = findViewById(R.id.statusDot)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        progress = findViewById(R.id.progress)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnRestart = findViewById(R.id.btnRestart)
        btnStop = findViewById(R.id.btnStop)
        valueAddress = findViewById(R.id.valueAddress)
        valueLan = findViewById(R.id.valueLan)
        valueLanToggle = findViewById(R.id.valueLanToggle)
        valueModel = findViewById(R.id.valueModel)
        valueWorkspace = findViewById(R.id.valueWorkspace)
        logContainer = findViewById(R.id.logContainer)
        logText = findViewById(R.id.logText)
        pageTitle = findViewById(R.id.pageTitle)
        btnOpenWeb = findViewById(R.id.btnOpenWeb)

        pageHome = findViewById(R.id.pageHome)
        pagePlugins = findViewById(R.id.pagePlugins)
        pageSettings = findViewById(R.id.pageSettings)
        pageTerminal = findViewById(R.id.pageTerminal)
        pageData = findViewById(R.id.pageData)
        shell = findViewById(R.id.shell)
        pluginList = findViewById(R.id.pluginList)
        termOutput = findViewById(R.id.termOutput)
        termScroll = findViewById(R.id.termScroll)
        termInput = findViewById(R.id.termInput)

        webContainer = findViewById(R.id.webContainer)
        webView = findViewById(R.id.webView)
        webLoading = findViewById(R.id.webLoading)
        webErrorBox = findViewById(R.id.webErrorBox)
        webErrorTitle = findViewById(R.id.webErrorTitle)
        webErrorDetail = findViewById(R.id.webErrorDetail)
        findViewById<View>(R.id.webErrorRetry).setOnClickListener { reloadWorkspace() }

        btnPrimary.setOnClickListener { onPrimaryAction() }
        btnRestart.setOnClickListener {
            Runtime.log("用户请求重启")
            // The port is pinned, so the URL survives a restart. Do not clear
            // loadedUrl: a forced reload used to drop the last reply.
            RuntimeService.restart(this)
        }
        btnStop.setOnClickListener {
            Runtime.log("用户请求停止")
            RuntimeService.stop(this)
        }
        findViewById<View>(R.id.rowAddress).setOnClickListener { copyAddress() }
        findViewById<View>(R.id.rowLan).setOnClickListener { toggleLan() }
        findViewById<View>(R.id.rowModel).setOnClickListener { openProviderPage() }
        // 工作台顶栏右上角「模型供应商」按钮：直接从聊天入口管理模型。
        findViewById<View>(R.id.btnProviderEntry).setOnClickListener { openProviderPage() }
        findViewById<View>(R.id.rowWorkspace).setOnClickListener { promptWorkspace() }
        findViewById<View>(R.id.btnSaveLog).setOnClickListener { saveLog() }
        btnOpenWeb.setOnClickListener { openWorkspace() }
    }

    // ---------------------------------------------------------------- navigation

    private fun setupNavigation() {
        navItems = listOf(
            findViewById<View>(R.id.navHome) to
                (findViewById<TextView>(R.id.iconHome) to findViewById<TextView>(R.id.labelHome)),
            findViewById<View>(R.id.navPlugins) to
                (findViewById<TextView>(R.id.iconPlugins) to findViewById<TextView>(R.id.labelPlugins)),
            findViewById<View>(R.id.navSettings) to
                (findViewById<TextView>(R.id.iconSettings) to findViewById<TextView>(R.id.labelSettings)),
            findViewById<View>(R.id.navData) to
                (findViewById<TextView>(R.id.iconData) to findViewById<TextView>(R.id.labelData)),
            findViewById<View>(R.id.navTerminal) to
                (findViewById<TextView>(R.id.iconTerminal) to findViewById<TextView>(R.id.labelTerminal)),
        )
        val targets = listOf(
            R.id.navHome to R.id.pageHome,
            R.id.navPlugins to R.id.pagePlugins,
            R.id.navSettings to R.id.pageSettings,
            R.id.navData to R.id.pageData,
            R.id.navTerminal to R.id.pageTerminal,
        )
        targets.forEach { (navId, pageId) ->
            findViewById<View>(navId).setOnClickListener { switchPage(pageId) }
        }
    }

    /** 2.16.0：Eta 风格的模型供应商两级管理页。2.16.2 加统一转场动效。 */
    private fun openProviderPage() {
        startActivity(Intent(this, ProviderActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.eta_slide_in, 0)
    }

    private fun switchPage(pageId: Int) {
        // 2.16.1：底部「设置」与顶栏「模型供应商」按钮都进 Eta 风格供应商页。
        // 旧设置页（模型商编辑器 / 多选弹窗 / 手动输入 / 高级选项）彻底不再
        // 展示，避免两套界面并存让用户觉得繁琐。App 偏好开关已搬进供应商页。
        if (pageId == R.id.pageSettings) {
            openProviderPage()
            return
        }
        currentPage = pageId
        pageHome.visibility = if (pageId == R.id.pageHome) View.VISIBLE else View.GONE
        pagePlugins.visibility = if (pageId == R.id.pagePlugins) View.VISIBLE else View.GONE
        pageSettings.visibility = if (pageId == R.id.pageSettings) View.VISIBLE else View.GONE
        pageTerminal.visibility = if (pageId == R.id.pageTerminal) View.VISIBLE else View.GONE
        pageData.visibility = if (pageId == R.id.pageData) View.VISIBLE else View.GONE
        hideWorkspace()
        // 2.16.2 全 App 动效：切页时目标页淡入上移，和弹层/列表同一套节奏
        EtaMotion.enter(this, when (pageId) {
            R.id.pagePlugins -> pagePlugins
            R.id.pageTerminal -> pageTerminal
            R.id.pageData -> pageData
            else -> pageHome
        }, fromDp = 12f)

        val pages = listOf(
            R.id.pageHome,
            R.id.pagePlugins,
            R.id.pageSettings,
            R.id.pageData,
            R.id.pageTerminal,
        )
        navItems.forEachIndexed { index, (container, texts) ->
            val active = pages[index] == pageId
            val color = if (active) getColor(R.color.accent) else getColor(R.color.text_muted)
            texts.first.setTextColor(color)
            texts.second.setTextColor(color)
            texts.second.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }

        pageTitle.text = getString(
            when (pageId) {
                R.id.pagePlugins -> R.string.nav_plugins
                R.id.pageSettings -> R.string.nav_settings
                R.id.pageData -> R.string.nav_data
                R.id.pageTerminal -> R.string.nav_terminal
                else -> R.string.app_name
            }
        )
        if (pageId == R.id.pageSettings) loadSettingsIntoUi()
        if (pageId == R.id.pagePlugins) renderPlugins()
        if (pageId == R.id.pageData) renderDataPage()
    }

    // ---------------------------------------------------------------- home page

    private fun onPrimaryAction() {
        when (Runtime.phase) {
            Runtime.Phase.READY -> openWorkspace()
            else -> {
                if (!Paths.isRuntimeInstalled(this)) Runtime.log("开始安装运行环境")
                RuntimeService.start(this)
            }
        }
    }

    private fun copyAddress() {
        if (Runtime.phase != Runtime.Phase.READY || Runtime.port == 0) {
            toast("服务尚未运行")
            return
        }
        val text = "http://${localIp()}:${Runtime.port}/"
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("Kimi Code", text))
        toast("已复制：$text")
    }

    private fun toggleLan() {
        val next = !Prefs.lan(this)
        Prefs.setLan(this, next)
        Runtime.log(if (next) "已开启局域网访问，重启后生效" else "已关闭局域网访问，重启后生效")
        toast(if (next) "已开启，重启服务后生效" else "已关闭，重启服务后生效")
        render()
    }

    /**
     * 2.17.0：「工作目录与自主执行」。
     *
     * 用户反馈手机上 AI 不会自己动文件 / 跑构建。根因有两层：
     * 1. 工作目录默认在容器内（/root/workspace），改的不是手机上的文件；
     * 2. 网页默认权限模式是 manual，每次工具调用都要手动批准。
     * 这里一次把两件事都交给用户：目录指到共享存储，权限切到全自主，并给出
     * 一键环境自检 / 安装 git、python3 的入口。
     */
    private fun promptWorkspace() {
        EtaSheet.show(this, "工作目录与自主执行", tall = true) { sheet ->
            sheet.body.addView(TextView(this).apply {
                text = "Agent 读写文件、执行命令都发生在这个目录。改成 " +
                    "/storage/emulated/0/KimiCode 就能直接操作手机文件 —— 这会开启一个" +
                    "新工作区，旧会话仍留在原来的目录里。"
                textSize = 13f
                setTextColor(getColor(R.color.text_muted))
                setPadding(0, 0, 0, dp(12))
            })
            val (pathBox, pathInput) = EtaSheet.field(
                this, "工作目录", Prefs.workspace(this),
                "/storage/emulated/0/KimiCode",
            )
            sheet.body.addView(pathBox, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) })

            val switchCard = EtaUi.card(this, 16)
            val autonomy = android.widget.Switch(this).apply {
                text = "全自主执行（自动批准工具调用）"
                textSize = 15f
                isChecked = Prefs.autonomy(this@MainActivity)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            switchCard.addView(autonomy)
            sheet.body.addView(switchCard, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) })
            sheet.body.addView(TextView(this).apply {
                text = "开启后，读文件 / 写文件 / 跑命令不再逐条询问；关闭则恢复" +
                    "「每次都要点批准」。"
                textSize = 12f
                setTextColor(getColor(R.color.text_muted))
                setPadding(dp(4), 0, dp(4), dp(14))
            })

            // 登录自检：账号掉了时先看这两项，能区分「凭据文件没了」还是
            // 「config 里的 oauth 段被覆盖了」，不用猜。
            val credDir = File(File(Paths.rootfs(this), "root/.kimi-code"), "credentials")
            val credCount = credDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.size ?: 0
            val configText = runCatching { ConfigWriter.configFile(this).readText() }.getOrDefault("")
            val oauthRefs = Regex("(?m)^\\s*oauth\\s*=").findAll(configText).count()
            sheet.body.addView(TextView(this).apply {
                text = "登录自检：凭据文件 $credCount 个 · config 内 oauth 引用 $oauthRefs 处" +
                    if (credCount == 0) "（未登录，或凭据文件被清理）" else ""
                textSize = 12f
                setTextColor(getColor(R.color.text_muted))
                setPadding(dp(4), 0, dp(4), dp(10))
            })

            val output = TextView(this).apply {
                text = "点下面的按钮检查容器里的 Node / Git / Python 是否可用。"
                textSize = 11.5f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(getColor(R.color.text_primary))
                setTextIsSelectable(true)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = EtaUi.rounded(EtaUi.FIELD, 14)
            }
            val outputScroll = ScrollView(this).apply {
                addView(output)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(150),
                ).apply { bottomMargin = dp(12) }
            }
            sheet.body.addView(outputScroll)

            fun runScript(label: String, script: String) {
                output.text = "[$label] 执行中…\n"
                Thread {
                    val process = runCatching { ProotRuntime.shell(this, script) }.getOrNull()
                    if (process == null) {
                        runOnUiThread { output.append("\n无法启动容器命令") }
                        return@Thread
                    }
                    runCatching {
                        BufferedReader(java.io.InputStreamReader(process.inputStream)).use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                runOnUiThread {
                                    output.append(line + "\n")
                                    outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
                                }
                            }
                        }
                        val code = process.waitFor()
                        runOnUiThread { output.append("\n[结束] 退出码 $code") }
                    }
                }.apply { isDaemon = true }.start()
            }

            val check = EtaUi.primaryButton(this, "检查开发环境") {
                runScript(
                    "检查",
                    "cd \"\${KIMI_WORKDIR:-/root/workspace}\" && pwd && ls -la | head -15; " +
                        "echo; node -v; npm -v; git --version 2>&1 | head -1; " +
                        "python3 --version 2>&1 | head -1; make --version 2>&1 | head -1",
                )
            }
            sheet.body.addView(check, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })

            val install = EtaUi.primaryButton(this, "安装 git / python3 / make") {
                runScript(
                    "安装",
                    "export DEBIAN_FRONTEND=noninteractive; " +
                        "apt-get update -y && " +
                        "apt-get install -y --no-install-recommends git python3 make ca-certificates",
                )
            }
            sheet.body.addView(install, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })

            val (row, _) = EtaSheet.dualButtons(
                this, getString(R.string.action_cancel), "保存并重启",
                onCancel = { sheet.dismiss() },
                onConfirm = {},
            )
            (row as LinearLayout).getChildAt(1).setOnClickListener {
                val path = pathInput.text.toString().trim()
                val pathChanged = path != Prefs.workspace(this)
                Prefs.setWorkspace(this, path)
                Prefs.setAutonomy(this, autonomy.isChecked)
                WebBootInjector.invalidate()
                sheet.dismiss()
                render()
                if (pathChanged) {
                    RuntimeService.restart(this)
                    toast("已保存，正在按新目录重启")
                } else {
                    toast("已保存")
                }
            }
            sheet.footer.addView(row)
        }
    }

    private fun saveLog() {
        val body = Runtime.snapshotLogs().joinToString("\n")
        if (body.isBlank()) {
            toast("暂无日志")
            return
        }
        val file = File(Paths.logsDir(this).apply { mkdirs() }, "kimi-log.txt")
        val shared = File(File(filesDir, "shared").apply { mkdirs() }, "kimi-log.txt")
        runCatching {
            file.writeText(body)
            shared.writeText(body)
        }.onSuccess {
            val intent = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, LocalFileProvider.uriFor(this, shared))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(Intent.createChooser(intent, "保存日志")) }
            toast("日志已导出")
        }.onFailure { toast("保存失败：${it.message}") }
    }

    // ---------------------------------------------------------------- workspace

    /**
     * Restores the WebView's back/forward list after an Activity recreation.
     * Without this, leaving the workspace and coming back rebuilt the DOM and
     * any reply the web UI had not yet flushed to a session file disappeared.
     */
    private fun restoreWebViewState(savedInstanceState: Bundle?) {
        val state = savedInstanceState?.getBundle(KEY_WEB_STATE) ?: savedWebViewState ?: return
        val list = runCatching { webView.restoreState(state) }.getOrNull() ?: return
        if (list.size == 0) return
        webViewRestored = true
        // Keep the page's own URL (hash route included); RestartPolicy compares
        // origins, so a different hash will not trigger a redundant reload.
        loadedUrl = runCatching { list.currentItem?.url }.getOrNull()
    }

    /** Saves WebView state both into the instance bundle and a process-local slot. */
    private fun captureWebViewState(): Bundle? {
        if (!::webView.isInitialized) return null
        val bundle = Bundle()
        val list = runCatching { webView.saveState(bundle) }.getOrNull() ?: return null
        if (list.size == 0) return null
        savedWebViewState = bundle
        return bundle
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        // 2.18.0：附件上传。文件选择器回传的是 content:// URI，这两项一旦关掉，
        // 网页即使拿到 File 也读不出字节，表现就是「选了文件没反应」。页面本身
        // 只加载 127.0.0.1 的工作台，不存在把远程页面放进来的风险。
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        settings.builtInZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // 2.16.1：登录态保活。WebView 的 cookie 是异步落盘的，覆盖安装/切后台
        // 时进程被系统杀掉可能来不及写盘，表现就是「Kimi 账号掉了」。这里显式
        // 允许 cookie 并在 onPause 强制 flush；localStorage 仍按同源保留。
        settings.saveFormData = true
        runCatching {
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(webView, true)
        }
        // 2.16.0 流畅性：本地工作台的静态资源复用缓存（LOAD_DEFAULT 已覆盖），
        // 开启硬件加速渲染层，滚动与输入更跟手。不动 localStorage，登录态与
        // 聊天记录依旧按同源保留。
        settings.loadsImagesAutomatically = true
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.setBackgroundColor(Color.WHITE)
        // The web UI opens the Kimi sign-in page with `window.open`, and the
        // sign-in page itself is an external origin. Loading either of those in
        // this WebView replaces the workspace with a page this app cannot render,
        // which shows up as a blank screen. Popups are therefore routed to the
        // content owner below and never into the workspace WebView.
        settings.setSupportMultipleWindows(true)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                // The popup URL is not on the message, so hand the transport a
                // throwaway WebView whose only job is to report that first URL.
                // Reuse a single probe: the old implementation allocated a
                // WebView per popup and never destroyed it (leak on sign-in).
                probeWebView?.let { old ->
                    runCatching { old.post { old.destroy() } }
                }
                val probe = WebView(this@MainActivity)
                probeWebView = probe
                probe.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean = openExternally(request?.url?.toString())

                    @Deprecated("Superseded by the WebResourceRequest overload")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                        openExternally(url)

                    // Some popups start from `about:blank` and only navigate later;
                    // this catches the ones `shouldOverrideUrlLoading` misses.
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (url == null || url.startsWith("about:")) return
                        openExternally(url)
                    }
                }
                transport.webView = probe
                resultMsg.sendToTarget()
                return true
            }

            /**
             * 2.18.0：附件按钮的真正落点。对话输入框里有一个隐藏的
             * `<input type="file" multiple>`（`composer-attach`），WebView 不实现
             * 这个回调就会把点击静默吞掉 —— 这正是「点上传没反应、发不了文档和
             * 图片」的根因。返回 true 表示我们自己处理，网页会等 callback。
             */
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?,
            ): Boolean {
                if (callback == null) return false
                // 上一次没走完的选择先取消，避免回调堆积导致后面的点击全哑。
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                val accept = params?.acceptTypes
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()
                val wantsImage = accept.any { it.startsWith("image/") } ||
                    accept.any { it.lowercase() in IMAGE_EXTENSIONS }
                // capture 只对图片有意义；有些页面会带 capture 但仍想从相册选，
                // 这里优先按「拍照」处理，失败再退回文件选择器。
                if (params?.isCaptureEnabled == true && wantsImage) {
                    return startCameraCapture()
                }
                return openDocumentPicker(accept)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            /**
             * 把界面增强注进同源的 `boot.js`（页面初始化前生效，CSP 允许）：
             * 模型选择弹层按供应商分组；「全自主执行」打开时再补上权限模式。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.path?.trimEnd('/')?.endsWith("boot.js") != true) return null
                if (url.host != "127.0.0.1" && url.host != "localhost") return null
                val script = runCatching {
                    WebBootInjector.pageScript(
                        Runtime.port,
                        ProotRuntime.readToken(this@MainActivity),
                        Prefs.autonomy(this@MainActivity),
                    )
                }.getOrNull() ?: return null
                return WebResourceResponse(
                    "application/javascript", "UTF-8", 200, "OK",
                    mapOf("Cache-Control" to "no-store"),
                    WebBootInjector.stream(script),
                )
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val uri = request?.url ?: return false
                return handleNavigation(uri)
            }

            @Deprecated("Superseded by the WebResourceRequest overload")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val uri = url?.let(Uri::parse) ?: return false
                return handleNavigation(uri)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webLoading.visibility = View.GONE
                // 2.18.1：工作区是网页打开时才登记的，服务刚就绪那一刻还查不到。
                // 页面加载完成后补一次，把「信任 + 手机存储附加目录」落实。
                if (Runtime.phase == Runtime.Phase.READY && Runtime.port > 0) {
                    WorkspaceBootstrap.ensureTrusted(this@MainActivity, Runtime.port)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                // Only the top document matters; sub-resource failures are noise.
                if (request?.isForMainFrame != true) return
                val detail = error?.description?.toString().orEmpty()
                showWebError(detail.ifEmpty { "页面加载失败" })
            }
        }
        // Swipe from the left edge to go back to the launcher, same as DSHA.
        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = kotlin.math.abs(e2.y - e1.y)
                val fromEdge = e1.x < dp(48)
                if (!Prefs.swipeBack(this@MainActivity)) return false
                if (fromEdge && dx > dp(90) && dy < dp(120)) {
                    handleWorkspaceBack()
                    return true
                }
                return false
            }
        })
        webView.setOnTouchListener { _, event ->
            gesture.onTouchEvent(event)
            false
        }
        // 2.18.0：网页「导出 / 下载」的落点。http(s) 交给系统下载器，
        // blob:（前端导出配置、会话 Markdown 用的就是它）走 JS 桥转 base64。
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }
        webView.addJavascriptInterface(HostBridge(), "KimiHost")
    }

    // ------------------------------------------------------------ 附件与下载

    /**
     * 打开系统文件选择器。`accept` 里可能混着 MIME（`image/*`）和扩展名
     * （`.json`）。只给扩展名时不能把 type 直接设成它 —— 国产文件管理器会把
     * 不匹配的文件全部置灰，用户以为「点了没反应」。这种情况退回 `*/*`。
     */
    private fun openDocumentPicker(accept: List<String>): Boolean {
        val mimeTypes = accept.filter { it.contains("/") && it != "*/*" }
        val hasExtensionOnly = accept.any { it.startsWith(".") }
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when {
                mimeTypes.size == 1 && !hasExtensionOnly -> mimeTypes[0]
                else -> "*/*"
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
        }
        return runCatching {
            startActivityForResult(Intent.createChooser(intent, "选择文件"), REQUEST_FILE_CHOOSER)
            true
        }.getOrElse {
            Runtime.log("无法打开文件选择器：${it.message}")
            finishFileChooser(null)
            toast("没有可用的文件管理器")
            false
        }
    }

    /** 拍照上传：需要 CAMERA 权限和一个可写的 content:// 输出 URI。 */
    private fun startCameraCapture(): Boolean {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCaptureCallback = fileChooserCallback
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
            return true
        }
        val file = LocalFileProvider.newCameraFile(this)
        val uri = LocalFileProvider.cameraUri(this, file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) == null) {
            // 没有相机 App：退回文件选择器，至少还能从相册挑图。
            return openDocumentPicker(listOf("image/*"))
        }
        return runCatching {
            cameraOutputUri = uri
            startActivityForResult(intent, REQUEST_CAPTURE_IMAGE)
            true
        }.getOrElse {
            cameraOutputUri = null
            Runtime.log("无法启动相机：${it.message}")
            finishFileChooser(null)
            toast("无法启动相机")
            false
        }
    }

    /** 把结果交还给网页，并清空所有挂起状态。 */
    private fun finishFileChooser(uris: Array<Uri>?) {
        val callback = fileChooserCallback
        fileChooserCallback = null
        pendingCaptureCallback = null
        cameraOutputUri = null
        runCatching { callback?.onReceiveValue(uris) }
    }

    /**
     * 下载分三类：
     * - `http(s)`：本地服务生成的文件（或外链），交给 DownloadManager 落盘；
     * - `blob:`：前端 `URL.createObjectURL` 的结果，DownloadManager 存不了，
     *   让页面 fetch 回二进制再 base64 交回来；
     * - `data:`：直接解码。
     */
    private fun handleDownload(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        if (url.isNullOrBlank()) return
        val name = guessFileName(url, contentDisposition, mimeType)
        when {
            url.startsWith("blob:", true) -> {
                toast("正在准备下载…")
                webView.evaluateJavascript(WebBootInjector.blobFetchScript(url, name), null)
            }
            url.startsWith("data:", true) -> {
                Thread {
                    runCatching {
                        val bytes = decodeDataUrl(url) ?: error("无法解析 data URL")
                        val file = writeDownload(name, bytes)
                        runOnUiThread { toast("已保存到 下载/KimiCode/${file.name}") }
                    }.onFailure {
                        runOnUiThread { toast("保存失败：${it.message}") }
                    }
                }.start()
            }
            else -> {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    mimeType?.takeIf { it.isNotBlank() }?.let { setMimeType(it) }
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "KimiCode/$name")
                    userAgent?.takeIf { it.isNotBlank() }?.let { addRequestHeader("User-Agent", it) }
                    // 工作台的静态与导出接口都要 bearer token；只对本地回环发，
                    // 免得不小心把凭据带给外链。
                    val host = runCatching { Uri.parse(url).host }.getOrNull()
                    if (host == "127.0.0.1" || host == "localhost") {
                        ProotRuntime.readToken(this@MainActivity)?.let {
                            addRequestHeader("Authorization", "Bearer $it")
                        }
                        CookieManager.getInstance().getCookie(url)?.let {
                            addRequestHeader("Cookie", it)
                        }
                    }
                }
                runCatching {
                    val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    manager.enqueue(request)
                    toast("已开始下载，保存在 下载/KimiCode/")
                }.onFailure {
                    Runtime.log("下载失败：${it.message}")
                    toast("下载失败：${it.message}")
                }
            }
        }
    }

    private fun decodeDataUrl(url: String): ByteArray? {
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val meta = url.substring(5, comma)
        val payload = url.substring(comma + 1)
        return if (meta.contains("base64", true)) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            java.net.URLDecoder.decode(payload, "UTF-8").toByteArray()
        }
    }

    /** 写入 `下载/KimiCode/`；公共目录不可写时退回应用外部目录。 */
    private fun downloadDir(): File {
        val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(public, "KimiCode")
        if (dir.exists() || dir.mkdirs()) return dir
        val fallback = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        return File(fallback, "KimiCode").apply { mkdirs() }
    }

    private fun writeDownload(name: String, bytes: ByteArray): File {
        val dir = downloadDir()
        var file = File(dir, name)
        if (file.exists()) {
            val stem = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            var index = 2
            while (file.exists()) {
                val candidate = if (ext.isEmpty()) "$stem-$index" else "$stem-$index.$ext"
                file = File(dir, candidate)
                index++
            }
        }
        file.writeBytes(bytes)
        return file
    }

    private fun guessFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        val fromHeader = contentDisposition
            ?.substringAfter("filename=", "")
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotEmpty() }
        if (fromHeader != null) return sanitizeFileName(fromHeader)
        val fromUrl = runCatching {
            Uri.parse(url).lastPathSegment?.substringAfterLast('/')
        }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("blob:") }
        if (fromUrl != null) return sanitizeFileName(java.net.URLDecoder.decode(fromUrl, "UTF-8"))
        val ext = when {
            mimeType?.contains("json") == true -> "json"
            mimeType?.startsWith("image/jpeg") == true -> "jpg"
            mimeType?.startsWith("image/png") == true -> "png"
            mimeType?.contains("markdown") == true -> "md"
            mimeType?.startsWith("text/") == true -> "txt"
            else -> "bin"
        }
        return "kimi-${System.currentTimeMillis()}.$ext"
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), "_").take(120).ifBlank { "download" }

    /**
     * 页面侧调用的桥。只暴露「保存一段 base64 到下载目录」这一件事，
     * 不碰文件系统其它位置。
     */
    inner class HostBridge {
        @JavascriptInterface
        fun saveBase64(name: String?, mime: String?, base64: String?) {
            if (base64.isNullOrEmpty()) return
            Thread {
                runCatching {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val file = writeDownload(sanitizeFileName(name ?: "download"), bytes)
                    runOnUiThread { toast("已保存到 下载/KimiCode/${file.name}") }
                }.onFailure {
                    runOnUiThread { toast("保存失败：${it.message}") }
                }
            }.start()
        }
    }

    private fun openWorkspace() {
        if (Runtime.phase != Runtime.Phase.READY || Runtime.workspaceUrl.isEmpty()) {
            toast("服务还没就绪")
            return
        }
        workspaceVisible = true
        val url = Runtime.workspaceUrl
        val reload = forceReloadAfterRestart || RestartPolicy.shouldReloadWorkspace(
            loadedUrl,
            url,
            webViewRestored,
            webErrorBox.visibility == View.VISIBLE,
        )
        // A catalogue registration adds `[models.*]` sections; the server reads
        // them at startup, so the web page must fetch its model list once more.
        // One reload here (same pinned origin) keeps login and chats intact.
        forceReloadAfterRestart = false
        webViewRestored = false
        loadedUrl = url
        if (reload) reloadWorkspace()
        shell.visibility = View.GONE
        webContainer.visibility = View.VISIBLE
        applyImmersive(true)
    }

    private fun reloadWorkspace() {
        val url = Runtime.workspaceUrl
        if (url.isEmpty()) return
        webErrorBox.visibility = View.GONE
        webLoading.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    /**
     * Keeps the workspace itself in the WebView and sends everything else to the
     * app that owns it. This is what makes the Kimi sign-in work: the device-code
     * page belongs in a browser, and the workspace has to stay loaded so it can
     * pick the sign-in up when the user comes back.
     */
    private fun handleNavigation(uri: Uri): Boolean {
        val url = uri.toString()
        // The sign-in device flow, docs links and `mailto:` handoffs all belong
        // to another app; blob/data URLs stay with the WebView that made them.
        if (!LinkPolicy.shouldOpenExternally(url, Runtime.port)) return false
        return openExternally(url)
    }

    private fun openExternally(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        // `about:`, `blob:` and friends have nowhere to go; keep them quiet.
        if (!LinkPolicy.canHandOff(url)) return true
        // A popup can report the same target twice (cancel + started); one
        // browser launch per link is enough.
        val now = System.currentTimeMillis()
        if (url == lastExternalUrl && now - lastExternalAt < 1500) return true
        lastExternalUrl = url
        lastExternalAt = now
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK,
        )
        return runCatching {
            startActivity(intent)
            toast(getString(R.string.web_open_browser_hint))
            true
        }.getOrElse {
            Runtime.log("无法打开外部链接：$url")
            toast(getString(R.string.web_no_browser))
            true
        }
    }

    private fun showWebError(detail: String) {
        webLoading.visibility = View.GONE
        webErrorBox.visibility = View.VISIBLE
        webErrorTitle.text = getString(R.string.web_error_title)
        webErrorDetail.text = getString(R.string.web_error_body, detail)
        Runtime.log("工作台加载失败：$detail")
    }

    /**
     * Brings the launcher back. The shell was hidden entirely while the
     * workspace was open, so restoring it restores the tab the user left from.
     */
    private fun hideWorkspace() {
        workspaceVisible = false
        webContainer.visibility = View.GONE
        shell.visibility = View.VISIBLE
        pageHome.visibility = if (currentPage == R.id.pageHome) View.VISIBLE else View.GONE
        pagePlugins.visibility = if (currentPage == R.id.pagePlugins) View.VISIBLE else View.GONE
        pageSettings.visibility = if (currentPage == R.id.pageSettings) View.VISIBLE else View.GONE
        pageData.visibility = if (currentPage == R.id.pageData) View.VISIBLE else View.GONE
        pageTerminal.visibility = if (currentPage == R.id.pageTerminal) View.VISIBLE else View.GONE
        applyImmersive(false)
    }

    /**
     * The workspace is meant to read like the desktop web UI shrunk to a phone.
     * Keeping the status bar and the app's own title bar on screen would eat the
     * top of the page, so the whole viewport belongs to the web UI while it is
     * open. Swiping from the left edge brings the launcher back.
     */
    private fun applyImmersive(enabled: Boolean) {
        val window = window
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    // ---------------------------------------------------------------- settings

    private lateinit var modelList: LinearLayout
    private lateinit var modelListEmpty: TextView
    private lateinit var editorPanel: View
    private lateinit var editorTitle: TextView
    private lateinit var editorHint: TextView
    private lateinit var spinnerProvider: Spinner
    private lateinit var editAlias: EditText
    private lateinit var editBaseUrl: EditText
    private lateinit var editApiKey: EditText
    private lateinit var editModel: EditText
    private lateinit var editContext: EditText
    private lateinit var spinnerEffort: Spinner
    private lateinit var saveHint: TextView
    private lateinit var checkAutoEnter: CheckBox
    private lateinit var checkSwipeBack: CheckBox
    private lateinit var checkLan: CheckBox
    private lateinit var checkKeepAwake: CheckBox
    private lateinit var rowModelPick: View
    private lateinit var textModelPick: TextView
    private lateinit var textModelPickSub: TextView
    private lateinit var btnPickModel: TextView
    private lateinit var btnFetchModels: TextView
    private lateinit var btnProbeModel: TextView
    private lateinit var textModelStatus: TextView
    private lateinit var btnToggleAdvanced: TextView
    private lateinit var advancedPanel: View
    private lateinit var checkManualModel: CheckBox

    /** Index of the entry being edited, or null while adding a new one. */
    private var editingIndex: Int? = null

    /**
     * 正在编辑的分组里，除默认模型外额外登记的模型。
     * 2.15.0 起「选择」一次可勾选多个，全部保存在同一个分组内。
     */
    private var editingModels: MutableList<String> = mutableListOf()

    /** Set while a config-triggered restart is in flight, so we return to the workspace. */
    private var reenterAfterRestart = false

    /** Newly registered `[models.*]` sections only reach the web UI after one reload. */
    private var forceReloadAfterRestart = false

    /** Upstream model ids per endpoint, so reopening the picker is instant. */
    private val catalogCache = LinkedHashMap<String, List<String>>()
    /** Bumped per fetch; only the newest result may update the UI. */
    private var fetchGeneration = 0L
    /** One-shot upgrade heal: endpoints registered with a single model. */
    private var autoRegisterRunning = false
    private var autoRegisterAttempts = 0

    private fun catalogKey(baseUrl: String, apiKey: String, type: String): String =
        "$type|${baseUrl.trim()}|${apiKey.trim().hashCode()}"

    private fun setupSettingsPage() {
        modelList = findViewById(R.id.modelList)
        modelListEmpty = findViewById(R.id.modelListEmpty)
        editorPanel = findViewById(R.id.editorPanel)
        editorTitle = findViewById(R.id.editorTitle)
        editorHint = findViewById(R.id.editorHint)
        spinnerProvider = findViewById(R.id.spinnerProvider)
        editAlias = findViewById(R.id.editAlias)
        editBaseUrl = findViewById(R.id.editBaseUrl)
        editApiKey = findViewById(R.id.editApiKey)
        editModel = findViewById(R.id.editModel)
        editContext = findViewById(R.id.editContext)
        spinnerEffort = findViewById(R.id.spinnerEffort)
        saveHint = findViewById(R.id.saveHint)
        rowModelPick = findViewById(R.id.rowModelPick)
        textModelPick = findViewById(R.id.textModelPick)
        textModelPickSub = findViewById(R.id.textModelPickSub)
        btnPickModel = findViewById(R.id.btnPickModel)
        btnFetchModels = findViewById(R.id.btnFetchModels)
        btnProbeModel = findViewById(R.id.btnProbeModel)
        textModelStatus = findViewById(R.id.textModelStatus)
        btnToggleAdvanced = findViewById(R.id.btnToggleAdvanced)
        advancedPanel = findViewById(R.id.advancedPanel)
        checkManualModel = findViewById(R.id.checkManualModel)
        spinnerEffort.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Prefs.EFFORTS.map { it.second },
        )
        checkAutoEnter = findViewById(R.id.checkAutoEnter)
        checkSwipeBack = findViewById(R.id.checkSwipeBack)
        checkLan = findViewById(R.id.checkLan)
        checkKeepAwake = findViewById(R.id.checkKeepAwake)

        spinnerProvider.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Prefs.PRESETS.map { it.label },
        )
        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // While editing an existing entry the picker only mirrors that
                // entry; autofilling would wipe what the user already typed.
                val index = editingIndex
                if (index != null || position !in Prefs.PRESETS.indices) return
                val preset = Prefs.PRESETS[position]
                fillEditor(preset, suggestAlias(preset))
                catalogCache.clear()
                autoFetchModels()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        checkAutoEnter.setOnCheckedChangeListener { _, checked -> Prefs.setAutoEnter(this, checked) }
        checkSwipeBack.setOnCheckedChangeListener { _, checked -> Prefs.setSwipeBack(this, checked) }
        checkKeepAwake.setOnCheckedChangeListener { _, checked ->
            Prefs.setKeepAwake(this, checked)
            applyKeepAwake()
        }
        checkLan.setOnCheckedChangeListener { _, checked ->
            Prefs.setLan(this, checked)
            render()
        }

        findViewById<View>(R.id.btnAddModel).setOnClickListener { openProviderPage() }
        // Adding several models under one API key belongs in settings, not in a
        // floating chip over the WebView (that chip used to cover the page's
        // send button). The web UI's own model dropdown does the switching;
        // this dialog only edits the configuration once, up front.
        val modelCard = findViewById<View>(R.id.btnAddModel).parent as? LinearLayout
        // Primary action: register every model of this endpoint in one tap.
        modelCard?.addView(
            TextView(this).apply {
                text = getString(R.string.action_register_all_models)
                setTextColor(getColor(R.color.white))
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.btn_primary)
                setPadding(dp(10), dp(12), dp(10), dp(12))
                setOnClickListener { registerAllModels() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        // 2.16.0：主入口改为 Eta 风格两级页，不再让用户面对多选 dialog
        // + 手动输入 + 高级选项那一长串控件。
        modelCard?.addView(
            TextView(this).apply {
                text = getString(R.string.action_open_providers) + "  ›"
                setTextColor(getColor(R.color.white))
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.btn_primary)
                setPadding(dp(10), dp(13), dp(10), dp(13))
                setOnClickListener { openProviderPage() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        modelCard?.addView(
            TextView(this).apply {
                text = getString(R.string.provider_entry_hint)
                setTextColor(getColor(R.color.text_muted))
                textSize = 11f
                setPadding(dp(10), dp(8), dp(10), dp(12))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        rowModelPick.setOnClickListener { openModelPicker() }
        btnPickModel.setOnClickListener { openModelPicker() }
        btnFetchModels.setOnClickListener { fetchModels(showDialog = true) }
        btnProbeModel.setOnClickListener { probeModel() }
        btnToggleAdvanced.setOnClickListener {
            val expanded = advancedPanel.visibility != View.VISIBLE
            advancedPanel.visibility = if (expanded) View.VISIBLE else View.GONE
            btnToggleAdvanced.text = getString(
                if (expanded) R.string.action_advanced_hide else R.string.action_advanced,
            )
        }
        checkManualModel.setOnCheckedChangeListener { _, checked ->
            editModel.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) {
                editModel.requestFocus()
                setStatus(getString(R.string.model_status_manual))
            }
            updateModelPick()
        }
        // Keep the pick row in sync when the user types an id by hand.
        editModel.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { updateModelPick() }
        })
        editApiKey.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) autoFetchModels() }
        editBaseUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                catalogCache.clear()
                autoFetchModels()
            }
        }
        findViewById<Button>(R.id.btnConfirmEntry).setOnClickListener { confirmEntry() }
        findViewById<Button>(R.id.btnCancelEdit).setOnClickListener { closeEditor() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        findViewById<TextView>(R.id.textVersion).text =
            getString(R.string.about_version, appVersionName())
    }

    private fun loadSettingsIntoUi() {
        val entries = Prefs.load(this)
        val editing = editingIndex
        if (editing != null && editing >= entries.size) closeEditor()
        renderModelList()
        checkAutoEnter.isChecked = Prefs.autoEnter(this)
        checkSwipeBack.isChecked = Prefs.swipeBack(this)
        checkLan.isChecked = Prefs.lan(this)
        checkKeepAwake.isChecked = Prefs.keepAwake(this)
        saveHint.setTextColor(getColor(R.color.text_secondary))
        saveHint.text = getString(R.string.settings_hint)
    }

    private fun renderModelList() {
        // 2.16.2：设置页已被「模型供应商」页（ProviderActivity）整体取代，
        // 旧的「分组卡片 + 多选 + 手动输入」UI 不再可达。这里保留空实现，
        // 让散落各处的调用点保持原控制流，但不再每次回前台都重建几十个
        // 不可见的 View —— 既彻底去掉旧界面，也少一份主线程开销。
        modelList.removeAllViews()
        modelListEmpty.visibility = View.GONE
    }

    /**
     * 把某个模型设为该分组的默认模型；不同分组互不影响。
     *
     * 2.16.0：改为轻量热更新 —— 只改 config.toml 顶层的 `default_model` 一行，
     * 不重建 `[models.*]` 段（那正是 2.15.0 报错的根源），也不重启整个 proot
     * 环境。网页重新加载一次即可拿到新默认值，聊天记录与登录态都不受影响。
     */
    private fun switchModelInGroup(index: Int, id: String) {
        val entries = Prefs.load(this).toMutableList()
        val entry = entries.getOrNull(index) ?: return
        if (entry.model.equals(id, ignoreCase = true)) return
        entries[index] = entry.copy(model = id)
        Prefs.save(this, entries)
        val key = ConfigMerger.modelKey(entry.alias, id)
        val changed = runCatching { ConfigWriter.setDefaultModel(this, key, entry.contextSize) }
            .onFailure { toast("写入配置失败：${it.message}") }
            .getOrDefault(false)
        if (workspaceVisible && changed) reloadWorkspace()
        renderModelList()
        render()
        toast("「$id」已设为分组「${entry.alias}」的默认模型（无需重启）")
    }

    /**
     * True when we have fetched this endpoint's catalogue and the saved id is
     * not in it -- typically a display name typed by hand, which the gateway
     * rejects with "no category route found". Only flags when we actually have
     * a catalogue, so an unreachable endpoint never produces a false alarm.
     */
    private fun isUnknownToCatalog(entry: Prefs.Entry): Boolean {
        val baseUrl = entry.baseUrl.ifBlank { entry.preset.baseUrl }
        val key = catalogKey(baseUrl, entry.apiKey, entry.type)
        val ids = catalogCache[key] ?: return false
        return !ModelCatalog.isKnownId(ids, entry.model)
    }

    private fun actionButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.accent))
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }

    /**
     * Short alias that does not collide with an already configured entry.
     * 2.11.0 used `"$preset.id-$suffix"`, which Kotlin parses as
     * `preset.toString() + ".id-2"` and produced a `Preset(...)` monster in
     * both TOML keys and the web UI's model menu. Always interpolate a String.
     */
    private fun suggestAlias(preset: Prefs.Preset): String =
        ConfigMerger.uniqueAlias(preset.id, Prefs.load(this).map { it.alias }.toSet())

    private fun fillEditor(preset: Prefs.Preset, alias: String) {
        editAlias.setText(alias)
        editBaseUrl.setText(preset.baseUrl)
        editModel.setText(preset.model)
        editContext.setText(preset.contextSize.toString())
        editApiKey.setText("")
        spinnerEffort.setSelection(0, false)
        updateModelPick()
    }

    private fun openEditor(index: Int?) {
        editingIndex = index
        editingModels = mutableListOf()
        editorPanel.visibility = View.VISIBLE
        editorHint.visibility = View.GONE
        advancedPanel.visibility = View.GONE
        btnToggleAdvanced.text = getString(R.string.action_advanced)
        checkManualModel.isChecked = false
        editModel.visibility = View.GONE
        setStatus(getString(R.string.model_pick_hint))
        if (index == null) {
            editorTitle.text = getString(R.string.action_add_model)
            val position = spinnerProvider.selectedItemPosition.coerceIn(0, Prefs.PRESETS.lastIndex)
            fillEditor(Prefs.PRESETS[position], suggestAlias(Prefs.PRESETS[position]))
            return
        }
        val entry = Prefs.load(this).getOrNull(index)
        if (entry == null) {
            closeEditor()
            return
        }
        editorTitle.text = entry.alias
        editingModels = entry.models.toMutableList()
        editAlias.setText(entry.alias)
        editBaseUrl.setText(entry.baseUrl.ifBlank { entry.preset.baseUrl })
        editApiKey.setText(entry.apiKey)
        editModel.setText(entry.model)
        editContext.setText(entry.contextSize.toString())
        val position = Prefs.PRESETS.indexOfFirst { it.id == entry.presetId }
        spinnerProvider.setSelection(position.coerceAtLeast(0), false)
        val effortIndex = Prefs.EFFORTS.indexOfFirst { it.first == entry.effort }
        spinnerEffort.setSelection(effortIndex.coerceAtLeast(0), false)
        updateModelPick()
        autoFetchModels()
    }

    private fun closeEditor() {
        editingIndex = null
        editorPanel.visibility = View.GONE
        editorHint.visibility = View.GONE
    }

    private fun confirmEntry() {
        val preset = currentPreset()
        val index = editingIndex
        val entries = Prefs.load(this).toMutableList()
        val others = entries.filterIndexed { i, _ -> i != index }.map { it.alias }.toSet()
        val alias = ConfigMerger.uniqueAlias(
            ConfigMerger.sanitizeAlias(editAlias.text.toString().trim(), suggestAlias(preset)),
            others,
        )
        val apiKey = editApiKey.text.toString().trim()
        val baseUrl = editBaseUrl.text.toString().trim().ifBlank { preset.baseUrl }
        val model = editModel.text.toString().trim()
        val contextSize = editContext.text.toString().trim().toIntOrNull() ?: 131072

        editorHint.visibility = View.VISIBLE
        if (alias.isEmpty() || model.isEmpty() || apiKey.isEmpty()) {
            editorHint.setTextColor(getColor(R.color.warn))
            editorHint.text = getString(R.string.save_need_key_new)
            return
        }

        val effort = Prefs.EFFORTS[spinnerEffort.selectedItemPosition.coerceIn(0, Prefs.EFFORTS.lastIndex)].first
        // 2.15.0: one picker can tick several models, all registered under the
        // same group. When nothing was ticked, keep 2.14.0's whole-catalogue
        // registration so the web dropdown stays complete.
        val picked = editingModels.toList()
        val previous = if (index != null && index in entries.indices) {
            entries[index].models
        } else {
            emptyList()
        }
        val catalog = catalogCache[catalogKey(baseUrl, apiKey, preset.type)].orEmpty()
        val existingModels = if (picked.isNotEmpty()) {
            picked
        } else {
            ModelCatalog.registrationExtras(
                current = model,
                extra = previous,
                catalog = catalog,
                cap = Prefs.MAX_MODELS_PER_PROVIDER - 1,
            )
        }
        val entry = Prefs.Entry(
            presetId = preset.id,
            alias = alias,
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            contextSize = contextSize,
            effort = effort,
            models = existingModels,
        )
        if (index != null && index in entries.indices) entries[index] = entry else entries.add(entry)
        Prefs.save(this, entries)
        renderModelList()
        closeEditor()
        toast(
            if (existingModels.size > 1) getString(R.string.model_added_all, existingModels.size + 1)
            else getString(R.string.model_added),
        )
    }

    // ------------------------------------------------------- model discovery

    private fun currentPreset(): Prefs.Preset =
        Prefs.PRESETS[spinnerProvider.selectedItemPosition.coerceIn(0, Prefs.PRESETS.lastIndex)]

    private fun editorEndpoint(): Triple<String, String, String> {
        val baseUrl = editBaseUrl.text.toString().trim().ifBlank { currentPreset().baseUrl }
        return Triple(baseUrl, editApiKey.text.toString().trim(), currentPreset().type)
    }

    private fun setStatus(message: String, warn: Boolean = false) {
        textModelStatus.text = message
        textModelStatus.setTextColor(getColor(if (warn) R.color.warn else R.color.text_muted))
    }

    private fun updateModelPick() {
        val id = editModel.text.toString().trim()
        if (id.isEmpty()) {
            textModelPick.text = getString(R.string.model_pick_placeholder)
            textModelPickSub.text = getString(R.string.model_pick_sub)
        } else {
            val info = ModelCatalog.describe(id)
            textModelPick.text = info.id
            textModelPickSub.text = "${info.vendor} · 上下文 ${info.contextSize / 1024}K"
        }
    }

    private fun openModelPicker() {
        val (baseUrl, apiKey, type) = editorEndpoint()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            toast(getString(R.string.model_status_need_key))
            setStatus(getString(R.string.model_status_need_key), warn = true)
            return
        }
        val key = catalogKey(baseUrl, apiKey, type)
        val current = editModel.text.toString().trim()
        ModelMultiPickerDialog.show(
            activity = this,
            baseUrl = baseUrl,
            apiKey = apiKey,
            type = type,
            already = (listOf(current) + editingModels).filter { it.isNotBlank() },
            cached = catalogCache[key] ?: emptyList(),
            onCache = { ids ->
                catalogCache[key] = ids
                setStatus(getString(R.string.model_fetched, ids.size))
            },
            onPicked = { picked -> applyPickedModels(picked) },
        )
    }

    /** 一次把勾中的多个模型登记进当前分组，第一个作为该组默认模型。 */
    private fun applyPickedModels(picked: List<CatalogModel>) {
        val current = editModel.text.toString().trim()
        val (defaultModel, rest) = ModelGroup.mergePicked(
            current, editingModels, picked.map { it.id },
        )
        editingModels = rest.toMutableList()
        if (defaultModel.isNotEmpty()) {
            editModel.setText(defaultModel)
            editContext.setText(ModelCatalog.describe(defaultModel).contextSize.toString())
        }
        if (editAlias.text.toString().isBlank()) editAlias.setText(suggestAlias(currentPreset()))
        updateModelPick()
        setStatus("已勾选 ${picked.size} 个模型，全部登记在同一分组；保存后工作台按分组隔开显示")
    }

    /** Fetch on key / base-url blur, but never repeat a request we already made. */
    private fun autoFetchModels() {
        val (baseUrl, apiKey, type) = editorEndpoint()
        if (baseUrl.isBlank() || apiKey.isBlank()) return
        val key = catalogKey(baseUrl, apiKey, type)
        val cached = catalogCache[key]
        if (cached != null) {
            setStatus(getString(R.string.model_fetched, cached.size))
            return
        }
        fetchModels(showDialog = false)
    }

    private fun fetchModels(showDialog: Boolean) {
        val (baseUrl, apiKey, type) = editorEndpoint()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            setStatus(getString(R.string.model_status_need_key), warn = true)
            return
        }
        val generation = ++fetchGeneration
        setStatus(getString(R.string.model_status_fetching))
        val key = catalogKey(baseUrl, apiKey, type)
        Thread {
            val result = ModelCatalog.fetch(baseUrl, apiKey, type)
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != fetchGeneration) return@runOnUiThread
                if (result.error != null) {
                    setStatus(result.error, warn = true)
                } else {
                    catalogCache[key] = result.ids
                    val current = editModel.text.toString().trim()
                    val stale = !ModelCatalog.isKnownId(result.ids, current)
                    if (stale) {
                        // A saved display name is the classic cause of
                        // "no category route found". Nudge the user to re-pick.
                        setStatus(getString(R.string.model_id_not_in_catalog, current), warn = true)
                    } else {
                        setStatus(getString(R.string.model_fetched, result.ids.size))
                    }
                    if (showDialog) openModelPicker()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun probeModel() {
        val model = editModel.text.toString().trim()
        if (model.isEmpty()) {
            toast(getString(R.string.model_probe_need))
            return
        }
        val (baseUrl, apiKey, type) = editorEndpoint()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            setStatus(getString(R.string.model_status_need_key), warn = true)
            return
        }
        setStatus(getString(R.string.model_status_probing))
        Thread {
            val result = ModelCatalog.probe(baseUrl, apiKey, model, type)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setStatus(result.message, warn = !result.ok)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun deleteEntry(index: Int) {
        val entries = Prefs.load(this).toMutableList()
        if (index !in entries.indices) return
        entries.removeAt(index)
        Prefs.save(this, entries)
        if (entries.isNotEmpty()) {
            val activeIndex = entries.indexOfFirst { it.modelAlias == Prefs.activeEntry(this)?.modelAlias }
            Prefs.setActiveEntry(this, activeIndex.coerceIn(0, entries.size - 1))
        }

        val editing = editingIndex
        if (editing != null) {
            editingIndex = when {
                editing == index -> null
                editing > index -> editing - 1
                else -> editing
            }
            if (editingIndex == null) closeEditor()
        }
        renderModelList()
        render()
    }

    /**
     * Fold every catalogue we already fetched into the entries before writing
     * config.toml. One `[models.*]` section == one switchable model in the web
     * dropdown, so registering only the picked model is exactly why the menu
     * used to show a single entry. This is pure/local (uses the catalogue cached
     * on key/URL blur), so saving stays instant and never waits on the network.
     */
    private fun mergeWithCatalog(entries: List<Prefs.Entry>): List<Prefs.Entry> =
        entries.map { entry ->
            if (!entry.isConfigured) return@map entry
            val base = entry.baseUrl.ifBlank { entry.preset.baseUrl }
            val ids = catalogCache[catalogKey(base, entry.apiKey, entry.type)]
                ?: return@map entry
            if (ids.isEmpty()) return@map entry
            entry.copy(
                models = ModelCatalog.registrationExtras(
                    current = entry.model,
                    extra = entry.models,
                    catalog = ids,
                    cap = Prefs.MAX_MODELS_PER_PROVIDER - 1,
                ),
            )
        }

    /**
     * Persist the entries and apply them.
     *
     * Saving also registers the whole catalogue: the web dropdown is built from
     * one `[models.*]` section per model, so a save that only knows the picked
     * model leaves the user with a one-item menu and forces another trip
     * through settings to switch. Endpoints whose catalogue was never fetched
     * are listed right here, once, before the config is written.
     */
    private fun saveSettings() {
        val usable = Prefs.load(this).filter { it.isConfigured }
        saveHint.setTextColor(getColor(R.color.text_secondary))
        saveHint.text = getString(R.string.settings_hint)
        if (usable.isEmpty()) {
            saveHint.setTextColor(getColor(R.color.warn))
            saveHint.text = getString(R.string.save_need_entries)
            return
        }
        val unfetched = usable.filter { entry ->
            val base = entry.baseUrl.ifBlank { entry.preset.baseUrl }
            base.isNotBlank() && entry.apiKey.isNotBlank() &&
                catalogCache[catalogKey(base, entry.apiKey, entry.type)] == null
        }
        if (unfetched.isEmpty()) {
            persistAndRestart()
            return
        }
        val generation = ++fetchGeneration
        saveHint.text = getString(R.string.model_status_fetching)
        Thread {
            val fresh = HashMap<String, List<String>>()
            for (entry in unfetched) {
                val base = entry.baseUrl.ifBlank { entry.preset.baseUrl }
                val ids = ModelCatalog.fetch(base, entry.apiKey, entry.type).ids
                if (ids.isNotEmpty()) {
                    fresh[catalogKey(base, entry.apiKey, entry.type)] = ids
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != fetchGeneration) return@runOnUiThread
                catalogCache.putAll(fresh)
                persistAndRestart()
            }
        }.apply { isDaemon = true }.start()
    }

    /** Merge every cached catalogue, store the entries, write config.toml. */
    private fun persistAndRestart(reloadWeb: Boolean = false) {
        val entries = mergeWithCatalog(Prefs.load(this))
        Prefs.save(this, entries)
        val total = entries.filter { it.isConfigured }.sumOf { it.registeredModels.size }
        val message = if (total > 1) getString(R.string.model_registered_all, total) else null
        writeConfigAndRestart(entries, successMessage = message, reloadWeb = reloadWeb)
    }

    /**
     * Upgrade heal, runs once after the service is ready.
     *
     * Builds up to 2.13.0 saved only the model the user had picked, so the web
     * dropdown listed a single entry per endpoint and switching meant going
     * back to settings. Filling in the rest of the catalogue here means the
     * dropdown is complete the first time the user opens the workspace, with no
     * manual step. Capped at two attempts so a dead endpoint cannot loop.
     */
    private fun ensureCatalogRegistered() {
        if (autoRegisterRunning || autoRegisterAttempts >= 2) return
        val thin = Prefs.load(this).filter { it.isConfigured && it.registeredModels.size <= 1 }
        if (thin.isEmpty()) return
        autoRegisterRunning = true
        autoRegisterAttempts++
        Thread {
            val fresh = HashMap<String, List<String>>()
            for (entry in thin) {
                val base = entry.baseUrl.ifBlank { entry.preset.baseUrl }
                if (base.isBlank() || entry.apiKey.isBlank()) continue
                val ids = ModelCatalog.fetch(base, entry.apiKey, entry.type).ids
                if (ids.isNotEmpty()) {
                    fresh[catalogKey(base, entry.apiKey, entry.type)] = ids
                }
            }
            runOnUiThread {
                autoRegisterRunning = false
                if (isFinishing || isDestroyed || fresh.isEmpty()) return@runOnUiThread
                Runtime.log("自动补全接口模型：${fresh.values.sumOf { it.size }} 个")
                catalogCache.putAll(fresh)
                persistAndRestart(reloadWeb = true)
            }
        }.apply { isDaemon = true }.start()
    }

    /** Shared tail for a normal save and a full-catalogue registration. */
    private fun writeConfigAndRestart(
        entries: List<Prefs.Entry>,
        successMessage: String? = null,
        reloadWeb: Boolean = false,
    ) {
        val written = runCatching { ConfigWriter.write(this, entries, Prefs.activeEntry(this)) }
        if (written.isFailure) {
            saveHint.setTextColor(getColor(R.color.danger))
            saveHint.text = "保存失败：${written.exceptionOrNull()?.message}"
            return
        }
        val changed = written.getOrDefault(false)
        saveHint.setTextColor(getColor(R.color.ok))
        when {
            !changed -> saveHint.text = successMessage ?: getString(R.string.save_unchanged)
            !Paths.isRuntimeInstalled(this) -> saveHint.text = getString(R.string.save_pending_install)
            Runtime.phase != Runtime.Phase.READY -> saveHint.text = getString(R.string.save_pending_start)
            else -> {
                // Restarting is only needed when the config actually changed.
                // The port is pinned and the WebView keeps its page, so a
                // restart no longer logs the user out or drops the last reply.
                saveHint.text = successMessage ?: getString(R.string.save_apply)
                reenterAfterRestart = true
                if (reloadWeb) forceReloadAfterRestart = true
                RuntimeService.restart(this)
            }
        }
        renderModelList()
        render()
    }

    /**
     * One tap: refetch every configured endpoint's /models catalogue and
     * register ALL of its ids, then write config.toml and restart once. After
     * this the web dropdown lists every model of the same provider and
     * switching between them never needs another save or restart.
     */
    private fun registerAllModels() {
        val usable = Prefs.load(this).filter { it.isConfigured }
        if (usable.isEmpty()) {
            toast(getString(R.string.save_need_entries))
            return
        }
        val generation = ++fetchGeneration
        saveHint.setTextColor(getColor(R.color.text_secondary))
        saveHint.text = getString(R.string.model_status_registering)
        Thread {
            val fresh = HashMap<String, List<String>>()
            for (entry in usable) {
                val base = entry.baseUrl.ifBlank { entry.preset.baseUrl }
                if (base.isBlank() || entry.apiKey.isBlank()) continue
                val key = catalogKey(base, entry.apiKey, entry.type)
                // Always refresh so a gateway that added models is picked up;
                // keep the old cache as a fallback when the network is down.
                val fetched = ModelCatalog.fetch(base, entry.apiKey, entry.type).ids
                val ids = fetched.ifEmpty { catalogCache[key].orEmpty() }
                if (ids.isNotEmpty()) fresh[key] = ids
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != fetchGeneration) return@runOnUiThread
                catalogCache.putAll(fresh)
                persistAndRestart(reloadWeb = true)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun applyKeepAwake() {
        val keep = Prefs.keepAwake(this)
        if (lastKeepAwake == keep) return
        lastKeepAwake = keep
        if (keep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------------------------------------------------------------- plugins

    private fun setupPlugins() {
        findViewById<Button>(R.id.btnAddPlugin).setOnClickListener { openWorkspace() }
    }

    /**
     * Plugins are installed by the agent itself (`/plugins` in the workspace)
     * into `$KIMI_CODE_HOME/plugins`; this page reports what is actually there
     * instead of pretending an arbitrary JS file can be dropped in.
     */
    private fun renderPlugins() {
        pluginList.removeAllViews()
        val installed = readInstalledPlugins()
        if (installed.isEmpty()) {
            pluginList.addView(textLabel(getString(R.string.plugin_empty), getColor(R.color.text_secondary), 12f))
            return
        }
        installed.forEach { (name, enabled) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(12), dp(13), dp(12))
                setBackgroundResource(R.drawable.bg_card)
            }
            val title = textLabel(name, getColor(R.color.text_primary), 13f).apply {
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val state = textLabel(
                getString(if (enabled) R.string.plugin_enabled else R.string.plugin_disabled),
                getColor(if (enabled) R.color.ok else R.color.text_muted),
                11f,
            )
            row.addView(state)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            pluginList.addView(row, params)
        }
    }

    private fun readInstalledPlugins(): List<Pair<String, Boolean>> {
        val file = File(Prefs.pluginsDir(this), "installed.json")
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = org.json.JSONObject(file.readText())
            val array = root.optJSONArray("plugins") ?: org.json.JSONArray()
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").ifBlank { return@mapNotNull null }
                id to item.optBoolean("enabled", true)
            }
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- data

    private lateinit var backupStatus: TextView
    private lateinit var backupProgress: ProgressBar
    private lateinit var btnExportBackup: Button
    private lateinit var btnImportBackup: Button
    private lateinit var valueBackupLocation: TextView
    private lateinit var valueDataSize: TextView
    private var backupBusy = false

    private fun setupDataPage() {
        backupStatus = findViewById(R.id.backupStatus)
        backupProgress = findViewById(R.id.backupProgress)
        btnExportBackup = findViewById(R.id.btnExportBackup)
        btnImportBackup = findViewById(R.id.btnImportBackup)
        valueBackupLocation = findViewById(R.id.valueBackupLocation)
        valueDataSize = findViewById(R.id.valueDataSize)

        btnExportBackup.setOnClickListener { confirmBackup() }
        btnImportBackup.setOnClickListener { pickBackupFile() }
        findViewById<View>(R.id.rowBackupLocation).setOnClickListener { openBackupFolder() }
    }

    private fun renderDataPage() {
        val dir = BackupManager.backupDir(this)
        val public = dir.absolutePath.contains("/storage/emulated/0/")
        valueBackupLocation.text = if (public) {
            dir.absolutePath.replace("/storage/emulated/0/", "内部存储/")
        } else {
            getString(R.string.value_backup_location)
        }

        val latest = BackupManager.latestBackup(this)
        backupStatus.text = if (latest == null) {
            getString(R.string.backup_none)
        } else {
            getString(
                R.string.backup_done,
                latest.name,
                BackupManager.formatSize(latest.length()),
            )
        }

        val cached = cachedDataSize
        if (cached != null) {
            valueDataSize.text = BackupManager.formatSize(cached)
        } else if (!dataSizeInFlight) {
            dataSizeInFlight = true
            valueDataSize.text = getString(R.string.value_calculating)
            Thread {
                val size = BackupManager.dataSize(applicationContext)
                runOnUiThread {
                    dataSizeInFlight = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    cachedDataSize = size
                    valueDataSize.text = BackupManager.formatSize(size)
                }
            }.apply { isDaemon = true }.start()
        }

        val busy = backupBusy
        btnExportBackup.isEnabled = !busy
        btnImportBackup.isEnabled = !busy
        backupProgress.visibility = if (busy) View.VISIBLE else View.GONE
        if (!busy) backupProgress.isIndeterminate = false
    }

    private fun setBackupBusy(busy: Boolean, message: String? = null) {
        backupBusy = busy
        btnExportBackup.isEnabled = !busy
        btnImportBackup.isEnabled = !busy
        backupProgress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) backupProgress.isIndeterminate = true
        message?.let { backupStatus.text = it }
    }

    private fun confirmBackup() {
        if (backupBusy) return
        // 2.16.2：统一为 Eta 风格 BottomSheet，动效与全 App 一致
        EtaSheet.confirm(
            this,
            getString(R.string.backup_confirm_title),
            getString(R.string.backup_confirm_body),
            getString(R.string.action_export_backup),
        ) { startBackup() }
    }

    private fun startBackup() {
        setBackupBusy(true, getString(R.string.backup_running))

        Thread {
            val wasRunning = Runtime.phase == Runtime.Phase.READY
            if (wasRunning) {
                RuntimeService.stop(this)
                val deadline = System.currentTimeMillis() + 8000
                while (ProotRuntime.isRunning() && System.currentTimeMillis() < deadline) {
                    runCatching { Thread.sleep(200) }
                }
            }

            val result = runCatching {
                BackupManager.createBackup(this, object : BackupManager.Progress {
                    override fun onProgress(percent: Int) {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            backupProgress.isIndeterminate = false
                            backupProgress.progress = percent.coerceAtLeast(0)
                            backupStatus.text =
                                getString(R.string.backup_running) + " " + percent + "%"
                        }
                    }

                    override fun onMessage(message: String) {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            backupStatus.text = message
                        }
                    }
                })
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBackupBusy(false)
                cachedDataSize = null
                result.onSuccess { backup ->
                    Runtime.log("备份完成：${backup.file.name}（${backup.items} 个文件）")
                    backupStatus.text = getString(
                        R.string.backup_done,
                        backup.file.name,
                        BackupManager.formatSize(backup.bytes),
                    )
                    shareBackup(backup.file)
                }.onFailure { error ->
                    Runtime.log("备份失败：${error.message}")
                    backupStatus.text = getString(R.string.backup_failed, error.message ?: "未知错误")
                }
                if (wasRunning) RuntimeService.start(this)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun shareBackup(file: File) {
        Thread {
            val share = runCatching { BackupManager.stageForSharing(this, file) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                share.onSuccess { uri ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        startActivity(Intent.createChooser(intent, getString(R.string.backup_share_title)))
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun pickBackupFile() {
        if (backupBusy) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQUEST_PICK_BACKUP) }
            .onFailure { toast("没有可用的文件选择器") }
    }

    private fun confirmRestore(uri: android.net.Uri) {
        // 2.16.2：统一为 Eta 风格 BottomSheet
        EtaSheet.confirm(
            this,
            getString(R.string.restore_confirm_title),
            getString(R.string.restore_confirm_body),
            getString(R.string.action_import_backup),
        ) { startRestore(uri) }
    }

    private fun startRestore(uri: android.net.Uri) {
        setBackupBusy(true, getString(R.string.restore_running))

        Thread {
            val wasRunning = Runtime.phase == Runtime.Phase.READY
            if (wasRunning) {
                RuntimeService.stop(this)
                val deadline = System.currentTimeMillis() + 8000
                while (ProotRuntime.isRunning() && System.currentTimeMillis() < deadline) {
                    runCatching { Thread.sleep(200) }
                }
            }

            val result = runCatching {
                BackupManager.restoreBackup(this, uri, object : BackupManager.Progress {
                    override fun onProgress(percent: Int) = Unit

                    override fun onMessage(message: String) {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            backupStatus.text = message
                        }
                    }
                })
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBackupBusy(false)
                cachedDataSize = null
                result.onSuccess { count ->
                    Runtime.log("备份已恢复：$count 个文件")
                    backupStatus.text = getString(R.string.restore_done, count)
                    // A restore replaces session files, so the page must be rebuilt.
                    loadedUrl = null
                    webViewRestored = false
                    loadSettingsIntoUi()
                    render()
                }.onFailure { error ->
                    Runtime.log("恢复失败：${error.message}")
                    backupStatus.text = getString(R.string.restore_failed, error.message ?: "未知错误")
                }
                // Mirror startBackup(): only resume the service if it was
                // running before, otherwise restoring would silently start it.
                if (wasRunning) RuntimeService.start(this)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun openBackupFolder() {
        val dir = BackupManager.backupDir(this)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.parse("content://com.android.externalstorage.documents/root/primary"), "*/*")
        }
        runCatching { startActivity(intent) }
            .onFailure { toast("备份目录：${dir.absolutePath}") }
    }

    // ---------------------------------------------------------------- terminal

    private fun setupTerminal() {
        findViewById<Button>(R.id.btnTermSend).setOnClickListener { runTerminalCommand() }
        termInput.setOnEditorActionListener { _, _, _ ->
            runTerminalCommand()
            true
        }
    }

    private fun runTerminalCommand() {
        val command = termInput.text.toString().trim()
        if (command.isEmpty()) return
        termInput.setText("")
        appendTerminal("\n❯ $command\n")

        if (!Paths.isRuntimeInstalled(this)) {
            appendTerminal("运行环境尚未安装完成，无法执行命令\n")
            return
        }
        Thread {
            try {
                val process = ProotRuntime.shell(this, command)
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            appendTerminal("$line\n")
                        }
                    }
                }
                val code = process.waitFor()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    appendTerminal("[退出码 $code]\n")
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    appendTerminal("执行失败：${error.message}\n")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun appendTerminal(text: String) {
        termOutput.append(text)
        termScroll.post { termScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- render

    private fun render() {
        statusTitle.text = Runtime.title
        statusDetail.text = Runtime.detail.ifBlank {
            if (Runtime.phase == Runtime.Phase.IDLE) getString(R.string.status_idle_detail) else ""
        }

        statusDot.setBackgroundResource(
            when (Runtime.phase) {
                Runtime.Phase.READY -> R.drawable.bg_dot_ok
                Runtime.Phase.ERROR -> R.drawable.bg_dot_error
                Runtime.Phase.INSTALLING, Runtime.Phase.STARTING -> R.drawable.bg_dot_busy
                else -> R.drawable.bg_dot_idle
            },
        )

        val showProgress = Runtime.progress in 0..100
        progress.visibility = if (showProgress) View.VISIBLE else View.GONE
        if (showProgress) {
            if (progress.isIndeterminate) progress.isIndeterminate = false
            progress.progress = Runtime.progress
        }

        btnPrimary.text = when (Runtime.phase) {
            Runtime.Phase.READY -> getString(R.string.action_open)
            Runtime.Phase.INSTALLING -> "安装中…"
            Runtime.Phase.STARTING -> "启动中…"
            else -> getString(R.string.action_start)
        }
        btnPrimary.isEnabled = Runtime.phase != Runtime.Phase.INSTALLING &&
            Runtime.phase != Runtime.Phase.STARTING

        valueAddress.text = if (Runtime.phase == Runtime.Phase.READY && Runtime.port > 0) {
            "http://${localIp()}:${Runtime.port}/"
        } else {
            getString(R.string.value_not_running)
        }

        val lan = Prefs.lan(this)
        valueLan.text = if (lan) getString(R.string.value_lan_on) else getString(R.string.value_lan_off)
        valueLanToggle.text = getString(if (lan) R.string.action_disable else R.string.action_enable)
        valueLanToggle.setTextColor(getColor(if (lan) R.color.danger else R.color.accent))

        val active = Prefs.activeEntry(this)
        valueModel.text = if (active != null && active.isConfigured) {
            "${active.model} · ${active.preset.label}"
        } else {
            getString(R.string.value_model_empty)
        }
        valueWorkspace.text = Prefs.workspace(this)

        val logVersion = Runtime.logVersion
        if (logVersion != lastRenderedLogVersion) {
            lastRenderedLogVersion = logVersion
            val lines = Runtime.snapshotLogs()
            logText.text = if (lines.isEmpty()) getString(R.string.log_empty) else lines.joinToString("\n")
            logContainer.post { logContainer.fullScroll(View.FOCUS_DOWN) }
        }

        applyKeepAwake()

        val ready = Runtime.phase == Runtime.Phase.READY && Runtime.workspaceUrl.isNotEmpty()
        if (ready) ensureCatalogRegistered()
        // 2.16.1：一律不自动进入工作台。服务/解压就绪后停在启动页，由用户自己
        // 点「进入工作台」；配置保存后的 restart 也一样，不再自动跳回网页。
        // restart 标记仍在这里消费掉，避免残留到后续 render。
        if (reenterAfterRestart && Runtime.phase != Runtime.Phase.STARTING &&
            Runtime.phase != Runtime.Phase.INSTALLING) {
            reenterAfterRestart = false
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_FILE_CHOOSER -> {
                // parseResult 会读 data.data 与 clipData（多选），比手写分支稳。
                val uris = runCatching {
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                }.getOrNull()
                finishFileChooser(uris?.takeIf { it.isNotEmpty() })
            }
            REQUEST_CAPTURE_IMAGE -> {
                val uri = cameraOutputUri
                if (resultCode == RESULT_OK && uri != null) {
                    finishFileChooser(arrayOf(uri))
                } else {
                    finishFileChooser(null)
                }
            }
            REQUEST_PICK_BACKUP -> {
                if (resultCode != RESULT_OK) return
                val uri = data?.data ?: return
                confirmRestore(uri)
            }
        }
    }

    /**
     * 2.17.0：存储权限往往是服务启动后才弹的。授予之后补一次工作区登记，
     * 让 Agent 立刻能访问 /storage/emulated/0，不用等下一次重启。
     *
     * 2.18.0：拍照前申请的 CAMERA 也走这里，授权后继续打开相机。
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted && fileChooserCallback != null) {
                pendingCaptureCallback = null
                startCameraCapture()
            } else {
                finishFileChooser(null)
                toast("没有相机权限，已取消拍照")
            }
            return
        }
        if (requestCode != 1001) return
        if (Runtime.phase != Runtime.Phase.READY || Runtime.port == 0) return
        WorkspaceBootstrap.ensureTrusted(this, Runtime.port)
    }

    override fun onResume() {
        super.onResume()
        cachedLocalIp = null
        if (workspaceVisible) applyImmersive(true)
        // WebView.onResume fires `visibilitychange` inside the page. The web UI
        // only re-polls the sign-in flow when the document becomes visible again,
        // so without this the user returns from the browser and stays stuck on
        // "waiting" until the next timer tick.
        runCatching { webView.onResume() }
        // 从「模型供应商」页返回：配置已变化，刷新主界面并让网页重新读一次
        // 模型清单。切换默认模型只改了一行 config，重载网页即可，无需重启 proot。
        if (ProviderActivity.changed) {
            ProviderActivity.changed = false
            renderModelList()
            if (workspaceVisible) reloadWorkspace()
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureWebViewState()?.let { outState.putBundle(KEY_WEB_STATE, it) }
    }

    override fun onPause() {
        // Snapshot before pausing so a later recreation can restore the page.
        captureWebViewState()
        // 2.16.1：把 cookie 立刻刷到磁盘，避免进程被杀（覆盖安装、切后台回收）
        // 时登录态还没落盘而丢失。
        runCatching { CookieManager.getInstance().flush() }
        runCatching { webView.onPause() }
        super.onPause()
    }

    override fun onDestroy() {
        Runtime.removeListener(listener)
        probeWebView?.let { runCatching { it.destroy() } }
        probeWebView = null
        super.onDestroy()
    }

    /**
     * 2.16.1：网页里的「更多模型 / 模型下拉」是纯 DOM 浮层或用 replaceState，
     * 不产生 WebView 历史记录。旧实现里 canGoBack()==false 就直接 hideWorkspace()，
     * 于是一按返回就跳出工作台（用户反馈的现象）。这里先让网页自己关浮层，
     * 确认没有浮层之后才退出工作台；有真实历史时仍走 goBack 回到上一级。
     */
    private fun handleWorkspaceBack() {
        if (webView.canGoBack()) {
            webView.goBack()
            return
        }
        val js = """
            (function() {
              var sels = ['[role="dialog"]','[role="menu"]','[role="listbox"]',
                          '[aria-modal="true"]','[data-state="open"]',
                          '.modal','.popover','.dropdown-menu',
                          '[data-radix-popper-content-wrapper]'];
              for (var i = 0; i < sels.length; i++) {
                var nodes = document.querySelectorAll(sels[i]);
                for (var j = 0; j < nodes.length; j++) {
                  var el = nodes[j];
                  var r = el.getBoundingClientRect();
                  var st = window.getComputedStyle(el);
                  if (r.width > 0 && r.height > 0 && st.visibility !== 'hidden' && st.display !== 'none') {
                    var close = el.querySelector('[aria-label="Close"],[aria-label="close"],[aria-label="关闭"],button.close,[data-dismiss]');
                    if (close) { close.click(); return 'true'; }
                    var ev = new KeyboardEvent('keydown', {key:'Escape', code:'Escape', keyCode:27, which:27, bubbles:true});
                    document.dispatchEvent(ev);
                    window.dispatchEvent(ev);
                    return 'true';
                  }
                }
              }
              return 'false';
            })()
        """.trimIndent()
        val exitIfIdle = Runnable {
            if (webContainer.visibility == View.VISIBLE) hideWorkspace()
        }
        val ok = runCatching {
            webView.evaluateJavascript(js) { value ->
                val handled = value?.trim()?.trim('"') == "true"
                if (!handled) exitIfIdle.run()
            }
        }.isSuccess
        if (!ok) exitIfIdle.run()
    }

    override fun onBackPressed() {
        if (webContainer.visibility == View.VISIBLE) {
            handleWorkspaceBack()
            return
        }
        if (currentPage != R.id.pageHome) {
            switchPage(R.id.pageHome)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---------------------------------------------------------------- helpers

    private fun requestStoragePermission() {
        // 2.18.0：旧实现从 Android 11 起直接 return，理由是「分区存储不需要」。
        // 但本应用 targetSdk=28 且开了 requestLegacyExternalStorage，系统仍按
        // 旧模型走，读 /storage/emulated/0 依旧要 READ_EXTERNAL_STORAGE。
        // 少了这一步，Agent 能在容器里看到路径、却没有读权限 —— 用户感知就是
        // 「根本没有读写我手机文件的权限」。Android 13+ 对旧 targetSdk 仍认这个
        // 权限（会显示为「照片和视频」），所以统一请求。
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        // 写公共下载目录（DownloadManager / 保存图片）在旧存储模型下仍要 WRITE。
        // Android 13+ 对 targetSdk<=32 的应用会把它合并成「文件和媒体」授权。
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1001)
    }

    private fun localIp(): String {
        cachedLocalIp?.let { return it }
        val found = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress
        }.getOrNull() ?: "127.0.0.1"
        cachedLocalIp = found
        return found
    }

    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "1.0"

    private fun textLabel(text: String, color: Int, sizeSp: Float): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = sizeSp
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
