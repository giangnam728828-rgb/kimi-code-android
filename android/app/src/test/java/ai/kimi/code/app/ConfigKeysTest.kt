package ai.kimi.code.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for 2.12.0: the broken provider alias, multi-model
 * providers and the config.toml upgrade path.
 */
class ConfigKeysTest {

    private val broken =
        "Preset(id=custom, label=自定义（OpenAI 兼容）, type=openai, baseUrl=, model=, contextSize=131072).id-2"

    @Test
    fun `second alias is a short word, never an object toString`() {
        assertEquals("custom-2", ConfigMerger.uniqueAlias("custom", setOf("custom")))
        assertEquals("custom-3", ConfigMerger.uniqueAlias("custom", setOf("custom", "custom-2")))
        assertFalse(ConfigMerger.uniqueAlias("custom", setOf("custom")).contains("Preset("))
    }

    @Test
    fun `names are sanitised into short keys`() {
        // 2.16.2：别名保留 Unicode 字母（含中文），因为工作台分组标题就是别名，
        // 只有 ASCII 会把「深度求索」清成 custom，改名等于没改。
        assertEquals("deepseek-网关", ConfigMerger.sanitizeAlias("DeepSeek 网关", "custom"))
        assertEquals("深度求索", ConfigMerger.sanitizeAlias("深度求索", "custom"))
        assertEquals("my-gateway", ConfigMerger.sanitizeAlias("My Gateway", "custom"))
    }

    @Test
    fun `broken legacy alias falls back to the preset id`() {
        assertEquals("custom", ConfigMerger.sanitizeAlias(broken, "custom"))
        assertEquals("deepseek", ConfigMerger.sanitizeAlias(broken, "deepseek"))
    }

    @Test
    fun `saved aliases heal and stay unique`() {
        val cleaned = ConfigMerger.cleanAliases(
            listOf("custom", broken, broken, "My Gateway"),
            listOf("custom", "custom", "custom", "openai"),
        )
        assertEquals(listOf("custom", "custom-2", "custom-3", "my-gateway"), cleaned)
    }

    @Test
    fun `model keys are lowercase so the web id matches config toml`() {
        assertEquals("custom/spark-x2.6-4b", ConfigMerger.modelKey("custom", "Spark-X2.6-4B"))
        assertEquals("models.\"custom/spark-x2.6\"", ConfigMerger.modelSectionKey("custom", "spark-x2.6"))
    }

    @Test
    fun `upgrade drops broken sections and keeps workspace-authored models`() {
        val existing = """
            default_model = "custom/Spark-X2.6-4B"

            [providers."Preset(id=custom, label=x, contextSize=131072).id-2"]
            type = "openai"
            api_key = "sk-2"

            [models."Preset(id=custom, label=x, contextSize=131072).id-2/deepseek-v4.1-flash"]
            provider = "x"
            model = "deepseek-v4.1-flash"

            [models."my-own/hand-added"]
            provider = "mine"
            model = "hand-added"
        """.trimIndent()
        val managed = """
            [providers.custom]
            type = "openai"
            api_key = "sk-1"

            [models."custom/spark-x2.6"]
            provider = "custom"
            model = "spark-x2.6"

            [models."custom/deepseek-v4.1-flash"]
            provider = "custom"
            model = "deepseek-v4.1-flash"
        """.trimIndent()

        val out = ConfigMerger.merge(existing, managed, "custom/spark-x2.6")

        assertFalse(out.contains("Preset("))
        assertTrue(out.contains("[models.\"custom/spark-x2.6\"]"))
        assertTrue(out.contains("[models.\"custom/deepseek-v4.1-flash\"]"))
        assertTrue(out.contains("[models.\"my-own/hand-added\"]"))
        assertEquals(
            1,
            Regex("^default_model\\s*=", RegexOption.MULTILINE).findAll(out).count(),
        )
    }

    @Test
    fun `stale models under an app alias are removed but foreign ones survive`() {
        val existing = """
            [providers.custom]
            type = "openai"
            api_key = "sk-1"

            [models."custom/old-model"]
            provider = "custom"

            [models."my-own/hand-added"]
            provider = "mine"
        """.trimIndent()
        val managed = """
            [providers.custom]
            type = "openai"
            api_key = "sk-1"

            [models."custom/spark-x2.6"]
            provider = "custom"
        """.trimIndent()

        val out = ConfigMerger.merge(
            existing, managed, "custom/spark-x2.6",
            dropPrefixes = setOf("models.\"custom/"),
        )

        assertFalse(out.contains("old-model"))
        assertTrue(out.contains("[models.\"custom/spark-x2.6\"]"))
        assertTrue(out.contains("[models.\"my-own/hand-added\"]"))
    }

    @Test
    fun `codec round trips the extra models field and old records still decode`() {
        val records = listOf(
            PrefsCodec.Record(
                "custom", "custom", "sk", "url", "spark-x2.6", 131072, "",
                "deepseek-v4.1-flash\u001Dgpt-4o",
            ),
        )
        assertEquals(records, PrefsCodec.decode(PrefsCodec.encode(records), 131072))
        assertEquals(
            listOf("deepseek-v4.1-flash", "gpt-4o"),
            PrefsCodec.splitModels(records[0].models),
        )

        val legacy = listOf("custom", "custom", "sk", "url", "spark-x2.6", "131072", "low")
            .joinToString(PrefsCodec.FIELD_SEP)
        assertEquals("", PrefsCodec.decode(legacy, 131072)[0].models)
        assertEquals("low", PrefsCodec.decode(legacy, 131072)[0].effort)
    }
}
