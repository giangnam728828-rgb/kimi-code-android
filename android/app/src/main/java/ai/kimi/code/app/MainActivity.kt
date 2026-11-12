package ai.kimi.code.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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

    private val listener: () -> Unit = { runOnUiThread { render() } }

    private companion object {
        const val REQUEST_PICK_BACKUP = 3001
        const val KEY_WEB_STATE = "web_view_state"

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
        findViewById<View>(R.id.rowModel).setOnClickListener { switchPage(R.id.pageSettings) }
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

    private fun switchPage(pageId: Int) {
        currentPage = pageId
        pageHome.visibility = if (pageId == R.id.pageHome) View.VISIBLE else View.GONE
        pagePlugins.visibility = if (pageId == R.id.pagePlugins) View.VISIBLE else View.GONE
        pageSettings.visibility = if (pageId == R.id.pageSettings) View.VISIBLE else View.GONE
        pageTerminal.visibility = if (pageId == R.id.pageTerminal) View.VISIBLE else View.GONE
        pageData.visibility = if (pageId == R.id.pageData) View.VISIBLE else View.GONE
        hideWorkspace()

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

    private fun promptWorkspace() {
        val input = EditText(this).apply {
            setText(Prefs.workspace(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT
            setSelectAllOnFocus(true)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.row_workspace)
            .setMessage("容器内的绝对路径，重启服务后生效")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Prefs.setWorkspace(this, input.text.toString())
                render()
                toast("已保存，重启服务后生效")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        settings.builtInZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
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
        }
        webView.webViewClient = object : WebViewClient() {
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
                    if (webView.canGoBack()) webView.goBack() else hideWorkspace()
                    return true
                }
                return false
            }
        })
        webView.setOnTouchListener { _, event ->
            gesture.onTouchEvent(event)
            false
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

        findViewById<View>(R.id.btnAddModel).setOnClickListener { openEditor(null) }
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
        modelCard?.addView(
            TextView(this).apply {
                text = getString(R.string.action_manage_provider_models)
                setTextColor(getColor(R.color.accent))
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(10), dp(12), dp(10), dp(14))
                setOnClickListener {
                    QuickSettingsDialog.show(
                        this@MainActivity,
                        onRefresh = { reloadWorkspace() },
                        onChanged = { renderModelList(); render() },
                        onRegisterAll = { registerAllModels() },
                    )
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        modelCard?.addView(
            TextView(this).apply {
                text = getString(R.string.model_hint_web_switch)
                setTextColor(getColor(R.color.text_muted))
                textSize = 11f
                setPadding(dp(10), 0, dp(10), dp(12))
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
        val entries = Prefs.load(this)
        val active = Prefs.activeEntry(this)
        modelList.removeAllViews()
        modelListEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        entries.forEachIndexed { index, entry ->
            val isActive = entry.modelAlias == active?.modelAlias
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(12), dp(13), dp(12))
                setBackgroundResource(R.drawable.bg_card)
                setOnClickListener { openEditor(index) }
            }
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            column.addView(
                textLabel(entry.model.ifBlank { entry.alias }, getColor(R.color.text_primary), 13f).apply {
                    setTypeface(null, android.graphics.Typeface.BOLD)
                },
            )
            column.addView(
                textLabel(
                    buildString {
                        append(entry.alias)
                        append(" · ")
                        append(ModelCatalog.describe(entry.model).vendor)
                        append(" · ")
                        append(entry.contextSize / 1024).append("K")
                        val count = entry.registeredModels.size
                        if (count > 1) append(" · ").append(count).append(" 个模型")
                        if (isActive) append(" · ").append(getString(R.string.model_entry_default))
                        if (isUnknownToCatalog(entry)) {
                            append(" · ").append(getString(R.string.model_entry_unknown_id))
                        }
                    },
                    getColor(R.color.text_muted),
                    11f,
                ),
            )
            row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (!isActive) {
                row.addView(
                    actionButton(getString(R.string.action_set_default)) {
                        Prefs.setActiveEntry(this@MainActivity, index)
                        renderModelList()
                        render()
                        toast(getString(R.string.model_default_pending))
                    },
                )
            }
            row.addView(
                actionButton(getString(R.string.action_delete)) { deleteEntry(index) },
            )
            modelList.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) },
            )
        }
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
        // Editing an entry must not drop the other models registered under it.
        val existingModels = if (index != null && index in entries.indices) {
            entries[index].models
        } else {
            emptyList()
        }
        // The web UI's "more models" menu lists one entry per `[models.*]`
        // section in config.toml. Registering only the model the user picked is
        // why every other model of the same endpoint was missing from that menu
        // and had to be added by hand. Fold the whole fetched catalogue in so a
        // single save makes them all switchable inside the web page.
        val catalog = catalogCache[catalogKey(baseUrl, apiKey, preset.type)].orEmpty()
        val registered = ModelCatalog.registrationExtras(
            current = model,
            extra = existingModels,
            catalog = catalog,
            cap = Prefs.MAX_MODELS_PER_PROVIDER - 1,
        )
        val entry = Prefs.Entry(
            presetId = preset.id,
            alias = alias,
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            contextSize = contextSize,
            effort = effort,
            models = registered,
        )
        if (index != null && index in entries.indices) entries[index] = entry else entries.add(entry)
        Prefs.save(this, entries)
        renderModelList()
        closeEditor()
        toast(
            if (registered.size > 1) getString(R.string.model_added_all, registered.size + 1)
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
        ModelPickerDialog.show(
            activity = this,
            baseUrl = baseUrl,
            apiKey = apiKey,
            type = type,
            current = editModel.text.toString().trim(),
            cached = catalogCache[key] ?: emptyList(),
            onCache = { ids ->
                catalogCache[key] = ids
                setStatus(getString(R.string.model_fetched, ids.size))
            },
            onPicked = { model -> applyPickedModel(model) },
        )
    }

    private fun applyPickedModel(model: CatalogModel) {
        editModel.setText(model.id)
        editContext.setText(model.contextSize.toString())
        if (editAlias.text.toString().isBlank()) editAlias.setText(suggestAlias(currentPreset()))
        updateModelPick()
        setStatus("已选择 ${model.id} · ${model.vendor}；点「验证模型可用」可提前确认")
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
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.backup_confirm_title)
            .setMessage(R.string.backup_confirm_body)
            .setPositiveButton(android.R.string.ok) { _, _ -> startBackup() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.restore_confirm_title)
            .setMessage(R.string.restore_confirm_body)
            .setPositiveButton(android.R.string.ok) { _, _ -> startRestore(uri) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        val restartSettled = reenterAfterRestart &&
            Runtime.phase != Runtime.Phase.STARTING &&
            Runtime.phase != Runtime.Phase.INSTALLING
        if (restartSettled) {
            // Consume the flag whether the restart succeeded or failed, so it
            // cannot fire much later on an unrelated render.
            reenterAfterRestart = false
            if (ready && !workspaceVisible && currentPage == R.id.pageSettings) {
                Runtime.log("配置已生效，自动返回工作台")
                openWorkspace()
            }
        } else if (ready && !workspaceVisible && Prefs.autoEnter(this) && currentPage == R.id.pageHome) {
            Runtime.log("已开启自动进入，直接打开工作台")
            openWorkspace()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_BACKUP || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        confirmRestore(uri)
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
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureWebViewState()?.let { outState.putBundle(KEY_WEB_STATE, it) }
    }

    override fun onPause() {
        // Snapshot before pausing so a later recreation can restore the page.
        captureWebViewState()
        runCatching { webView.onPause() }
        super.onPause()
    }

    override fun onDestroy() {
        Runtime.removeListener(listener)
        probeWebView?.let { runCatching { it.destroy() } }
        probeWebView = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webContainer.visibility == View.VISIBLE) {
            if (webView.canGoBack()) webView.goBack() else hideWorkspace()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
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
