package ai.kimi.code.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.16.1 回归：
 * 1) 覆盖安装 / config.toml 重写后，服务端写入的登录凭据段不能丢
 *    （用户反馈「Kimi 的账号掉了」）；
 * 2) 拉取模型只列「尚未新增」的候选，默认绝不自动加入
 *    （用户反馈「一点拉取就全加进来，还要一个一个删」）。
 */
class LoginStateConfigTest {

    private val start = ConfigMerger.MANAGED_START
    private val end = ConfigMerger.MANAGED_END

    @Test
    fun `auth section is preserved when config is rewritten`() {
        val existing = buildString {
            appendLine("default_model = \"custom/glm-5\"")
            appendLine()
            appendLine("[auth]")
            appendLine("access_token = \"eyJhbGciOi...\"")
            appendLine("refresh_token = \"r-123\"")
            appendLine()
            appendLine(start)
            appendLine("[models.\"custom/glm-5\"]")
            appendLine("provider = \"custom\"")
            appendLine(end)
        }
        val composed = buildString {
            appendLine("default_model = \"custom/glm-5\"")
            appendLine()
            appendLine(start)
            appendLine("[models.\"custom/glm-5\"]")
            appendLine("provider = \"custom\"")
            appendLine(end)
        }
        val restored = ModelConfig.restoreCredentials(existing, composed)
        assertTrue(restored.contains("[auth]"))
        assertTrue(restored.contains("access_token = \"eyJhbGciOi...\""))
        assertTrue(restored.contains("refresh_token = \"r-123\""))
        // 凭据段必须落在 managed 块之外，否则下次重写又会被剥掉
        assertTrue(restored.indexOf("[auth]") < restored.indexOf(start))
        assertTrue(restored.contains("default_model"))
    }

    @Test
    fun `credential section already present is not duplicated`() {
        val text = buildString {
            appendLine("[auth]")
            appendLine("token = \"abc\"")
            appendLine()
            appendLine(start)
            appendLine(end)
        }
        val restored = ModelConfig.restoreCredentials(text, text)
        assertEquals(text, restored)
        assertEquals(1, restored.split("[auth]").size - 1)
    }

    @Test
    fun `provider api_key section is not mistaken for a login credential`() {
        val text = buildString {
            appendLine("token = \"top-level\"")
            appendLine()
            appendLine("[providers.custom]")
            appendLine("api_key = \"sk-test\"")
            appendLine()
            appendLine("[auth]")
            appendLine("session_token = \"s-1\"")
            appendLine()
            appendLine("[models.\"custom/glm-5\"]")
            appendLine("model = \"glm-5\"")
        }
        // 顶层行由 ConfigMerger 自己保留；App 生成的 providers/models 段
        // 绝不能被当成凭据补回，否则会造成 TOML 重复键、服务端起不来。
        val blocks = ModelConfig.credentialBlocks(text)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].contains("[auth]"))
        assertTrue(blocks[0].contains("session_token"))
    }

    @Test
    fun `fetch candidates only list models not already added`() {
        val remote = listOf("glm-5.2", "kimi-k3", "glm-5", "glm-5.2", "  ", "DeepSeek-V4")
        val pending = ModelConfig.pendingModelIds(remote, setOf("glm-5"))
        assertEquals(listOf("DeepSeek-V4", "glm-5.2", "kimi-k3"), pending)
    }

    @Test
    fun `fetch candidates are empty when everything is already added`() {
        assertTrue(
            ModelConfig.pendingModelIds(listOf("a", "A ", "b"), setOf("a", "b")).isEmpty(),
        )
    }

    @Test
    fun `fetch candidates deduplicate case insensitively`() {
        assertEquals(
            listOf("GPT-4o"),
            ModelConfig.pendingModelIds(listOf("GPT-4o", "gpt-4o"), emptySet()),
        )
    }

    @Test
    fun `server written oauth provider survives an app config rewrite`() {
        // 订阅登录写在 [providers."Kimi 订阅"] 里，靠 oauth 引用指向 credentials
        // 目录。App 重建 managed 块时不能把它当成「自己的 providers 段」删掉。
        val existing = buildString {
            appendLine("default_model = \"讯飞/spark-x2.6\"")
            appendLine()
            appendLine("[providers.\"Kimi 订阅\"]")
            appendLine("type = \"kimi\"")
            appendLine("oauth = { key = \"kimi/default\" }")
            appendLine("default_model = \"Kimi 订阅/kimi-k2\"")
            appendLine()
            appendLine("[models.\"Kimi 订阅/kimi-k2\"]")
            appendLine("provider = \"Kimi 订阅\"")
            appendLine("model = \"kimi-k2\"")
            appendLine()
            appendLine(start)
            appendLine("[providers.讯飞]")
            appendLine("type = \"openai\"")
            appendLine(end)
        }
        val managed = buildString {
            appendLine("[providers.讯飞]")
            appendLine("type = \"openai\"")
            appendLine("base_url = \"https://example.com/v1\"")
            appendLine()
            appendLine("[models.\"讯飞/spark-x2.6\"]")
            appendLine("provider = \"讯飞\"")
            appendLine("model = \"spark-x2.6\"")
        }
        val merged = ConfigMerger.merge(existing, managed, "讯飞/spark-x2.6")
        assertTrue(merged.contains("[providers.\"Kimi 订阅\"]"))
        assertTrue(merged.contains("oauth = { key = \"kimi/default\" }"))
        assertTrue(merged.contains("models.\"Kimi 订阅/kimi-k2\""))
    }
}
