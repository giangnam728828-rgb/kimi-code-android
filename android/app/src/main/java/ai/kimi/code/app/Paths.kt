package ai.kimi.code.app

import android.content.Context
import java.io.File

object Paths {
    const val DEFAULT_PORT = 58630

    fun rootfs(context: Context): File = File(context.filesDir, "rootfs")
    fun prootDir(context: Context): File = File(context.filesDir, "proot")
    fun tmpDir(context: Context): File = File(context.filesDir, "tmp")
    fun logsDir(context: Context): File = File(context.filesDir, "logs")

    fun prootBin(context: Context): File = File(prootDir(context), "proot")
    fun prootLoader(context: Context): File = File(prootDir(context), "loader")
    fun tokenFile(context: Context): File =
        File(File(File(rootfs(context), "root"), ".kimi-code"), "server.token")

    fun isRuntimeInstalled(context: Context): Boolean {
        val marker = File(rootfs(context), ".installed")
        val entry = File(rootfs(context), "opt/kimi/dist/main.mjs")
        return marker.isFile && entry.isFile
    }

    fun ensureDirs(context: Context) {
        listOf(rootfs(context), prootDir(context), tmpDir(context), logsDir(context)).forEach {
            if (!it.exists()) it.mkdirs()
        }
    }
}
