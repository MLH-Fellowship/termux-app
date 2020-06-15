package com.termux.terminal

import java.nio.charset.StandardCharsets

/** A client which receives callbacks from events triggered by feeding input to a [TerminalEmulator].  */
abstract class TerminalOutput {
    /** Write a string using the UTF-8 encoding to the terminal client.  */
    fun write(data: String) {
        val bytes: ByteArray = data.toByteArray(StandardCharsets.UTF_8)
        write(bytes, 0, bytes.size)
    }

    /** Write bytes to the terminal client.  */
    abstract fun write(data: ByteArray, offset: Int, count: Int)

    /** Notify the terminal client that the terminal title has changed.  */
    abstract fun titleChanged(oldTitle: String?, newTitle: String?)

    /** Notify the terminal client that the terminal title has changed.  */
    abstract fun clipboardText(text: String?)

    /** Notify the terminal client that a bell character (ASCII 7, bell, BEL, \a, ^G)) has been received.  */
    abstract fun onBell()
    abstract fun onColorsChanged()
}
