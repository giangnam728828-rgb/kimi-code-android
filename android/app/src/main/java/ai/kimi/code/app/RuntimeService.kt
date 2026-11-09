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
 */
class RuntimeService : Service() {

    companion object {
        const val ACTION_START = "ai.kimi.code.app.START"
        const val ACTION_STOP = "ai.kimi.code.app.STOP"
        const val ACTION_RESTART = "ai.kimi.code.app.RESTART"

        private const val CHANNEL_ID = "kimi_runtime"
        private const val NOTIFICATION_ID = 42

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
    private var port = Paths.DEFAULT_PORT

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在准备运行环境…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                wantStop = true
                ProotRuntime.stop()
                Runtime.update(Runtime.Phase.STOPPED, "已停止", "点击启动重新运行")
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                wantStop = true
                ProotRuntime.stop()
                worker?.let { runCatching { it.join(3000) } }
                wantStop = false
            }
            else -> Unit
        }

        if (worker?.isAlive != true) {
            wantStop = false
            worker = Thread({ runLoop() }, "kimi-runtime").apply {
                isDaemon = true
                start()
            }
        }
        return START_STICKY
    }

    private fun runLoop() {
        var consecutiveFailures = 0
        while (!wantStop) {
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

                while (!wantStop && process.isAlive) {
                    Thread.sleep(1500)
                }
                if (wantStop) break
                Runtime.log("服务已退出，3 秒后自动重启")
                Thread.sleep(3000)
            } catch (error: InterruptedException) {
                break
            } catch (error: Throwable) {
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
        ProotRuntime.stop()
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

    private fun pickPort(): Int {
        var candidate = Paths.DEFAULT_PORT
        repeat(20) {
            if (!ProotRuntime.isPortOpen(candidate)) return candidate
            candidate++
        }
        return Paths.DEFAULT_PORT
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
        wantStop = true
        ProotRuntime.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
