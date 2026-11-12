package ai.kimi.code.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    // ------------------------------------------------------------ JSON shapes

    @Test
    fun `parses standard openai data array`() {
        val json = """{"object":"list","data":[{"id":"gpt-4o","owned_by":"openai"},{"id":"kimi-k2"}]}"""
        assertEquals(listOf("gpt-4o", "kimi-k2"), ModelCatalog.parseModelIds(json))
    }

    @Test
    fun `parses string array and models field`() {
        assertEquals(listOf("a", "b"), ModelCatalog.parseModelIds("""{"data":["a","b"]}"""))
        assertEquals(listOf("x"), ModelCatalog.parseModelIds("""{"models":[{"id":"x"}]}"""))
    }

    @Test
    fun `parses top level array mixed shapes`() {
        assertEquals(listOf("x", "y"), ModelCatalog.parseModelIds("""[{"id":"x"},"y"]"""))
    }

    @Test
    fun `parses single model object`() {
        assertEquals(listOf("solo"), ModelCatalog.parseModelIds("""{"id":"solo"}"""))
    }

    @Test
    fun `deduplicates and drops blanks without losing order`() {
        val json = """{"data":[{"id":"a"},{"id":""},{"id":"b"},{"id":"a"},{"name":"c"}]}"""
        assertEquals(listOf("a", "b", "c"), ModelCatalog.parseModelIds(json))
    }

    @Test
    fun `handles escaped slashes and unicode in ids`() {
        assertEquals(listOf("a/b"), ModelCatalog.parseModelIds("""{"data":[{"id":"a\/b"}]}"""))
        assertEquals(listOf("模型-中"), ModelCatalog.parseModelIds("""{"data":[{"id":"\u6a21\u578b-\u4e2d"}]}"""))
    }

    @Test
    fun `malformed json never throws and empty object yields empty list`() {
        assertTrue(ModelCatalog.parseModelIds("").isEmpty())
        assertTrue(ModelCatalog.parseModelIds("not json at all").isEmpty())
        assertTrue(ModelCatalog.parseModelIds("""{}""").isEmpty())
        // Truncated payloads must not crash the picker thread.
        assertTrue(runCatching { ModelCatalog.parseModelIds("""{"data":[{"id":"broken""") }.isSuccess)
    }

    @Test
    fun `nested data list is flattened`() {
        assertEquals(listOf("a", "b"), ModelCatalog.parseModelIds("""{"data":[["a"],[{"id":"b"}]]}"""))
    }

    // -------------------------------------------------------------- URL shaping

    @Test
    fun `model urls keep only one candidate when base already ends with v1`() {
        assertEquals(listOf("https://api.x.com/v1/models"), ModelCatalog.modelUrls("https://api.x.com/v1"))
    }

    @Test
    fun `model urls try both shapes when base has no version`() {
        assertEquals(
            listOf("https://api.x.com/models", "https://api.x.com/v1/models"),
            ModelCatalog.modelUrls("https://api.x.com/"),
        )
    }

    @Test
    fun `model urls strip concrete endpoint suffixes`() {
        assertEquals(
            listOf("https://api.x.com/v1/models"),
            ModelCatalog.modelUrls("https://api.x.com/v1/chat/completions"),
        )
    }

    @Test
    fun `model urls reject empty and non http bases`() {
        assertTrue(ModelCatalog.modelUrls("").isEmpty())
        assertTrue(ModelCatalog.modelUrls("   ").isEmpty())
        assertTrue(ModelCatalog.modelUrls("ftp://api.x.com/v1").isEmpty())
    }

    @Test
    fun `chat urls prefer v1 then bare path`() {
        assertEquals(
            listOf("https://api.x.com/v1/chat/completions", "https://api.x.com/chat/completions"),
            ModelCatalog.chatUrls("https://api.x.com"),
        )
        assertEquals(
            listOf("https://api.x.com/v1/chat/completions"),
            ModelCatalog.chatUrls("https://api.x.com/v1"),
        )
    }

    // ------------------------------------------------------- vendor / context

    @Test
    fun `describes common vendors`() {
        assertEquals("moonshot", ModelCatalog.describe("kimi-k2-turbo-preview").vendorKey)
        assertEquals("anthropic", ModelCatalog.describe("claude-sonnet-4-20250514").vendorKey)
        assertEquals("deepseek", ModelCatalog.describe("xopdeepseekv4pro").vendorKey)
        assertEquals("glm", ModelCatalog.describe("xopglm52").vendorKey)
        assertEquals("spark", ModelCatalog.describe("Spark-X2.6-4B").vendorKey)
        assertEquals("gateway", ModelCatalog.describe("astronclaw-auto").vendorKey)
        assertEquals("other", ModelCatalog.describe("totally-unknown").vendorKey)
    }

    @Test
    fun `context size is conservative and specific where known`() {
        assertEquals(262144, ModelCatalog.contextSizeFor("kimi-k2"))
        assertEquals(200000, ModelCatalog.contextSizeFor("claude-sonnet-4"))
        assertEquals(128000, ModelCatalog.contextSizeFor("glm-4-plus"))
        assertEquals(128000, ModelCatalog.contextSizeFor("gpt-4o"))
        assertEquals(131072, ModelCatalog.contextSizeFor("my-custom-model"))
    }

    @Test
    fun `every described model keeps the exact upstream id`() {
        val m = ModelCatalog.describe("Spark-X2.6-4B")
        assertEquals("Spark-X2.6-4B", m.id) // never prettified into a display name
        assertEquals("spark", m.vendorKey)
    }

    // ------------------------------------------------------------- search/group

    @Test
    fun `search matches every term and ranks prefix first`() {
        val models = ModelCatalog.describeAll(listOf("openai/gpt-4o-mini", "gpt-4o", "claude-3"))
        assertEquals(listOf("gpt-4o", "openai/gpt-4o-mini"), ModelCatalog.search(models, "gpt").map { it.id })
        assertTrue(ModelCatalog.search(models, "gpt claude").isEmpty())
        assertEquals(models, ModelCatalog.search(models, "   "))
    }

    @Test
    fun `search also matches vendor labels`() {
        val models = ModelCatalog.describeAll(listOf("xopglm52", "kimi-k2"))
        assertEquals(listOf("xopglm52"), ModelCatalog.search(models, "glm").map { it.id })
        assertEquals(listOf("kimi-k2"), ModelCatalog.search(models, "moonshot").map { it.id })
    }

    @Test
    fun `group keeps vendor order and sorts ids inside a group`() {
        val grouped = ModelCatalog.group(
            ModelCatalog.describeAll(listOf("zz-model", "kimi-k2", "xopglm52", "aa-model")),
        )
        assertEquals(listOf("Moonshot / Kimi", "智谱 GLM", "其他"), grouped.map { it.first })
        assertEquals(listOf("aa-model", "zz-model"), grouped.last().second.map { it.id })
    }

    @Test
    fun `jsonString escapes quotes backslashes and controls`() {
        assertEquals("\"a\\\"b\"", ModelCatalog.jsonString("a\"b"))
        assertEquals("\"a\\\\b\"", ModelCatalog.jsonString("a\\b"))
        assertEquals("\"a\\nb\"", ModelCatalog.jsonString("a\nb"))
        assertEquals("\"\\u0001\"", ModelCatalog.jsonString("\u0001"))
    }

    @Test
    fun `fetch with blank base url reports a usable hint`() {
        val result = ModelCatalog.fetch("", "sk-x")
        assertTrue(result.ids.isEmpty())
        assertTrue(result.error?.contains("接口地址") == true)
    }
}
