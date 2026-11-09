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
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
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
    private lateinit var pluginList: LinearLayout
    private lateinit var termOutput: TextView
    private lateinit var termScroll: ScrollView
    private lateinit var termInput: EditText

    private lateinit var webContainer: View
    private lateinit var webView: WebView
    private lateinit var webLoading: ProgressBar

    private lateinit var navItems: List<Pair<View, Pair<TextView, TextView>>>

    private var loadedUrl: String? = null
    private var workspaceVisible = false
    private var currentPage = R.id.pageHome

    private val listener: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupWebView()
        setupNavigation()
        setupSettingsPage()
        setupTerminal()
        setupPlugins()
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
        pluginList = findViewById(R.id.pluginList)
        termOutput = findViewById(R.id.termOutput)
        termScroll = findViewById(R.id.termScroll)
        termInput = findViewById(R.id.termInput)

        webContainer = findViewById(R.id.webContainer)
        webView = findViewById(R.id.webView)
        webLoading = findViewById(R.id.webLoading)

        btnPrimary.setOnClickListener { onPrimaryAction() }
        btnRestart.setOnClickListener {
            Runtime.log("用户请求重启")
            loadedUrl = null
            RuntimeService.restart(this)
        }
        btnStop.setOnClickListener {
            Runtime.log("用户请求停止")
            loadedUrl = null
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
            findViewById<View>(R.id.navTerminal) to
                (findViewById<TextView>(R.id.iconTerminal) to findViewById<TextView>(R.id.labelTerminal)),
        )
        val targets = listOf(
            R.id.navHome to R.id.pageHome,
            R.id.navPlugins to R.id.pagePlugins,
            R.id.navSettings to R.id.pageSettings,
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
        hideWorkspace()

        val pages = listOf(R.id.pageHome, R.id.pagePlugins, R.id.pageSettings, R.id.pageTerminal)
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
                R.id.pageTerminal -> R.string.nav_terminal
                else -> R.string.app_name
            }
        )
        if (pageId == R.id.pageSettings) loadSettingsIntoUi()
        if (pageId == R.id.pagePlugins) renderPlugins()
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
        runCatching { file.writeText(body) }
            .onSuccess {
                val intent = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, android.net.Uri.fromFile(file))
                runCatching { startActivity(Intent.createChooser(intent, "保存日志")) }
                toast("已保存到 ${file.absolutePath}")
            }
            .onFailure { toast("保存失败：${it.message}") }
    }

    // ---------------------------------------------------------------- workspace

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
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webLoading.visibility = View.GONE
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
        if (loadedUrl != Runtime.workspaceUrl) {
            loadedUrl = Runtime.workspaceUrl
            webLoading.visibility = View.VISIBLE
            webView.loadUrl(Runtime.workspaceUrl)
        }
        webContainer.visibility = View.VISIBLE
        hidePages()
    }

    private fun hideWorkspace() {
        workspaceVisible = false
        webContainer.visibility = View.GONE
        pageHome.visibility = if (currentPage == R.id.pageHome) View.VISIBLE else View.GONE
        pagePlugins.visibility = if (currentPage == R.id.pagePlugins) View.VISIBLE else View.GONE
        pageSettings.visibility = if (currentPage == R.id.pageSettings) View.VISIBLE else View.GONE
        pageTerminal.visibility = if (currentPage == R.id.pageTerminal) View.VISIBLE else View.GONE
    }

    private fun hidePages() {
        pageHome.visibility = View.GONE
        pagePlugins.visibility = View.GONE
        pageSettings.visibility = View.GONE
        pageTerminal.visibility = View.GONE
    }

    // ---------------------------------------------------------------- settings

    private lateinit var spinnerProvider: Spinner
    private lateinit var editBaseUrl: EditText
    private lateinit var editApiKey: EditText
    private lateinit var editModel: EditText
    private lateinit var editContext: EditText
    private lateinit var saveHint: TextView
    private lateinit var checkAutoEnter: CheckBox
    private lateinit var checkSwipeBack: CheckBox
    private lateinit var checkLan: CheckBox
    private lateinit var checkKeepAwake: CheckBox
    private var selectedProvider: String = "kimi"

    private fun setupSettingsPage() {
        spinnerProvider = findViewById(R.id.spinnerProvider)
        editBaseUrl = findViewById(R.id.editBaseUrl)
        editApiKey = findViewById(R.id.editApiKey)
        editModel = findViewById(R.id.editModel)
        editContext = findViewById(R.id.editContext)
        saveHint = findViewById(R.id.saveHint)
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
                val preset = Prefs.PRESETS[position]
                if (preset.id == selectedProvider) return
                selectedProvider = preset.id
                editBaseUrl.setText(preset.baseUrl)
                editModel.setText(preset.model)
                editContext.setText(preset.contextSize.toString())
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

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        findViewById<TextView>(R.id.textVersion).text =
            getString(R.string.about_version, appVersionName())
    }

    private fun loadSettingsIntoUi() {
        val settings = Prefs.load(this)
        selectedProvider = settings.provider
        val index = Prefs.PRESETS.indexOfFirst { it.id == settings.provider }.coerceAtLeast(0)
        spinnerProvider.setSelection(index, false)
        editBaseUrl.setText(settings.baseUrl)
        editApiKey.setText(settings.apiKey)
        editModel.setText(settings.model)
        editContext.setText(settings.contextSize.toString())
        checkAutoEnter.isChecked = Prefs.autoEnter(this)
        checkSwipeBack.isChecked = Prefs.swipeBack(this)
        checkLan.isChecked = Prefs.lan(this)
        checkKeepAwake.isChecked = Prefs.keepAwake(this)
    }

    private fun saveSettings() {
        val settings = Prefs.ModelSettings(
            provider = selectedProvider,
            baseUrl = editBaseUrl.text.toString().trim(),
            apiKey = editApiKey.text.toString().trim(),
            model = editModel.text.toString().trim(),
            contextSize = editContext.text.toString().trim().toIntOrNull()
                ?: Prefs.preset(selectedProvider).contextSize,
        )
        Prefs.save(this, settings)
        render()

        if (!settings.isConfigured) {
            saveHint.setTextColor(getColor(R.color.warn))
            saveHint.text = getString(R.string.save_need_key)
            return
        }
        val written = runCatching { ConfigWriter.write(this, settings) }
        if (written.isSuccess) {
            saveHint.setTextColor(getColor(R.color.ok))
            saveHint.text = getString(R.string.save_ok)
        } else {
            saveHint.setTextColor(getColor(R.color.danger))
            saveHint.text = "保存失败：${written.exceptionOrNull()?.message}"
        }
    }

    private fun applyKeepAwake() {
        if (Prefs.keepAwake(this)) {
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
                        runOnUiThread { appendTerminal("$line\n") }
                    }
                }
                val code = process.waitFor()
                runOnUiThread { appendTerminal("[退出码 $code]\n") }
            } catch (error: Throwable) {
                runOnUiThread { appendTerminal("执行失败：${error.message}\n") }
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

        val settings = Prefs.load(this)
        valueModel.text = if (settings.isConfigured) {
            "${settings.model} · ${Prefs.preset(settings.provider).label}"
        } else {
            getString(R.string.value_model_empty)
        }
        valueWorkspace.text = Prefs.workspace(this)

        if (logText.text.isNullOrEmpty() || Runtime.snapshotLogs().isNotEmpty()) {
            val lines = Runtime.snapshotLogs()
            logText.text = if (lines.isEmpty()) getString(R.string.log_empty) else lines.joinToString("\n")
            logContainer.post { logContainer.fullScroll(View.FOCUS_DOWN) }
        }

        applyKeepAwake()

        val ready = Runtime.phase == Runtime.Phase.READY && Runtime.workspaceUrl.isNotEmpty()
        if (ready && !workspaceVisible && Prefs.autoEnter(this) && currentPage == R.id.pageHome) {
            Runtime.log("已开启自动进入，直接打开工作台")
            openWorkspace()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        Runtime.removeListener(listener)
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
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress
        }.getOrNull() ?: "127.0.0.1"
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
