package ai.kimi.code.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Minimal provider for files inside the app sandbox.
 *
 * Sharing a backup (or the log dump) needs a `content://` URI: `file://` URIs
 * throw `FileUriExposedException` from API 24 on. Pulling in AndroidX just for
 * FileProvider would add a dependency the rest of the app does not use, so the
 * few methods that matter are implemented directly.
 *
 * 2.18.0：新增可写的 `camera/` 目录。拍照上传必须给相机一个能写的
 * `content://` URI，旧实现只暴露只读的 `shared/`，`ACTION_IMAGE_CAPTURE`
 * 没有输出目标，附件面板里的「拍照」等于摆设。
 */
class LocalFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY_SUFFIX = ".files"

        private const val DIR_SHARED = "shared"
        private const val DIR_CAMERA = "camera"

        fun authority(packageName: String) = packageName + AUTHORITY_SUFFIX

        /** 只读分享（日志、备份）。 */
        fun uriFor(context: Context, file: File): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context.packageName))
                .appendPath(DIR_SHARED)
                .appendPath(file.name)
                .build()

        /** 相机输出：唯一允许写入的入口。 */
        fun cameraUri(context: Context, file: File): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context.packageName))
                .appendPath(DIR_CAMERA)
                .appendPath(file.name)
                .build()

        fun newCameraFile(context: Context): File {
            val dir = File(context.cacheDir, DIR_CAMERA).apply { mkdirs() }
            return File(dir, "capture-${System.currentTimeMillis()}.jpg")
        }
    }

    private class Target(val file: File, val writable: Boolean)

    private fun sharedDir(): File = File(context!!.filesDir, DIR_SHARED).apply { mkdirs() }

    private fun cameraDir(): File = File(context!!.cacheDir, DIR_CAMERA).apply { mkdirs() }

    private fun resolve(uri: Uri): Target? {
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        val dir: File
        val writable: Boolean
        when (segments[0]) {
            DIR_SHARED -> {
                dir = sharedDir()
                writable = false
            }
            DIR_CAMERA -> {
                dir = cameraDir()
                writable = true
            }
            else -> return null
        }
        val file = File(dir, segments[1])
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return null
        val base = runCatching { dir.canonicalPath }.getOrNull() ?: return null
        if (!canonical.startsWith(base + File.separator)) return null
        return Target(file, writable)
    }

    override fun onCreate(): Boolean {
        sharedDir()
        cameraDir()
        return true
    }

    override fun getType(uri: Uri): String = when {
        uri.lastPathSegment?.endsWith(".zip") == true -> "application/zip"
        uri.lastPathSegment?.endsWith(".txt") == true -> "text/plain"
        uri.lastPathSegment?.endsWith(".jpg") == true -> "image/jpeg"
        uri.lastPathSegment?.endsWith(".jpeg") == true -> "image/jpeg"
        uri.lastPathSegment?.endsWith(".png") == true -> "image/png"
        else -> "application/octet-stream"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val target = resolve(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        val file = target?.file
        if (file != null) {
            val row = columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            }
            cursor.addRow(row)
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val target = resolve(uri) ?: throw java.io.FileNotFoundException("$uri")
        val wantsWrite = mode.contains('w') || mode.contains('a')
        if (wantsWrite && !target.writable) throw java.io.FileNotFoundException("$uri")
        if (wantsWrite) target.file.parentFile?.mkdirs()
        val flags = when {
            !wantsWrite -> ParcelFileDescriptor.MODE_READ_ONLY
            mode.contains('a') -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
            mode.contains('r') -> ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE
            else -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        }
        return ParcelFileDescriptor.open(target.file, flags)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
