package ai.kimi.code.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the bug where saving a model logged the user out:
 * config.toml used to be rewritten wholesale, erasing the credential and any
 * effort settings the server had written.
 */
class ConfigMergerTest {

    private val managed = """
        [providers.kimi]
        type = "kimi"
        base_url = "https://api.moonshot.cn/v1"
        api_key = "sk-test"
        default_model = "kimi/k2"

        [models."kimi/k2"]
        provider = "kimi"
        model = "k2"
        max_context_size = 262144
    """.trimIndent()

    @Test
    fun `fresh file has the managed block and a top-level default_model`() {
        val out = ConfigMerger.merge("", managed, "kimi/k2")
        assertTrue(out.startsWith("default_model = \"kimi/k2\""))
        assertTrue(out.contains(ConfigMerger.MANAGED_START))
        assertTrue(out.contains(ConfigMerger.MANAGED_END))
        assertTrue(out.contains("[providers.kimi]"))
    }

    @Test
    fun `server owned sections survive a rewrite`() {
        val existing = """
            default_model = "kimi/k2"

            [auth]
            access_token = "oauth-secret"
            refresh_token = "refresh-secret"

            [ui]
            theme = "dark"

            [models."custom/my-model"]
            provider = "custom"
            model = "my-model"
            support_efforts = ["low", "medium", "high"]
            default_effort = "high"
        """.trimIndent() + "\n"

        val out = ConfigMerger.merge(existing, managed, "kimi/k2")

        assertTrue("login credential must survive", out.contains("access_token = \"oauth-secret\""))
        assertTrue(out.contains("refresh_token = \"refresh-secret\""))
        assertTrue(out.contains("theme = \"dark\""))
        assertTrue("workspace-authored model must survive", out.contains("[models.\"custom/my-model\"]"))
        assertTrue(out.contains("default_effort = \"high\""))
        // Exactly one *top-level* key; provider sections legitimately have
        // their own `default_model`, so counting the whole file would be wrong.
        val topLevel = out.lineSequence()
            .takeWhile { !it.trimStart().startsWith("[") }
            .joinToString("\n")
        assertEquals(1, topLevel.lines().count { it.startsWith("default_model =") })
        assertTrue(out.indexOf("default_model =") < out.indexOf("[auth]"))
    }

    @Test
    fun `app owned sections are replaced not duplicated`() {
        val first = ConfigMerger.merge("", managed, "kimi/k2")
        val second = ConfigMerger.merge(first, managed, "kimi/k2")
        assertEquals(first, second)
        assertEquals("idempotent merge", 1, countOccurrences(second, "[providers.kimi]"))
        assertEquals(1, countOccurrences(second, ConfigMerger.MANAGED_START))
    }

    @Test
    fun `removing a model drops only that section`() {
        // Real path: the app writes both models into its managed block, then
        // the user deletes one and saves again.
        val withTwo = """
            [providers.kimi]
            api_key = "a"
            [providers.deepseek]
            api_key = "b"
            [models."kimi/k2"]
            model = "k2"
            [models."deepseek/chat"]
            model = "chat"
        """.trimIndent()
        val onlyKimi = "[providers.kimi]\napi_key = \"a\"\n[models.\"kimi/k2\"]\nmodel = \"k2\"\n"
        val first = ConfigMerger.merge("", withTwo, "kimi/k2")
        val out = ConfigMerger.merge(first, onlyKimi, "kimi/k2")
        assertFalse(out.contains("[providers.deepseek]"))
        assertFalse(out.contains("[models.\"deepseek/chat\"]"))
        assertTrue(out.contains("[providers.kimi]"))
    }

    @Test
    fun `unmarked external sections are never deleted`() {
        // A config authored by the server/workspace (no managed markers) must
        // be preserved even for provider names the app does not manage.
        val external = """
            [providers.gateway]
            api_key = "external-secret"
            [models."gateway/model-a"]
            model = "model-a"
        """.trimIndent() + "\n"
        val out = ConfigMerger.merge(external, managed, "kimi/k2")
        assertTrue(out.contains("api_key = \"external-secret\""))
        assertTrue(out.contains("[models.\"gateway/model-a\"]"))
        assertTrue(out.contains("[providers.kimi]"))
    }

    @Test
    fun `legacy whole-app file is migrated without duplication`() {
        val legacy = ConfigMerger.LEGACY_MARKER + "\n" + """
            default_model = "kimi/k2"
            [providers.kimi]
            api_key = "old"
            [models."kimi/k2"]
            model = "k2"
        """.trimIndent() + "\n"
        val out = ConfigMerger.merge(legacy, managed, "kimi/k2")
        assertEquals(1, countOccurrences(out, "[providers.kimi]"))
        assertFalse(out.contains("api_key = \"old\""))
        assertTrue(out.contains("api_key = \"sk-test\""))
    }

    @Test
    fun `legacy migration keeps a hand added auth block`() {
        val legacy = ConfigMerger.LEGACY_MARKER + "\n" + """
            default_model = "kimi/k2"
            [providers.kimi]
            api_key = "old"
            [models."kimi/k2"]
            model = "k2"

            [auth]
            access_token = "keep-this-login"
        """.trimIndent() + "\n"
        val out = ConfigMerger.merge(legacy, managed, "kimi/k2")
        assertTrue("login must survive an upgrade", out.contains("access_token = \"keep-this-login\""))
        assertEquals(1, countOccurrences(out, "[providers.kimi]"))
        assertEquals(1, countOccurrences(out, "[auth]"))
    }

    @Test
    fun `removeManaged keeps credentials but drops the app block`() {
        val existing = """
            default_model = "kimi/k2"
            [auth]
            access_token = "keep-me"
        """.trimIndent() + "\n"
        val merged = ConfigMerger.merge(existing, managed, "kimi/k2")
        val cleaned = ConfigMerger.removeManaged(merged)
        assertTrue(cleaned.contains("access_token = \"keep-me\""))
        assertFalse(cleaned.contains(ConfigMerger.MANAGED_START))
        assertFalse(cleaned.contains("[providers.kimi]"))
        assertFalse(cleaned.contains("default_model ="))
    }

    @Test
    fun `escape neutralises quotes newlines and control chars`() {
        assertEquals("a\\\"b", ConfigMerger.escape("a\"b"))
        assertEquals("a\\nb", ConfigMerger.escape("a\nb"))
        assertEquals("a\\\\b", ConfigMerger.escape("a\\b"))
        assertTrue(ConfigMerger.escape("a\u0001b").contains("\\u0001"))
    }

    @Test
    fun `quoteKey quotes aliases containing slashes`() {
        assertEquals("plain_key", ConfigMerger.quoteKey("plain_key"))
        assertTrue(ConfigMerger.quoteKey("alias/model").startsWith("\""))
    }

    // --- thinking-effort regression ---------------------------------------
    // 2.8.0 wrote `support_efforts` into every model section. The kimi-code
    // server then rejects `effort=off` with `off-needs-offeffort` because a
    // model that declares supportEfforts is assumed to reason by default and
    // must also declare offEffort. Custom endpoints cannot safely do that.

    @Test
    fun `effortLines emits nothing when following the server`() {
        assertEquals("", ConfigMerger.effortLines(""))
    }

    @Test
    fun `effortLines emits only default_effort never capability metadata`() {
        val out = ConfigMerger.effortLines("medium")
        assertEquals("default_effort = \"medium\"\n", out)
        assertFalse("must not declare support_efforts", out.contains("support_efforts"))
        assertFalse("must not declare off_effort", out.contains("off_effort"))
    }

    @Test
    fun `effortLines escapes the value`() {
        val out = ConfigMerger.effortLines("hi\"g\nh")
        assertFalse(out.contains("hi\"g"))
        assertTrue(out.contains("hi\\\"g\\nh"))
    }

    @Test
    fun `upgrading from 2_8_0 removes the stale support_efforts line`() {
        // Exactly what 2.8.0 wrote into its managed block.
        val oldManaged = managed.replace(
            "max_context_size = 262144",
            "max_context_size = 262144\nsupport_efforts = [\"low\", \"medium\", \"high\"]\n" +
                "default_effort = \"medium\"",
        )
        val file280 = ConfigMerger.merge("", oldManaged, "kimi/k2")
        assertTrue("fixture must contain the bad line", file280.contains("support_efforts"))

        // 2.8.1 rewrites with a fresh managed block: same model, but effort
        // lines now generated by effortLines() (preference only).
        val newManaged = managed.replace(
            "max_context_size = 262144",
            "max_context_size = 262144\n" + ConfigMerger.effortLines("medium").trimEnd('\n'),
        )
        val upgraded = ConfigMerger.merge(file280, newManaged, "kimi/k2")

        assertFalse("stale capability metadata must be gone", upgraded.contains("support_efforts"))
        assertFalse(upgraded.contains("off_effort"))
        assertTrue("user's chosen effort must survive", upgraded.contains("default_effort = \"medium\""))
        assertEquals(1, countOccurrences(upgraded, ConfigMerger.MANAGED_START))
        assertEquals(1, countOccurrences(upgraded, "[models.\"kimi/k2\"]"))
    }

    @Test
    fun `server authored support_efforts outside the managed block is preserved`() {
        // The app must not delete capability metadata the workspace itself
        // configured -- only its own stale block is rewritten.
        val existing = """
            default_model = "kimi/k2"

            [models."custom/spark"]
            model = "Spark-X2.6-4B"
            support_efforts = ["low", "high"]
            off_effort = "none"
        """.trimIndent() + "\n"
        val out = ConfigMerger.merge(existing, managed, "kimi/k2")
        assertTrue(out.contains("off_effort = \"none\""))
        assertTrue(out.contains("[models.\"custom/spark\"]"))
    }

    private fun countOccurrences(text: String, needle: String): Int =
        text.split(needle).size - 1
}
