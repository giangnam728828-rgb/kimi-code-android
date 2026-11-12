package ai.kimi.code.app

/**
 * `config.toml` 里 `[models.*]` 段的稳定化管理（纯 JVM，不依赖 Android）。
 *
 * ### 2.15.0 的回归根因
 * 网页工作台的「更多模型」下拉列表，是**页面加载时**由服务端读取
 * `config.toml` 里所有 `[models.*]` 段渲染出来的。而 2.15.0 的
 * `ConfigWriter.write()` 每次保存都用 `dropPrefixes` 把该分组 alias 前缀下的
 * 模型段整体删除、再按 App 本地清单重建；重建后的清单一旦变少，网页缓存的
 * 旧列表里就会出现「网页能选、config 里没有」的 key。用户点它发消息，服务端
 * 回查 config 找不到段，于是报
 * `Model "<alias>/<id>" is not configured in config.toml`，连消息都发不出去。
 * `switchModelInGroup()` 还会整包重启，进一步拉长了这个不一致窗口。
 *
 * ### 本文件保证的不变量
 * 1. **模型段只增不删**：`config.toml` 里出现过的 `[models.*]` 段，重建时一律
 *    原样保留；除非用户在管理页显式删除某个模型。
 * 2. **切换默认模型只改一行**：`default_model` 是顶层 key，改它不需要重建任何
 *    `[models.*]` 段，也不需要重启运行环境。
 * 3. **自愈补段**：若 `default_model` 指向的 key 在 config 里没有对应段，按
 *    所属 provider 的配置补一个可用段，直接消掉历史上的报错状态。
 *
 * 这样「网页下拉能选到的每个 key」在 config 里都必然有段，从根本上消除报错。
 */
object ModelConfig {

    private val SECTION_HEADER = Regex("""^\s*\[{1,2}\s*([^\]]+?)\s*\]{1,2}\s*(?:#.*)?$""")
    private val DEFAULT_MODEL_LINE = Regex("""^\s*default_model\s*=""")

    /** 生成一个 `[models.*]` 段所需的最小输入。 */
    data class Spec(
        val key: String,
        val alias: String,
        val modelId: String,
        val contextSize: Int,
        val effort: String = "",
    )

    /** 2.16.0：每个模型的独立设置，用于覆盖分组默认值。 */
    data class ModelTuning(
        val contextSize: Int,
        val effort: String,
        val reasoning: Boolean = false,
        val enabled: Boolean = true,
    )

    /** 去掉 TOML key 外层引号与常见转义。 */
    fun unquote(raw: String): String {
        val t = raw.trim()
        if (t.length >= 2 && t.first() == '"' && t.last() == '"') {
            return t.substring(1, t.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        return t
    }

    /**
     * 从 section 名（如 `models."custom-2/deepseek-v4.1-flash"`）取出网页端使用的
     * model key（`custom-2/deepseek-v4.1-flash`）。非模型段返回 null。
     */
    fun modelKeyOf(sectionName: String): String? {
        val s = sectionName.trim()
        if (!s.startsWith("models.")) return null
        val rest = unquote(s.substring("models.".length))
        return rest.takeIf { it.contains('/') && it.isNotBlank() }
    }

    /** config 文本里已有的全部 model key（managed 块内外都算）。 */
    fun modelKeys(text: String): Set<String> = text.lineSequence()
        .mapNotNull { SECTION_HEADER.matchEntire(it)?.groupValues?.get(1)?.trim() }
        .mapNotNull(::modelKeyOf)
        .toSet()

    /** 某个分组 alias 下已经登记过的 model id 列表。 */
    fun idsFor(text: String, alias: String): List<String> {
        val prefix = "$alias/"
        return modelKeys(text)
            .filter { it.startsWith(prefix) }
            .map { it.substring(prefix.length) }
            .sorted()
    }

    /** 生成一个可被服务端解析的 `[models."<key>"]` 段。 */
    fun renderSection(spec: Spec): String = buildString {
        appendLine("[models.${ConfigMerger.quoteKey(spec.key)}]")
        appendLine("provider = \"${ConfigMerger.escape(spec.alias)}\"")
        appendLine("model = \"${ConfigMerger.escape(spec.modelId)}\"")
        appendLine("max_context_size = ${spec.contextSize.coerceAtLeast(1)}")
        append(ConfigMerger.effortLines(spec.effort))
    }

    /**
     * 提取 config 里所有「本次没有重新生成」的 `[models.*]` 段，原样返回。
     *
     * 用于重建 managed 块时把历史段一并带回去：只要网页下拉里曾经出现过这个
     * key，它就必须继续存在，否则切换过去必然报 not configured。段内可能含有
     * 服务端写入的能力元数据（如 support_efforts），原样保留最安全。
     */
    fun preservedSections(existing: String, generatedKeys: Set<String>): String {
        if (existing.isBlank()) return ""
        val lines = existing.lines()
        val out = mutableListOf<String>()
        var current: MutableList<String>? = null
        var keep = false

        fun flush() {
            val block = current ?: return
            if (keep) {
                while (block.lastOrNull()?.isBlank() == true) block.removeAt(block.lastIndex)
                if (block.isNotEmpty()) {
                    out.addAll(block)
                    out.add("")
                }
            }
            current = null
            keep = false
        }

        for (line in lines) {
            val header = SECTION_HEADER.matchEntire(line)?.groupValues?.get(1)?.trim()
            if (header != null) {
                flush()
                val key = modelKeyOf(header)
                keep = key != null && key !in generatedKeys
                current = mutableListOf(line)
            } else {
                if (current == null) current = mutableListOf()
                current!!.add(line)
            }
        }
        flush()
        return if (out.isEmpty()) "" else out.joinToString("\n").trimEnd('\n') + "\n"
    }

    /**
     * 只替换顶层 `default_model`，其余内容逐字节不变。用于「切换默认模型」这种
     * 高频轻量操作：不重建任何模型段、不触发运行环境重启。
     */
    fun withDefaultModel(text: String, key: String): String {
        val trailingNewline = text.endsWith("\n")
        val lines = text.lines().toMutableList()
        val newLine = "default_model = \"${ConfigMerger.escape(key)}\""
        val idx = lines.indexOfFirst { DEFAULT_MODEL_LINE.containsMatchIn(it) }
        if (idx >= 0) {
            if (lines[idx] == newLine) return text
            lines[idx] = newLine
        } else {
            // 顶层 key 必须在第一个 section header 之前，否则会被解析进上一个段。
            val firstSection = lines.indexOfFirst { SECTION_HEADER.matches(it) }
            lines.add(if (firstSection < 0) 0 else firstSection, newLine)
        }
        val joined = lines.joinToString("\n")
        return if (trailingNewline && !joined.endsWith("\n")) "$joined\n" else joined
    }

    /**
     * 自愈：确保 [key] 在 config 里有对应 `[models.*]` 段。
     * 没有就按 [providerAliases] 里能匹配的 alias 与默认上下文补一个。
     * 返回需要追加的段文本，已存在则返回空串。
     */
    fun healMissingSection(text: String, key: String, defaultContext: Int): String {
        if (key.isBlank()) return ""
        if (key in modelKeys(text)) return ""
        val alias = key.substringBefore('/')
        val modelId = key.substringAfter('/', "")
        if (alias.isBlank() || modelId.isBlank()) return ""
        return renderSection(
            Spec(
                key = key,
                alias = alias,
                modelId = modelId,
                contextSize = defaultContext,
            ),
        )
    }
}
