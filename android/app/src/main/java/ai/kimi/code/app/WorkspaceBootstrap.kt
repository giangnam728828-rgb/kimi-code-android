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

    fun ensureTrusted(context: Context, port: Int) {
        val token = ProotRuntime.readToken(context) ?: return
        val workdir = ProotRuntime.currentWorkdir(context)
        if (workdir.isBlank()) return
        Thread {
            runCatching {
                val id = find(port, token, workdir)
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
     * 老用户的工作区还在 `/root/workspace`（换目录会让历史会话看起来消失），
     * 但手机共享存储依然要能被工具访问。把它加进 workspace 的
     * `additional_dirs`，Agent 就能读写 /storage/emulated/0 下的真实文件。
     */
    private fun grantPhoneStorage(context: Context, port: Int, token: String, id: String, workdir: String) {
        val phone = "/storage/emulated/0"
        if (!java.io.File(phone).isDirectory) {
            Runtime.log("没有可访问的手机存储目录（可能未授予存储权限）")
            return
        }
        if (workdir.startsWith(phone)) return   // 工作区本身就在手机存储里
        val list = request(port, token, "/api/v1/workspaces", "GET", null) ?: return
        val items = runCatching {
            JSONObject(list).optJSONObject("data")?.optJSONArray("items")
        }.getOrNull() ?: return
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("id") != id) continue
            val dirs = item.optJSONArray("additional_dirs") ?: continue
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
