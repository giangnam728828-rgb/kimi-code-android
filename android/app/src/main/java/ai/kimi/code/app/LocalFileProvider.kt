package ai.kimi.code.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Minimal read-only provider for files inside the app sandbox.
 *
 * Sharing a backup (or the log dump) needs a `content://` URI: `file://` URIs
 * throw `FileUriExposedException` from API 24 on. Pulling in AndroidX just for
 * FileProvider would add a dependency the rest of the app does not use, so the
 * few methods that matter are implemented directly.
 */
class LocalFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY_SUFFIX = ".files"

        fun authority(packageName: String) = packageName + AUTHORITY_SUFFIX

        fun uriFor(context: android.content.Context, file: File): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context.packageName))
                .appendPath(file.name)
                .build()
    }

    private fun baseDir(): File = File(context!!.filesDir, "shared")

    private fun resolve(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        val file = File(baseDir(), name)
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return null
        val base = runCatching { baseDir().canonicalPath }.getOrNull() ?: return null
        return if (canonical.startsWith(base + File.separator)) file.takeIf { it.isFile } else null
    }

    override fun onCreate(): Boolean {
        baseDir().mkdirs()
        return true
    }

    override fun getType(uri: Uri): String = when {
        uri.lastPathSegment?.endsWith(".zip") == true -> "application/zip"
        uri.lastPathSegment?.endsWith(".txt") == true -> "text/plain"
        else -> "application/octet-stream"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = resolve(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
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
        val file = resolve(uri) ?: throw java.io.FileNotFoundException("$uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
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
