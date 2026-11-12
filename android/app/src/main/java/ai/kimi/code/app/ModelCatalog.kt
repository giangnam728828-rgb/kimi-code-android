package ai.kimi.code.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * One model returned by an upstream `{base_url}/v1/models` catalogue.
 *
 * [id] is exactly what must be sent as the `model` field. It is deliberately
 * never prettified: gateways reject display names such as `Spark-X2.6-4B` with
 * `no category route found`, so the picker must surface the raw upstream id.
 */
data class CatalogModel(
    val id: String,
    val vendorKey: String,
    val vendor: String,
    val contextSize: Int,
)

/** Result of a catalogue fetch. [error] is null on success. */
data class CatalogResult(
    val ids: List<String> = emptyList(),
    val error: String? = null,
    val url: String? = null,
)

/** Result of a one-token smoke request against a concrete model. */
data class ProbeResult(val ok: Boolean, val message: String)

/**
 * Model discovery for OpenAI-compatible endpoints.
 *
 * Zero third-party dependencies: the app has no OkHttp/Retrofit and adding one
 * would change the release build chain. Everything here is plain JVM + Android
 * APIs, so the whole file is unit-testable on the JVM. Network calls MUST run
 * off the main thread; callers use a background Thread and post back to UI.
 */
object ModelCatalog {

    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 15000
    private const val PROBE_TIMEOUT_MS = 25000

    // ------------------------------------------------------------- vendor rules

    private data class Rule(
        val key: String,
        val label: String,
        val needles: List<String>,
        val contextSize: Int,
        val order: Int,
    )

    /** Ordered: specific gateway ids must be matched before the generic `xop` prefix. */
    private val RULES = listOf(
        Rule("moonshot", "Moonshot / Kimi", listOf("kimi", "moonshot"), 262144, 1),
        Rule("deepseek", "DeepSeek", listOf("deepseek", "xopds", "ds-v"), 131072, 2),
        Rule("anthropic", "Anthropic Claude", listOf("claude"), 200000, 3),
        Rule("openai", "OpenAI", listOf("gpt-", "gpt4", "chatgpt", "o1-", "o3-", "o4-", "codex"), 128000, 4),
        Rule("google", "Google Gemini", listOf("gemini"), 1048576, 5),
        Rule("glm", "智谱 GLM", listOf("glm-", "xopglm"), 1048576, 6),
        Rule("qwen", "通义千问", listOf("qwen", "qwq", "qvq"), 131072, 7),
        Rule("spark", "讯飞星火", listOf("spark", "xspark"), 131072, 8),
        Rule("minimax", "MiniMax", listOf("minimax", "xminimax"), 131072, 9),
        Rule("doubao", "豆包", listOf("doubao", "seed-"), 131072, 10),
        Rule("ernie", "文心", listOf("ernie", "wenxin"), 131072, 11),
        Rule("hunyuan", "混元", listOf("hunyuan"), 131072, 12),
        Rule("opensource", "开源模型", listOf("llama", "mistral", "mixtral", "gemma", "phi-"), 131072, 13),
        Rule("gateway", "聚合网关", listOf("xop", "astron", "auto", "claw"), 131072, 14),
    )

    private val FALLBACK = Rule("other", "其他", emptyList(), 131072, 99)

    private fun ruleFor(id: String): Rule {
        val lower = id.lowercase()
        return RULES.firstOrNull { r -> r.needles.any { lower.contains(it) } } ?: FALLBACK
    }

    /** Conservative context guess; the user can still override it in advanced options. */
    fun contextSizeFor(id: String): Int {
        val lower = id.lowercase()
        return when {
            lower.contains("glm-4") -> 128000
            lower.contains("gpt-4o") || lower.contains("gpt-4.1") -> 128000
            lower.contains("claude") -> 200000
            lower.contains("kimi") || lower.contains("moonshot") -> 262144
            else -> ruleFor(id).contextSize
        }
    }

    fun describe(id: String): CatalogModel {
        val rule = ruleFor(id)
        return CatalogModel(id, rule.key, rule.label, contextSizeFor(id))
    }

    fun describeAll(ids: List<String>): List<CatalogModel> = ids.map(::describe)

    /** Groups models by vendor, keeping the vendor order stable and ids sorted inside a group. */
    fun group(models: List<CatalogModel>): List<Pair<String, List<CatalogModel>>> =
        models.groupBy { it.vendorKey }
            .toSortedMap(compareBy { ruleOrder(it) })
            .map { (key, items) ->
                (RULES.firstOrNull { it.key == key }?.label ?: FALLBACK.label) to
                    items.sortedBy { it.id.lowercase() }
            }

    private fun ruleOrder(key: String): Int =
        RULES.firstOrNull { it.key == key }?.order ?: FALLBACK.order

    /**
     * Multi-term fuzzy filter. Every term must match somewhere; ids starting
     * with a term rank first, then substring matches, then vendor matches.
     */
    fun search(models: List<CatalogModel>, query: String): List<CatalogModel> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return models
        return models.mapNotNull { model ->
            val id = model.id.lowercase()
            val vendor = model.vendor.lowercase()
            if (terms.any { !id.contains(it) && !vendor.contains(it) }) return@mapNotNull null
            var score = 0
            for (t in terms) {
                score += when {
                    id.startsWith(t) -> 100
                    id.contains(t) -> 40
                    else -> 5
                }
            }
            score -= id.length / 100 // prefer short exact ids over long variants
            model to score
        }.sortedWith(compareByDescending<Pair<CatalogModel, Int>> { it.second }
            .thenBy { it.first.id.lowercase() })
            .map { it.first }
    }

    // -------------------------------------------------------------- URL shaping

    /** Strip a concrete endpoint suffix so users can paste either a base or a full URL. */
    private fun cleanBase(baseUrl: String): String {
        var b = baseUrl.trim().trimEnd('/')
        for (suffix in listOf("/chat/completions", "/completions", "/responses", "/messages", "/models")) {
            if (b.endsWith(suffix)) {
                b = b.removeSuffix(suffix).trimEnd('/')
                break
            }
        }
        return b
    }

    /** Candidate `/models` URLs, most likely first. */
    fun modelUrls(baseUrl: String): List<String> {
        val b = cleanBase(baseUrl)
        if (b.isEmpty() || (!b.startsWith("http://") && !b.startsWith("https://"))) return emptyList()
        val out = LinkedHashSet<String>()
        out.add("$b/models")
        if (!b.endsWith("/v1")) out.add("$b/v1/models")
        return out.toList()
    }

    /** Candidate chat endpoints for the smoke probe. */
    fun chatUrls(baseUrl: String): List<String> {
        val b = cleanBase(baseUrl)
        if (b.isEmpty() || (!b.startsWith("http://") && !b.startsWith("https://"))) return emptyList()
        val out = LinkedHashSet<String>()
        if (b.endsWith("/v1")) {
            out.add("$b/chat/completions")
        } else {
            out.add("$b/v1/chat/completions")
            out.add("$b/chat/completions")
        }
        return out.toList()
    }

    // ----------------------------------------------------------------- fetching

    /**
     * GET the upstream model list. Tries every candidate URL; a 404 simply
     * means that shape is wrong, so we move on. A 401/403 is reported verbatim
     * because retrying another URL with the same bad key is pointless.
     */
    fun fetch(baseUrl: String, apiKey: String, type: String = "openai"): CatalogResult {
        val urls = modelUrls(baseUrl)
        if (urls.isEmpty()) return CatalogResult(error = "请先填写接口地址（https://…/v1）")
        var lastError = "接口地址无法访问"
        for (u in urls) {
            val response: Pair<Int, String>? = try {
                httpGet(u, apiKey, type, READ_TIMEOUT_MS)
            } catch (e: Exception) {
                lastError = friendlyNetworkError(e)
                null
            }
            if (response == null) continue
            val code = response.first
            val body = response.second
            when {
                code in 200..299 -> {
                    val ids = parseModelIds(body)
                    return if (ids.isEmpty()) {
                        CatalogResult(error = "接口返回成功但没有模型列表，可手动输入模型名", url = u)
                    } else {
                        CatalogResult(ids = ids, url = u)
                    }
                }
                code == 401 || code == 403 ->
                    return CatalogResult(error = "API Key 无效或没有权限（HTTP $code）${brief(body)}", url = u)
                code == 404 -> lastError = "该地址没有 /models 接口（HTTP 404）"
                code in 500..599 -> lastError = "上游服务异常（HTTP $code）"
                else -> lastError = "接口返回 HTTP $code ${brief(body)}"
            }
        }
        return CatalogResult(error = lastError, url = urls.first())
    }

    /**
     * One-token smoke test of a concrete model. Surfaces gateway business
     * errors such as `no category route found` before the user saves.
     */
    fun probe(baseUrl: String, apiKey: String, model: String, type: String = "openai"): ProbeResult {
        if (type == "anthropic") return ProbeResult(false, "Anthropic 端点不支持自动验证，请直接保存后在工作台里试")
        val urls = chatUrls(baseUrl)
        if (urls.isEmpty()) return ProbeResult(false, "请先填写接口地址")
        val payload = "{\"model\":${jsonString(model)},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]," +
            "\"max_tokens\":1,\"stream\":false}"
        var last = "无法连接接口地址"
        for (u in urls) {
            val response: Pair<Int, String>? = try {
                httpPost(u, apiKey, payload, type, PROBE_TIMEOUT_MS)
            } catch (e: Exception) {
                last = friendlyNetworkError(e)
                null
            }
            if (response == null) continue
            val code = response.first
            val body = response.second
            if (code in 200..299) return ProbeResult(true, "模型可用，上游已正常响应")
            val detail = brief(body)
            if (code == 401 || code == 403) return ProbeResult(false, "API Key 无效或没有权限（HTTP $code）$detail")
            last = when (code) {
                404 -> "该地址没有对话接口（HTTP 404）$detail"
                429 -> "上游限流（HTTP 429）$detail"
                in 500..599 -> "上游服务异常（HTTP $code）$detail"
                else -> "模型被上游拒绝（HTTP $code）$detail"
            }
        }
        return ProbeResult(false, last)
    }

    private fun brief(body: String): String {
        val t = body.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return ""
        val msg = Regex("\"(?:message|error|msg)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(t)?.groupValues?.get(1)
        val text = (msg ?: t).take(160)
        return " · $text"
    }

    private fun friendlyNetworkError(e: Throwable): String = when (e) {
        is java.net.SocketTimeoutException -> "连接超时，检查网络或接口地址"
        is java.net.UnknownHostException -> "域名无法解析，检查接口地址"
        is javax.net.ssl.SSLException -> "TLS/证书校验失败"
        else -> "网络请求失败：${e.message?.take(90) ?: e.javaClass.simpleName}"
    }

    private fun headers(conn: HttpURLConnection, apiKey: String, type: String) {
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/json")
        if (apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            if (type == "anthropic") {
                conn.setRequestProperty("x-api-key", apiKey)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun httpGet(url: String, apiKey: String, type: String, timeout: Int): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = timeout
            headers(conn, apiKey, type)
            conn.connect()
            readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun httpPost(
        url: String,
        apiKey: String,
        payload: String,
        type: String,
        timeout: Int,
    ): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = timeout
            headers(conn, apiKey, type)
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
            readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun readResponse(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
        } ?: ""
        return code to body
    }

    // ------------------------------------------------------------ JSON handling

    /**
     * Accepts the shapes real gateways return:
     * `{"data":[{"id":"x"}]}`, `{"data":["x"]}`, `{"models":[…]}`, `[{"id":"x"}]`,
     * `["x"]`, and a single `{"id":"x"}` object. Unknown shapes yield empty.
     */
    fun parseModelIds(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        val root = runCatching { JsonParser(json).parse() }.getOrNull() ?: return emptyList()
        val raw: Any? = when (root) {
            is Map<*, *> -> root["data"] ?: root["models"]
                ?: if (root.containsKey("id")) root else emptyList<Any?>()
            is List<*> -> root
            else -> emptyList<Any?>()
        }
        val out = LinkedHashSet<String>()
        fun collect(v: Any?) {
            when (v) {
                is String -> if (v.isNotBlank()) out.add(v.trim())
                is Map<*, *> -> {
                    val id = (v["id"] ?: v["name"] ?: v["model"]) as? String
                    if (!id.isNullOrBlank()) out.add(id.trim())
                }
                is List<*> -> v.forEach(::collect)
            }
        }
        collect(raw)
        return out.toList()
    }

    fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /** Minimal tolerant JSON reader; malformed input degrades to null, never throws. */
    private class JsonParser(private val s: String) {
        private var i = 0

        fun parse(): Any? {
            skipWs()
            val v = value()
            skipWs()
            return v
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun value(): Any? {
            if (i >= s.length) return null
            return when {
                s[i] == '{' -> obj()
                s[i] == '[' -> arr()
                s[i] == '"' -> str()
                s.startsWith("true", i) -> { i += 4; true }
                s.startsWith("false", i) -> { i += 5; false }
                s.startsWith("null", i) -> { i += 4; null }
                else -> num()
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (i < s.length) {
                skipWs()
                if (i >= s.length || s[i] != '"') break
                val k = str()
                skipWs()
                if (i >= s.length || s[i] != ':') break
                i++
                m[k] = value()
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') i++
                break
            }
            return m
        }

        private fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (i < s.length) {
                l.add(value())
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') i++
                break
            }
            return l
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) break
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 <= s.length) {
                                    val cp = s.substring(i, i + 4).toIntOrNull(16)
                                    if (cp != null) { sb.append(cp.toChar()); i += 4 } else sb.append(e)
                                } else sb.append(e)
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun num(): Any? {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull() ?: 0.0
        }
    }
}
