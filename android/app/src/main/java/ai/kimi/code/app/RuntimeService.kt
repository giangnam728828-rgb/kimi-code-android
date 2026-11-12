package ai.kimi.code.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Owns the whole local stack: install the rootfs on first run, launch proot,
 * wait for the server, expose the token, and keep it alive.
 *
 * Restarts are the delicate part. The WebView keeps login state and session
 * lists in localStorage, scoped to the `host:port` origin, so a restart must
 * reuse the exact same port. A [generation] counter also makes sure a stale
 * worker can never spawn a second server or kill the fresh one.
 */
class RuntimeService : Service() {

    companion object {
        const val ACTION_START = "ai.kimi.code.app.START"
        const val ACTION_STOP = "ai.kimi.code.app.STOP"
        const val ACTION_RESTART = "ai.kimi.code.app.RESTART"

        private const val CHANNEL_ID = "kimi_runtime"
        private const val NOTIFICATION_ID = 42
        private const val PORT_RELEASE_TIMEOUT_MS = 10_000L
        private const val STOP_TIMEOUT_MS = 6_000L

        fun start(context: Context) {
            val intent = Intent(context, RuntimeService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RuntimeService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }

        fun restart(context: Context) {
            // Flip the phase synchronously: startService delivers the intent
            // asynchronously, and without this the UI still sees READY and can
            // open the workspace against the process that is about to die.
            Runtime.update(Runtime.Phase.STARTING, "正在重启本地服务", "", -1)
            val intent = Intent(context, RuntimeService::class.java).setAction(ACTION_RESTART)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    @Volatile private var wantStop = false
    @Volatile private var worker: Thread? = null

    /** Bumped on every stop/restart; workers only act while theirs is current. */
    @Volatile private var generation = 0L
    private var port = Paths.DEFAULT_PORT

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在准备运行环境…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                generation++
                wantStop = true
                ProotRuntime.stopAndWait(STOP_TIMEOUT_MS)
                worker?.let { runCatching { it.interrupt(); it.join(2000) } }
                Runtime.update(Runtime.Phase.STOPPED, "已停止", "点击启动重新运行")
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                generation++
                wantStop = true
                // Wait for the old process to actually die BEFORE probing the
                // port. The previous implementation only called destroy() and
                // reset wantStop immediately, so the dying server still held
                // 58630 and pickPort() silently moved the workspace to 58631.
                ProotRuntime.stopAndWait(STOP_TIMEOUT_MS)
                worker?.let { runCatching { it.interrupt(); it.join(2000) } }
                startWorker()
                return START_STICKY
            }
            else -> Unit
        }

        if (worker?.isAlive != true) startWorker()
        return START_STICKY
    }

    private fun startWorker() {
        wantStop = false
        val epoch = generation
        worker = Thread({ runLoop(epoch) }, "kimi-runtime").apply {
            isDaemon = true
            start()
        }
    }

    private fun runLoop(epoch: Long) {
        var consecutiveFailures = 0
        while (!wantStop && epoch == generation) {
            try {
                if (!Paths.isRuntimeInstalled(this)) {
                    Runtime.update(Runtime.Phase.INSTALLING, "首次安装运行环境", "需要下载约 87 MB", 0)
                    notify("首次安装运行环境")
                    Installer.installProotRuntime(this)
                    Installer.installRootfs(this)
                } else {
                    Installer.installProotRuntime(this)
                }

                Runtime.update(Runtime.Phase.STARTING, "正在启动本地服务", "", -1)
                notify("正在启动本地服务")
                port = pickPort()
                val process = ProotRuntime.start(this, port)

                val token = ProotRuntime.awaitToken(this, port)
                if (token == null) {
                    throw IllegalStateException(explainLaunchFailure())
                }
                val url = "http://127.0.0.1:$port/#token=$token"
                Runtime.markReady(port, url)
                Runtime.log("工作台已就绪：$url")
                notify("运行中 · 端口 $port")
                consecutiveFailures = 0

                while (!wantStop && epoch == generation && process.isAlive) {
                    Thread.sleep(1500)
                }
                if (wantStop || epoch != generation) break
                Runtime.log("服务已退出，3 秒后自动重启")
                Thread.sleep(3000)
            } catch (error: InterruptedException) {
                break
            } catch (error: Throwable) {
                if (epoch != generation) break
                consecutiveFailures++
                val message = error.message ?: error.javaClass.simpleName
                Runtime.log("启动失败：$message")
                Runtime.update(Runtime.Phase.ERROR, "启动失败", message)
                notify("启动失败：$message")
                if (consecutiveFailures >= 3) {
                    Runtime.log("连续失败 3 次，已停止重试")
                    wantStop = true
                    break
                }
                runCatching { Thread.sleep(4000) }
            }
        }
        // A stale worker must not kill the process owned by the new generation.
        if (epoch == generation) ProotRuntime.stop()
    }

    /**
     * Turns the raw proot death into something the user can act on. The most
     * common on-device failures are a linker error (missing shared object) and
     * the Android 10+ W^X refusal to exec a writable file.
     */
    private fun explainLaunchFailure(): String {
        val logs = Runtime.snapshotLogs().joinToString("\n")
        val code = ProotRuntime.lastExitCode()
        return when {
            logs.contains("library") && logs.contains("not found") ->
                "缺少运行库（proot 无法加载），请查看日志"
            logs.contains("Permission denied") ->
                "proot 无法执行：请检查存储权限或重新安装"
            code == 126 || code == 127 ->
                "proot 无法执行（退出码 $code）"
            code == 139 || code == 11 ->
                "proot 崩溃（段错误），此机型暂不兼容"
            code != null ->
                "服务未能启动（proot 退出码 $code）"
            else ->
                "服务启动超时，请查看日志"
        }
    }

    /**
     * Reuse the port from the previous run so the WebView origin never changes.
     * If it is still held (usually the process we just stopped), wait for it to
     * be released instead of hopping to the next port.
     */
    private fun pickPort(): Int {
        val pinned = Prefs.fixedPort(this)
        if (pinned in 1024..65535) {
            if (waitForPortFree(pinned, PORT_RELEASE_TIMEOUT_MS)) return pinned
            Runtime.log("端口 $pinned 仍被占用，尝试寻找可用端口")
        }
        var candidate = Paths.DEFAULT_PORT
        repeat(20) {
            if (!ProotRuntime.isPortOpen(candidate)) {
                Prefs.setFixedPort(this, candidate)
                return candidate
            }
            candidate++
        }
        Prefs.setFixedPort(this, Paths.DEFAULT_PORT)
        return Paths.DEFAULT_PORT
    }

    private fun waitForPortFree(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!ProotRuntime.isPortOpen(port)) return true
            runCatching { Thread.sleep(200) }
        }
        return !ProotRuntime.isPortOpen(port)
    }

    private fun notify(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Kimi Code 本地运行时状态"
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        generation++
        wantStop = true
        ProotRuntime.stopAndWait(STOP_TIMEOUT_MS)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
