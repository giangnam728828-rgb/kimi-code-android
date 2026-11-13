package ai.kimi.code.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.16.2：改名生效 + 改名迁移的安全性。
 *
 * 背景：工作台「更多模型」的分组标题只能取 `[providers.<alias>]` 的段名
 * （后端 toProviderResponses 只透传 type/base_url/default_model/api_key_env，
 * 没有独立的显示名字段）。所以「改名生效」必须真的把 alias 换掉。
 *
 * 换 alias 会带来两个风险，这里逐一钉死：
 *  1. 旧 alias 名下的历史 `[models.*]` 段会变成悬空段（provider 已不存在）；
 *  2. 中文名字如果被别名清洗成 custom，改名等于没改。
 */
class ModelRenameTest {

    private val managed = """
        # <<< kimi-code-android managed:start
        [providers.custom-2]
        type = "openai"
        base_url = "https://api.example.com/v1"
        api_key = "sk-a"
        default_model = "custom-2/deepseek-v4.1-flash"

        [models."custom-2/deepseek-v4.1-flash"]
        provider = "custom-2"
        model = "deepseek-v4.1-flash"
        max_context_size = 131072

        [models."custom-2/deepseek-v4.1-pro"]
        provider = "custom-2"
        model = "deepseek-v4.1-pro"
        max_context_size = 131072
        # <<< kimi-code-android managed:end
    """.trimIndent()

    // ------------------------------------------------ 中文名不能被清洗掉

    @Test
    fun `中文供应商名保留为别名`() {
        assertEquals("深度求索", ConfigMerger.sanitizeAlias("深度求索", "custom"))
    }

    @Test
    fun `名称里的空格与特殊字符转为连字符`() {
        assertEquals("deepseek-官方", ConfigMerger.sanitizeAlias("DeepSeek 官方", "custom"))
    }

    @Test
    fun `中文别名带斜杠的模型键会被加引号`() {
        val key = ConfigMerger.modelKey("深度求索", "deepseek-chat")
        assertEquals("深度求索/deepseek-chat", key)
        // 非裸键必须加引号，否则 TOML 解析失败
        assertTrue(ConfigMerger.quoteKey(key).startsWith("\""))
    }

    // ---------------------------------------------- 退役别名要清掉悬空段

    @Test
    fun `退役别名名下的历史模型段会被剥离`() {
        val preserved = ModelConfig.preservedSections(
            existing = managed,
            generatedKeys = emptySet(),
            retiredAliases = setOf("custom-2"),
        )
        assertEquals("", preserved.trim())
    }

    @Test
    fun `未退役的其他分组段仍然保留`() {
        val preserved = ModelConfig.preservedSections(
            existing = managed,
            generatedKeys = emptySet(),
            retiredAliases = setOf("别的分组"),
        )
        assertTrue(preserved.contains("custom-2/deepseek-v4.1-flash"))
        assertTrue(preserved.contains("custom-2/deepseek-v4.1-pro"))
    }

    @Test
    fun `退役只影响自己的前缀，不误伤同名前缀组`() {
        // alias "custom" 不应把 "custom-2/..." 当成自己的段
        val preserved = ModelConfig.preservedSections(
            existing = managed,
            generatedKeys = emptySet(),
            retiredAliases = setOf("custom"),
        )
        assertTrue(preserved.contains("custom-2/deepseek-v4.1-flash"))
    }

    // ------------------------------------------------- 展示名写进 display_name

    @Test
    fun `模型展示名写进 display_name 字段`() {
        val section = ModelConfig.renderSection(
            ModelConfig.Spec(
                key = "深度求索/deepseek-chat",
                alias = "深度求索",
                modelId = "deepseek-chat",
                contextSize = 131072,
                displayName = "DeepSeek 聊天",
            ),
        )
        assertTrue(section.contains("display_name = \"DeepSeek 聊天\""))
        assertTrue(section.contains("[models.\"深度求索/deepseek-chat\"]"))
    }

    @Test
    fun `展示名为空时不写 display_name，保持历史段不变`() {
        val section = ModelConfig.renderSection(
            ModelConfig.Spec(
                key = "custom-2/m",
                alias = "custom-2",
                modelId = "m",
                contextSize = 8192,
            ),
        )
        assertFalse(section.contains("display_name"))
    }

    @Test
    fun `renderManaged 输出供应商名称与逐模型展示名`() {
        val groups = ModelGroup.from(
            listOf(
                ModelGroup.Spec(
                    alias = "深度求索",
                    title = "深度求索",
                    type = "openai",
                    baseUrl = "https://api.example.com/v1",
                    apiKey = "sk-a",
                    model = "deepseek-chat",
                    models = listOf("deepseek-chat"),
                    contextSize = 131072,
                    effort = "",
                    sourceIndex = 0,
                ),
            ),
        )
        val text = ModelGroup.renderManaged(
            groups,
            tuning = mapOf(
                "深度求索/deepseek-chat" to ModelConfig.ModelTuning(
                    contextSize = 65536,
                    effort = "high",
                    displayName = "聊天模型",
                ),
            ),
            providerNames = mapOf("深度求索" to "深度求索"),
        )
        assertTrue(text.contains("[providers.\"深度求索\"]"))
        assertTrue(text.contains("name = \"深度求索\""))
        assertTrue(text.contains("display_name = \"聊天模型\""))
        assertTrue(text.contains("max_context_size = 65536"))
    }
}
