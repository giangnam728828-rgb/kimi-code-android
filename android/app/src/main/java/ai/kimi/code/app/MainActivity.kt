package ai.kimi.code.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.app.Activity

/**
 * One screen: the launcher (install/start/status) and, once the local server is
 * up, the Kimi Code workspace inside a WebView that already carries the bearer
 * token. No terminal, no manual token entry.
 */
class MainActivity : Activity() {

    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnPrimary: Button
    private lateinit var btnRestart: Button
    private lateinit var btnStop: Button
    private lateinit var btnLogs: Button
    private lateinit var btnSettings: Button
    private lateinit var logContainer: ScrollView
    private lateinit var logText: TextView
    private lateinit var launcher: ScrollView
    private lateinit var webContainer: View
    private lateinit var webView: WebView
    private lateinit var btnBackToLauncher: TextView

    private var loadedUrl: String? = null
    private var logsVisible = false

    /** Workspace stays hidden until the user taps in or auto-enter is on. */
    private var workspaceVisible = false

    private val listener: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        progress = findViewById(R.id.progress)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnRestart = findViewById(R.id.btnRestart)
        btnStop = findViewById(R.id.btnStop)
        btnLogs = findViewById(R.id.btnLogs)
        btnSettings = findViewById(R.id.btnSettings)
        logContainer = findViewById(R.id.logContainer)
        logText = findViewById(R.id.logText)
        launcher = findViewById(R.id.launcher)
        webContainer = findViewById(R.id.webContainer)
        webView = findViewById(R.id.webView)
        btnBackToLauncher = findViewById(R.id.btnBackToLauncher)

        setupWebView()
        requestStoragePermission()

        btnPrimary.setOnClickListener { onPrimaryAction() }
        btnRestart.setOnClickListener {
            Runtime.log("用户请求重启")
            nativeStop()
            hideWorkspace()
            loadedUrl = null
            RuntimeService.restart(this)
        }
        btnStop.setOnClickListener {
            Runtime.log("用户请求停止")
            nativeStop()
            hideWorkspace()
            loadedUrl = null
            RuntimeService.stop(this)
        }
        btnLogs.setOnClickListener {
            logsVisible = !logsVisible
            logContainer.visibility = if (logsVisible) View.VISIBLE else View.GONE
            render()
        }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnBackToLauncher.setOnClickListener { hideWorkspace() }

        Runtime.addListener(listener)
        render()

        if (Paths.isRuntimeInstalled(this) && !ProotRuntime.isRunning() && Runtime.phase == Runtime.Phase.IDLE) {
            // Everything is already on disk: boot straight into the workspace.
            RuntimeService.start(this)
        }
    }

    private fun nativeStop() {
        runCatching { startService(Intent(this, RuntimeService::class.java).setAction(RuntimeService.ACTION_STOP)) }
        ProotRuntime.stop()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        Runtime.removeListener(listener)
        super.onDestroy()
    }

    private fun onPrimaryAction() {
        when (Runtime.phase) {
            Runtime.Phase.READY -> showWorkspace()
            else -> {
                if (!Paths.isRuntimeInstalled(this)) {
                    Runtime.log("开始安装运行环境")
                }
                RuntimeService.start(this)
            }
        }
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
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Runtime.log("工作台已加载")
            }
        }
    }

    private fun render() {
        statusTitle.text = Runtime.title
        statusDetail.text = Runtime.detail.ifBlank {
            if (Runtime.phase == Runtime.Phase.IDLE) getString(R.string.status_idle_detail) else ""
        }

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

        btnLogs.text = if (logsVisible) "收起日志" else getString(R.string.action_logs)

        if (logsVisible) {
            val lines = Runtime.snapshotLogs()
            logText.text = if (lines.isEmpty()) "（暂无日志）" else lines.joinToString("\n")
            logContainer.post { logContainer.fullScroll(View.FOCUS_DOWN) }
        }

        val ready = Runtime.phase == Runtime.Phase.READY && Runtime.workspaceUrl.isNotEmpty()
        if (ready && !workspaceVisible && Prefs.autoEnter(this)) {
            Runtime.log("已开启自动进入，直接打开工作台")
            showWorkspace()
        } else if (!ready && Runtime.phase != Runtime.Phase.STARTING && Runtime.phase != Runtime.Phase.INSTALLING) {
            workspaceVisible = false
            webContainer.visibility = View.GONE
            launcher.visibility = View.VISIBLE
        }
    }

    private fun showWorkspace() {
        if (Runtime.workspaceUrl.isEmpty()) return
        workspaceVisible = true
        if (loadedUrl != Runtime.workspaceUrl) {
            loadedUrl = Runtime.workspaceUrl
            webView.loadUrl(Runtime.workspaceUrl)
        }
        webContainer.visibility = View.VISIBLE
        launcher.visibility = View.GONE
    }

    private fun hideWorkspace() {
        workspaceVisible = false
        webContainer.visibility = View.GONE
        launcher.visibility = View.VISIBLE
        render()
    }

    override fun onBackPressed() {
        if (webContainer.visibility == View.VISIBLE) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                hideWorkspace()
            }
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1001)
        }
    }
}
