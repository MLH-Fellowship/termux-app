package com.termux.terminal

import junit.framework.TestCase

/** "\033[" is the Control Sequence Introducer char sequence (CSI).  */
class ControlSequenceIntroducerTest : TerminalTestCase() {
    /** CSI Ps P Scroll down Ps lines (default = 1) (SD).  */
    fun testCsiT() {
        withTerminalSized(4, 6).enterString("1\r\n2\r\n3\r\nhi\u001b[2Tyo\r\nA\r\nB").assertLinesAre("    ", "    ", "1   ", "2 yo", "A   ",
            "Bi  ")
        // Default value (1):
        withTerminalSized(4, 6).enterString("1\r\n2\r\n3\r\nhi\u001b[Tyo\r\nA\r\nB").assertLinesAre("    ", "1   ", "2   ", "3 yo", "Ai  ",
            "B   ")
    }

    /** CSI Ps S Scroll up Ps lines (default = 1) (SU).  */
    fun testCsiS() {
        // The behaviour here is a bit inconsistent between terminals - this is how the OS X Terminal.app does it:
        withTerminalSized(3, 4).enterString("1\r\n2\r\n3\r\nhi\u001b[2Sy").assertLinesAre("3  ", "hi ", "   ", "  y")
        // Default value (1):
        withTerminalSized(3, 4).enterString("1\r\n2\r\n3\r\nhi\u001b[Sy").assertLinesAre("2  ", "3  ", "hi ", "  y")
    }

    /** CSI Ps X  Erase Ps Character(s) (default = 1) (ECH).  */
    fun testCsiX() {
        // See https://code.google.com/p/chromium/issues/detail?id=212712 where test was extraced from.
        withTerminalSized(13, 2).enterString("abcdefghijkl\b\b\b\b\b\u001b[X").assertLinesAre("abcdefg ijkl ", "             ")
        withTerminalSized(13, 2).enterString("abcdefghijkl\b\b\b\b\b\u001b[1X").assertLinesAre("abcdefg ijkl ", "             ")
        withTerminalSized(13, 2).enterString("abcdefghijkl\b\b\b\b\b\u001b[2X").assertLinesAre("abcdefg  jkl ", "             ")
        withTerminalSized(13, 2).enterString("abcdefghijkl\b\b\b\b\b\u001b[20X").assertLinesAre("abcdefg      ", "             ")
    }

    /** CSI Pm m  Set SGR parameter(s) from semicolon-separated list Pm.  */
    fun testCsiSGRParameters() {
        // Set more parameters (19) than supported (16).  Additional parameters should be silently consumed.
        withTerminalSized(3, 2).enterString("\u001b[0;38;2;255;255;255;48;2;0;0;0;1;2;3;4;5;7;8;9mabc").assertLinesAre("abc", "   ")
    }

    /** CSI Ps b  Repeat the preceding graphic character Ps times (REP).  */
    fun testRepeat() {
        withTerminalSized(3, 2).enterString("a\u001b[b").assertLinesAre("aa ", "   ")
        withTerminalSized(3, 2).enterString("a\u001b[2b").assertLinesAre("aaa", "   ")
        // When no char has been output we ignore REP:
        withTerminalSized(3, 2).enterString("\u001b[b").assertLinesAre("   ", "   ")
        // This shows that REP outputs the last emitted code point and not the one relative to the
        // current cursor position:
        withTerminalSized(5, 2).enterString("abcde\u001b[2G\u001b[2b\n").assertLinesAre("aeede", "     ")
    }

    /** CSI 3 J  Clear scrollback (xterm, libvte; non-standard).  */
    fun testCsi3J() {
        withTerminalSized(3, 2).enterString("a\r\nb\r\nc\r\nd")
        TestCase.assertEquals("a\nb\nc\nd", mTerminal!!.screen.transcriptText)
        enterString("\u001b[3J")
        TestCase.assertEquals("c\nd", mTerminal!!.screen.transcriptText)
        withTerminalSized(3, 2).enterString("Lorem_ipsum")
        TestCase.assertEquals("Lorem_ipsum", mTerminal!!.screen.transcriptText)
        enterString("\u001b[3J")
        TestCase.assertEquals("ipsum", mTerminal!!.screen.transcriptText)
        withTerminalSized(3, 2).enterString("w\r\nx\r\ny\r\nz\u001b[?1049h\u001b[3J\u001b[?1049l")
        TestCase.assertEquals("y\nz", mTerminal!!.screen.transcriptText)
    }
}
