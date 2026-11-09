package ai.kimi.code.app

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Packs the parts of the container a user would actually miss — sessions,
 * configuration, plugins and the workspace — into a single zip, and restores
 * it on the same or another device.
 *
 * Everything lives under the rootfs, which is plain app-private storage, so the
 * archive can be produced without proot: the backup is a file-tree copy, and
 * the service is stopped first so session files are not caught mid-write.
 */
object BackupManager {

    private const val MANIFEST = "kimi-backup.json"
    private const val PREFS_ENTRY = "app/kimi_prefs.xml"

    /** Rebuildable caches; keeping them would inflate the archive for nothing. */
    private val SKIP_DIRS = setOf("node_modules", "__pycache__", ".cache", ".turbo")

    private const val MAX_FILE_BYTES = 1L * 1024 * 1024 * 1024

    interface Progress {
        fun onProgress(percent: Int)
        fun onMessage(message: String)
    }

    private class Item(val file: File, val entry: String, val size: Long)

    data class Backup(val file: File, val bytes: Long, val items: Int)

    // ------------------------------------------------------------------ paths

    /**
     * Public Downloads so the archive survives an uninstall — a backup inside
     * the app sandbox disappears together with the app it was meant to save.
     */
    fun backupDir(context: Context): File {
        val public = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "KimiCode",
        )
        if (runCatching { public.mkdirs(); public.isDirectory && public.canWrite() }.getOrDefault(false)) {
            return public
        }
        return File(context.getExternalFilesDir(null) ?: context.filesDir, "backups").apply { mkdirs() }
    }

    fun latestBackup(context: Context): File? =
        backupDir(context).listFiles { file -> file.isFile && file.name.endsWith(".zip") }
            ?.maxByOrNull { it.lastModified() }

    /**
     * Sharing needs a `content://` URI, and the archive lives in public storage
     * the provider deliberately cannot reach. A copy into the sandbox is the
     * simplest way to hand it to another app without widening the provider.
     */
    fun stageForSharing(context: Context, file: File): Uri {
        val dir = File(context.filesDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, file.name)
        file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        return LocalFileProvider.uriFor(context, target)
    }

    // ----------------------------------------------------------------- backup

    fun createBackup(context: Context, progress: Progress): Backup {
        val rootfs = Paths.rootfs(context)
        val items = ArrayList<Item>()

        collect(File(File(rootfs, "root"), ".kimi-code"), "root/.kimi-code/", items)
        workspaceHostPath(context)?.let { workspace ->
            val prefix = "root/" + workspace.relativeTo(rootfs).path.replace(File.separatorChar, '/').trimEnd('/') + "/"
            collect(workspace, prefix, items)
        }
        progress.onMessage("共 ${items.size} 个文件，准备写入归档")

        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val target = File(backupDir(context), "kimi-backup-$stamp.zip")
        val total = items.sumOf { it.size }.coerceAtLeast(1)

        ZipOutputStream(FileOutputStream(target).buffered()).use { zip ->
            writeManifest(context, zip, items.size)

            val prefs = prefsFile(context)
            if (prefs.isFile) {
                zip.putNextEntry(ZipEntry(PREFS_ENTRY))
                prefs.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

            var written = 0L
            var lastPercent = -1
            items.forEach { item ->
                zip.putNextEntry(ZipEntry(item.entry))
                item.file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                written += item.size
                val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                if (percent != lastPercent) {
                    lastPercent = percent
                    progress.onProgress(percent)
                }
            }
        }

        return Backup(target, target.length(), items.size)
    }

    private fun writeManifest(context: Context, zip: ZipOutputStream, items: Int) {
        val manifest = """
            {
              "app": "kimi-code-android",
              "version": "${BuildConfig.VERSION_NAME}",
              "createdAt": "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}",
              "files": $items
            }
        """.trimIndent()
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(manifest.toByteArray())
        zip.closeEntry()
    }

    /**
     * The workspace is a container-absolute path; only paths that live inside
     * the rootfs can be archived (a workspace on /sdcard is reachable, but it
     * belongs to the user, not to the container).
     */
    private fun workspaceHostPath(context: Context): File? {
        val rootfs = Paths.rootfs(context)
        val containerPath = Prefs.workspace(context).trim().trimEnd('/')
        if (containerPath.isEmpty()) return null
        val host = File(rootfs, containerPath.trimStart('/'))
        val canonical = runCatching { host.canonicalFile }.getOrNull() ?: return null
        val rootCanonical = runCatching { rootfs.canonicalFile }.getOrNull() ?: return null
        if (!canonical.path.startsWith(rootCanonical.path + File.separator)) return null
        return canonical.takeIf { it.isDirectory }
    }

    private fun collect(root: File, prefix: String, out: MutableList<Item>) {
        if (!root.isDirectory) return
        val base = root.toPath()
        runCatching {
            Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    return if (name in SKIP_DIRS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile || attrs.isSymbolicLink) return FileVisitResult.CONTINUE
                    if (attrs.size() > MAX_FILE_BYTES) return FileVisitResult.CONTINUE
                    val relative = base.relativize(file).toString().replace(File.separatorChar, '/')
                    out.add(Item(file.toFile(), prefix + relative, attrs.size()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            })
        }
    }

    // ---------------------------------------------------------------- restore

    fun restoreBackup(context: Context, uri: Uri, progress: Progress): Int {
        val rootfs = Paths.rootfs(context)
        val rootCanonical = rootfs.canonicalFile.path + File.separator
        var restored = 0
        var prefsBytes: ByteArray? = null

        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选文件")
        stream.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    when {
                        entry.isDirectory -> Unit
                        name == MANIFEST -> Unit
                        name == PREFS_ENTRY -> prefsBytes = zip.readBytes()
                        name.startsWith("root/") -> {
                            val target = File(rootfs, name)
                            val canonical = target.canonicalPath
                            if (!canonical.startsWith(rootCanonical)) throw IOException("备份包含非法路径：$name")
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output -> zip.copyTo(output) }
                            if (name.endsWith(".sh") || name.contains("/bin/")) {
                                target.setExecutable(true, false)
                            }
                            restored++
                            if (restored % 25 == 0) progress.onProgress(-1)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }

        prefsBytes?.let {
            progress.onMessage("恢复设置与 API Key")
            restorePrefs(context, it)
        }

        if (restored == 0) throw IOException("备份里没有可恢复的容器数据")
        return restored
    }

    private fun prefsFile(context: Context): File =
        File(File(context.filesDir.parentFile, "shared_prefs"), "kimi_prefs.xml")

    /**
     * SharedPreferences keeps an in-memory cache, so writing the XML file back
     * would not take effect until the process restarts. Parse it and copy the
     * values through the normal API instead.
     */
    private fun restorePrefs(context: Context, bytes: ByteArray) {
        val parser = android.util.Xml.newPullParser()
        parser.setInput(java.io.InputStreamReader(bytes.inputStream(), "UTF-8"))
        val editor = context.getSharedPreferences("kimi_prefs", Context.MODE_PRIVATE).edit()

        var event = parser.eventType
        var currentName: String? = null
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    val name = parser.getAttributeValue(null, "name")
                    when (tag) {
                        "string" -> {
                            currentName = name
                            if (name != null) editor.putString(name, parser.nextText())
                        }
                        "int" -> if (name != null) {
                            editor.putInt(name, parser.getAttributeValue(null, "value")?.toIntOrNull() ?: 0)
                        }
                        "boolean" -> if (name != null) {
                            editor.putBoolean(name, parser.getAttributeValue(null, "value") == "true")
                        }
                        "float" -> if (name != null) {
                            editor.putFloat(name, parser.getAttributeValue(null, "value")?.toFloatOrNull() ?: 0f)
                        }
                        "long" -> if (name != null) {
                            editor.putLong(name, parser.getAttributeValue(null, "value")?.toLongOrNull() ?: 0L)
                        }
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> currentName = null
            }
            event = parser.next()
        }
        editor.apply()
    }

    // ------------------------------------------------------------------ sizes

    fun dataSize(context: Context): Long {
        val root = File(Paths.rootfs(context), "root")
        if (!root.isDirectory) return 0
        var total = 0L
        runCatching {
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            })
        }
        return total
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
