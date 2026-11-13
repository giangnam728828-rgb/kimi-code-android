package ai.kimi.code.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 2.17.0：让工作台一打开就是「可执行」状态。
 *
 * Kimi Web 对每个工作目录有一次性信任门槛（`workspace.requireTrust`）：没信任
 * 之前连消息都发不出去，项目级配置也不会加载。手机上这个确认弹层很容易被忽略，
 * 表现就是「AI 什么都干不了」。App 在服务就绪后直接把**自己配置的工作目录**
 * 登记并信任，等于替用户点了一次「Trust project」。
 */
object WorkspaceBootstrap {

    /** 4 秒节流：页面每次加载都会调一次，避免连续 PATCH。 */
    @Volatile private var lastAttemptAt = 0L

    fun ensureTrusted(context: Context, port: Int) {
        val token = ProotRuntime.readToken(context) ?: return
        val workdir = ProotRuntime.currentWorkdir(context)
        if (workdir.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < 4000) return
        lastAttemptAt = now
        Thread {
            runCatching {
                // 2.18.1：服务就绪时网页往往还没打开，工作区尚未登记；旧实现只
                // 查一次就放弃，新装用户因此永远拿不到 /storage/emulated/0 的
                // 附加目录 —— Agent 只能看见 KimiCode 子目录，看不到 Download、
                // DCIM、Documents，表现就是「不能查找我的手机文件夹」。
                // 这里最多等 4.5 秒，让网页把工作区建出来。
                var id: String? = null
                for (attempt in 0..3) {
                    id = find(port, token, workdir)
                    if (id != null) break
                    if (attempt < 3) Thread.sleep(1500)
                }
                if (id == null) {
                    // 服务端按 root 建工作区，这里不抢先创建：否则网页首次打开
                    // 会再建一个同路径的空工作区，侧边栏出现两条同名记录。
                    Runtime.log("工作区尚未登记，等网页首次打开时自动创建：$workdir")
                    return@runCatching
                }
                grantPhoneStorage(context, port, token, id, workdir)
                val ok = request(port, token, "/api/v1/workspaces/$id/trust", "POST", "{}") != null
                Runtime.log(
                    if (ok) "工作区已信任：$workdir"
                    else "工作区信任失败：$workdir",
                )
            }.onFailure { Runtime.log("工作区信任异常：${it.message}") }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 把整个手机共享存储挂进 workspace 的 `additional_dirs`，Agent 的
     * Read / Write / Edit / Glob / Grep 才能作用在用户真实文件上。
     *
     * 2.18.1 修正：旧实现只要工作区在 `/storage/emulated/0` 下就直接 return，
     * 而默认工作区正是 `/storage/emulated/0/KimiCode` —— 等于新装用户从来没
     * 拿到过父目录授权。现在只有工作区本身就是存储根时才跳过。
     */
    private fun grantPhoneStorage(context: Context, port: Int, token: String, id: String, workdir: String) {
        val phone = "/storage/emulated/0"
        if (!java.io.File(phone).isDirectory) {
            Runtime.log("没有可访问的手机存储目录（可能未授予存储权限）")
            return
        }
        if (workdir.trimEnd('/') == phone) return
        val list = request(port, token, "/api/v1/workspaces", "GET", null) ?: return
        val items = runCatching {
            JSONObject(list).optJSONObject("data")?.optJSONArray("items")
        }.getOrNull() ?: return
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("id") != id) continue
            val dirs = item.optJSONArray("additional_dirs") ?: org.json.JSONArray()
            for (j in 0 until dirs.length()) {
                if (dirs.optString(j).trimEnd('/') == phone) return
            }
            val next = org.json.JSONArray()
            for (j in 0 until dirs.length()) next.put(dirs.optString(j))
            next.put(phone)
            val body = JSONObject().put("additional_dirs", next).toString()
            val ok = request(port, token, "/api/v1/workspaces/$id", "PATCH", body) != null
            Runtime.log(if (ok) "已允许 Agent 访问手机存储：$phone" else "手机存储授权失败")
            return
        }
    }

    private fun find(port: Int, token: String, workdir: String): String? {
        val list = request(port, token, "/api/v1/workspaces", "GET", null) ?: return null
        val items = runCatching {
            JSONObject(list).optJSONObject("data")?.optJSONArray("items")
        }.getOrNull() ?: return null
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val root = item.optString("root").trimEnd('/')
            if (root == workdir.trimEnd('/')) {
                val id = item.optString("id")
                if (id.isNotBlank()) return id
            }
        }
        return null
    }

    private fun request(port: Int, token: String, path: String, method: String, body: String?): String? {
        var connection: HttpURLConnection? = null
        return runCatching {
            connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
            val code = connection!!.responseCode
            if (code !in 200..299) return null
            connection!!.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }
}
