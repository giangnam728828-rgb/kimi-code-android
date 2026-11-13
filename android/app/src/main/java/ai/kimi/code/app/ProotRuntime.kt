package ai.kimi.code.app

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import android.system.OsConstants
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

    /** 当前服务端口与 bearer token，供优雅关闭使用。 */
    @Volatile private var activePort = 0
    @Volatile private var activeToken: String? = null

    fun rememberToken(token: String) {
        activeToken = token
    }

    fun isRunning(): Boolean = process?.isAlive == true

    fun start(context: Context, port: Int): Process {
        stop()

        require(Paths.prootBin(context).isFile) { "proot 未安装" }
        require(Paths.prootLoader(context).isFile) { "proot loader 未安装" }
        require(File(Paths.rootfs(context), "opt/kimi/entrypoint.sh").isFile) { "rootfs 未安装" }

        writeResolvConf(context)
        CredentialGuard.restoreIfMissing(context)
        ConfigWriter.applyIfConfigured(context)

        // 2.17.0：工作目录默认落到手机共享存储，Agent 读写/构建才作用在用户
        // 真实文件上；同时把 rootfs 里的挂载点补齐，否则 proot 的 -b 会失败。
        val workdir = prepareWorkspace(context)
        val host = if (Prefs.lan(context)) "0.0.0.0" else "127.0.0.1"
        val extra = buildList {
            add("KIMI_PORT=$port")
            add("KIMI_HOST=$host")
            add("KIMI_WORKDIR=$workdir")
        }
        ensureGuestDir(context, "/storage/emulated/0")

        Runtime.log("启动 proot：端口 $port（监听 $host）")
        stopping = false
        activePort = port
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
            add("KIMI_WORKDIR=${activeWorkdir ?: Prefs.workspace(context)}")
            addAll(extraEnv)
        }
        return proot(context, listOf("/bin/bash", "-lc", script), env)
    }

    /** 当前真正生效的工作目录（可能是 /root/workspace 兜底）。 */
    @Volatile private var activeWorkdir: String? = null

    fun currentWorkdir(context: Context): String =
        activeWorkdir ?: Prefs.workspace(context)

    /**
     * 2.17.0：准备宿主机工作目录，并在 rootfs 里建好 proot 的 bind 目标。
     *
     * `/storage/emulated/0` 由 proot 直接绑进容器，但 guest 侧的目录必须存在，
     * 否则 bind 会被跳过（表现为 Agent 看不到手机文件）。这里两边都建一次：
     * 手机侧是用户能看到的真实目录，rootfs 侧只是挂载点。
     */
    private fun prepareWorkspace(context: Context): String {
        val preferred = Prefs.workspace(context)
        if (preferred.startsWith("/storage/")) {
            val real = File(preferred)
            val usable = real.isDirectory || runCatching { real.mkdirs() }.getOrDefault(false)
            if (usable) {
                ensureGuestDir(context, preferred)
                activeWorkdir = preferred
                return preferred
            }
            Runtime.log("无法创建 $preferred（存储权限未授予？），本次回退到 /root/workspace")
        }
        ensureGuestDir(context, "/root/workspace")
        activeWorkdir = "/root/workspace"
        return "/root/workspace"
    }

    /** 在 rootfs 内创建目录；proot 的 -b 需要有这个挂载点。 */
    private fun ensureGuestDir(context: Context, guestPath: String) {
        runCatching {
            File(Paths.rootfs(context), guestPath.trimStart('/')).mkdirs()
        }
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

    /**
     * `Process.destroy()` only signals proot itself. proot traces its children
     * with ptrace; when the parent dies the node server is orphaned but keeps
     * running and keeps holding the listening socket. That used to make
     * `pickPort()` move to a new origin and wipe localStorage (chats + login).
     * Snapshot the descendants while the parent is still alive, then SIGKILL
     * them explicitly.
     */
    private fun pidOf(target: Process): Int {
        val viaMethod = runCatching {
            (target.javaClass.getMethod("pid").invoke(target) as? Number)?.toInt()
        }.getOrNull()
        if (viaMethod != null && viaMethod > 0) return viaMethod
        return runCatching {
            val field = target.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(target)
        }.getOrDefault(-1)
    }

    /** Best-effort recursive read of the per-thread children files under /proc. */
    private fun descendantPids(rootPid: Int): Set<Int> {
        if (rootPid <= 0) return emptySet()
        val found = LinkedHashSet<Int>()
        val queue = java.util.ArrayDeque<Int>()
        queue.add(rootPid)
        var guard = 0
        while (!queue.isEmpty() && guard++ < 256) {
            val pid = queue.poll() ?: break
            val taskDir = File("/proc/$pid/task")
            val tids = taskDir.list() ?: continue
            for (tid in tids) {
                val children = runCatching { File(taskDir, "$tid/children").readText().trim() }
                    .getOrNull() ?: continue
                if (children.isEmpty()) continue
                for (token in children.split(Regex("\\s+"))) {
                    val child = token.toIntOrNull() ?: continue
                    if (child <= 1 || child == android.os.Process.myPid()) continue
                    if (found.add(child)) queue.add(child)
                }
            }
        }
        return found
    }

    private fun killTree(pids: Set<Int>) {
        pids.forEach { pid -> runCatching { Os.kill(pid, OsConstants.SIGKILL) } }
    }

    fun stop() {
        val current = process ?: return
        stopping = true
        // 2.17.0：先请服务端自己退出（/api/v1/shutdown），它会把 OAuth 凭据、
        // 会话文件刷盘；直接 SIGKILL 可能丢掉刚轮换的 refresh token，表现就是
        // 下次启动「Kimi 账号掉了」。整个过程放后台线程，不阻塞主线程。
        Thread {
            shutdownQuietly()
            val deadline = System.currentTimeMillis() + 900
            while (current.isAlive && System.currentTimeMillis() < deadline) {
                runCatching { Thread.sleep(80) }
            }
            killTree(descendantPids(pidOf(current)))
            runCatching { current.destroy() }
            runCatching { Thread.sleep(300) }
            runCatching { if (current.isAlive) current.destroyForcibly() }
        }.apply { isDaemon = true }.start()
        process = null
    }

    /**
     * 同步变体，重启前使用。先优雅关闭拿回端口，再强杀整棵进程树。
     */
    fun stopAndWait(timeoutMs: Long) {
        stopping = true
        val current = process
        process = null
        if (current == null) return
        shutdownQuietly()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (current.isAlive && System.currentTimeMillis() < deadline) {
            runCatching { Thread.sleep(50) }
        }
        killTree(descendantPids(pidOf(current)))
        runCatching { current.destroy() }
        val hardDeadline = System.currentTimeMillis() + 600
        while (current.isAlive && System.currentTimeMillis() < hardDeadline) {
            runCatching { Thread.sleep(30) }
        }
        if (current.isAlive) runCatching { current.destroyForcibly() }
        // Let the kernel reap the children and tear down the listening socket.
        runCatching { Thread.sleep(150) }
    }

    /** POST /api/v1/shutdown，失败就静默走强杀路径。 */
    private fun shutdownQuietly() {
        val port = activePort
        val token = activeToken ?: return
        if (port <= 0) return
        var connection: java.net.HttpURLConnection? = null
        runCatching {
            connection = (java.net.URL("http://127.0.0.1:$port/api/v1/shutdown")
                .openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 600
                readTimeout = 600
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
            connection!!.outputStream.use { it.write("{}".toByteArray()) }
            connection!!.responseCode
            connection!!.disconnect()
        }
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
