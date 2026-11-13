package ai.kimi.code.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 手机操控的开关、token 与 MCP 接线。
 *
 * 链路：容器里的 `phone-mcp` 进程（stdio MCP，模型直接看到 phone_* 工具）
 *   → HTTP 127.0.0.1:<port>（只绑本机，带 token）
 *   → [PhoneBridge] → [PhoneAccessibilityService]。
 *
 * 之所以用 MCP 而不是「教模型敲 shell 命令」：kimi-cli 自带 MCP 客户端，
 * 工具会以原生工具的形式进入模型上下文，参数有 schema，失败有结构化错误，
 * 不会出现模型把 `phone tap 100 200` 写成别的格式的情况。
 */
object PhoneAccess {

    const val PREFS = "phone_access"
    private const val KEY_TOKEN = "token"
    private const val KEY_PORT = "port"

    private val main = Handler(Looper.getMainLooper())

    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        if (!manager.isEnabled) return false
        val expected = "${context.packageName}/${PhoneAccessibilityService::class.java.name}"
        val enabled = manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabled.any { it.id == expected || it.resolveInfo.serviceInfo.name == PhoneAccessibilityService::class.java.name }
    }

    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun token(context: Context): String {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = sp.getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = (1..24).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        sp.edit().putString(KEY_TOKEN, fresh).apply()
        return fresh
    }

    /** Starts the HTTP bridge (idempotent) and returns its port, or 0. */
    fun ensureBridge(context: Context): Int = PhoneBridge.start(context)

    fun port(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PORT, 0)

    /**
     * Writes everything the container needs:
     *   /opt/kimi/bin/phone-mcp.js   the MCP server
     *   /root/.kimi-code/phone.json  url + token
     *   /root/.kimi-code/mcp.json    registers the server with kimi-cli
     *
     * Called after the runtime is installed and on every start, so an upgraded
     * app replaces an old script without the user doing anything.
     */
    fun installIntoContainer(context: Context) {
        val bridgePort = ensureBridge(context)
        if (bridgePort == 0) return
        val root = File(Paths.rootfs(context), "root")
        val home = File(root, ".kimi-code")
        val bin = File(Paths.rootfs(context), "opt/kimi/bin")
        if (!bin.isDirectory && !bin.mkdirs()) return
        if (!home.isDirectory && !home.mkdirs()) return

        // 1) script
        val script = File(bin, "phone-mcp.js")
        runCatching {
            context.assets.open("phone-mcp.js").use { input ->
                script.outputStream().use { output -> input.copyTo(output) }
            }
            script.setExecutable(true)
        }

        // 2) endpoint file
        val endpoint = JSONObject()
            .put("url", "http://127.0.0.1:$bridgePort")
            .put("token", token(context))
        File(home, "phone.json").writeText(endpoint.toString(2))

        // 3) mcp.json — merge, never clobber another server
        val mcpFile = File(home, "mcp.json")
        val rootJson = runCatching {
            if (mcpFile.isFile) JSONObject(mcpFile.readText()) else JSONObject()
        }.getOrElse { JSONObject() }
        val servers = rootJson.optJSONObject("mcpServers") ?: JSONObject()
        servers.put(
            "phone",
            JSONObject()
                .put("transport", "stdio")
                .put("command", "/opt/node/bin/node")
                .put("args", JSONArray().put("/opt/kimi/bin/phone-mcp.js"))
                .put("enabled", true)
                .put("toolTimeoutMs", 20000),
        )
        rootJson.put("mcpServers", servers)
        runCatching { mcpFile.writeText(rootJson.toString(2)) }
        Runtime.log("手机操控：MCP 已注册（端口 $bridgePort）")
    }

    /** Runs [block] on the main thread and waits for its result. */
    fun <T> onMain(timeoutSeconds: Long = 6, fallback: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var value = fallback
        val latch = CountDownLatch(1)
        main.post {
            value = runCatching { block() }.getOrElse { fallback }
            latch.countDown()
        }
        latch.await(timeoutSeconds, TimeUnit.SECONDS)
        return value
    }
}
