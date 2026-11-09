package ai.kimi.code.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Installs the two things the app cannot ship inside the APK: the Linux rootfs
 * (downloaded once) and the proot runtime (copied out of assets so it can be
 * made executable).
 */
object Installer {

    const val ROOTFS_URL =
        "https://github.com/giangnam728828-rgb/kimi-code-android/releases/download/rootfs-v1/rootfs-arm64.tar.gz"

    private const val USER_AGENT = "KimiCodeAndroid/2.0"

    private val PROOT_ASSETS = listOf(
        "proot" to "proot",
        "loader" to "loader",
        "libtalloc.so" to "libtalloc.so",
        "libandroid-shmem.so" to "libandroid-shmem.so",
    )

    fun installProotRuntime(context: Context) {
        val dir = Paths.prootDir(context)
        if (!dir.exists()) dir.mkdirs()
        for ((assetName, fileName) in PROOT_ASSETS) {
            val target = File(dir, fileName)
            if (target.isFile && target.length() > 0) {
                target.setExecutable(true, false)
                continue
            }
            context.assets.open("proot/$assetName").use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output, 1 shl 16) }
            }
            target.setReadable(true, false)
            target.setExecutable(true, false)
        }
        Runtime.log("proot 运行时已就绪")
    }

    /**
     * Downloads and unpacks the rootfs. Safe to call repeatedly: an existing
     * installation with a marker file short-circuits.
     */
    fun installRootfs(context: Context) {
        Paths.ensureDirs(context)
        val rootfs = Paths.rootfs(context)
        if (Paths.isRuntimeInstalled(context)) {
            Runtime.log("运行时已安装，跳过下载")
            return
        }

        val archive = File(Paths.tmpDir(context), "rootfs-arm64.tar.gz")
        if (!archive.isFile || archive.length() < 1024 * 1024) {
            download(archive)
        } else {
            Runtime.log("复用已下载的安装包（${archive.length() / 1024 / 1024} MB）")
        }

        Runtime.update(Runtime.Phase.INSTALLING, "正在解压运行环境", "首次安装需要几分钟，请不要退出", 0)
        Runtime.log("开始解压到 ${rootfs.absolutePath}")

        if (rootfs.exists()) {
            rootfs.deleteRecursively()
        }
        rootfs.mkdirs()

        var lastNotified = 0
        TarExtractor.extractGzip(archive, rootfs) { progress ->
            if (progress.files - lastNotified >= 100) {
                lastNotified = progress.files
                Runtime.update(
                    Runtime.Phase.INSTALLING,
                    "正在解压运行环境",
                    "已解压 ${progress.files} 个文件",
                    -1,
                )
            }
        }

        File(rootfs, ".installed").writeText(System.currentTimeMillis().toString())
        archive.delete()
        Runtime.log("运行环境安装完成")
        applyPermissions(rootfs)
    }

    /** Restore the executable bits the archive expects on key entry points. */
    private fun applyPermissions(rootfs: File) {
        listOf(
            "opt/node/bin/node",
            "opt/kimi/entrypoint.sh",
            "usr/local/bin/rg",
            "bin/bash",
            "bin/sh",
            "usr/bin/env",
            "root/.kimi-code/bin/rg",
        ).forEach { relative ->
            val file = File(rootfs, relative)
            if (file.exists()) {
                file.setReadable(true, false)
                file.setWritable(true, false)
                file.setExecutable(true, false)
            }
        }
    }

    private fun download(target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        val partial = File(target.parentFile, target.name + ".part")
        if (partial.exists()) partial.delete()

        Runtime.update(Runtime.Phase.INSTALLING, "正在下载运行环境", "首次使用需要下载约 87 MB", 0)
        var connection: HttpURLConnection? = null
        try {
            connection = openFollowRedirects(URL(ROOTFS_URL))
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("下载失败：HTTP $code")
            }
            val total = connection.contentLengthLong
            Runtime.log("开始下载运行环境（${total / 1024 / 1024} MB）")

            var downloaded = 0L
            var lastPercent = -1
            FileOutputStream(partial).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                Runtime.update(
                                    Runtime.Phase.INSTALLING,
                                    "正在下载运行环境",
                                    "${downloaded / 1024 / 1024} MB / ${total / 1024 / 1024} MB",
                                    percent,
                                )
                            }
                        }
                    }
                }
            }
            if (total > 0 && partial.length() != total) {
                throw IllegalStateException("下载不完整：${partial.length()} / $total")
            }
            if (!partial.renameTo(target)) {
                throw IllegalStateException("无法保存安装包")
            }
            Runtime.log("下载完成：${target.length() / 1024 / 1024} MB")
        } finally {
            connection?.disconnect()
        }
    }

    private fun openFollowRedirects(url: URL): HttpURLConnection {
        var current = url
        repeat(6) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrEmpty()) throw IllegalStateException("重定向地址缺失")
                current = URL(current, location)
                return@repeat
            }
            return connection
        }
        throw IllegalStateException("重定向次数过多")
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
