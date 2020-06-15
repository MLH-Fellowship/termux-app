package com.termux.app

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * When allow-external-apps property is set to "true", Termux is able to process execute intents
 * sent by third-party applications.
 *
 *
 * Third-party program must declare com.termux.permission.RUN_COMMAND permission and it should be
 * granted by user.
 *
 *
 * Sample code to run command "top":
 * Intent intent = new Intent();
 * intent.setClassName("com.termux", "com.termux.app.RunCommandService");
 * intent.setAction("com.termux.RUN_COMMAND");
 * intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/top");
 * startService(intent);
 */
class RunCommandService : Service() {
    private val mBinder: IBinder = LocalBinder()
    override fun onBind(intent: Intent): IBinder {
        return mBinder
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (allowExternalApps() && RUN_COMMAND_ACTION == intent.action) {
            val programUri = Uri.Builder().scheme("com.termux.file").path(intent.getStringExtra(RUN_COMMAND_PATH)).build()
            val execIntent = Intent(TermuxService.Companion.ACTION_EXECUTE, programUri)
            execIntent.setClass(this, TermuxService::class.java)
            execIntent.putExtra(TermuxService.Companion.EXTRA_ARGUMENTS, intent.getStringExtra(RUN_COMMAND_ARGUMENTS))
            execIntent.putExtra(TermuxService.Companion.EXTRA_CURRENT_WORKING_DIRECTORY, intent.getStringExtra(RUN_COMMAND_WORKDIR))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(execIntent)
            } else {
                startService(execIntent)
            }
        }
        return START_NOT_STICKY
    }

    private fun allowExternalApps(): Boolean {
        var propsFile = File(TermuxService.Companion.HOME_PATH + "/.termux/termux.properties")
        if (!propsFile.exists()) propsFile = File(TermuxService.Companion.HOME_PATH + "/.config/termux/termux.properties")
        val props = Properties()
        try {
            if (propsFile.isFile && propsFile.canRead()) {
                FileInputStream(propsFile).use { `in` -> props.load(InputStreamReader(`in`, StandardCharsets.UTF_8)) }
            }
        } catch (e: Exception) {
            Log.e("termux", "Error loading props", e)
        }
        return props.getProperty("allow-external-apps", "false") == "true"
    }

    internal inner class LocalBinder : Binder() {
        val service = this@RunCommandService
    }

    companion object {
        const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
        const val RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        const val RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val RUN_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    }
}
