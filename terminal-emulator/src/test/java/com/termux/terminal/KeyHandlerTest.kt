package com.termux.terminal

import android.view.KeyEvent
import com.termux.terminal.KeyHandler.getCode
import com.termux.terminal.KeyHandler.getCodeFromTermcap
import junit.framework.TestCase

class KeyHandlerTest : TestCase() {
    /** See http://pubs.opengroup.org/onlinepubs/7990989799/xcurses/terminfo.html  */
    fun testTermCaps() {
        // Backspace.
        assertKeysEquals("\u007f", getCodeFromTermcap("kb", false, false))

        // Back tab.
        assertKeysEquals("\u001b[Z", getCodeFromTermcap("kB", false, false))

        // Arrow keys (up/down/right/left):
        assertKeysEquals("\u001b[A", getCodeFromTermcap("ku", false, false))
        assertKeysEquals("\u001b[B", getCodeFromTermcap("kd", false, false))
        assertKeysEquals("\u001b[C", getCodeFromTermcap("kr", false, false))
        assertKeysEquals("\u001b[D", getCodeFromTermcap("kl", false, false))
        // .. shifted:
        assertKeysEquals("\u001b[1;2A", getCodeFromTermcap("kUP", false, false))
        assertKeysEquals("\u001b[1;2B", getCodeFromTermcap("kDN", false, false))
        assertKeysEquals("\u001b[1;2C", getCodeFromTermcap("%i", false, false))
        assertKeysEquals("\u001b[1;2D", getCodeFromTermcap("#4", false, false))

        // Home/end keys:
        assertKeysEquals("\u001b[H", getCodeFromTermcap("kh", false, false))
        assertKeysEquals("\u001b[F", getCodeFromTermcap("@7", false, false))
        // ... shifted:
        assertKeysEquals("\u001b[1;2H", getCodeFromTermcap("#2", false, false))
        assertKeysEquals("\u001b[1;2F", getCodeFromTermcap("*7", false, false))

        // The traditional keyboard keypad:
        // [Insert] [Home] [Page Up ]
        // [Delete] [End] [Page Down]
        //
        // Termcap names (with xterm response in parenthesis):
        // K1=Upper left of keypad (xterm sends same "<ESC>[H" = Home).
        // K2=Center of keypad (xterm sends invalid response).
        // K3=Upper right of keypad (xterm sends "<ESC>[5~" = Page Up).
        // K4=Lower left of keypad (xterm sends "<ESC>[F" = End key).
        // K5=Lower right of keypad (xterm sends "<ESC>[6~" = Page Down).
        //
        // vim/neovim (runtime/doc/term.txt):
        // t_K1 <kHome> keypad home key
        // t_K3 <kPageUp> keypad page-up key
        // t_K4 <kEnd> keypad end key
        // t_K5 <kPageDown> keypad page-down key
        //
        assertKeysEquals("\u001b[H", getCodeFromTermcap("K1", false, false))
        assertKeysEquals("\u001bOH", getCodeFromTermcap("K1", true, false))
        assertKeysEquals("\u001b[5~", getCodeFromTermcap("K3", false, false))
        assertKeysEquals("\u001b[F", getCodeFromTermcap("K4", false, false))
        assertKeysEquals("\u001bOF", getCodeFromTermcap("K4", true, false))
        assertKeysEquals("\u001b[6~", getCodeFromTermcap("K5", false, false))

        // Function keys F1-F12:
        assertKeysEquals("\u001bOP", getCodeFromTermcap("k1", false, false))
        assertKeysEquals("\u001bOQ", getCodeFromTermcap("k2", false, false))
        assertKeysEquals("\u001bOR", getCodeFromTermcap("k3", false, false))
        assertKeysEquals("\u001bOS", getCodeFromTermcap("k4", false, false))
        assertKeysEquals("\u001b[15~", getCodeFromTermcap("k5", false, false))
        assertKeysEquals("\u001b[17~", getCodeFromTermcap("k6", false, false))
        assertKeysEquals("\u001b[18~", getCodeFromTermcap("k7", false, false))
        assertKeysEquals("\u001b[19~", getCodeFromTermcap("k8", false, false))
        assertKeysEquals("\u001b[20~", getCodeFromTermcap("k9", false, false))
        assertKeysEquals("\u001b[21~", getCodeFromTermcap("k;", false, false))
        assertKeysEquals("\u001b[23~", getCodeFromTermcap("F1", false, false))
        assertKeysEquals("\u001b[24~", getCodeFromTermcap("F2", false, false))
        // Function keys F13-F24 (same as shifted F1-F12):
        assertKeysEquals("\u001b[1;2P", getCodeFromTermcap("F3", false, false))
        assertKeysEquals("\u001b[1;2Q", getCodeFromTermcap("F4", false, false))
        assertKeysEquals("\u001b[1;2R", getCodeFromTermcap("F5", false, false))
        assertKeysEquals("\u001b[1;2S", getCodeFromTermcap("F6", false, false))
        assertKeysEquals("\u001b[15;2~", getCodeFromTermcap("F7", false, false))
        assertKeysEquals("\u001b[17;2~", getCodeFromTermcap("F8", false, false))
        assertKeysEquals("\u001b[18;2~", getCodeFromTermcap("F9", false, false))
        assertKeysEquals("\u001b[19;2~", getCodeFromTermcap("FA", false, false))
        assertKeysEquals("\u001b[20;2~", getCodeFromTermcap("FB", false, false))
        assertKeysEquals("\u001b[21;2~", getCodeFromTermcap("FC", false, false))
        assertKeysEquals("\u001b[23;2~", getCodeFromTermcap("FD", false, false))
        assertKeysEquals("\u001b[24;2~", getCodeFromTermcap("FE", false, false))
    }

    fun testKeyCodes() {
        // Return sends carriage return (\r), which normally gets translated by the device driver to newline (\n) unless the ICRNL termios
        // flag has been set.
        assertKeysEquals("\r", getCode(KeyEvent.KEYCODE_ENTER, 0, false, false))

        // Backspace.
        assertKeysEquals("\u007f", getCode(KeyEvent.KEYCODE_DEL, 0, false, false))

        // Space.
        assertNull(getCode(KeyEvent.KEYCODE_SPACE, 0, false, false))
        assertKeysEquals("\u0000", getCode(KeyEvent.KEYCODE_SPACE, KeyHandler.KEYMOD_CTRL, false, false))

        // Back tab.
        assertKeysEquals("\u001b[Z", getCode(KeyEvent.KEYCODE_TAB, KeyHandler.KEYMOD_SHIFT, false, false))

        // Arrow keys (up/down/right/left):
        assertKeysEquals("\u001b[A", getCode(KeyEvent.KEYCODE_DPAD_UP, 0, false, false))
        assertKeysEquals("\u001b[B", getCode(KeyEvent.KEYCODE_DPAD_DOWN, 0, false, false))
        assertKeysEquals("\u001b[C", getCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0, false, false))
        assertKeysEquals("\u001b[D", getCode(KeyEvent.KEYCODE_DPAD_LEFT, 0, false, false))
        // .. shifted:
        assertKeysEquals("\u001b[1;2A", getCode(KeyEvent.KEYCODE_DPAD_UP, KeyHandler.KEYMOD_SHIFT, false, false))
        assertKeysEquals("\u001b[1;2B", getCode(KeyEvent.KEYCODE_DPAD_DOWN, KeyHandler.KEYMOD_SHIFT, false, false))
        assertKeysEquals("\u001b[1;2C", getCode(KeyEvent.KEYCODE_DPAD_RIGHT, KeyHandler.KEYMOD_SHIFT, false, false))
        assertKeysEquals("\u001b[1;2D", getCode(KeyEvent.KEYCODE_DPAD_LEFT, KeyHandler.KEYMOD_SHIFT, false, false))
        // .. ctrl:ed:
        assertKeysEquals("\u001b[1;5A", getCode(KeyEvent.KEYCODE_DPAD_UP, KeyHandler.KEYMOD_CTRL, false, false))
        assertKeysEquals("\u001b[1;5B", getCode(KeyEvent.KEYCODE_DPAD_DOWN, KeyHandler.KEYMOD_CTRL, false, false))
        assertKeysEquals("\u001b[1;5C", getCode(KeyEvent.KEYCODE_DPAD_RIGHT, KeyHandler.KEYMOD_CTRL, false, false))
        assertKeysEquals("\u001b[1;5D", getCode(KeyEvent.KEYCODE_DPAD_LEFT, KeyHandler.KEYMOD_CTRL, false, false))
        // .. ctrl:ed and shifted:
        val mod = KeyHandler.KEYMOD_CTRL or KeyHandler.KEYMOD_SHIFT
        assertKeysEquals("\u001b[1;6A", getCode(KeyEvent.KEYCODE_DPAD_UP, mod, false, false))
        assertKeysEquals("\u001b[1;6B", getCode(KeyEvent.KEYCODE_DPAD_DOWN, mod, false, false))
        assertKeysEquals("\u001b[1;6C", getCode(KeyEvent.KEYCODE_DPAD_RIGHT, mod, false, false))
        assertKeysEquals("\u001b[1;6D", getCode(KeyEvent.KEYCODE_DPAD_LEFT, mod, false, false))

        // Home/end keys:
        assertKeysEquals("\u001b[H", getCode(KeyEvent.KEYCODE_MOVE_HOME, 0, false, false))
        assertKeysEquals("\u001b[F", getCode(KeyEvent.KEYCODE_MOVE_END, 0, false, false))
        // ... shifted:
        assertKeysEquals("\u001b[1;2H", getCode(KeyEvent.KEYCODE_MOVE_HOME, KeyHandler.KEYMOD_SHIFT, false, false))
        assertKeysEquals("\u001b[1;2F", getCode(KeyEvent.KEYCODE_MOVE_END, KeyHandler.KEYMOD_SHIFT, false, false))

        // Function keys F1-F12:
        assertKeysEquals("\u001bOP", getCode(KeyEvent.KEYCODE_F1, 0, false, false))
        assertKeysEquals("\u001bOQ", getCode(KeyEvent.KEYCODE_F2, 0, false, false))
        assertKeysEquals("\u001bOR", getCode(KeyEvent.KEYCODE_F3, 0, false, false))
        assertKeysEquals("\u001bOS", getCode(KeyEvent.KEYCODE_F4, 0, false, false))
        assertKeysEquals("\u001b[15~", getCode(KeyEvent.KEYCODE_F5, 0, false, false))
        assertKeysEquals("\u001b[17~", getCode(KeyEvent.KEYCODE_F6, 0, false, false))
        assertKeysEquals("\u001b[18~", getCode(KeyEvent.KEYCODE_F7, 0, false, false))
        assertKeysEquals("\u001b[19~", getCode(KeyEvent.KEYCODE_F8, 0, false, false))
        assertKeysEquals("\u001b[20~", getCode(KeyEvent.KEYCODE_F9, 0, false, false))
        assertKeysEquals("\u001b[21~", getCode(KeyEvent.KEYCODE_F10, 0, false, false))
        assertKeysEquals("\u001b[23~", getCode(KeyEvent.KEYCODE_F11, 0, false, false))
        assertKeysEquals("\u001b[24~", getCode(KeyEvent.KEYCODE_F12, 0, false, false))
        // Function keys F13-F24 (same as shifted F1-F12):
        assertKeysEquals("\u001b[1;2P", getCode(KeyEvent.KEYCODE_F1, KeyHandler.KEYMOD_SHIFT, false, false))
        assertKeysEquals("\u001b[1;2Q", getCode(KeyEvent.KEYCODE_F2, KeyHandler.KEYMOD_SHIFT, false, false))
        assertKeysEquals("\u001b[1;2R", getCode(KeyEvent.KEYCODE_F3, KeyHandler.KEYMOD_SHIFT, false, false))
        assertKeysEquals("\u001b[1;2S", getCode(KeyEvent.KEYCODE_F4, KeyHandler.KEYMOD_SHIFT, false, false))
        assertKeysEquals("\u001b[15;2~", getCode(KeyEvent.KEYCODE_F5, KeyHandler.KEYMOD_SHIFT, false, false))
        assertKeysEquals("\u001b[17;2~", getCode(KeyEvent.KEYCODE_F6, KeyHandler.KEYMOD_SHIFT, false, false))
        assertKeysEquals("\u001b[18;2~", getCode(KeyEvent.KEYCODE_F7, KeyHandler.KEYMOD_SHIFT, false, false))
        assertKeysEquals("\u001b[19;2~", getCode(KeyEvent.KEYCODE_F8, KeyHandler.KEYMOD_SHIFT, false, false))
        assertKeysEquals("\u001b[20;2~", getCode(KeyEvent.KEYCODE_F9, KeyHandler.KEYMOD_SHIFT, false, false))
        assertKeysEquals("\u001b[21;2~", getCode(KeyEvent.KEYCODE_F10, KeyHandler.KEYMOD_SHIFT, false, false))
        assertKeysEquals("\u001b[23;2~", getCode(KeyEvent.KEYCODE_F11, KeyHandler.KEYMOD_SHIFT, false, false))
        assertKeysEquals("\u001b[24;2~", getCode(KeyEvent.KEYCODE_F12, KeyHandler.KEYMOD_SHIFT, false, false))
        assertKeysEquals("0", getCode(KeyEvent.KEYCODE_NUMPAD_0, 0, false, false))
        assertKeysEquals("1", getCode(KeyEvent.KEYCODE_NUMPAD_1, 0, false, false))
        assertKeysEquals("2", getCode(KeyEvent.KEYCODE_NUMPAD_2, 0, false, false))
        assertKeysEquals("3", getCode(KeyEvent.KEYCODE_NUMPAD_3, 0, false, false))
        assertKeysEquals("4", getCode(KeyEvent.KEYCODE_NUMPAD_4, 0, false, false))
        assertKeysEquals("5", getCode(KeyEvent.KEYCODE_NUMPAD_5, 0, false, false))
        assertKeysEquals("6", getCode(KeyEvent.KEYCODE_NUMPAD_6, 0, false, false))
        assertKeysEquals("7", getCode(KeyEvent.KEYCODE_NUMPAD_7, 0, false, false))
        assertKeysEquals("8", getCode(KeyEvent.KEYCODE_NUMPAD_8, 0, false, false))
        assertKeysEquals("9", getCode(KeyEvent.KEYCODE_NUMPAD_9, 0, false, false))
        assertKeysEquals(",", getCode(KeyEvent.KEYCODE_NUMPAD_COMMA, 0, false, false))
        assertKeysEquals(".", getCode(KeyEvent.KEYCODE_NUMPAD_DOT, 0, false, false))
    }

    companion object {
        private fun stringToHex(s: String?): String? {
            if (s == null) return null
            val buffer = StringBuilder()
            for (i in 0 until s.length) {
                if (buffer.length > 0) {
                    buffer.append(" ")
                }
                buffer.append("0x")
                buffer.append(Integer.toHexString(s[i].toInt()))
            }
            return buffer.toString()
        }

        private fun assertKeysEquals(expected: String, actual: String?) {
            if (expected != actual) {
                assertEquals(stringToHex(expected), stringToHex(actual))
            }
        }
    }
}
