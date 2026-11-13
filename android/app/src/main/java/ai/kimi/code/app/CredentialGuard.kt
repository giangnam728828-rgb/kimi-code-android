package ai.kimi.code.app

import android.content.Context
import java.io.File

/**
 * 2.17.0：Kimi 账号登录态兜底。
 *
 * 服务端把设备登录凭据写在 `~/.kimi-code/credentials/<name>.json`（0600，
 * 原子写入），`config.toml` 里只留 `providers.*.oauth` 的引用。运行环境一旦
 * 被重装 / 目录被清掉，网页就会退回「未登录」，而用户在 App 里看到的就是
 * 「滑掉后台再进来，Kimi 账号掉了」。
 *
 * 这里在每次服务就绪后做一份**只增不减**的旁路备份（App 私有目录，和 rootfs
 * 同等级别），下次启动前发现凭据目录整个消失就原样恢复。用户主动退出登录时
 * 目录仍在（只是文件被删），不会触发恢复，避免出现「退出后又自动登录」。
 */
object CredentialGuard {

    private const val DIR = "credential-backup"
    private const val CREDENTIALS = "credentials"
    private const val CONFIG = "config.toml"

    private fun home(context: Context): File =
        File(File(Paths.rootfs(context), "root"), ".kimi-code")

    private fun liveCredentials(context: Context): File = File(home(context), CREDENTIALS)

    private fun backupRoot(context: Context): File = File(context.filesDir, DIR)

    /** 把当前凭据复制到 App 私有备份区；返回备份的文件数（0 表示还没登录）。 */
    fun backup(context: Context): Int {
        val source = liveCredentials(context)
        val files = source.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
        if (files.isEmpty()) return 0
        val target = File(backupRoot(context), CREDENTIALS)
        if (!target.exists()) target.mkdirs()
        var copied = 0
        files.forEach { file ->
            runCatching {
                val dest = File(target, file.name)
                // 凭据刷新后内容会变（refresh token 轮换），有变化才覆盖。
                if (!dest.isFile || dest.readText() != file.readText()) {
                    file.copyTo(dest, overwrite = true)
                }
                copied++
            }
        }
        // config.toml 里的 `providers.*.oauth` 引用也要一起留档。
        runCatching {
            val config = File(home(context), CONFIG)
            if (config.isFile && config.length() > 0) {
                config.copyTo(File(backupRoot(context), CONFIG), overwrite = true)
            }
        }
        return copied
    }

    /** 启动前调用：凭据文件不见了才恢复，返回恢复的文件数。 */
    fun restoreIfMissing(context: Context): Int {
        val live = liveCredentials(context)
        val liveFiles = live.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
        if (liveFiles.isNotEmpty()) return 0

        // 主动退出登录时服务端也会把文件删掉，但 config.toml 里的 oauth 引用
        // 同时消失。只有「config 仍指向 oauth、文件却没了」才判定为丢失并恢复，
        // 避免用户退出后又被自动登回去。
        val config = File(home(context), CONFIG)
        val expectsLogin = config.isFile && runCatching { config.readText() }.getOrDefault("")
            .contains("oauth")
        if (live.exists() && !expectsLogin) return 0

        val backup = File(backupRoot(context), CREDENTIALS)
        val files = backup.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
        if (files.isEmpty()) return 0
        if (!live.exists() && !live.mkdirs()) return 0
        var restored = 0
        files.forEach { file ->
            runCatching {
                file.copyTo(File(live, file.name), overwrite = true)
                restored++
            }
        }
        val configBackup = File(backupRoot(context), CONFIG)
        if (restored > 0 && !config.exists() && configBackup.isFile) {
            runCatching { configBackup.copyTo(config, overwrite = true) }
        }
        Runtime.log("已从备份恢复 $restored 个登录凭据文件")
        return restored
    }
}
