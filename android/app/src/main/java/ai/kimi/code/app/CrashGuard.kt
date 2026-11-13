package ai.kimi.code.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃兜底。
 *
 * 2.19.0 的首页因为一个空 layoutParams 在 onCreate 里直接 NPE，用户只看到
 * 白屏闪退，没有任何可用的信息。这里做两件事：
 *
 *  1. 任何未捕获异常先写进 filesDir/last-crash.txt，再交回系统默认处理；
 *  2. 下次启动时把这份堆栈取出来，首页顶部显示一条红条，点一下整段复制到
 *     剪贴板——用户粘出来就能定位，不用连电脑抓 logcat。
 */
object CrashGuard {

    private const val FILE = "last-crash.txt"

    /** 崩之前用户停在哪一页，写进日志帮助定位。 */
    @Volatile
    var route: String = "启动"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(app, thread, error)
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(context: Context, thread: Thread, error: Throwable) {
        runCatching {
            val trace = StringWriter()
            error.printStackTrace(PrintWriter(trace))
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            val text = buildString {
                append("时间：").append(stamp).append('\n')
                append("页面：").append(route).append('\n')
                append("线程：").append(thread.name).append('\n')
                append("版本：").append(version(context)).append('\n')
                append("机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                append(" / Android ").append(Build.VERSION.RELEASE).append('\n')
                append("----\n")
                append(trace)
            }
            File(context.filesDir, FILE).writeText(text)
        }
    }

    fun record(context: Context, error: Throwable) {
        record(context, Thread.currentThread(), error)
    }

    /** 取出上次的崩溃并删除，避免每次启动都提示。 */
    fun consume(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        file.delete()
        return text?.takeIf { it.isNotBlank() }
    }

    fun copyToClipboard(context: Context, text: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("kimi-crash", text))
        }
    }

    /** 一行摘要，给红条当正文。 */
    fun summary(text: String): String {
        val firstFrame = text.lineSequence()
            .firstOrNull { it.trimStart().startsWith("at ") }
            ?.trim()
            ?: "未知异常"
        val type = text.lineSequence()
            .firstOrNull { it.contains("Exception") || it.contains("Error") }
            ?.trim()
            ?: "未知异常"
        return "$type\n$firstFrame"
    }

    private fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
