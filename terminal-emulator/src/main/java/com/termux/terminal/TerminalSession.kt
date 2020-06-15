package com.termux.terminal

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Message
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.*
import java.lang.reflect.Field
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 *
 *
 * The subprocess will be executed by the constructor, and when the size is made known by a call to
 * [.updateSize] terminal emulation will begin and threads will be spawned to handle the subprocess I/O.
 * All terminal emulation and callback methods will be performed on the main thread.
 *
 *
 * The child process may be exited forcefully by using the [.finishIfRunning] method.
 *
 *
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
class TerminalSession constructor(private val mShellPath: String, private val mCwd: String, private val mArgs: Array<String?>, private val mEnv: Array<String?>,
                                  /** Callback which gets notified when a session finishes or changes title.  */
                                  val mChangeCallback: SessionChangedCallback) : TerminalOutput() {
    /** Callback to be invoked when a [TerminalSession] changes.  */
    open interface SessionChangedCallback {
        fun onTextChanged(changedSession: TerminalSession?)
        fun onTitleChanged(changedSession: TerminalSession?)
        fun onSessionFinished(finishedSession: TerminalSession?)
        fun onClipboardText(session: TerminalSession?, text: String?)
        fun onBell(session: TerminalSession?)
        fun onColorsChanged(session: TerminalSession?)
    }

    val mHandle: String = UUID.randomUUID().toString()
    var emulator: TerminalEmulator? = null

    /**
     * A queue written to from a separate thread when the process outputs, and read by main thread to process by
     * terminal emulator.
     */
    val mProcessToTerminalIOQueue: ByteQueue = ByteQueue(4096)

    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the [.mTerminalFileDescriptor].
     */
    val mTerminalToProcessIOQueue: ByteQueue = ByteQueue(4096)

    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue  */
    private val mUtf8InputBuffer: ByteArray = ByteArray(5)

    /** The pid of the shell process. 0 if not started and -1 if finished running.  */
    var pid: Int = 0

    /** Only valid if not [.isRunning].  */
    /** The exit status of the shell process. Only valid if $[.mShellPid] is -1.  */
    @get:Synchronized
    var exitStatus: Int = 0

    /**
     * The file descriptor referencing the master half of a pseudo-terminal pair, resulting from calling
     * [JNI.createSubprocess].
     */
    private var mTerminalFileDescriptor: Int = 0

    /** Set by the application for user identification of session, not by terminal.  */
    var mSessionName: String? = null

    @SuppressLint("HandlerLeak")
    val mMainThreadHandler: Handler = object : Handler() {
        val mReceiveBuffer: ByteArray = ByteArray(4 * 1024)
        override fun handleMessage(msg: Message) {
            if (msg.what == MSG_NEW_INPUT && isRunning) {
                val bytesRead: Int = mProcessToTerminalIOQueue.read(mReceiveBuffer, false)
                if (bytesRead > 0) {
                    emulator!!.append(mReceiveBuffer, bytesRead)
                    notifyScreenUpdate()
                }
            } else if (msg.what == MSG_PROCESS_EXITED) {
                val exitCode: Int = msg.obj as Int
                cleanupResources(exitCode)
                mChangeCallback.onSessionFinished(this@TerminalSession)
                var exitDescription: String = "\r\n[Process completed"
                if (exitCode > 0) {
                    // Non-zero process exit.
                    exitDescription += " (code " + exitCode + ")"
                } else if (exitCode < 0) {
                    // Negated signal.
                    exitDescription += " (signal " + (-exitCode) + ")"
                }
                exitDescription += " - press Enter]"
                val bytesToWrite: ByteArray = exitDescription.toByteArray(StandardCharsets.UTF_8)
                emulator!!.append(bytesToWrite, bytesToWrite.size)
                notifyScreenUpdate()
            }
        }
    }

    /** Inform the attached pty of the new size and reflow or initialize the emulator.  */
    fun updateSize(columns: Int, rows: Int) {
        if (emulator == null) {
            initializeEmulator(columns, rows)
        } else {
            JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns)
            emulator!!.resize(columns, rows)
        }
    }

    /** The terminal title as set through escape sequences or null if none set.  */
    val title: String?
        get() {
            return if ((emulator == null)) null else emulator.getTitle()
        }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    fun initializeEmulator(columns: Int, rows: Int) {
        emulator = TerminalEmulator(this, columns, rows,  /* transcript= */2000)
        val processId: IntArray = IntArray(1)
        mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns)
        pid = processId.get(0)
        val terminalFileDescriptorWrapped: FileDescriptor = wrapFileDescriptor(mTerminalFileDescriptor)
        object : Thread("TermSessionInputReader[pid=" + pid + "]") {
            override fun run() {
                try {
                    FileInputStream(terminalFileDescriptorWrapped).use({ termIn ->
                        val buffer: ByteArray = ByteArray(4096)
                        while (true) {
                            val read: Int = termIn.read(buffer)
                            if (read == -1) return
                            if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return
                            mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT)
                        }
                    })
                } catch (e: Exception) {
                    // Ignore, just shutting down.
                }
            }
        }.start()
        object : Thread("TermSessionOutputWriter[pid=" + pid + "]") {
            override fun run() {
                val buffer: ByteArray = ByteArray(4096)
                try {
                    FileOutputStream(terminalFileDescriptorWrapped).use({ termOut ->
                        while (true) {
                            val bytesToWrite: Int = mTerminalToProcessIOQueue.read(buffer, true)
                            if (bytesToWrite == -1) return
                            termOut.write(buffer, 0, bytesToWrite)
                        }
                    })
                } catch (e: IOException) {
                    // Ignore.
                }
            }
        }.start()
        object : Thread("TermSessionWaiter[pid=" + pid + "]") {
            override fun run() {
                val processExitCode: Int = JNI.waitFor(pid)
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode))
            }
        }.start()
    }

    /** Write data to the shell process.  */
    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (pid > 0) mTerminalToProcessIOQueue.write(data, offset, count)
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8.  */
    fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw IllegalArgumentException("Invalid code point: " + codePoint)
        }
        var bufferPosition: Int = 0
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27
        if (codePoint <=  /* 7 bits */127) {
            mUtf8InputBuffer[bufferPosition++] = codePoint.toByte()
        } else if (codePoint <=  /* 11 bits */2047) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (192 or (codePoint shr 6)).toByte()
            /* 10xxxxxx continuation byte with following 6 bits */mUtf8InputBuffer[bufferPosition++] = (128 or (codePoint and 63)).toByte()
        } else if (codePoint <=  /* 16 bits */65535) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (224 or (codePoint shr 12)).toByte()
            /* 10xxxxxx continuation byte with following 6 bits */mUtf8InputBuffer[bufferPosition++] = (128 or ((codePoint shr 6) and 63)).toByte()
            /* 10xxxxxx continuation byte with following 6 bits */mUtf8InputBuffer.get(bufferPosition++) = (128 or (codePoint and 63)).toByte()
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (240 or (codePoint shr 18)).toByte()
            /* 10xxxxxx continuation byte with following 6 bits */mUtf8InputBuffer[bufferPosition++] = (128 or ((codePoint shr 12) and 63)).toByte()
            /* 10xxxxxx continuation byte with following 6 bits */mUtf8InputBuffer[bufferPosition++] = (128 or ((codePoint shr 6) and 63)).toByte()
            /* 10xxxxxx continuation byte with following 6 bits */mUtf8InputBuffer[bufferPosition++] = (128 or (codePoint and 63)).toByte()
        }
        write(mUtf8InputBuffer, 0, bufferPosition)
    }

    /** Notify the [.mChangeCallback] that the screen has changed.  */
    protected fun notifyScreenUpdate() {
        mChangeCallback.onTextChanged(this)
    }

    /** Reset state for terminal emulator state.  */
    fun reset() {
        emulator!!.reset()
        notifyScreenUpdate()
    }

    /** Finish this terminal session by sending SIGKILL to the shell.  */
    fun finishIfRunning() {
        if (isRunning) {
            try {
                Os.kill(pid, OsConstants.SIGKILL)
            } catch (e: ErrnoException) {
                Log.w("termux", "Failed sending SIGKILL: " + e.message)
            }
        }
    }

    /** Cleanup resources when the process exits.  */
    fun cleanupResources(exitStatus: Int) {
        synchronized(this, {
            pid = -1
            this.exitStatus = exitStatus
        })

        // Stop the reader and writer threads, and close the I/O streams
        mTerminalToProcessIOQueue.close()
        mProcessToTerminalIOQueue.close()
        JNI.close(mTerminalFileDescriptor)
    }

    override fun titleChanged(oldTitle: String?, newTitle: String?) {
        mChangeCallback.onTitleChanged(this)
    }

    @get:Synchronized
    val isRunning: Boolean
        get() {
            return pid != -1
        }

    override fun clipboardText(text: String?) {
        mChangeCallback.onClipboardText(this, text)
    }

    override fun onBell() {
        mChangeCallback.onBell(this)
    }

    override fun onColorsChanged() {
        mChangeCallback.onColorsChanged(this)
    }

    /** Returns the shell's working directory or null if it was unavailable.  */
    val cwd: String?
        get() {
            if (pid < 1) {
                return null
            }
            try {
                val cwdSymlink: String = String.format("/proc/%s/cwd/", pid)
                val outputPath: String = File(cwdSymlink).canonicalPath
                var outputPathWithTrailingSlash: String = outputPath
                if (!outputPath.endsWith("/")) {
                    outputPathWithTrailingSlash += '/'
                }
                if (!(cwdSymlink == outputPathWithTrailingSlash)) {
                    return outputPath
                }
            } catch (e: IOException) {
                Log.e(EmulatorDebug.LOG_TAG, "Error getting current directory", e)
            } catch (e: SecurityException) {
                Log.e(EmulatorDebug.LOG_TAG, "Error getting current directory", e)
            }
            return null
        }

    companion object {
        private fun wrapFileDescriptor(fileDescriptor: Int): FileDescriptor {
            val result: FileDescriptor = FileDescriptor()
            try {
                var descriptorField: Field
                try {
                    descriptorField = FileDescriptor::class.java.getDeclaredField("descriptor")
                } catch (e: NoSuchFieldException) {
                    // For desktop java:
                    descriptorField = FileDescriptor::class.java.getDeclaredField("fd")
                }
                descriptorField.isAccessible = true
                descriptorField.set(result, fileDescriptor)
            } catch (e: NoSuchFieldException) {
                Log.wtf(EmulatorDebug.LOG_TAG, "Error accessing FileDescriptor#descriptor private field", e)
                System.exit(1)
            } catch (e: IllegalAccessException) {
                Log.wtf(EmulatorDebug.LOG_TAG, "Error accessing FileDescriptor#descriptor private field", e)
                System.exit(1)
            } catch (e: IllegalArgumentException) {
                Log.wtf(EmulatorDebug.LOG_TAG, "Error accessing FileDescriptor#descriptor private field", e)
                System.exit(1)
            }
            return result
        }

        private val MSG_NEW_INPUT: Int = 1
        private val MSG_PROCESS_EXITED: Int = 4
    }

}
