package ai.kimi.code.app

import java.net.URI

/**
 * Decides which links stay inside the workspace WebView and which must be handed
 * to the system. It is deliberately free of Android types so the rule can be
 * unit-tested: sending the sign-in page into the workspace WebView is exactly
 * what replaced the UI with a blank page.
 */
object LinkPolicy {
    private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")

    /**
     * Schemes the WebView has to handle itself. Handing these to another app
     * would either fail or open something unrelated, so they stay put.
     */
    private val IN_WEBVIEW_SCHEMES =
        setOf("blob", "data", "about", "javascript", "file", "content", "chrome")

    /**
     * True when [url] belongs to the locally running workspace server. Those
     * navigations stay in the WebView; everything else is somebody else's page.
     */
    fun isWorkspace(url: String?, port: Int): Boolean {
        if (port <= 0) return false
        val parsed = parse(url) ?: return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = parsed.host?.lowercase()?.removeSurrounding("[", "]") ?: return false
        if (host !in LOCAL_HOSTS) return false
        val effectivePort = if (parsed.port != -1) parsed.port else if (scheme == "https") 443 else 80
        return effectivePort == port
    }

    /**
     * True when [url] must be handed to the system. This is the sign-in page,
     * ordinary links, and the `mailto:` / `tel:` handoffs.
     */
    fun shouldOpenExternally(url: String?, port: Int): Boolean =
        !isWorkspace(url, port) && canHandOff(url)

    /** True when another app can meaningfully receive [url]. */
    fun canHandOff(url: String?): Boolean {
        val scheme = parse(url)?.scheme?.lowercase() ?: return false
        return scheme !in IN_WEBVIEW_SCHEMES
    }

    private fun parse(url: String?): URI? {
        if (url.isNullOrBlank()) return null
        return runCatching { URI(url) }.getOrNull()
    }
}
