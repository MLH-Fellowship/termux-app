package com.termux.app

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Environment
import android.os.Process
import android.os.UserManager
import android.system.Os
import android.util.Log
import android.util.Pair
import android.view.WindowManager
import com.termux.R
import com.termux.terminal.EmulatorDebug
import java.io.*
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 *
 *
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX folder below.
 *
 *
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 *
 *
 * (3) A staging folder, $STAGING_PREFIX, is [.deleteFolder] if left over from broken installation below.
 *
 *
 * (4) The zip file is loaded from a shared library.
 *
 *
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 *
 *
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 *
 *
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
internal object TermuxInstaller {
    /**
     * Performs setup if necessary.
     */
    fun setupIfNeeded(activity: androidx.appcompat.app.AppCompatActivity, whenDone: Runnable) {
        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        val um = activity.getSystemService(Context.USER_SERVICE) as UserManager
        val isPrimaryUser = um.getSerialNumberForUser(Process.myUserHandle()) == 0L
        if (!isPrimaryUser) {
            androidx.appcompat.app.AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_not_primary_user_message)
                .setOnDismissListener { dialog: DialogInterface? -> System.exit(0) }.setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val PREFIX_FILE = File(TermuxService.Companion.PREFIX_PATH)
        if (PREFIX_FILE.isDirectory) {
            whenDone.run()
            return
        }
        val progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false)
        object : Thread() {
            override fun run() {
                try {
                    val STAGING_PREFIX_PATH: String = TermuxService.Companion.FILES_PATH + "/usr-staging"
                    val STAGING_PREFIX_FILE = File(STAGING_PREFIX_PATH)
                    if (STAGING_PREFIX_FILE.exists()) {
                        deleteFolder(STAGING_PREFIX_FILE)
                    }
                    val buffer = ByteArray(8096)
                    val symlinks: MutableList<Pair<String, String>> = ArrayList(50)
                    val zipBytes = loadZipBytes()
                    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zipInput ->
                        var zipEntry: ZipEntry
                        while (zipInput.nextEntry.also { zipEntry = it } != null) {
                            if (zipEntry.name == "SYMLINKS.txt") {
                                val symlinksReader = BufferedReader(InputStreamReader(zipInput))
                                var line: String
                                while (symlinksReader.readLine().also { line = it } != null) {
                                    val parts = line.split("←").toTypedArray()
                                    if (parts.size != 2) throw RuntimeException("Malformed symlink line: $line")
                                    val oldPath = parts[0]
                                    val newPath = STAGING_PREFIX_PATH + "/" + parts[1]
                                    symlinks.add(Pair.create(oldPath, newPath))
                                    ensureDirectoryExists(File(newPath).parentFile)
                                }
                            } else {
                                val zipEntryName = zipEntry.name
                                val targetFile = File(STAGING_PREFIX_PATH, zipEntryName)
                                val isDirectory = zipEntry.isDirectory
                                ensureDirectoryExists(if (isDirectory) targetFile else targetFile.parentFile)
                                if (!isDirectory) {
                                    FileOutputStream(targetFile).use { outStream ->
                                        var readBytes: Int
                                        while (zipInput.read(buffer).also { readBytes = it } != -1) outStream.write(buffer, 0, readBytes)
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") || zipEntryName.startsWith("lib/apt/methods")) {
                                        Os.chmod(targetFile.absolutePath, 448)
                                    }
                                }
                            }
                        }
                    }
                    if (symlinks.isEmpty()) throw RuntimeException("No SYMLINKS.txt encountered")
                    for (symlink in symlinks) {
                        Os.symlink(symlink.first, symlink.second)
                    }
                    if (!STAGING_PREFIX_FILE.renameTo(PREFIX_FILE)) {
                        throw RuntimeException("Unable to rename staging folder")
                    }
                    activity.runOnUiThread(whenDone)
                } catch (e: Exception) {
                    Log.e(EmulatorDebug.LOG_TAG, "Bootstrap error", e)
                    activity.runOnUiThread {
                        try {
                            androidx.appcompat.app.AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                                .setNegativeButton(R.string.bootstrap_error_abort) { dialog: DialogInterface, which: Int ->
                                    dialog.dismiss()
                                    activity.finish()
                                }.setPositiveButton(R.string.bootstrap_error_try_again) { dialog: DialogInterface, which: Int ->
                                    dialog.dismiss()
                                    setupIfNeeded(activity, whenDone)
                                }.show()
                        } catch (e1: WindowManager.BadTokenException) {
                            // Activity already dismissed - ignore.
                        }
                    }
                } finally {
                    activity.runOnUiThread {
                        try {
                            progress.dismiss()
                        } catch (e: RuntimeException) {
                            // Activity already dismissed - ignore.
                        }
                    }
                }
            }
        }.start()
    }

    private fun ensureDirectoryExists(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw RuntimeException("Unable to create directory: " + directory.absolutePath)
        }
    }

    fun loadZipBytes(): ByteArray {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap")
        return zip
    }

    val zip: ByteArray
        external get

    /**
     * Delete a folder and all its content or throw. Don't follow symlinks.
     */
    @Throws(IOException::class)
    fun deleteFolder(fileOrDirectory: File) {
        if (fileOrDirectory.canonicalPath == fileOrDirectory.absolutePath && fileOrDirectory.isDirectory) {
            val children = fileOrDirectory.listFiles()
            if (children != null) {
                for (child in children) {
                    deleteFolder(child)
                }
            }
        }
        if (!fileOrDirectory.delete()) {
            throw RuntimeException("Unable to delete " + (if (fileOrDirectory.isDirectory) "directory " else "file ") + fileOrDirectory.absolutePath)
        }
    }

    fun setupStorageSymlinks(context: Context) {
        val LOG_TAG = "termux-storage"
        object : Thread() {
            override fun run() {
                try {
                    val storageDir = File(TermuxService.Companion.HOME_PATH, "storage")
                    if (storageDir.exists()) {
                        try {
                            deleteFolder(storageDir)
                        } catch (e: IOException) {
                            Log.e(LOG_TAG, "Could not delete old \$HOME/storage, " + e.message)
                            return
                        }
                    }
                    if (!storageDir.mkdirs()) {
                        Log.e(LOG_TAG, "Unable to mkdirs() for \$HOME/storage")
                        return
                    }
                    val sharedDir = Environment.getExternalStorageDirectory()
                    Os.symlink(sharedDir.absolutePath, File(storageDir, "shared").absolutePath)
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    Os.symlink(downloadsDir.absolutePath, File(storageDir, "downloads").absolutePath)
                    val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                    Os.symlink(dcimDir.absolutePath, File(storageDir, "dcim").absolutePath)
                    val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    Os.symlink(picturesDir.absolutePath, File(storageDir, "pictures").absolutePath)
                    val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    Os.symlink(musicDir.absolutePath, File(storageDir, "music").absolutePath)
                    val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    Os.symlink(moviesDir.absolutePath, File(storageDir, "movies").absolutePath)
                    val dirs = context.getExternalFilesDirs(null)
                    if (dirs != null && dirs.size > 1) {
                        for (i in 1 until dirs.size) {
                            val dir = dirs[i] ?: continue
                            val symlinkName = "external-$i"
                            Os.symlink(dir.absolutePath, File(storageDir, symlinkName).absolutePath)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error setting up link", e)
                }
            }
        }.start()
    }
}
