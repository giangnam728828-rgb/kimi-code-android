package ai.kimi.code.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.15.0 的两条硬规则：
 *  - 不同分组必须彼此隔开（独立 alias + 独立 model key 前缀）；
 *  - 同一分组可以一次登记多个模型。
 */
class ModelGroupTest {

    private fun spec(
        alias: String,
        model: String,
        models: List<String> = emptyList(),
        index: Int = 0,
    ) = ModelGroup.Spec(
        alias = alias,
        type = "openai",
        baseUrl = "https://example.com/v1",
        apiKey = "sk-test",
        model = model,
        models = models,
        contextSize = 131072,
        effort = "",
        sourceIndex = index,
    )

    @Test
    fun `different groups get separate provider sections and key prefixes`() {
        val groups = ModelGroup.from(
            listOf(
                spec("glm-group", "glm-5.3-flash"),
                spec("ds-group", "deepseek-v4.1-flash"),
            ),
        )
        assertEquals(2, groups.size)
        assertTrue(ModelGroup.validate(groups).isEmpty())

        val toml = ModelGroup.renderManaged(groups)
        assertTrue(toml.contains("[providers.glm-group]"))
        assertTrue(toml.contains("[providers.ds-group]"))
        assertTrue(toml.contains("""[models."glm-group/glm-5.3-flash"]"""))
        assertTrue(toml.contains("""[models."ds-group/deepseek-v4.1-flash"]"""))
        assertFalse(toml.contains("""[models."glm-group/deepseek-v4.1-flash"]"""))
    }

    @Test
    fun `duplicate alias from 2_14_0 is split instead of merged`() {
        // 2.14.0 把两个分组都写成了 CUSTOM-2，于是菜单里合成了平铺的一列。
        val groups = ModelGroup.from(
            listOf(
                spec("CUSTOM-2", "glm-5.3-flash", index = 0),
                spec("CUSTOM-2", "deepseek-v4.1-flash", index = 1),
            ),
        )
        assertEquals(2, groups.size)
        assertEquals("custom-2", groups[0].alias)
        assertEquals("custom-2-2", groups[1].alias)
        assertTrue(ModelGroup.validate(groups).isEmpty())

        val toml = ModelGroup.renderManaged(groups)
        assertEquals(2, toml.lines().count { it.startsWith("[providers.") })
        assertTrue(toml.contains("""[models."custom-2-2/deepseek-v4.1-flash"]"""))
    }

    @Test
    fun `one group can hold several picked models`() {
        val picked = listOf("xopglm52", "xopglm53", "xopdeepseekv4flash0731")
        val (defaultModel, rest) = ModelGroup.mergePicked("", emptyList(), picked)
        assertEquals("xopglm52", defaultModel)
        assertEquals(2, rest.size)

        val groups = ModelGroup.from(listOf(spec("my-gateway", defaultModel, rest)))
        val toml = ModelGroup.renderManaged(groups)
        assertEquals(1, toml.lines().count { it.startsWith("[providers.") })
        assertEquals(3, toml.lines().count { it.startsWith("[models.") })
        groups[0].modelKeys().forEach {
            assertTrue(it.startsWith("my-gateway/"))
        }
        assertTrue(ModelGroup.validate(groups).isEmpty())
    }

    @Test
    fun `mergePicked deduplicates case insensitively and keeps the cap`() {
        val (defaultModel, rest) = ModelGroup.mergePicked(
            "DeepSeek-V3.1",
            listOf("glm-4.6", "deepseek-v3.1"),
            listOf("GLM-4.6", "qwen-plus"),
        )
        assertEquals("DeepSeek-V3.1", defaultModel)
        assertEquals(listOf("glm-4.6", "qwen-plus"), rest)

        val many = (1..60).map { "model-$it" }
        val (_, capped) = ModelGroup.mergePicked("", emptyList(), many)
        assertEquals(ModelGroup.MAX_MODELS_PER_GROUP, capped.size + 1)
    }

    @Test
    fun `managed toml separates groups with a blank line`() {
        val toml = ModelGroup.renderManaged(
            ModelGroup.from(listOf(spec("a", "m1"), spec("b", "m2"))),
        )
        assertTrue(toml.contains("\n\n# --- 分组 b"))
    }

    @Test
    fun `validate catches duplicate group aliases`() {
        // 两个分组共用一个 alias 就是 2.14.0 的合并形态：模型 key 会重复，
        // 菜单只会画出一个标题。校验必须把这种情况报出来。
        val a = ModelGroup.from(listOf(spec("a", "m1"))).first()
        val b = a.copy(sourceIndex = 1)
        val problems = ModelGroup.validate(listOf(a, b))
        assertTrue(problems.any { it.contains("分组名重复") })
        assertTrue(problems.any { it.contains("重复的 model key") })
    }

    @Test
    fun `dropPrefixes covers every group`() {
        val groups = ModelGroup.from(listOf(spec("a", "m1"), spec("b", "m2")))
        val prefixes = ModelGroup.dropPrefixes(groups)
        assertEquals(2, prefixes.size)
        assertTrue(prefixes.all { it.startsWith("models.") })
    }
}
