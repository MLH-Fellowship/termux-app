package com.termux.app

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import com.termux.terminal.EmulatorDebug
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class TermuxOpenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val data = intent.data
        if (data == null) {
            Log.e(EmulatorDebug.LOG_TAG, "termux-open: Called without intent data")
            return
        }
        val filePath = data.path
        val contentTypeExtra = intent.getStringExtra("content-type")
        val useChooser = intent.getBooleanExtra("chooser", false)
        val intentAction = if (intent.action == null) Intent.ACTION_VIEW else intent.action
        when (intentAction) {
            Intent.ACTION_SEND, Intent.ACTION_VIEW -> {
            }
            else -> Log.e(EmulatorDebug.LOG_TAG, "Invalid action '$intentAction', using 'view'")
        }
        val isExternalUrl = data.scheme != null && data.scheme != "file"
        if (isExternalUrl) {
            val urlIntent = Intent(intentAction, data)
            if (intentAction == Intent.ACTION_SEND) {
                urlIntent.putExtra(Intent.EXTRA_TEXT, data.toString())
                urlIntent.data = null
            } else if (contentTypeExtra != null) {
                urlIntent.setDataAndType(data, contentTypeExtra)
            }
            urlIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(urlIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e(EmulatorDebug.LOG_TAG, "termux-open: No app handles the url $data")
            }
            return
        }
        val fileToShare = File(filePath)
        if (!(fileToShare.isFile && fileToShare.canRead())) {
            Log.e(EmulatorDebug.LOG_TAG, "termux-open: Not a readable file: '" + fileToShare.absolutePath + "'")
            return
        }
        var sendIntent = Intent()
        sendIntent.action = intentAction
        sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        var contentTypeToUse: String?
        if (contentTypeExtra == null) {
            val fileName = fileToShare.name
            val lastDotIndex = fileName.lastIndexOf('.')
            val fileExtension = fileName.substring(lastDotIndex + 1)
            val mimeTypes = MimeTypeMap.getSingleton()
            // Lower casing makes it work with e.g. "JPG":
            contentTypeToUse = mimeTypes.getMimeTypeFromExtension(fileExtension.toLowerCase())
            if (contentTypeToUse == null) contentTypeToUse = "application/octet-stream"
        } else {
            contentTypeToUse = contentTypeExtra
        }
        val uriToShare = Uri.parse("content://com.termux.files" + fileToShare.absolutePath)
        if (Intent.ACTION_SEND == intentAction) {
            sendIntent.putExtra(Intent.EXTRA_STREAM, uriToShare)
            sendIntent.type = contentTypeToUse
        } else {
            sendIntent.setDataAndType(uriToShare, contentTypeToUse)
        }
        if (useChooser) {
            sendIntent = Intent.createChooser(sendIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(sendIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e(EmulatorDebug.LOG_TAG, "termux-open: No app handles the url $data")
        }
    }

    class ContentProvider : android.content.ContentProvider() {
        override fun onCreate(): Boolean {
            return true
        }

        override fun query(uri: Uri, projection: Array<String>, selection: String, selectionArgs: Array<String>, sortOrder: String): Cursor {
            var projection: Array<String>? = projection
            val file = File(uri.path)
            if (projection == null) {
                projection = arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns._ID
                )
            }
            val row = arrayOfNulls<Any>(projection.size)
            for (i in projection.indices) {
                val column = projection[i]
                var value: Any?
                value = when (column) {
                    MediaStore.MediaColumns.DISPLAY_NAME -> file.name
                    MediaStore.MediaColumns.SIZE -> file.length().toInt()
                    MediaStore.MediaColumns._ID -> 1
                    else -> null
                }
                row[i] = value
            }
            val cursor = MatrixCursor(projection)
            cursor.addRow(row)
            return cursor
        }

        override fun getType(uri: Uri): String {
            return null
        }

        override fun insert(uri: Uri, values: ContentValues): Uri {
            return null
        }

        override fun delete(uri: Uri, selection: String, selectionArgs: Array<String>): Int {
            return 0
        }

        override fun update(uri: Uri, values: ContentValues, selection: String, selectionArgs: Array<String>): Int {
            return 0
        }

        @Throws(FileNotFoundException::class)
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = File(uri.path)
            try {
                val path = file.canonicalPath
                val storagePath = Environment.getExternalStorageDirectory().canonicalPath
                // See https://support.google.com/faqs/answer/7496913:
                require(path.startsWith(TermuxService.Companion.FILES_PATH) || path.startsWith(storagePath)) { "Invalid path: $path" }
            } catch (e: IOException) {
                throw IllegalArgumentException(e)
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
}
