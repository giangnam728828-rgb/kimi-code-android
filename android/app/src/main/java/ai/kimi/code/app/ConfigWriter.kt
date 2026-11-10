package ai.kimi.code.app

import android.content.Context
import java.io.File

/**
 * Persisted user settings, edited on the in-app settings page.
 *
 * [entries] holds every configured model endpoint. Each entry is one
 * `[providers.<alias>]` + `[models."<alias>/<model>"]` pair in `config.toml`,
 * so several providers can be configured at once and picked from the model
 * list inside the workspace.
 */
object Prefs {
    private const val FILE = "kimi_prefs"

    const val KEY_AUTO_ENTER = "auto_enter"
    const val KEY_SWIPE_BACK = "swipe_back"
    const val KEY_LAN = "lan"
    const val KEY_KEEP_AWAKE = "keep_awake"
    const val KEY_WORKSPACE = "workspace"
    const val KEY_ACTIVE = "active_entry"
    const val KEY_ACTIVE_INDEX = "active_index"

    private const val LEGACY_PROVIDER = "provider"
    private const val LEGACY_BASE_URL = "base_url"
    private const val LEGACY_API_KEY = "api_key"
    private const val LEGACY_MODEL = "model"
    private const val LEGACY_CONTEXT = "context"

    const val DEFAULT_WORKSPACE = "/root/workspace"

    /** Access protocol of a configured provider, mirroring `providers.<name>.type`. */
    data class Preset(
        val id: String,
        val label: String,
        val type: String,
        val baseUrl: String,
        val model: String,
        val contextSize: Int,
    )

    /** One configured model endpoint. [alias] is the `[providers.<alias>]` key. */
    data class Entry(
        val presetId: String,
        val alias: String,
        val apiKey: String,
        val baseUrl: String,
        val model: String,
        val contextSize: Int,
    ) {
        val preset: Preset get() = preset(presetId)
        val type: String get() = preset.type
        val isConfigured: Boolean get() = apiKey.isNotBlank() && model.isNotBlank()

        /** Alias used by `default_model` and `-m`, i.e. `models."<alias>/<model>"`. */
        val modelAlias: String get() = "$alias/$model"
    }

    val PRESETS = listOf(
        Preset("kimi", "Kimi（Moonshot）", "kimi", "https://api.moonshot.cn/v1", "kimi-k2-turbo-preview", 262144),
        Preset("deepseek", "DeepSeek", "openai", "https://api.deepseek.com/v1", "deepseek-chat", 131072),
        Preset("openai", "OpenAI", "openai", "https://api.openai.com/v1", "gpt-4o", 128000),
        Preset("anthropic", "Anthropic Claude", "anthropic", "https://api.anthropic.com/v1", "claude-sonnet-4-20250514", 200000),
        Preset("openrouter", "OpenRouter", "openai", "https://openrouter.ai/api/v1", "", 131072),
        Preset("custom", "自定义（OpenAI 兼容）", "openai", "", "", 131072),
    )

    fun preset(id: String): Preset = PRESETS.firstOrNull { it.id == id } ?: PRESETS.first()

    fun load(context: Context): List<Entry> {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp.getString(KEY_ACTIVE, null)?.let { return decodeEntries(it) }
        return migrateLegacySettings(sp)
    }

    /**
     * Versions before multi-provider support stored exactly one endpoint in
     * flat keys. Carrying it into the list keeps the configured API key and
     * model alive across the update instead of silently dropping them.
     */
    private fun migrateLegacySettings(sp: android.content.SharedPreferences): List<Entry> {
        val apiKey = sp.getString(LEGACY_API_KEY, "")?.trim().orEmpty()
        if (apiKey.isEmpty()) return emptyList()
        val presetId = sp.getString(LEGACY_PROVIDER, "kimi") ?: "kimi"
        val preset = preset(presetId)
        val entry = Entry(
            presetId = preset.id,
            alias = preset.id,
            apiKey = apiKey,
            baseUrl = sp.getString(LEGACY_BASE_URL, preset.baseUrl) ?: preset.baseUrl,
            model = sp.getString(LEGACY_MODEL, preset.model) ?: preset.model,
            contextSize = sp.getInt(LEGACY_CONTEXT, preset.contextSize),
        )
        sp.edit()
            .putString(KEY_ACTIVE, encodeEntries(listOf(entry)))
            .remove(LEGACY_PROVIDER)
            .remove(LEGACY_BASE_URL)
            .remove(LEGACY_API_KEY)
            .remove(LEGACY_MODEL)
            .remove(LEGACY_CONTEXT)
            .apply()
        return listOf(entry)
    }

    fun save(context: Context, entries: List<Entry>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE, encodeEntries(entries))
            .apply()
    }

    /** The entry `default_model` points at; falls back to the first usable one. */
    fun activeEntry(context: Context): Entry? {
        val entries = load(context)
        if (entries.isEmpty()) return null
        val index = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_ACTIVE_INDEX, 0)
        return entries.getOrNull(index) ?: entries.first()
    }

    fun setActiveEntry(context: Context, index: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ACTIVE_INDEX, index)
            .apply()
    }

    private fun encodeEntries(entries: List<Entry>): String = entries.joinToString("\u001E") { entry ->
        listOf(
            entry.presetId,
            entry.alias,
            entry.apiKey,
            entry.baseUrl,
            entry.model,
            entry.contextSize.toString(),
        ).joinToString("\u001F")
    }

    private fun decodeEntries(raw: String): List<Entry> = raw.split("\u001E")
        .filter { it.isNotBlank() }
        .mapNotNull { record ->
            val fields = record.split("\u001F")
            if (fields.size < 6) return@mapNotNull null
            Entry(
                presetId = fields[0],
                alias = fields[1],
                apiKey = fields[2],
                baseUrl = fields[3],
                model = fields[4],
                contextSize = fields[5].toIntOrNull() ?: PRESETS.first().contextSize,
            )
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
 *
 * The layout follows the engine's own `providers` / `models` sections: every
 * entry becomes one provider plus one model alias named `<alias>/<model>`, and
 * `default_model` points at the active alias.
 */
object ConfigWriter {

    private const val MARKER = "# Generated by the Kimi Code Android app"

    fun configFile(context: Context): File =
        File(File(File(Paths.rootfs(context), "root"), ".kimi-code"), "config.toml")

    fun applyIfConfigured(context: Context): Boolean {
        val entries = Prefs.load(context).filter { it.isConfigured }
        if (entries.isEmpty()) {
            Runtime.log("尚未配置模型，可在设置页填入 API Key")
            return false
        }
        return runCatching { write(context, entries, Prefs.activeEntry(context)) }
            .onFailure { Runtime.log("写入配置失败: ${it.message}") }
            .isSuccess
    }

    fun write(context: Context, entries: List<Prefs.Entry>, active: Prefs.Entry?) {
        val usable = entries.filter { it.isConfigured }
        require(usable.isNotEmpty()) { "没有任何可用的模型配置" }

        val chosen = active?.takeIf { candidate -> usable.any { it.modelAlias == candidate.modelAlias } }
            ?: usable.first()

        val text = buildString {
            appendLine(MARKER)
            appendLine("default_model = \"${escape(chosen.modelAlias)}\"")
            appendLine()

            for (entry in usable) {
                val baseUrl = entry.baseUrl.ifBlank { entry.preset.baseUrl }
                appendLine("[providers.${quoteKey(entry.alias)}]")
                appendLine("type = \"${escape(entry.type)}\"")
                if (baseUrl.isNotBlank()) {
                    appendLine("base_url = \"${escape(baseUrl)}\"")
                }
                appendLine("api_key = \"${escape(entry.apiKey)}\"")
                appendLine("default_model = \"${escape(entry.modelAlias)}\"")
                appendLine()

                appendLine("[models.${quoteKey(entry.modelAlias)}]")
                appendLine("provider = \"${escape(entry.alias)}\"")
                appendLine("model = \"${escape(entry.model)}\"")
                appendLine("max_context_size = ${entry.contextSize.coerceAtLeast(1)}")
                appendLine()
            }
        }

        val file = configFile(context)
        file.parentFile?.mkdirs()
        file.writeText(text)
        Runtime.log("已写入 ${usable.size} 个模型配置，默认 ${chosen.modelAlias}（${chosen.type}）")
    }

    /** Removes a previous app-generated config so the login flow starts clean. */
    fun clearGenerated(context: Context) {
        val file = configFile(context)
        if (!file.isFile) return
        val head = runCatching { file.readLines().firstOrNull() }.getOrNull()
        if (head?.contains("Generated by the Kimi Code Android app") == true) {
            file.delete()
            Runtime.log("已清除 App 写入的模型配置")
        }
    }

    private fun quoteKey(value: String): String =
        if (value.matches(Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*"))) value else "\"${escape(value)}\""

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
