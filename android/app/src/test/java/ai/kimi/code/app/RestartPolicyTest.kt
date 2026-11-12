package ai.kimi.code.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the two bugs behind "chat disappeared / restart froze":
 * the port must never drift (localStorage is origin-scoped) and the workspace
 * must not be reloaded when nothing about its origin changed.
 */
class RestartPolicyTest {

    @Test
    fun `pinned port is mandatory even when it still looks busy`() {
        assertEquals(58631, RestartPolicy.portToUse(58631, 58630) { false })
        assertEquals(58631, RestartPolicy.portToUse(58631, 58630) { true })
        assertEquals(58630, RestartPolicy.portToUse(58630, 58630) { false })
    }

    @Test
    fun `first launch scans for a free port`() {
        assertEquals(58630, RestartPolicy.portToUse(0, 58630) { true })
        // 58630..58632 busy, 58633 free.
        assertEquals(58633, RestartPolicy.portToUse(0, 58630) { it >= 58633 })
        // Everything busy: fall back to the default instead of drifting on a pin.
        assertEquals(58630, RestartPolicy.portToUse(0, 58630) { false })
    }

    @Test
    fun `out of range pin falls back to scanning`() {
        assertEquals(58630, RestartPolicy.portToUse(70_000, 58630) { true })
        assertEquals(58630, RestartPolicy.portToUse(-1, 58630) { true })
        assertEquals(1024, RestartPolicy.portToUse(0, 10) { true })
    }

    @Test
    fun `origin ignores hash routes and tokens`() {
        assertEquals(
            "http://127.0.0.1:58630",
            RestartPolicy.originOf("http://127.0.0.1:58630/#token=abc"),
        )
        assertEquals(
            "http://127.0.0.1:58630",
            RestartPolicy.originOf("http://127.0.0.1:58630/#/chat/42"),
        )
        assertEquals(
            "http://127.0.0.1:58630",
            RestartPolicy.originOf("http://127.0.0.1:58630/some/path?q=1"),
        )
        assertNotEquals(
            RestartPolicy.originOf("http://127.0.0.1:58630/#token=a"),
            RestartPolicy.originOf("http://127.0.0.1:58631/#token=a"),
        )
    }

    @Test
    fun `same origin never reloads even with a different hash`() {
        assertFalse(
            RestartPolicy.shouldReloadWorkspace(
                "http://127.0.0.1:58630/#/chat/42",
                "http://127.0.0.1:58630/#token=abc",
                false,
                false,
            ),
        )
    }

    @Test
    fun `restored page is not reloaded but a fresh activity is`() {
        val url = "http://127.0.0.1:58630/#token=abc"
        assertFalse(RestartPolicy.shouldReloadWorkspace(null, url, true, false))
        assertTrue(RestartPolicy.shouldReloadWorkspace(null, url, false, false))
    }

    @Test
    fun `origin change and error retry still reload`() {
        assertTrue(
            RestartPolicy.shouldReloadWorkspace(
                "http://127.0.0.1:58630/#token=a",
                "http://127.0.0.1:58631/#token=a",
                false,
                false,
            ),
        )
        assertTrue(
            RestartPolicy.shouldReloadWorkspace(
                "http://127.0.0.1:58630/#/chat/1",
                "http://127.0.0.1:58630/#token=a",
                false,
                true,
            ),
        )
        assertFalse(RestartPolicy.shouldReloadWorkspace("http://x", "", false, false))
    }
}
