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
    const val KEY_CONFIG_SIGNATURE = "config_signature"
    const val KEY_FIXED_PORT = "fixed_port"

    private const val LEGACY_PROVIDER = "provider"
    private const val LEGACY_BASE_URL = "base_url"
    private const val LEGACY_API_KEY = "api_key"
    private const val LEGACY_MODEL = "model"
    private const val LEGACY_CONTEXT = "context"

    const val DEFAULT_WORKSPACE = "/root/workspace"

    /** Cap the menu length: a gateway can expose hundreds of ids. */
    const val MAX_MODELS_PER_PROVIDER = 40

    /** Access protocol of a configured provider, mirroring `providers.<name>.type`. */
    data class Preset(
        val id: String,
        val label: String,
        val type: String,
        val baseUrl: String,
        val model: String,
        val contextSize: Int,
    )

    /** Thinking-effort choices. An empty value leaves the server default alone. */
    val EFFORTS = listOf(
        "" to "跟随服务端",
        "low" to "低",
        "medium" to "中",
        "high" to "高",
    )

    /** One configured model endpoint. [alias] is the `[providers.<alias>]` key. */
    data class Entry(
        val presetId: String,
        val alias: String,
        val apiKey: String,
        val baseUrl: String,
        val model: String,
        val contextSize: Int,
        /** `default_effort` written into the model section; empty = don't touch it. */
        val effort: String = "",
        /** Other model ids registered under the same provider / API key. */
        val models: List<String> = emptyList(),
    ) {
        val preset: Preset get() = preset(presetId)
        val type: String get() = preset.type
        val isConfigured: Boolean get() = apiKey.isNotBlank() && model.isNotBlank()

        /** Alias used by `default_model`, i.e. `models."<alias>/<model>"`. */
        val modelAlias: String get() = ConfigMerger.modelKey(alias, model)

        /**
         * Every model id this provider registers in config.toml. One provider
         * (one API key + base URL) can now serve several models, so the web UI's
         * "more models" menu can switch between them without re-saving.
         *
         * De-duplicated case-insensitively on purpose: [ConfigMerger.modelKey]
         * lowercases the section name, so `Foo` and `foo` would emit the same
         * `[models."alias/foo"]` header twice and make the whole file invalid
         * TOML -- which stops the server from booting.
         */
        val registeredModels: List<String>
            get() = (models + model)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .take(Prefs.MAX_MODELS_PER_PROVIDER)
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
        val raw = sp.getString(KEY_ACTIVE, null)
        if (raw == null) return migrateLegacySettings(sp)
        val decoded = PrefsCodec.decode(raw, PRESETS.first().contextSize)
        // Heal aliases written by <= 2.11.0 (`Preset(...).id-2`) in place. This
        // is deterministic, so every caller sees the same short alias without a
        // one-off migration that could race a restart.
        val aliases = ConfigMerger.cleanAliases(decoded.map { it.alias }, decoded.map { it.presetId })
        return decoded.mapIndexed { index, r ->
            Entry(
                r.presetId,
                aliases[index],
                r.apiKey,
                r.baseUrl,
                r.model,
                r.contextSize,
                r.effort,
                PrefsCodec.splitModels(r.models),
            )
        }
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

    private fun encodeEntries(entries: List<Entry>): String = PrefsCodec.encode(entries.map {
        PrefsCodec.Record(
            it.presetId, it.alias, it.apiKey, it.baseUrl, it.model, it.contextSize,
            it.effort, PrefsCodec.joinModels(it.models),
        )
    })

    /**
     * Fingerprint of the App-owned config. When it is unchanged the launcher
     * must not touch `config.toml` at all, otherwise every proot restart would
     * overwrite whatever the server or the workspace wrote into that file.
     */
    fun configSignature(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_CONFIG_SIGNATURE, "").orEmpty()

    fun setConfigSignature(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_CONFIG_SIGNATURE, value)
            .apply()
    }

    /**
     * The workspace WebView keeps login state and session lists in localStorage,
     * which is scoped to the origin (`host:port`). Reusing the same port across
     * restarts is therefore essential: a port change looks like a brand new,
     * empty site to the web UI.
     */
    fun fixedPort(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_FIXED_PORT, 0)

    fun setFixedPort(context: Context, port: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FIXED_PORT, port)
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

    /** Plugins are managed by the agent itself under `$KIMI_CODE_HOME/plugins`. */
    fun pluginsDir(context: Context): File =
        File(File(File(Paths.rootfs(context), "root"), ".kimi-code"), "plugins")
}

/**
 * Writes the App-owned part of `~/.kimi-code/config.toml`.
 *
 * Everything outside the marked block belongs to the server: the OAuth
 * credential from a Kimi sign-in, thinking-effort capability metadata, and
 * models the user added from inside the workspace. The old implementation
 * replaced the whole file on every launch, which logged users out whenever
 * they saved a model (saving triggers a restart). Now it merges, and only
 * when its own inputs actually changed.
 *
 * The App writes only `default_effort` (a user preference). It must NOT write
 * `support_efforts` / `off_effort` (capability metadata): declaring those for
 * a custom endpoint makes the server reject `effort=off` -- see
 * [ConfigMerger.effortLines].
 */
object ConfigWriter {

    fun configFile(context: Context): File =
        File(File(File(Paths.rootfs(context), "root"), ".kimi-code"), "config.toml")

    /** Called on every proot start. No-op unless the App-owned config changed. */
    fun applyIfConfigured(context: Context): Boolean {
        val entries = Prefs.load(context).filter { it.isConfigured }
        if (entries.isEmpty()) {
            Runtime.log("尚未配置模型，可在设置页填入 API Key")
            return false
        }
        val active = Prefs.activeEntry(context)
        val signature = signature(entries, active)
        val file = configFile(context)
        val existing = readText(file)
        val alreadyMerged = existing.contains(ConfigMerger.MANAGED_START)
        if (file.isFile && alreadyMerged && Prefs.configSignature(context) == signature) {
            // Inputs unchanged: do not rewrite, so server-side state survives.
            return true
        }
        return runCatching { write(context, entries, active) }
            .onFailure { Runtime.log("写入配置失败: ${it.message}") }
            .isSuccess
    }

    /**
     * Stable fingerprint of the App-owned inputs. Both [applyIfConfigured] and
     * [write] must compute it the same way, otherwise every restart would look
     * like a change and rewrite the file. Filtering/choosing lives here so the
     * caller's list shape cannot make the two disagree.
     */
    fun signature(entries: List<Prefs.Entry>, active: Prefs.Entry?): String {
        val usable = entries.filter { it.isConfigured }
        val chosen = active?.takeIf { c -> usable.any { it.modelAlias == c.modelAlias } }
            ?: usable.firstOrNull()
        return buildString {
            // v5: force one rewrite. 2.14.0 merged two model groups into a
            // single providers.<alias>, so the web menu showed a flat list with
            // no group separation. Bumping the signature makes this version
            // regenerate config.toml once, splitting the groups again.
            append("v5|")
            append(chosen?.modelAlias.orEmpty()).append('|')
            usable.sortedBy { it.modelAlias }.forEach {
                append(it.modelAlias).append('|')
                append(it.type).append('|')
                append(it.baseUrl).append('|')
                append(it.apiKey).append('|')
                append(it.contextSize).append('|')
                append(it.effort).append('|')
                append(it.registeredModels.joinToString(",")).append('|')
            }
        }
    }

    /**
     * Merge the generated sections into the existing file, preserving all other
     * content. Writes atomically so the server never reads a half-written TOML.
     *
     * @return true when the file actually changed (i.e. a restart is warranted)
     */
    fun write(context: Context, entries: List<Prefs.Entry>, active: Prefs.Entry?): Boolean {
        val usable = entries.filter { it.isConfigured }
        require(usable.isNotEmpty()) { "没有任何可用的模型配置" }

        val chosen = active?.takeIf { candidate -> usable.any { it.modelAlias == candidate.modelAlias } }
            ?: usable.first()

        // Each group owns exactly one [providers.<alias>] section and every
        // model key is prefixed with that same alias, so the web UI renders
        // one headed block per group instead of a single flat list.
        val groups = ModelGroup.from(usable.mapIndexed { index, entry ->
            ModelGroup.Spec(
                alias = entry.alias,
                title = entry.alias,
                type = entry.type,
                baseUrl = entry.baseUrl.ifBlank { entry.preset.baseUrl },
                apiKey = entry.apiKey,
                model = entry.model,
                models = entry.models,
                contextSize = entry.contextSize,
                effort = entry.effort,
                sourceIndex = index,
            )
        })
        val problems = ModelGroup.validate(groups)
        if (problems.isEmpty()) {
            Runtime.log(
                "模型分组校验通过：${groups.size} 个分组 / " +
                    "${groups.sumOf { it.models.size }} 个模型，分组之间彼此隔开",
            )
        } else {
            problems.forEach { Runtime.log("模型分组校验异常：$it") }
        }
        val managed = ModelGroup.renderManaged(groups)

        val file = configFile(context)
        val existing = readText(file)
        // Any model previously registered under one of our aliases but no
        // longer selected must disappear, otherwise the web menu keeps offering
        // a model that config.toml no longer points at a working provider.
        val dropPrefixes = ModelGroup.dropPrefixes(groups)
        // 顶层 default_model 必须指向一个真实存在的 model key。分组 alias 在
        // 拆开重复项后可能带 -2 后缀，所以不能再用旧 entry 的 modelAlias。
        val chosenIndex = usable.indexOfFirst { it.modelAlias == chosen.modelAlias }.coerceAtLeast(0)
        val chosenGroup = groups.firstOrNull { it.sourceIndex == chosenIndex }
        val defaultModel = chosenGroup?.let {
            ConfigMerger.modelKey(it.alias, chosen.model)
        } ?: chosen.modelAlias
        val merged = ConfigMerger.merge(
            existing, managed, defaultModel, dropPrefixes = dropPrefixes,
        )

        file.parentFile?.mkdirs()
        if (existing == merged) {
            Prefs.setConfigSignature(context, signature(entries, active))
            Runtime.log("模型配置未变化，保留现有 config.toml")
            return false
        }

        val tmp = File(file.parentFile, "config.toml.tmp")
        tmp.writeText(merged)
        if (!tmp.renameTo(file)) {
            file.writeText(merged)
            tmp.delete()
        }
        Prefs.setConfigSignature(context, signature(entries, active))
        Runtime.log(
            "已合并写入 ${groups.size} 个分组 / ${groups.sumOf { it.models.size }} 个模型，" +
                "默认 $defaultModel（保留服务端配置）",
        )
        return true
    }

    private fun readText(file: File): String =
        if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""

    /**
     * Removes only the App-owned block. The old implementation deleted the
     * whole file, which also deleted the login credential.
     */
    fun clearGenerated(context: Context) {
        val file = configFile(context)
        if (!file.isFile) return
        val existing = readText(file)
        if (!existing.contains(ConfigMerger.MANAGED_START) &&
            existing.lines().firstOrNull()?.trim() != ConfigMerger.LEGACY_MARKER) return
        val cleaned = ConfigMerger.removeManaged(existing)
        file.writeText(cleaned)
        Prefs.setConfigSignature(context, "")
        Runtime.log("已移除 App 写入的模型配置段，其它配置保留")
    }
}
