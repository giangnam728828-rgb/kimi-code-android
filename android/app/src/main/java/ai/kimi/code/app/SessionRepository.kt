package ai.kimi.code.app

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 会话列表的数据源。
 *
 * 官方 dist-web 的移动端把会话放在一个底部 sheet 里，没有 URL 深链，也没
 * 有「当前会话」的持久化接口。原生首页要列出会话，只能直接读本机服务端
 * 的 REST 接口：`GET /api/v1/sessions` 与 `GET /api/v1/workspaces`。
 *
 * 只读，不写。所有网络请求都在后台线程，回调切回主线程。
 */
object SessionRepository {

    data class Session(
        val id: String,
        val title: String,
        val workspaceId: String,
        val updatedAt: Long,
        val createdAt: Long,
        val busy: Boolean,
        val pending: Boolean,
        val archived: Boolean,
        val cwd: String,
        val messages: Int,
    )

    data class Workspace(
        val id: String,
        val name: String,
        val root: String,
        val sessions: Int,
    )

    data class Snapshot(
        val sessions: List<Session>,
        val workspaces: List<Workspace>,
    )

    private val main = Handler(Looper.getMainLooper())
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    fun load(port: Int, token: String?, onDone: (Snapshot?) -> Unit) {
        Thread {
            val snapshot = runCatching { fetch(port, token) }.getOrNull()
            main.post { onDone(snapshot) }
        }.apply { isDaemon = true }.start()
    }

    private fun fetch(port: Int, token: String?): Snapshot {
        val sessionsJson = get(port, token, "/api/v1/sessions") ?: return Snapshot(emptyList(), emptyList())
        val workspacesJson = get(port, token, "/api/v1/workspaces") ?: JSONObject()

        val workspaces = mutableListOf<Workspace>()
        workspacesJson.optJSONObject("data")?.optJSONArray("items")?.let { items ->
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                workspaces.add(
                    Workspace(
                        id = o.optString("id"),
                        name = o.optString("name").ifBlank { "workspace" },
                        root = o.optString("root"),
                        sessions = o.optInt("session_count"),
                    )
                )
            }
        }

        val sessions = mutableListOf<Session>()
        sessionsJson.optJSONObject("data")?.optJSONArray("items")?.let { items ->
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                if (o.optBoolean("archived")) continue
                sessions.add(
                    Session(
                        id = o.optString("id"),
                        title = o.optString("title").ifBlank { "新会话" },
                        workspaceId = o.optString("workspace_id"),
                        updatedAt = parse(o.optString("updated_at")),
                        createdAt = parse(o.optString("created_at")),
                        busy = o.optBoolean("busy") || o.optBoolean("main_turn_active"),
                        pending = o.optString("pending_interaction") !in listOf("", "none"),
                        archived = o.optBoolean("archived"),
                        cwd = o.optJSONObject("metadata")?.optString("cwd").orEmpty(),
                        messages = o.optInt("message_count"),
                    )
                )
            }
        }
        sessions.sortByDescending { it.updatedAt }
        return Snapshot(sessions, workspaces)
    }

    private fun get(port: Int, token: String?, path: String): JSONObject? {
        val conn = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 6000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(value: String): Long = runCatching { iso.parse(value)?.time ?: 0L }.getOrDefault(0L)

    // ---- grouping ----------------------------------------------------------

    /** 今天 / 本周 / 更早，和 DSH 抽屉里的分组一致。 */
    fun bucket(time: Long, now: Long = System.currentTimeMillis()): String {
        if (time <= 0L) return "更早"
        val today = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (time >= today.timeInMillis) return "今天"
        if (time >= today.timeInMillis - 6L * 24 * 3600 * 1000) return "本周"
        return "更早"
    }

    fun relative(time: Long, now: Long = System.currentTimeMillis()): String {
        if (time <= 0L) return ""
        val diff = now - time
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3600_000 -> "${diff / 60_000} 分钟前"
            diff < 86400_000 -> "${diff / 3600_000} 小时前"
            diff < 7 * 86400_000L -> "${diff / 86400_000} 天前"
            else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(time))
        }
    }
}
