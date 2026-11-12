package ai.kimi.code.app

import android.content.Context
import android.net.ConnectivityManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Owns the proot process that hosts the Linux runtime.
 *
 * The app targets API 28 on purpose: Android only allows executing binaries
 * stored in the app's private data directory for apps that do not target 29+,
 * and proot needs to be launched from there.
 */
object ProotRuntime {

    private var process: Process? = null

    @Volatile private var stopping = false

    fun isRunning(): Boolean = process?.isAlive == true

    fun start(context: Context, port: Int): Process {
        stop()

        require(Paths.prootBin(context).isFile) { "proot 未安装" }
        require(Paths.prootLoader(context).isFile) { "proot loader 未安装" }
        require(File(Paths.rootfs(context), "opt/kimi/entrypoint.sh").isFile) { "rootfs 未安装" }

        writeResolvConf(context)
        ConfigWriter.applyIfConfigured(context)

        val host = if (Prefs.lan(context)) "0.0.0.0" else "127.0.0.1"
        val extra = buildList {
            add("KIMI_PORT=$port")
            add("KIMI_HOST=$host")
            add("KIMI_WORKDIR=${Prefs.workspace(context)}")
        }

        Runtime.log("启动 proot：端口 $port（监听 $host）")
        stopping = false
        val started = runCatching {
            proot(context, listOf("/bin/bash", "/opt/kimi/entrypoint.sh"), extra)
        }.getOrElse { error ->
            throw IllegalStateException("无法启动 proot：${error.message}", error)
        }
        process = started
        pumpOutput(started)
        return started
    }

    /**
     * Runs a one-shot command inside the same rootfs. Used by the terminal page
     * and by the plugin installer; returns the process with merged output.
     */
    fun shell(context: Context, script: String, extraEnv: List<String> = emptyList()): Process {
        val env = buildList {
            add("KIMI_WORKDIR=${Prefs.workspace(context)}")
            addAll(extraEnv)
        }
        return proot(context, listOf("/bin/bash", "-lc", script), env)
    }

    private fun proot(context: Context, entry: List<String>, env: List<String>): Process {
        val prootDir = Paths.prootDir(context)
        val rootfs = Paths.rootfs(context)

        val command = buildList {
            add(Paths.prootBin(context).absolutePath)
            add("--link2symlink")
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            val external = File("/storage/emulated/0")
            if (external.isDirectory) {
                add("-b")
                add(external.absolutePath)
            }
            add("-w")
            add("/root")
            add("/usr/bin/env")
            add("-i")
            add("HOME=/root")
            add("KIMI_CODE_HOME=/root/.kimi-code")
            add("PATH=/root/.kimi-code/bin:/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            add("TERM=xterm-256color")
            add("LANG=C.UTF-8")
            add("LC_ALL=C.UTF-8")
            addAll(env)
            addAll(entry)
        }

        val builder = ProcessBuilder(command)
        builder.directory(prootDir)
        builder.redirectErrorStream(true)
        val environment = builder.environment()
        environment.clear()
        environment["LD_LIBRARY_PATH"] = prootDir.absolutePath
        environment["PROOT_LOADER"] = Paths.prootLoader(context).absolutePath
        environment["PROOT_TMP_DIR"] = Paths.tmpDir(context).absolutePath
        environment["PROOT_NO_SECCOMP"] = "1"
        environment["HOME"] = prootDir.absolutePath
        environment["TMPDIR"] = Paths.tmpDir(context).absolutePath
        return builder.start()
    }

    fun stop() {
        val current = process ?: return
        stopping = true
        runCatching { current.destroy() }
        Thread {
            runCatching { Thread.sleep(1500) }
            runCatching { if (current.isAlive) current.destroyForcibly() }
        }.apply { isDaemon = true }.start()
        process = null
    }

    /**
     * Synchronous variant used before a restart. `stop()` only fires destroy()
     * and returns immediately, so the old server is usually still listening
     * when the next worker probes the port. Waiting here keeps the port (and
     * therefore the WebView origin) stable across restarts.
     */
    fun stopAndWait(timeoutMs: Long) {
        stopping = true
        val current = process
        process = null
        if (current == null) return
        runCatching { current.destroy() }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (current.isAlive && System.currentTimeMillis() < deadline) {
            runCatching { Thread.sleep(100) }
        }
        if (current.isAlive) runCatching { current.destroyForcibly() }
        // Give the kernel a moment to tear down the listening socket and reap
        // proot's children, which can outlive the Java Process object.
        runCatching { Thread.sleep(300) }
    }

    fun shouldRestart(): Boolean = !stopping

    private fun pumpOutput(target: Process) {
        Thread {
            runCatching {
                BufferedReader(InputStreamReader(target.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) Runtime.log(line)
                    }
                }
            }
            val code = runCatching { target.waitFor() }.getOrDefault(-1)
            lastExit = code
            Runtime.log("proot 进程退出，退出码 $code")
        }.apply { isDaemon = true }.start()
    }

    /**
     * Android has no /etc/resolv.conf, so the rootfs gets one generated from
     * the system DNS servers (falling back to public resolvers).
     */
    fun writeResolvConf(context: Context) {
        val servers = runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.activeNetwork?.let { network ->
                manager.getLinkProperties(network)?.dnsServers
                    ?.mapNotNull { it.hostAddress }
                    ?.filter { it.isNotEmpty() }
            }
        }.getOrNull().orEmpty()

        val effective = if (servers.isNullOrEmpty()) {
            listOf("8.8.8.8", "1.1.1.1")
        } else {
            servers
        }
        val content = buildString {
            effective.forEach { appendLine("nameserver $it") }
            appendLine("nameserver 8.8.8.8")
            appendLine("nameserver 223.5.5.5")
        }
        runCatching {
            val file = File(Paths.rootfs(context), "etc/resolv.conf")
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
    }

    fun isPortOpen(port: Int, host: String = "127.0.0.1", timeoutMs: Int = 500): Boolean {
        return runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Waits for the server to answer HTTP, then reads the persistent bearer
     * token so the WebView can be opened with the `#token=` fragment (without
     * it the web UI shows a token prompt).
     */
    fun awaitToken(context: Context, port: Int, timeoutMs: Long = 180_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen(port)) {
                val token = readToken(context)
                if (token != null) return token
            }
            if (process?.isAlive != true) {
                Runtime.log("proot 在服务就绪前退出，放弃等待")
                return null
            }
            runCatching { Thread.sleep(600) }
        }
        Runtime.log("等待服务就绪超时（${timeoutMs / 1000} 秒）")
        return null
    }

    /** Human-readable reason why the last launch died, for the UI. */
    fun lastExitCode(): Int? = lastExit

    @Volatile private var lastExit: Int? = null

    fun readToken(context: Context): String? {
        return runCatching {
            val file = Paths.tokenFile(context)
            if (!file.isFile) return null
            val token = file.readText().trim()
            token.ifEmpty { null }
        }.getOrNull()
    }
}
