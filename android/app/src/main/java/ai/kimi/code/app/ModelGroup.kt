package ai.kimi.code.app

/**
 * 模型分组：一个分组 == `config.toml` 里一个独立的 `[providers.<alias>]` 段。
 *
 * 工作台「更多模型」菜单把 `providers.<alias>` 当作**分组标题**，并把每个
 * `models."<alias>/<id>"` 归到该标题下面。因此分组要真正分开，必须同时满足：
 *
 * 1. 不同分组拥有不同的 alias；
 * 2. 组内每个模型的 section key 都以 `"<alias>/"` 开头。
 *
 * 任一条件被破坏（2.14.0 把两个分组揉进同一个 alias、或复用同一个模型
 * key 前缀），菜单就会把所有模型平铺在一个标题下 —— 也就是用户看到的
 * 「分组没有分，两个合在一块」。
 *
 * 本文件不引用任何 Android 类型，可以直接跑 JVM 单元测试。
 */
object ModelGroup {

    /** 单个分组的菜单长度上限，与 [Prefs.MAX_MODELS_PER_PROVIDER] 保持一致。 */
    const val MAX_MODELS_PER_GROUP = 40

    /** 由 ConfigWriter 从 [Prefs.Entry] 映射而来的分组输入。 */
    data class Spec(
        val alias: String,
        val title: String = "",
        val type: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val models: List<String> = emptyList(),
        val contextSize: Int,
        val effort: String = "",
        /** 对应原始配置条目的下标，供调用方定位默认模型所在的分组。 */
        val sourceIndex: Int = -1,
    )

    data class Group(
        val alias: String,
        val title: String,
        val type: String,
        val baseUrl: String,
        val apiKey: String,
        val defaultModel: String,
        val models: List<String>,
        val contextSize: Int,
        val effort: String,
        val sourceIndex: Int,
    ) {
        /** 每个模型在 config.toml / 工作台里显示的完整 id。 */
        fun modelKeys(): List<String> = models.map { ConfigMerger.modelKey(alias, it) }
    }

    /**
     * 构建分组。
     *
     * 重复 alias 是「分组被合并」的根因，这里强制拆开：后出现的分组自动
     * 获得 `-2` / `-3` 后缀，绝不共用一个 provider 段。即使 2.14.0 已经把
     * 两份配置存成了同一个名字，加载后也会在这里被修复。
     */
    fun from(specs: List<Spec>): List<Group> {
        val used = HashSet<String>()
        return specs.map { spec ->
            val base = ConfigMerger.sanitizeAlias(spec.alias, "custom")
            var alias = base
            var suffix = 2
            while (!used.add(alias)) alias = "$base-${suffix++}"
            val registered = (listOf(spec.model) + spec.models)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_MODELS_PER_GROUP)
            Group(
                alias = alias,
                title = spec.title.ifBlank { alias },
                type = spec.type,
                baseUrl = spec.baseUrl,
                apiKey = spec.apiKey,
                defaultModel = registered.firstOrNull().orEmpty(),
                models = registered,
                contextSize = spec.contextSize,
                effort = spec.effort,
                sourceIndex = spec.sourceIndex,
            )
        }.filter { it.defaultModel.isNotEmpty() }
    }

    /**
     * 合并一次多选结果：返回（新的默认模型, 其余模型）。
     * 去重时大小写不敏感，避免同一个网关 id 被登记两次。
     */
    fun mergePicked(current: String, extras: List<String>, picked: List<String>): Pair<String, List<String>> {
        val seen = HashSet<String>()
        val all = (listOf(current) + extras + picked)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { seen.add(it.lowercase()) }
            .take(MAX_MODELS_PER_GROUP)
        val chosen = all.firstOrNull().orEmpty()
        return chosen to all.drop(1)
    }

    /**
     * 生成 App 托管的 TOML 段。
     *
     * 每个分组一个 `[providers.<alias>]` + 该组全部 `[models."<alias>/<id>"]`，
     * 组与组之间留空行；模型 key 一律用**本组** alias 作前缀，杜绝串组。
     */
    fun renderManaged(
        groups: List<Group>,
        tuning: Map<String, ModelConfig.ModelTuning> = emptyMap(),
    ): String = buildString {
        groups.forEachIndexed { index, group ->
            if (index > 0) appendLine()
            appendLine("# --- 分组 ${group.title} (${group.alias}) ---")
            appendLine("[providers.${ConfigMerger.quoteKey(group.alias)}]")
            appendLine("type = \"${ConfigMerger.escape(group.type)}\"")
            if (group.baseUrl.isNotBlank()) {
                appendLine("base_url = \"${ConfigMerger.escape(group.baseUrl)}\"")
            }
            appendLine("api_key = \"${ConfigMerger.escape(group.apiKey)}\"")
            appendLine(
                "default_model = \"" +
                    ConfigMerger.escape(ConfigMerger.modelKey(group.alias, group.defaultModel)) + "\"",
            )
            appendLine()
            group.models.forEach { id ->
                val key = ConfigMerger.modelKey(group.alias, id)
                // 逐模型设置覆盖分组默认值；没有单独设置时沿用分组参数，
                // 保证旧配置与旧单测行为不变。
                val own = tuning[key]
                appendLine("[${ConfigMerger.modelSectionKey(group.alias, id)}]")
                appendLine("provider = \"${ConfigMerger.escape(group.alias)}\"")
                appendLine("model = \"${ConfigMerger.escape(id)}\"")
                appendLine("max_context_size = ${(own?.contextSize ?: group.contextSize).coerceAtLeast(1)}")
                append(ConfigMerger.effortLines(own?.effort ?: group.effort))
                appendLine()
            }
        }
    }

    /** 需要清理的旧模型 section 前缀（按分组逐个生成）。 */
    fun dropPrefixes(groups: List<Group>): Set<String> =
        groups.map { "models." + ConfigMerger.quoteKey("${it.alias}/") }.toSet()

    /**
     * 分组隔离校验。返回问题描述，空列表表示健康。
     * 供 ConfigWriter 写日志与单元测试使用。
     */
    fun validate(groups: List<Group>): List<String> {
        val problems = mutableListOf<String>()
        val aliases = HashSet<String>()
        var keyCount = 0
        val keys = HashSet<String>()
        groups.forEach { group ->
            if (!aliases.add(group.alias)) problems.add("分组名重复：${group.alias}")
            if (group.models.isEmpty()) problems.add("分组 ${group.alias} 没有模型")
            if (group.defaultModel.isNotEmpty() && group.defaultModel !in group.models) {
                problems.add("分组 ${group.alias} 的默认模型不在组内")
            }
            group.modelKeys().forEach { key ->
                keyCount++
                if (!keys.add(key)) problems.add("重复的 model key：$key")
                if (!key.startsWith("${group.alias}/")) {
                    problems.add("模型 $key 没有归属到分组 ${group.alias}")
                }
            }
        }
        return problems
    }
}
