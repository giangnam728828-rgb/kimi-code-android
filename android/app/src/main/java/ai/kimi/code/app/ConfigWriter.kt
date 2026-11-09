package ai.kimi.code.app

import android.content.Context
import java.io.File

/** Persisted user settings, edited on the in-app settings page. */
object Prefs {
    private const val FILE = "kimi_prefs"

    const val KEY_PROVIDER = "provider"
    const val KEY_BASE_URL = "base_url"
    const val KEY_API_KEY = "api_key"
    const val KEY_MODEL = "model"
    const val KEY_CONTEXT = "context"
    const val KEY_AUTO_ENTER = "auto_enter"
    const val KEY_SWIPE_BACK = "swipe_back"
    const val KEY_LAN = "lan"
    const val KEY_KEEP_AWAKE = "keep_awake"
    const val KEY_WORKSPACE = "workspace"

    const val DEFAULT_WORKSPACE = "/root/workspace"

    data class ModelSettings(
        val provider: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val contextSize: Int,
    ) {
        val isConfigured: Boolean get() = apiKey.isNotBlank() && model.isNotBlank()
    }

    data class Preset(
        val id: String,
        val label: String,
        val type: String,
        val baseUrl: String,
        val model: String,
        val contextSize: Int,
    )

    val PRESETS = listOf(
        Preset("kimi", "Kimi（Moonshot）", "kimi", "https://api.moonshot.cn/v1", "kimi-k2-turbo-preview", 262144),
        Preset("deepseek", "DeepSeek", "openai", "https://api.deepseek.com/v1", "deepseek-chat", 131072),
        Preset("openai", "OpenAI", "openai", "https://api.openai.com/v1", "gpt-4o", 128000),
        Preset("anthropic", "Anthropic Claude", "anthropic", "https://api.anthropic.com/v1", "claude-sonnet-4-20250514", 200000),
        Preset("custom", "自定义（OpenAI 兼容）", "openai", "", "", 131072),
    )

    fun preset(id: String): Preset = PRESETS.firstOrNull { it.id == id } ?: PRESETS.first()

    fun load(context: Context): ModelSettings {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val providerId = sp.getString(KEY_PROVIDER, "kimi") ?: "kimi"
        val preset = preset(providerId)
        return ModelSettings(
            provider = providerId,
            baseUrl = sp.getString(KEY_BASE_URL, preset.baseUrl) ?: preset.baseUrl,
            apiKey = sp.getString(KEY_API_KEY, "") ?: "",
            model = sp.getString(KEY_MODEL, preset.model) ?: preset.model,
            contextSize = sp.getInt(KEY_CONTEXT, preset.contextSize),
        )
    }

    fun save(context: Context, settings: ModelSettings) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROVIDER, settings.provider)
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_MODEL, settings.model)
            .putInt(KEY_CONTEXT, settings.contextSize)
            .apply()
    }

    private fun flag(context: Context, key: String, fallback: Boolean): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(key, fallback)

    private fun setFlag(context: Context, key: String, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }

    fun autoEnter(context: Context): Boolean = flag(context, KEY_AUTO_ENTER, true)
    fun setAutoEnter(context: Context, enabled: Boolean) = setFlag(context, KEY_AUTO_ENTER, enabled)

    fun swipeBack(context: Context): Boolean = flag(context, KEY_SWIPE_BACK, true)
    fun setSwipeBack(context: Context, enabled: Boolean) = setFlag(context, KEY_SWIPE_BACK, enabled)

    fun lan(context: Context): Boolean = flag(context, KEY_LAN, false)
    fun setLan(context: Context, enabled: Boolean) = setFlag(context, KEY_LAN, enabled)

    fun keepAwake(context: Context): Boolean = flag(context, KEY_KEEP_AWAKE, true)
    fun setKeepAwake(context: Context, enabled: Boolean) = setFlag(context, KEY_KEEP_AWAKE, enabled)

    fun workspace(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_WORKSPACE, DEFAULT_WORKSPACE)
            ?: DEFAULT_WORKSPACE

    fun setWorkspace(context: Context, path: String) {
        val normalized = path.trim().ifEmpty { DEFAULT_WORKSPACE }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_WORKSPACE, normalized)
            .apply()
    }

    /**
     * Plugins are managed by the agent itself under `$KIMI_CODE_HOME/plugins`
     * (`installed.json` + `managed/<id>`), so the app only reads that directory
     * to report what is installed; installing happens through the workspace.
     */
    fun pluginsDir(context: Context): File =
        File(File(File(Paths.rootfs(context), "root"), ".kimi-code"), "plugins")
}

/**
 * Writes `~/.kimi-code/config.toml` inside the rootfs so the agent runs on the
 * model the user picked, with no manual terminal setup.
 */
object ConfigWriter {

    fun configFile(context: Context): File =
        File(File(File(Paths.rootfs(context), "root"), ".kimi-code"), "config.toml")

    fun applyIfConfigured(context: Context): Boolean {
        val settings = Prefs.load(context)
        if (!settings.isConfigured) {
            Runtime.log("尚未配置模型，可在设置页填入 API Key")
            return false
        }
        return runCatching { write(context, settings) }
            .onFailure { Runtime.log("写入配置失败: ${it.message}") }
            .isSuccess
    }

    fun write(context: Context, settings: Prefs.ModelSettings) {
        val preset = Prefs.preset(settings.provider)
        val alias = if (preset.id == "custom") "custom" else preset.id
        val type = preset.type
        val baseUrl = settings.baseUrl.ifBlank { preset.baseUrl }
        val contextSize = if (settings.contextSize > 0) settings.contextSize else preset.contextSize

        val text = buildString {
            appendLine("default_model = \"$alias\"")
            appendLine()
            appendLine("[providers.$alias]")
            appendLine("type = \"$type\"")
            if (baseUrl.isNotBlank()) {
                appendLine("base_url = \"${escape(baseUrl)}\"")
            }
            appendLine("api_key = \"${escape(settings.apiKey)}\"")
            appendLine("default_model = \"${escape(settings.model)}\"")
            appendLine()
            appendLine("[models.$alias]")
            appendLine("provider = \"$alias\"")
            appendLine("model = \"${escape(settings.model)}\"")
            appendLine("max_context_size = $contextSize")
        }

        val file = configFile(context)
        file.parentFile?.mkdirs()
        file.writeText(text)
        Runtime.log("已写入模型配置：${settings.model}（$type）")
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
