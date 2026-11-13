package ai.kimi.code.app

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 2.17.0：把「全自主执行」注入网页。
 *
 * 网页的权限模式是**启动时**从 localStorage（`kimi-web.permission`）读的，
 * 默认 `manual`：每一次读文件 / 跑命令都要用户点一次批准。手机上这个批准条
 * 又小又容易滑过去，看起来就像 AI「不会自己动手」。
 *
 * 页面 CSP 禁止内联脚本，但允许同源 `/boot.js`（它在主 bundle 之前同步执行）。
 * 这里在 WebView 请求 `boot.js` 时把设置写进响应末尾，页面初始化前就生效，
 * 不改动 rootfs 里的任何文件，升级网页版本也不会失效。
 */
object WebBootInjector {

    private const val SCRIPT_NAME = "boot.js"
    private var cachedPort = -1
    private var cachedScript: String? = null

    /** 返回注入了权限设置的 boot.js；未开启全自主或拉取失败时返回 null。 */
    fun autonomyScript(port: Int, token: String?): String? {
        if (port <= 0) return null
        if (cachedPort == port) return cachedScript
        val source = fetch(port, token) ?: return null
        val script = source + "\n" + AUTONOMY_JS + "\n"
        cachedPort = port
        cachedScript = script
        return script
    }

    fun invalidate() {
        cachedPort = -1
        cachedScript = null
    }

    private fun fetch(port: Int, token: String?): String? {
        var connection: HttpURLConnection? = null
        return runCatching {
            connection = (URL("http://127.0.0.1:$port/$SCRIPT_NAME").openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 2500
                if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            if (connection!!.responseCode !in 200..299) return null
            connection!!.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    /**
     * `auto` = Never Ask：工具调用、命令执行、文件改写全部自动决定，只有
     * Plan 模式的方案还需要人看。与设置页「全自主执行」开关一一对应。
     */
    private const val AUTONOMY_JS =
        ";(function(){try{localStorage.setItem('kimi-web.permission','auto');" +
            "localStorage.setItem('kimi-web.permission-sticky-at',String(Date.now()));}catch(e){}})();"

    fun stream(script: String): ByteArrayInputStream = ByteArrayInputStream(script.toByteArray())
}
