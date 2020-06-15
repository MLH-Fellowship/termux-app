package com.termux.app

import android.annotation.SuppressLint
import android.app.*
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WifiLock
import android.os.*
import android.os.PowerManager.WakeLock
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import com.termux.R
import com.termux.app.BackgroundJob
import com.termux.app.TermuxService
import com.termux.terminal.EmulatorDebug
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSession.SessionChangedCallback
import java.io.File
import java.util.*

/**
 * A service holding a list of terminal sessions, [.mTerminalSessions], showing a foreground notification while
 * running so that it is not terminated. The user interacts with the session through [TermuxActivity], but this
 * service may outlive the activity when the user or the system disposes of the activity. In that case the user may
 * restart [TermuxActivity] later to yet again access the sessions.
 *
 *
 * In order to keep both terminal sessions and spawned processes (who may outlive the terminal sessions) alive as long
 * as wanted by the user this service is a foreground service, [Service.startForeground].
 *
 *
 * Optionally may hold a wake and a wifi lock, in which case that is shown in the notification - see
 * [.buildNotification].
 */
class TermuxService : Service(), SessionChangedCallback {
    /**
     * The terminal sessions which this service manages.
     *
     *
     * Note that this list is observed by [TermuxActivity.mListViewAdapter], so any changes must be made on the UI
     * thread and followed by a call to [ArrayAdapter.notifyDataSetChanged] }.
     */
    val mTerminalSessions: MutableList<TerminalSession?> = ArrayList()
    val mBackgroundTasks: MutableList<BackgroundJob> = ArrayList()
    private val mBinder: IBinder = LocalBinder()
    private val mHandler = Handler()

    /**
     * Note that the service may often outlive the activity, so need to clear this reference.
     */
    var mSessionChangeCallback: SessionChangedCallback? = null

    /**
     * If the user has executed the [.ACTION_STOP_SERVICE] intent.
     */
    var mWantsToStop = false

    /**
     * The wake lock and wifi lock are always acquired and released together.
     */
    private var mWakeLock: WakeLock? = null
    private var mWifiLock: WifiLock? = null

    @SuppressLint("Wakelock")
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val action = intent.action
        if (ACTION_STOP_SERVICE == action) {
            mWantsToStop = true
            for (i in mTerminalSessions.indices) mTerminalSessions[i]!!.finishIfRunning()
            stopSelf()
        } else if (ACTION_LOCK_WAKE == action) {
            if (mWakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, EmulatorDebug.LOG_TAG)
                mWakeLock.acquire()

                // http://tools.android.com/tech-docs/lint-in-studio-2-3#TOC-WifiManager-Leak
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, EmulatorDebug.LOG_TAG)
                mWifiLock.acquire()
                val packageName = packageName
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val whitelist = Intent()
                    whitelist.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    whitelist.data = Uri.parse("package:$packageName")
                    whitelist.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    try {
                        startActivity(whitelist)
                    } catch (e: ActivityNotFoundException) {
                        Log.e(EmulatorDebug.LOG_TAG, "Failed to call ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", e)
                    }
                }
                updateNotification()
            }
        } else if (ACTION_UNLOCK_WAKE == action) {
            if (mWakeLock != null) {
                mWakeLock!!.release()
                mWakeLock = null
                mWifiLock!!.release()
                mWifiLock = null
                updateNotification()
            }
        } else if (ACTION_EXECUTE == action) {
            val executableUri = intent.data
            val executablePath = executableUri?.path
            val arguments = if (executableUri == null) null else intent.getStringArrayExtra(EXTRA_ARGUMENTS)
            val cwd = intent.getStringExtra(EXTRA_CURRENT_WORKING_DIRECTORY)
            if (intent.getBooleanExtra(EXTRA_EXECUTE_IN_BACKGROUND, false)) {
                val task = BackgroundJob(cwd, executablePath, arguments, this, intent.getParcelableExtra("pendingIntent"))
                mBackgroundTasks.add(task)
                updateNotification()
            } else {
                val failsafe = intent.getBooleanExtra(TermuxActivity.Companion.TERMUX_FAILSAFE_SESSION_ACTION, false)
                val newSession = createTermSession(executablePath, arguments, cwd, failsafe)

                // Transform executable path to session name, e.g. "/bin/do-something.sh" => "do something.sh".
                if (executablePath != null) {
                    val lastSlash = executablePath.lastIndexOf('/')
                    var name: String = if (lastSlash == -1) executablePath else executablePath.substring(lastSlash + 1)
                    name = name.replace('-', ' ')
                    newSession.mSessionName = name
                }

                // Make the newly created session the current one to be displayed:
                TermuxPreferences.Companion.storeCurrentSession(this, newSession)

                // Launch the main Termux app, which will now show the current session:
                startActivity(Intent(this, TermuxActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else if (action != null) {
            Log.e(EmulatorDebug.LOG_TAG, "Unknown TermuxService action: '$action'")
        }

        // If this service really do get killed, there is no point restarting it automatically - let the user do on next
        // start of {@link Term):
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return mBinder
    }

    override fun onCreate() {
        setupNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    /**
     * Update the shown foreground service notification after making any changes that affect it.
     */
    fun updateNotification() {
        if (mWakeLock == null && mTerminalSessions.isEmpty() && mBackgroundTasks.isEmpty()) {
            // Exit if we are updating after the user disabled all locks with no sessions or tasks running.
            stopSelf()
        } else {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val notifyIntent = Intent(this, TermuxActivity::class.java)
        // PendingIntent#getActivity(): "Note that the activity will be started outside of the context of an existing
        // activity, so you must use the Intent.FLAG_ACTIVITY_NEW_TASK launch flag in the Intent":
        notifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(this, 0, notifyIntent, 0)
        val sessionCount = mTerminalSessions.size
        val taskCount = mBackgroundTasks.size
        var contentText = sessionCount.toString() + " session" + if (sessionCount == 1) "" else "s"
        if (taskCount > 0) {
            contentText += ", " + taskCount + " task" + if (taskCount == 1) "" else "s"
        }
        val wakeLockHeld = mWakeLock != null
        if (wakeLockHeld) contentText += " (wake lock held)"
        val builder = Notification.Builder(this)
        builder.setContentTitle(getText(R.string.application_name))
        builder.setContentText(contentText)
        builder.setSmallIcon(R.drawable.ic_service_notification)
        builder.setContentIntent(pendingIntent)
        builder.setOngoing(true)

        // If holding a wake or wifi lock consider the notification of high priority since it's using power,
        // otherwise use a low priority
        builder.setPriority(if (wakeLockHeld) Notification.PRIORITY_HIGH else Notification.PRIORITY_LOW)

        // No need to show a timestamp:
        builder.setShowWhen(false)

        // Background color for small notification icon:
        builder.setColor(-0x9f8275)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(NOTIFICATION_CHANNEL_ID)
        }
        val res = resources
        val exitIntent = Intent(this, TermuxService::class.java).setAction(ACTION_STOP_SERVICE)
        builder.addAction(android.R.drawable.ic_delete, res.getString(R.string.notification_action_exit), PendingIntent.getService(this, 0, exitIntent, 0))
        val newWakeAction = if (wakeLockHeld) ACTION_UNLOCK_WAKE else ACTION_LOCK_WAKE
        val toggleWakeLockIntent = Intent(this, TermuxService::class.java).setAction(newWakeAction)
        val actionTitle = res.getString(if (wakeLockHeld) R.string.notification_action_wake_unlock else R.string.notification_action_wake_lock)
        val actionIcon = if (wakeLockHeld) android.R.drawable.ic_lock_idle_lock else android.R.drawable.ic_lock_lock
        builder.addAction(actionIcon, actionTitle, PendingIntent.getService(this, 0, toggleWakeLockIntent, 0))
        return builder.build()
    }

    override fun onDestroy() {
        val termuxTmpDir = File("$PREFIX_PATH/tmp")
        if (termuxTmpDir.exists()) {
            try {
                TermuxInstaller.deleteFolder(termuxTmpDir.canonicalFile)
            } catch (e: Exception) {
                Log.e(EmulatorDebug.LOG_TAG, "Error while removing file at " + termuxTmpDir.absolutePath, e)
            }
            termuxTmpDir.mkdirs()
        }
        if (mWakeLock != null) mWakeLock!!.release()
        if (mWifiLock != null) mWifiLock!!.release()
        stopForeground(true)
        for (i in mTerminalSessions.indices) mTerminalSessions[i]!!.finishIfRunning()
    }

    val sessions: List<TerminalSession?>
        get() = mTerminalSessions

    fun createTermSession(executablePath: String?, arguments: Array<String?>?, cwd: String?, failSafe: Boolean): TerminalSession {
        var executablePath = executablePath
        var cwd = cwd
        File(HOME_PATH).mkdirs()
        if (cwd == null) cwd = HOME_PATH
        val env: Array<String> = BackgroundJob.Companion.buildEnvironment(failSafe, cwd)
        var isLoginShell = false
        if (executablePath == null) {
            if (!failSafe) {
                for (shellBinary in arrayOf("login", "bash", "zsh")) {
                    val shellFile = File("$PREFIX_PATH/bin/$shellBinary")
                    if (shellFile.canExecute()) {
                        executablePath = shellFile.absolutePath
                        break
                    }
                }
            }
            if (executablePath == null) {
                // Fall back to system shell as last resort:
                executablePath = "/system/bin/sh"
            }
            isLoginShell = true
        }
        val processArgs: Array<String> = BackgroundJob.Companion.setupProcessArgs(executablePath, arguments)
        executablePath = processArgs[0]
        val lastSlashIndex = executablePath.lastIndexOf('/')
        val processName = (if (isLoginShell) "-" else "") +
            if (lastSlashIndex == -1) executablePath else executablePath.substring(lastSlashIndex + 1)
        val args = arrayOfNulls<String>(processArgs.size)
        args[0] = processName
        if (processArgs.size > 1) System.arraycopy(processArgs, 1, args, 1, processArgs.size - 1)
        val session = TerminalSession(executablePath, cwd, args, env, this)
        mTerminalSessions.add(session)
        updateNotification()

        // Make sure that terminal styling is always applied.
        val stylingIntent = Intent("com.termux.app.reload_style")
        stylingIntent.putExtra("com.termux.app.reload_style", "styling")
        sendBroadcast(stylingIntent)
        return session
    }

    fun removeTermSession(sessionToRemove: TerminalSession?): Int {
        val indexOfRemoved = mTerminalSessions.indexOf(sessionToRemove)
        mTerminalSessions.removeAt(indexOfRemoved)
        if (mTerminalSessions.isEmpty() && mWakeLock == null) {
            // Finish if there are no sessions left and the wake lock is not held, otherwise keep the service alive if
            // holding wake lock since there may be daemon processes (e.g. sshd) running.
            stopSelf()
        } else {
            updateNotification()
        }
        return indexOfRemoved
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        if (mSessionChangeCallback != null) mSessionChangeCallback!!.onTitleChanged(changedSession)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (mSessionChangeCallback != null) mSessionChangeCallback!!.onSessionFinished(finishedSession)
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        if (mSessionChangeCallback != null) mSessionChangeCallback!!.onTextChanged(changedSession)
    }

    override fun onClipboardText(session: TerminalSession, text: String) {
        if (mSessionChangeCallback != null) mSessionChangeCallback!!.onClipboardText(session, text)
    }

    override fun onBell(session: TerminalSession) {
        if (mSessionChangeCallback != null) mSessionChangeCallback!!.onBell(session)
    }

    override fun onColorsChanged(session: TerminalSession) {
        if (mSessionChangeCallback != null) mSessionChangeCallback!!.onColorsChanged(session)
    }

    fun onBackgroundJobExited(task: BackgroundJob) {
        mHandler.post {
            mBackgroundTasks.remove(task)
            updateNotification()
        }
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channelName = "Termux"
        val channelDescription = "Notifications from Termux"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, importance)
        channel.description = channelDescription
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    /**
     * This service is only bound from inside the same process and never uses IPC.
     */
    internal inner class LocalBinder : Binder() {
        val service = this@TermuxService
    }

    companion object {
        /**
         * Note that this is a symlink on the Android M preview.
         */
        @JvmField
        @SuppressLint("SdCardPath")
        val FILES_PATH = "/data/data/com.termux/files"
        val PREFIX_PATH = "$FILES_PATH/usr"
        @JvmField
        val HOME_PATH = "$FILES_PATH/home"

        /**
         * Intent action to launch a new terminal session. Executed from TermuxWidgetProvider.
         */
        const val ACTION_EXECUTE = "com.termux.service_execute"
        const val EXTRA_ARGUMENTS = "com.termux.execute.arguments"
        const val EXTRA_CURRENT_WORKING_DIRECTORY = "com.termux.execute.cwd"
        private const val NOTIFICATION_CHANNEL_ID = "termux_notification_channel"
        private const val NOTIFICATION_ID = 1337
        private const val ACTION_STOP_SERVICE = "com.termux.service_stop"
        private const val ACTION_LOCK_WAKE = "com.termux.service_wake_lock"
        private const val ACTION_UNLOCK_WAKE = "com.termux.service_wake_unlock"
        private const val EXTRA_EXECUTE_IN_BACKGROUND = "com.termux.execute.background"
    }
}
