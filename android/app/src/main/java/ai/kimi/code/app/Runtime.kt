package ai.kimi.code.app

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

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
        this.port = port
        this.workspaceUrl = url
        update(Phase.READY, "运行中", "工作台已就绪（端口 $port）")
    }

    fun notifyChanged() {
        if (listeners.isEmpty()) return
        mainHandler.post { listeners.forEach { it() } }
    }
}
