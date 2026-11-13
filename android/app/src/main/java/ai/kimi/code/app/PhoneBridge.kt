package ai.kimi.code.app

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 手机操控的本地 HTTP 桥。
 *
 * 只接受 127.0.0.1 的连接，每个请求都要带 `X-Phone-Token`。容器与宿主共享
 * 网络命名空间，所以 proot 里的 MCP 进程能直接访问 127.0.0.1。
 *
 * 协议刻意做成极简的 POST /phone/<tool> + JSON：
 * {"ok":true,"result":"..."} / {"ok":false,"error":"..."}
 */
object PhoneBridge {

    private const val HEADER_TOKEN = "X-Phone-Token"
    private var server: ServerSocket? = null
    @Volatile private var runningPort = 0

    @Synchronized
    fun start(context: Context): Int {
        server?.let { if (!it.isClosed) return runningPort }
        return runCatching {
            val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            server = socket
            runningPort = socket.localPort
            context.getSharedPreferences(PhoneAccess.PREFS, Context.MODE_PRIVATE)
                .edit().putInt("port", runningPort).apply()
            Thread { acceptLoop(socket, context.applicationContext) }
                .apply { isDaemon = true; name = "phone-bridge" }
                .start()
            Runtime.log("手机操控：本地桥监听 127.0.0.1:$runningPort")
            runningPort
        }.getOrElse {
            Runtime.log("手机操控：本地桥启动失败 ${it.message}")
            0
        }
    }

    private fun acceptLoop(socket: ServerSocket, context: Context) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            Thread { serve(client, context) }.apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket, context: Context) {
        client.use { socket ->
            socket.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val path = parts.getOrNull(1) ?: return
            var length = 0
            var token = ""
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val name = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    when {
                        name.equals("Content-Length", true) -> length = value.toIntOrNull() ?: 0
                        name.equals(HEADER_TOKEN, true) -> token = value
                    }
                }
            }
            val body = if (length > 0) {
                val buffer = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = reader.read(buffer, read, length - read)
                    if (n < 0) break
                    read += n
                }
                String(buffer, 0, read)
            } else ""

            val expected = PhoneAccess.token(context)
            val response = when {
                path == "/health" -> JSONObject().put("ok", true).put("result", "ok").toString()
                token != expected -> error("unauthorized")
                !path.startsWith("/phone/") -> error("not found")
                else -> handle(path.removePrefix("/phone/"), body)
            }
            val bytes = response.toByteArray(Charsets.UTF_8)
            val out = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            out.write("HTTP/1.1 200 OK\r\n")
            out.write("Content-Type: application/json; charset=utf-8\r\n")
            out.write("Content-Length: ${bytes.size}\r\n")
            out.write("Connection: close\r\n\r\n")
            out.write(response)
            out.flush()
        }
    }

    private fun error(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    private fun handle(tool: String, body: String): String {
        val args = runCatching { if (body.isBlank()) JSONObject() else JSONObject(body) }.getOrElse { JSONObject() }
        val service = PhoneAccessibilityService.instance
            ?: return JSONObject().put("ok", false)
                .put("error", "无障碍服务未开启。请到「设置 → 能力 → 手机操控」按提示开启。")
                .toString()

        return runCatching {
            val result: String = when (tool) {
                "screen" -> service.screen(args.optInt("limit", 120))
                "apps" -> service.apps(args.optString("query"), args.optInt("limit", 40))
                "open" -> service.open(args.optString("target"))
                "tap" -> service.tapText(args.optString("text"))
                "tap_xy" -> {
                    val ok = service.tapXY(args.optInt("x"), args.optInt("y"))
                    if (ok) "已点击" else "点击失败"
                }
                "long_press" -> {
                    val ok = service.longPress(args.optInt("x"), args.optInt("y"))
                    if (ok) "已长按" else "长按失败"
                }
                "type" -> service.type(args.optString("text"))
                "scroll" -> service.scroll(args.optString("direction", "down"))
                "swipe" -> {
                    val ok = service.swipe(
                        args.optInt("x1"), args.optInt("y1"),
                        args.optInt("x2"), args.optInt("y2"),
                        args.optLong("duration_ms", 320),
                    )
                    if (ok) "已滑动" else "滑动失败"
                }
                "key" -> service.key(args.optString("name", "back"))
                "clipboard" -> service.clipboard(args.optBoolean("read", true), args.optString("text"))
                "screenshot" -> service.screenshot()
                "back" -> service.key("back")
                "home" -> service.key("home")
                else -> return error("unknown tool: $tool")
            }
            JSONObject().put("ok", true).put("result", result).toString()
        }.getOrElse {
            JSONObject().put("ok", false).put("error", "执行失败：${it.message}").toString()
        }
    }
}
