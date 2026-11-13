package ai.kimi.code.app

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single source of truth for the on-device runtime state. The service writes,
 * the activity renders. Everything is guarded by simple volatility: the UI only
 * reads a snapshot on the main thread.
 */
object Runtime {
    enum class Phase {
        IDLE,
        INSTALLING,
        STARTING,
        READY,
        ERROR,
        STOPPED,
    }

    @Volatile var phase: Phase = Phase.IDLE
    @Volatile var title: String = "尚未启动"
    @Volatile var detail: String = ""
    @Volatile var progress: Int = -1
    @Volatile var port: Int = 0
    @Volatile var workspaceUrl: String = ""
    /** Wall clock of the last READY, for the run page's uptime. */
    @Volatile var readyAt: Long = 0L
    /**
     * Bumped on every log line. The UI uses it to skip rebuilding the whole
     * log TextView when nothing actually changed (see MainActivity.render).
     */
    @Volatile var logVersion: Int = 0

    private const val MAX_LOG_LINES = 500
    private val logLines = ArrayDeque<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun snapshotLogs(): List<String> = synchronized(logLines) { logLines.toList() }

    fun log(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        }
        logVersion++
        notifyChanged()
    }

    fun update(phase: Phase, title: String, detail: String = "", progress: Int = -1) {
        this.phase = phase
        this.title = title
        this.detail = detail
        this.progress = progress
        notifyChanged()
    }

    fun markReady(port: Int, url: String) {
        readyAt = System.currentTimeMillis()
        this.port = port
        this.workspaceUrl = url
        update(Phase.READY, "运行中", "工作台已就绪（端口 $port）")
    }

    // During install/extract proot can emit hundreds of log lines per second.
    // Coalesce them: only one notification runnable is ever queued, so the main
    // thread renders the latest state once per frame instead of once per line.
    private val notifyPending = AtomicBoolean(false)
    private val notifyRunnable = Runnable {
        notifyPending.set(false)
        listeners.forEach { it() }
    }

    fun notifyChanged() {
        if (listeners.isEmpty()) return
        if (notifyPending.compareAndSet(false, true)) {
            mainHandler.post(notifyRunnable)
        }
    }
}
