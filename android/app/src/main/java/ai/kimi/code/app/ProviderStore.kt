package ai.kimi.code.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2.16.0 数据层：一个「模型供应商」= 一个分组，组内每个模型可单独设置。
 *
 * 设计取舍：不推翻现有的 `Prefs.Entry` / `ConfigWriter` 链路（它负责登录态、
 * 服务端配置保留等一堆边界），而是在其上加一层更贴近 Eta 交互的模型。
 * 保存时通过 [toPrefsEntries] 降级写回 Prefs，配置生成仍走 ConfigWriter，
 * 逐模型的上下文 / 思考强度则由 [tuningFor] 注入到 `[models.*]` 段里。
 */
object ProviderStore {

    /** 单个模型的独立设置。 */
    data class ModelSpec(
        val id: String,
        val displayName: String = "",
        val contextSize: Int = 131072,
        val effort: String = "",
        val reasoning: Boolean = false,
        val enabled: Boolean = true,
        val source: String = SOURCE_MANUAL,
    ) {
        val label: String get() = displayName.ifBlank { id }
    }

    data class ProviderSpec(
        val alias: String,
        val name: String,
        val type: String = TYPE_OPENAI,
        val baseUrl: String = "",
        val apiKey: String = "",
        val endpointMode: String = ENDPOINT_CHAT,
        val customHeaders: String = "",
        val enabled: Boolean = true,
        val models: List<ModelSpec> = emptyList(),
        val defaultModel: String = "",
    ) {
        val usableModels: List<ModelSpec> get() = models.filter { it.id.isNotBlank() }
        val effectiveDefault: String
            get() = defaultModel.ifBlank { usableModels.firstOrNull()?.id.orEmpty() }
        val displayTitle: String get() = name.ifBlank { alias }
    }

    const val TYPE_OPENAI = "openai"
    const val TYPE_ANTHROPIC = "anthropic"
    const val TYPE_GEMINI = "gemini"
    const val TYPE_KIMI = "kimi"

    const val ENDPOINT_CHAT = "chat_completions"
    const val ENDPOINT_RESPONSES = "responses"
    const val ENDPOINT_MESSAGES = "messages"
    const val ENDPOINT_INTERACTIONS = "interactions"

    const val SOURCE_REMOTE = "remote"
    const val SOURCE_MANUAL = "manual"
    const val SOURCE_CATALOG = "catalog"

    const val PRESETS_FILE = "provider_store"
    private const val KEY_DATA = "providers_json"

    // ------------------------------------------------------------- catalog

    data class CatalogEntry(
        val key: String,
        val name: String,
        val type: String,
        val baseUrl: String,
        val endpointMode: String,
        val hint: String,
    )

    /** 「从目录添加」里的内置供应商清单，全部可编辑。 */
    val CATALOG = listOf(
        CatalogEntry("openai", "OpenAI", TYPE_OPENAI, "https://api.openai.com/v1", ENDPOINT_CHAT, "ChatGPT / GPT 系列"),
        CatalogEntry("anthropic", "Anthropic", TYPE_ANTHROPIC, "https://api.anthropic.com/v1", ENDPOINT_MESSAGES, "Claude 官方或兼容 API"),
        CatalogEntry("gemini", "Gemini", TYPE_GEMINI, "https://generativelanguage.googleapis.com/v1beta", ENDPOINT_INTERACTIONS, "Gemini 原生 Interactions API"),
        CatalogEntry("deepseek", "DeepSeek", TYPE_OPENAI, "https://api.deepseek.com/v1", ENDPOINT_CHAT, "deepseek-chat / reasoner"),
        CatalogEntry("moonshot", "Kimi（Moonshot）", TYPE_KIMI, "https://api.moonshot.cn/v1", ENDPOINT_CHAT, "Kimi K2 系列"),
        CatalogEntry("glm", "智谱 GLM", TYPE_OPENAI, "https://open.bigmodel.cn/api/paas/v4", ENDPOINT_CHAT, "GLM-4 系列"),
        CatalogEntry("qwen", "阿里百炼", TYPE_OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1", ENDPOINT_CHAT, "通义千问 / Qwen"),
        CatalogEntry("siliconflow", "硅基流动", TYPE_OPENAI, "https://api.siliconflow.cn/v1", ENDPOINT_CHAT, "开源模型聚合"),
        CatalogEntry("openrouter", "OpenRouter", TYPE_OPENAI, "https://openrouter.ai/api/v1", ENDPOINT_CHAT, "多厂商聚合网关"),
        CatalogEntry("sensenova", "商汤", TYPE_OPENAI, "https://token.sensenova.cn/v1", ENDPOINT_CHAT, "SenseNova / DeepSeek 系列"),
    )

    val TYPE_LABELS = mapOf(
        TYPE_OPENAI to "OpenAI-compatible",
        TYPE_ANTHROPIC to "Anthropic Messages",
        TYPE_GEMINI to "Gemini Interactions",
        TYPE_KIMI to "Kimi（Moonshot）",
    )

    val ENDPOINT_LABELS = mapOf(
        ENDPOINT_CHAT to "Chat Completions API",
        ENDPOINT_RESPONSES to "Responses API",
        ENDPOINT_MESSAGES to "Anthropic Messages API",
        ENDPOINT_INTERACTIONS to "Gemini Interactions API",
    )

    fun typeLabel(type: String): String = TYPE_LABELS[type] ?: "OpenAI-compatible"
    fun endpointLabel(mode: String): String = ENDPOINT_LABELS[mode] ?: "Chat Completions API"

    // ------------------------------------------------------------- persistence

    fun load(context: Context): MutableList<ProviderSpec> {
        val raw = context.getSharedPreferences(PRESETS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_DATA, null)
        if (raw.isNullOrBlank()) return migrateFromPrefs(context)
        return runCatching { decode(raw) }.getOrDefault(mutableListOf())
    }

    fun save(context: Context, providers: List<ProviderSpec>) {
        context.getSharedPreferences(PRESETS_FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_DATA, encode(providers))
            .apply()
    }

    /** 首次启动把旧的 Prefs.Entry 升级成供应商模型，配置不丢。 */
    private fun migrateFromPrefs(context: Context): MutableList<ProviderSpec> {
        val out = Prefs.load(context).map { entry ->
            val ids = entry.registeredModels
            ProviderSpec(
                alias = entry.alias,
                name = entry.alias,
                type = entry.type,
                baseUrl = entry.baseUrl.ifBlank { entry.preset.baseUrl },
                apiKey = entry.apiKey,
                endpointMode = when (entry.type) {
                    TYPE_ANTHROPIC -> ENDPOINT_MESSAGES
                    TYPE_GEMINI -> ENDPOINT_INTERACTIONS
                    else -> ENDPOINT_CHAT
                },
                enabled = entry.isConfigured,
                models = ids.map { id ->
                    ModelSpec(
                        id = id,
                        contextSize = entry.contextSize,
                        effort = entry.effort,
                        reasoning = ModelCatalog.describe(id).id.contains("reasoner") ||
                            id.contains("thinking", ignoreCase = true),
                        source = SOURCE_CATALOG,
                    )
                },
                defaultModel = entry.model,
            )
        }.toMutableList()
        if (out.isNotEmpty()) save(context, out)
        return out
    }

    // ------------------------------------------------------------- codec (JSON)

    fun encode(providers: List<ProviderSpec>): String {
        val arr = JSONArray()
        providers.forEach { p ->
            val models = JSONArray()
            p.models.forEach { m ->
                models.put(JSONObject().apply {
                    put("id", m.id)
                    put("displayName", m.displayName)
                    put("contextSize", m.contextSize)
                    put("effort", m.effort)
                    put("reasoning", m.reasoning)
                    put("enabled", m.enabled)
                    put("source", m.source)
                })
            }
            arr.put(JSONObject().apply {
                put("alias", p.alias)
                put("name", p.name)
                put("type", p.type)
                put("baseUrl", p.baseUrl)
                put("apiKey", p.apiKey)
                put("endpointMode", p.endpointMode)
                put("customHeaders", p.customHeaders)
                put("enabled", p.enabled)
                put("defaultModel", p.defaultModel)
                put("models", models)
            })
        }
        return arr.toString()
    }

    fun decode(raw: String): MutableList<ProviderSpec> {
        val arr = JSONArray(raw)
        val out = mutableListOf<ProviderSpec>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val modelsArr = o.optJSONArray("models") ?: JSONArray()
            val models = mutableListOf<ModelSpec>()
            for (j in 0 until modelsArr.length()) {
                val m = modelsArr.optJSONObject(j) ?: continue
                models.add(
                    ModelSpec(
                        id = m.optString("id"),
                        displayName = m.optString("displayName"),
                        contextSize = m.optInt("contextSize", 131072),
                        effort = m.optString("effort"),
                        reasoning = m.optBoolean("reasoning", false),
                        enabled = m.optBoolean("enabled", true),
                        source = m.optString("source").ifBlank { SOURCE_MANUAL },
                    ),
                )
            }
            out.add(
                ProviderSpec(
                    alias = o.optString("alias"),
                    name = o.optString("name"),
                    type = o.optString("type").ifBlank { TYPE_OPENAI },
                    baseUrl = o.optString("baseUrl"),
                    apiKey = o.optString("apiKey"),
                    endpointMode = o.optString("endpointMode").ifBlank { ENDPOINT_CHAT },
                    customHeaders = o.optString("customHeaders"),
                    enabled = o.optBoolean("enabled", true),
                    models = models.filter { it.id.isNotBlank() },
                    defaultModel = o.optString("defaultModel"),
                ),
            )
        }
        return out.filter { it.alias.isNotBlank() }.toMutableList()
    }

    // ------------------------------------------------------------- bridge

    /** 降级为现有 Prefs.Entry，让 ConfigWriter / 登录态链路保持不变。 */
    fun toPrefsEntries(providers: List<ProviderSpec>): List<Prefs.Entry> =
        providers.filter { it.enabled && it.apiKey.isNotBlank() && it.usableModels.isNotEmpty() }
            .map { p ->
                val defaultId = p.effectiveDefault
                val rest = p.usableModels.map { it.id }.filter { it != defaultId }
                val chosen = p.usableModels.firstOrNull { it.id == defaultId }
                    ?: p.usableModels.first()
                Prefs.Entry(
                    presetId = when (p.type) {
                        TYPE_KIMI -> "kimi"
                        TYPE_ANTHROPIC -> "anthropic"
                        TYPE_GEMINI -> "gemini"
                        else -> "custom"
                    },
                    alias = p.alias,
                    apiKey = p.apiKey,
                    baseUrl = p.baseUrl,
                    model = defaultId,
                    contextSize = chosen.contextSize,
                    effort = chosen.effort,
                    models = rest,
                )
            }

    /** 每个 model key -> 独立设置，供 ConfigWriter 生成逐模型参数。 */
    fun tuningFor(providers: List<ProviderSpec>): Map<String, ModelConfig.ModelTuning> {
        val out = HashMap<String, ModelConfig.ModelTuning>()
        providers.forEach { p ->
            p.usableModels.forEach { m ->
                out[ConfigMerger.modelKey(p.alias, m.id)] = ModelConfig.ModelTuning(
                    contextSize = m.contextSize,
                    effort = m.effort,
                    reasoning = m.reasoning,
                    enabled = m.enabled,
                )
            }
        }
        return out
    }

    fun uniqueAlias(base: String, used: Set<String>): String =
        ConfigMerger.uniqueAlias(ConfigMerger.sanitizeAlias(base, "custom"), used)

    fun modelsUrl(baseUrl: String, endpointMode: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when (endpointMode) {
            ENDPOINT_INTERACTIONS -> "$base/models"
            else -> "$base/models"
        }
    }
}
