package ai.kimi.code.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPolicyTest {

    private val port = 58630

    @Test
    fun `workspace origin stays in the webview`() {
        assertTrue(LinkPolicy.isWorkspace("http://127.0.0.1:58630/", port))
        assertTrue(LinkPolicy.isWorkspace("http://localhost:58630/", port))
        assertTrue(LinkPolicy.isWorkspace("http://127.0.0.1:58630/#token=abc", port))
        assertTrue(LinkPolicy.isWorkspace("http://127.0.0.1:58630/api/v1/sessions", port))
    }

    @Test
    fun `sign-in and other origins leave the webview`() {
        assertFalse(LinkPolicy.isWorkspace("https://auth.kimi.com/device", port))
        assertFalse(LinkPolicy.isWorkspace("https://www.kimi.com/code/authorize", port))
        assertFalse(LinkPolicy.isWorkspace("https://github.com/", port))
    }

    @Test
    fun `a different local port is a different server`() {
        assertFalse(LinkPolicy.isWorkspace("http://127.0.0.1:58631/", port))
        assertFalse(LinkPolicy.isWorkspace("http://localhost:80/", port))
    }

    @Test
    fun `non http schemes and junk are never the workspace`() {
        assertFalse(LinkPolicy.isWorkspace("mailto:someone@example.test", port))
        assertFalse(LinkPolicy.isWorkspace("tel:+10000000000", port))
        assertFalse(LinkPolicy.isWorkspace("kimiauth://callback", port))
        assertFalse(LinkPolicy.isWorkspace("", port))
        assertFalse(LinkPolicy.isWorkspace(null, port))
    }

    @Test
    fun `no port yet means nothing is local`() {
        assertFalse(LinkPolicy.isWorkspace("http://127.0.0.1:58630/", 0))
    }

    @Test
    fun `default ports are resolved`() {
        assertTrue(LinkPolicy.isWorkspace("http://127.0.0.1/", 80))
        assertTrue(LinkPolicy.isWorkspace("https://localhost/", 443))
    }

    @Test
    fun `registration routes external pages to the system`() {
        assertTrue(LinkPolicy.shouldOpenExternally("https://auth.kimi.com/device", port))
        assertTrue(LinkPolicy.shouldOpenExternally("mailto:someone@example.test", port))
        assertTrue(LinkPolicy.shouldOpenExternally("tel:+10000000000", port))
        assertFalse(LinkPolicy.shouldOpenExternally("http://127.0.0.1:58630/", port))
    }

    @Test
    fun `webview owned schemes are never handed off`() {
        assertFalse(LinkPolicy.canHandOff("blob:http://127.0.0.1:58630/abc"))
        assertFalse(LinkPolicy.canHandOff("data:text/plain,hi"))
        assertFalse(LinkPolicy.canHandOff("about:blank"))
        assertFalse(LinkPolicy.canHandOff("javascript:void(0)"))
        assertTrue(LinkPolicy.canHandOff("https://auth.kimi.com/device"))
    }
}
