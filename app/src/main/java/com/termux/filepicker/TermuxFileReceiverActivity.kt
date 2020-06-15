package com.termux.filepicker

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.util.Patterns
import com.termux.R
import com.termux.app.DialogUtils.TextSetListener
import com.termux.app.DialogUtils.textInput
import com.termux.app.TermuxService
import com.termux.filepicker.TermuxFileReceiverActivity
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

class TermuxFileReceiverActivity : Activity() {
    /**
     * If the activity should be finished when the name input dialog is dismissed. This is disabled
     * before showing an error dialog, since the act of showing the error dialog will cause the
     * name input dialog to be implicitly dismissed, and we do not want to finish the activity directly
     * when showing the error dialog.
     */
    var mFinishOnDismissNameDialog = true
    override fun onResume() {
        super.onResume()
        val intent = intent
        val action = intent.action
        val type = intent.type
        val scheme = intent.scheme
        if (Intent.ACTION_SEND == action && type != null) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            val sharedUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (sharedText != null) {
                if (isSharedTextAnUrl(sharedText)) {
                    handleUrlAndFinish(sharedText)
                } else {
                    var subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    if (subject == null) subject = intent.getStringExtra(Intent.EXTRA_TITLE)
                    if (subject != null) subject += ".txt"
                    promptNameAndSave(ByteArrayInputStream(sharedText.toByteArray(StandardCharsets.UTF_8)), subject)
                }
            } else if (sharedUri != null) {
                handleContentUri(sharedUri, intent.getStringExtra(Intent.EXTRA_TITLE))
            } else {
                showErrorDialogAndQuit("Send action without content - nothing to save.")
            }
        } else if ("content" == scheme) {
            handleContentUri(intent.data, intent.getStringExtra(Intent.EXTRA_TITLE))
        } else if ("file" == scheme) {
            // When e.g. clicking on a downloaded apk:
            val path = intent.data.path
            val file = File(path)
            try {
                val `in` = FileInputStream(file)
                promptNameAndSave(`in`, file.name)
            } catch (e: FileNotFoundException) {
                showErrorDialogAndQuit("Cannot open file: " + e.message + ".")
            }
        } else {
            showErrorDialogAndQuit("Unable to receive any file or URL.")
        }
    }

    fun showErrorDialogAndQuit(message: String?) {
        mFinishOnDismissNameDialog = false
        AlertDialog.Builder(this).setMessage(message).setOnDismissListener { dialog: DialogInterface? -> finish() }.setPositiveButton(android.R.string.ok) { dialog: DialogInterface?, which: Int -> finish() }.show()
    }

    fun handleContentUri(uri: Uri, subjectFromIntent: String?) {
        try {
            var attachmentFileName: String? = null
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            contentResolver.query(uri, projection, null, null, null).use { c ->
                if (c != null && c.moveToFirst()) {
                    val fileNameColumnId = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (fileNameColumnId >= 0) attachmentFileName = c.getString(fileNameColumnId)
                }
            }
            if (attachmentFileName == null) attachmentFileName = subjectFromIntent
            val `in` = contentResolver.openInputStream(uri)
            promptNameAndSave(`in`, attachmentFileName)
        } catch (e: Exception) {
            showErrorDialogAndQuit("""
    Unable to handle shared content:

    ${e.message}
    """.trimIndent())
            Log.e("termux", "handleContentUri(uri=$uri) failed", e)
        }
    }

    fun promptNameAndSave(`in`: InputStream, attachmentFileName: String?) {
        textInput(this, R.string.file_received_title, attachmentFileName, R.string.file_received_edit_button, TextSetListener { text: String? ->
            val outFile = saveStreamWithName(`in`, text) ?: return@textInput
            val editorProgramFile = File(EDITOR_PROGRAM)
            if (!editorProgramFile.isFile) {
                showErrorDialogAndQuit("""
    The following file does not exist:
    ${"$"}HOME/bin/termux-file-editor

    Create this file as a script or a symlink - it will be called with the received file as only argument.
    """.trimIndent())
                return@textInput
            }

            // Do this for the user if necessary:
            editorProgramFile.setExecutable(true)
            val scriptUri = Uri.Builder().scheme("file").path(EDITOR_PROGRAM).build()
            val executeIntent = Intent(TermuxService.ACTION_EXECUTE, scriptUri)
            executeIntent.setClass(this@TermuxFileReceiverActivity, TermuxService::class.java)
            executeIntent.putExtra(TermuxService.EXTRA_ARGUMENTS, arrayOf(outFile.absolutePath))
            startService(executeIntent)
            finish()
        },
            R.string.file_received_open_folder_button, TextSetListener { text: String? ->
            if (saveStreamWithName(`in`, text) == null) return@textInput
            val executeIntent = Intent(TermuxService.ACTION_EXECUTE)
            executeIntent.putExtra(TermuxService.EXTRA_CURRENT_WORKING_DIRECTORY, TERMUX_RECEIVEDIR)
            executeIntent.setClass(this@TermuxFileReceiverActivity, TermuxService::class.java)
            startService(executeIntent)
            finish()
        },
            android.R.string.cancel, TextSetListener { text: String? -> finish() }, DialogInterface.OnDismissListener { dialog: DialogInterface? -> if (mFinishOnDismissNameDialog) finish() })
    }

    fun saveStreamWithName(`in`: InputStream, attachmentFileName: String?): File? {
        val receiveDir = File(TERMUX_RECEIVEDIR)
        if (!receiveDir.isDirectory && !receiveDir.mkdirs()) {
            showErrorDialogAndQuit("Cannot create directory: " + receiveDir.absolutePath)
            return null
        }
        return try {
            val outFile = File(receiveDir, attachmentFileName)
            FileOutputStream(outFile).use { f ->
                val buffer = ByteArray(4096)
                var readBytes: Int
                while (`in`.read(buffer).also { readBytes = it } > 0) {
                    f.write(buffer, 0, readBytes)
                }
            }
            outFile
        } catch (e: IOException) {
            showErrorDialogAndQuit("Error saving file:\n\n$e")
            Log.e("termux", "Error saving file", e)
            null
        }
    }

    fun handleUrlAndFinish(url: String) {
        val urlOpenerProgramFile = File(URL_OPENER_PROGRAM)
        if (!urlOpenerProgramFile.isFile) {
            showErrorDialogAndQuit("""
    The following file does not exist:
    ${"$"}HOME/bin/termux-url-opener

    Create this file as a script or a symlink - it will be called with the shared URL as only argument.
    """.trimIndent())
            return
        }

        // Do this for the user if necessary:
        urlOpenerProgramFile.setExecutable(true)
        val urlOpenerProgramUri = Uri.Builder().scheme("file").path(URL_OPENER_PROGRAM).build()
        val executeIntent = Intent(TermuxService.ACTION_EXECUTE, urlOpenerProgramUri)
        executeIntent.setClass(this@TermuxFileReceiverActivity, TermuxService::class.java)
        executeIntent.putExtra(TermuxService.EXTRA_ARGUMENTS, arrayOf(url))
        startService(executeIntent)
        finish()
    }

    companion object {
        val TERMUX_RECEIVEDIR = TermuxService.FILES_PATH + "/home/downloads"
        val EDITOR_PROGRAM = TermuxService.HOME_PATH + "/bin/termux-file-editor"
        val URL_OPENER_PROGRAM = TermuxService.HOME_PATH + "/bin/termux-url-opener"
        fun isSharedTextAnUrl(sharedText: String?): Boolean {
            return (Patterns.WEB_URL.matcher(sharedText).matches()
                || Pattern.matches("magnet:\\?xt=urn:btih:.*?", sharedText))
        }
    }
}
