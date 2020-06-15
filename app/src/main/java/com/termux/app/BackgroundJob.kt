package com.termux.app

import androidx.appcompat.app.AppCompatActivity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * A background job launched by Termux.
 */
class BackgroundJob @JvmOverloads constructor(cwd: String?, fileToExecute: String?, args: Array<String?>?, service: TermuxService, pendingIntent: PendingIntent? = null) {
    var mProcess: Process?

    companion object {
        private const val LOG_TAG = "termux-task"
        private fun addToEnvIfPresent(environment: MutableList<String>, name: String) {
            val value = System.getenv(name)
            if (value != null) {
                environment.add("$name=$value")
            }
        }

        fun buildEnvironment(failSafe: Boolean, cwd: String?): Array<String> {
            var cwd = cwd
            File(TermuxService.Companion.HOME_PATH).mkdirs()
            if (cwd == null) cwd = TermuxService.Companion.HOME_PATH
            val environment: MutableList<String> = ArrayList()
            environment.add("TERM=xterm-256color")
            environment.add("HOME=" + TermuxService.Companion.HOME_PATH)
            environment.add("PREFIX=" + TermuxService.Companion.PREFIX_PATH)
            environment.add("BOOTCLASSPATH=" + System.getenv("BOOTCLASSPATH"))
            environment.add("ANDROID_ROOT=" + System.getenv("ANDROID_ROOT"))
            environment.add("ANDROID_DATA=" + System.getenv("ANDROID_DATA"))
            // EXTERNAL_STORAGE is needed for /system/bin/am to work on at least
            // Samsung S7 - see https://plus.google.com/110070148244138185604/posts/gp8Lk3aCGp3.
            environment.add("EXTERNAL_STORAGE=" + System.getenv("EXTERNAL_STORAGE"))
            // ANDROID_RUNTIME_ROOT and ANDROID_TZDATA_ROOT are required for `am` to run on Android Q
            addToEnvIfPresent(environment, "ANDROID_RUNTIME_ROOT")
            addToEnvIfPresent(environment, "ANDROID_TZDATA_ROOT")
            if (failSafe) {
                // Keep the default path so that system binaries can be used in the failsafe session.
                environment.add("PATH= " + System.getenv("PATH"))
            } else {
                if (shouldAddLdLibraryPath()) {
                    environment.add("LD_LIBRARY_PATH=" + TermuxService.Companion.PREFIX_PATH + "/lib")
                }
                environment.add("LANG=en_US.UTF-8")
                environment.add("PATH=" + TermuxService.Companion.PREFIX_PATH + "/bin:" + TermuxService.Companion.PREFIX_PATH + "/bin/applets")
                environment.add("PWD=$cwd")
                environment.add("TMPDIR=" + TermuxService.Companion.PREFIX_PATH + "/tmp")
            }
            return environment.toTypedArray()
        }

        private fun shouldAddLdLibraryPath(): Boolean {
            try {
                BufferedReader(InputStreamReader(FileInputStream(TermuxService.Companion.PREFIX_PATH + "/etc/apt/sources.list"))).use { `in` ->
                    var line: String
                    while (`in`.readLine().also { line = it } != null) {
                        if (!line.startsWith("#") && line.contains("//termux.net stable")) {
                            return true
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(LOG_TAG, "Error trying to read sources.list", e)
            }
            return false
        }

        fun getPid(p: Process?): Int {
            return try {
                val f = p!!.javaClass.getDeclaredField("pid")
                f.isAccessible = true
                try {
                    f.getInt(p)
                } finally {
                    f.isAccessible = false
                }
            } catch (e: Throwable) {
                -1
            }
        }

        fun setupProcessArgs(fileToExecute: String?, args: Array<String?>?): Array<String> {
            // The file to execute may either be:
            // - An elf file, in which we execute it directly.
            // - A script file without shebang, which we execute with our standard shell $PREFIX/bin/sh instead of the
            //   system /system/bin/sh. The system shell may vary and may not work at all due to LD_LIBRARY_PATH.
            // - A file with shebang, which we try to handle with e.g. /bin/foo -> $PREFIX/bin/foo.
            var interpreter: String? = null
            try {
                val file = File(fileToExecute)
                FileInputStream(file).use { `in` ->
                    val buffer = ByteArray(256)
                    val bytesRead = `in`.read(buffer)
                    if (bytesRead > 4) {
                        if (buffer[0] == 0x7F && buffer[1] == 'E' && buffer[2] == 'L' && buffer[3] == 'F') {
                            // Elf file, do nothing.
                        } else if (buffer[0] == '#' && buffer[1] == '!') {
                            // Try to parse shebang.
                            val builder = StringBuilder()
                            for (i in 2 until bytesRead) {
                                val c = buffer[i].toChar()
                                if (c == ' ' || c == '\n') {
                                    if (builder.length == 0) {
                                        // Skip whitespace after shebang.
                                    } else {
                                        // End of shebang.
                                        val executable = builder.toString()
                                        if (executable.startsWith("/usr") || executable.startsWith("/bin")) {
                                            val parts = executable.split("/").toTypedArray()
                                            val binary = parts[parts.size - 1]
                                            interpreter = TermuxService.Companion.PREFIX_PATH + "/bin/" + binary
                                        }
                                        break
                                    }
                                } else {
                                    builder.append(c)
                                }
                            }
                        } else {
                            // No shebang and no ELF, use standard shell.
                            interpreter = TermuxService.Companion.PREFIX_PATH + "/bin/sh"
                        }
                    }
                }
            } catch (e: IOException) {
                // Ignore.
            }
            val result: MutableList<String?> = ArrayList()
            if (interpreter != null) result.add(interpreter)
            result.add(fileToExecute)
            if (args != null) Collections.addAll(result, *args)
            return result.toTypedArray<String>()
        }
    }

    init {
        var cwd = cwd
        val env = buildEnvironment(false, cwd)
        if (cwd == null) cwd = TermuxService.Companion.HOME_PATH
        val progArray = setupProcessArgs(fileToExecute, args)
        val processDescription = Arrays.toString(progArray)
        val process: Process
        try {
            process = Runtime.getRuntime().exec(progArray, env, File(cwd))
        } catch (e: IOException) {
            mProcess = null
            // TODO: Visible error message?
            Log.e(LOG_TAG, "Failed running background job: $processDescription", e)
            return
        }
        mProcess = process
        val pid = getPid(mProcess)
        val result = Bundle()
        val outResult = StringBuilder()
        val errResult = StringBuilder()
        val errThread: Thread = object : Thread() {
            override fun run() {
                val stderr = mProcess.getErrorStream()
                val reader = BufferedReader(InputStreamReader(stderr, StandardCharsets.UTF_8))
                var line: String
                try {
                    // FIXME: Long lines.
                    while (reader.readLine().also { line = it } != null) {
                        errResult.append(line).append('\n')
                        Log.i(LOG_TAG, "[$pid] stderr: $line")
                    }
                } catch (e: IOException) {
                    // Ignore.
                }
            }
        }
        errThread.start()
        object : Thread() {
            override fun run() {
                Log.i(LOG_TAG, "[$pid] starting: $processDescription")
                val stdout = mProcess.getInputStream()
                val reader = BufferedReader(InputStreamReader(stdout, StandardCharsets.UTF_8))
                var line: String
                try {
                    // FIXME: Long lines.
                    while (reader.readLine().also { line = it } != null) {
                        Log.i(LOG_TAG, "[$pid] stdout: $line")
                        outResult.append(line).append('\n')
                    }
                } catch (e: IOException) {
                    Log.e(LOG_TAG, "Error reading output", e)
                }
                try {
                    val exitCode = mProcess.waitFor()
                    service.onBackgroundJobExited(this@BackgroundJob)
                    if (exitCode == 0) {
                        Log.i(LOG_TAG, "[$pid] exited normally")
                    } else {
                        Log.w(LOG_TAG, "[$pid] exited with code: $exitCode")
                    }
                    result.putString("stdout", outResult.toString())
                    result.putInt("exitCode", exitCode)
                    errThread.join()
                    result.putString("stderr", errResult.toString())
                    val data = Intent()
                    data.putExtra("result", result)
                    if (pendingIntent != null) {
                        try {
                            pendingIntent.send(service.applicationContext, androidx.appcompat.app.AppCompatActivity.RESULT_OK, data)
                        } catch (e: PendingIntent.CanceledException) {
                            // The caller doesn't want the result? That's fine, just ignore
                        }
                    }
                } catch (e: InterruptedException) {
                    // Ignore
                }
            }
        }.start()
    }
}
