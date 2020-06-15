package com.termux.terminal

import com.termux.terminal.TerminalColors.Companion.parse
import com.termux.terminal.TextStyle.decodeBackColor
import com.termux.terminal.TextStyle.decodeForeColor
import com.termux.terminal.WcWidth.width
import junit.framework.TestCase
import java.io.UnsupportedEncodingException

class TerminalTest : TerminalTestCase() {
    @Throws(Exception::class)
    fun testCursorPositioning() {
        withTerminalSized(10, 10).placeCursorAndAssert(1, 2).placeCursorAndAssert(3, 5).placeCursorAndAssert(2, 2).enterString("A")
            .assertCursorAt(2, 3)
    }

    @Throws(UnsupportedEncodingException::class)
    fun testScreen() {
        withTerminalSized(3, 3)
        assertLinesAre("   ", "   ", "   ")
        TestCase.assertEquals("", mTerminal!!.screen.transcriptText)
        enterString("hi").assertLinesAre("hi ", "   ", "   ")
        TestCase.assertEquals("hi", mTerminal!!.screen.transcriptText)
        enterString("\r\nu")
        TestCase.assertEquals("hi\nu", mTerminal!!.screen.transcriptText)
        mTerminal!!.reset()
        TestCase.assertEquals("hi\nu", mTerminal!!.screen.transcriptText)
        withTerminalSized(3, 3).enterString("hello")
        TestCase.assertEquals("hello", mTerminal!!.screen.transcriptText)
        enterString("\r\nworld")
        TestCase.assertEquals("hello\nworld", mTerminal!!.screen.transcriptText)
    }

    fun testScrollDownInAltBuffer() {
        withTerminalSized(3, 3).enterString("\u001b[?1049h")
        enterString("\u001b[38;5;111m1\r\n")
        enterString("\u001b[38;5;112m2\r\n")
        enterString("\u001b[38;5;113m3\r\n")
        enterString("\u001b[38;5;114m4\r\n")
        enterString("\u001b[38;5;115m5")
        assertLinesAre("3  ", "4  ", "5  ")
        assertForegroundColorAt(0, 0, 113)
        assertForegroundColorAt(1, 0, 114)
        assertForegroundColorAt(2, 0, 115)
    }

    @Throws(Exception::class)
    fun testMouseClick() {
        withTerminalSized(10, 10)
        TestCase.assertFalse(mTerminal!!.isMouseTrackingActive)
        enterString("\u001b[?1000h")
        TestCase.assertTrue(mTerminal!!.isMouseTrackingActive)
        enterString("\u001b[?1000l")
        TestCase.assertFalse(mTerminal!!.isMouseTrackingActive)
        enterString("\u001b[?1000h")
        TestCase.assertTrue(mTerminal!!.isMouseTrackingActive)
        enterString("\u001b[?1006h")
        mTerminal!!.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 3, 4, true)
        TestCase.assertEquals("\u001b[<0;3;4M", mOutput.outputAndClear)
        mTerminal!!.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 3, 4, false)
        TestCase.assertEquals("\u001b[<0;3;4m", mOutput.outputAndClear)

        // When the client says that a click is outside (which could happen when pixels are outside
        // the terminal area, see https://github.com/termux/termux-app/issues/501) the terminal
        // sends a click at the edge.
        mTerminal!!.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 0, 0, true)
        TestCase.assertEquals("\u001b[<0;1;1M", mOutput.outputAndClear)
        mTerminal!!.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 11, 11, false)
        TestCase.assertEquals("\u001b[<0;10;10m", mOutput.outputAndClear)
    }

    @Throws(UnsupportedEncodingException::class)
    fun testNormalization() {
        // int lowerCaseN = 0x006E;
        // int combiningTilde = 0x0303;
        // int combined = 0x00F1;
        withTerminalSized(3, 3).assertLinesAre("   ", "   ", "   ")
        enterString("\u006E\u0303")
        TestCase.assertEquals(1, width("\u006E\u0303".toCharArray(), 0))
        // assertEquals("\u00F1  ", new String(mTerminal.getScreen().getLine(0)));
        assertLinesAre("\u006E\u0303  ", "   ", "   ")
    }

    /** On "\e[18t" xterm replies with "\e[8;${HEIGHT};${WIDTH}t"  */
    @Throws(Exception::class)
    fun testReportTerminalSize() {
        withTerminalSized(5, 5)
        assertEnteringStringGivesResponse("\u001b[18t", "\u001b[8;5;5t")
        for (width in 3..11) {
            for (height in 3..11) {
                mTerminal!!.resize(width, height)
                assertEnteringStringGivesResponse("\u001b[18t", "\u001b[8;" + height + ";" + width + "t")
            }
        }
    }

    /** Device Status Report (DSR) and Report Cursor Position (CPR).  */
    @Throws(Exception::class)
    fun testDeviceStatusReport() {
        withTerminalSized(5, 5)
        assertEnteringStringGivesResponse("\u001b[5n", "\u001b[0n")
        assertEnteringStringGivesResponse("\u001b[6n", "\u001b[1;1R")
        enterString("AB")
        assertEnteringStringGivesResponse("\u001b[6n", "\u001b[1;3R")
        enterString("\r\n")
        assertEnteringStringGivesResponse("\u001b[6n", "\u001b[2;1R")
    }

    /** Test the cursor shape changes using DECSCUSR.  */
    @Throws(Exception::class)
    fun testSetCursorStyle() {
        withTerminalSized(5, 5)
        TestCase.assertEquals(TerminalEmulator.CURSOR_STYLE_BLOCK, mTerminal!!.cursorStyle)
        enterString("\u001b[3 q")
        TestCase.assertEquals(TerminalEmulator.CURSOR_STYLE_UNDERLINE, mTerminal!!.cursorStyle)
        enterString("\u001b[5 q")
        TestCase.assertEquals(TerminalEmulator.CURSOR_STYLE_BAR, mTerminal!!.cursorStyle)
        enterString("\u001b[0 q")
        TestCase.assertEquals(TerminalEmulator.CURSOR_STYLE_BLOCK, mTerminal!!.cursorStyle)
        enterString("\u001b[6 q")
        TestCase.assertEquals(TerminalEmulator.CURSOR_STYLE_BAR, mTerminal!!.cursorStyle)
        enterString("\u001b[4 q")
        TestCase.assertEquals(TerminalEmulator.CURSOR_STYLE_UNDERLINE, mTerminal!!.cursorStyle)
        enterString("\u001b[1 q")
        TestCase.assertEquals(TerminalEmulator.CURSOR_STYLE_BLOCK, mTerminal!!.cursorStyle)
        enterString("\u001b[4 q")
        TestCase.assertEquals(TerminalEmulator.CURSOR_STYLE_UNDERLINE, mTerminal!!.cursorStyle)
        enterString("\u001b[2 q")
        TestCase.assertEquals(TerminalEmulator.CURSOR_STYLE_BLOCK, mTerminal!!.cursorStyle)
    }

    fun testPaste() {
        withTerminalSized(5, 5)
        mTerminal!!.paste("hi")
        TestCase.assertEquals("hi", mOutput.outputAndClear)
        enterString("\u001b[?2004h")
        mTerminal!!.paste("hi")
        TestCase.assertEquals("\u001b[200~" + "hi" + "\u001b[201~", mOutput.outputAndClear)
        enterString("\u001b[?2004l")
        mTerminal!!.paste("hi")
        TestCase.assertEquals("hi", mOutput.outputAndClear)
    }

    fun testSelectGraphics() {
        withTerminalSized(5, 5)
        enterString("\u001b[31m")
        TestCase.assertEquals(mTerminal!!.mForeColor, 1)
        enterString("\u001b[32m")
        TestCase.assertEquals(mTerminal!!.mForeColor, 2)
        enterString("\u001b[43m")
        TestCase.assertEquals(2, mTerminal!!.mForeColor)
        TestCase.assertEquals(3, mTerminal!!.mBackColor)

        // SGR 0 should reset both foreground and background color.
        enterString("\u001b[0m")
        TestCase.assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, mTerminal!!.mForeColor)
        TestCase.assertEquals(TextStyle.COLOR_INDEX_BACKGROUND, mTerminal!!.mBackColor)

        // 256 colors:
        enterString("\u001b[38;5;119m")
        TestCase.assertEquals(119, mTerminal!!.mForeColor)
        TestCase.assertEquals(TextStyle.COLOR_INDEX_BACKGROUND, mTerminal!!.mBackColor)
        enterString("\u001b[48;5;129m")
        TestCase.assertEquals(119, mTerminal!!.mForeColor)
        TestCase.assertEquals(129, mTerminal!!.mBackColor)

        // Invalid parameter:
        enterString("\u001b[48;8;129m")
        TestCase.assertEquals(119, mTerminal!!.mForeColor)
        TestCase.assertEquals(129, mTerminal!!.mBackColor)

        // Multiple parameters at once:
        enterString("\u001b[38;5;178;48;5;179;m")
        TestCase.assertEquals(178, mTerminal!!.mForeColor)
        TestCase.assertEquals(179, mTerminal!!.mBackColor)

        // 24 bit colors:
        enterString("\u001b[0m") // Reset fg and bg colors.
        enterString("\u001b[38;2;255;127;2m")
        val expectedForeground = -0x1000000 or (255 shl 16) or (127 shl 8) or 2
        TestCase.assertEquals(expectedForeground, mTerminal!!.mForeColor)
        TestCase.assertEquals(TextStyle.COLOR_INDEX_BACKGROUND, mTerminal!!.mBackColor)
        enterString("\u001b[48;2;1;2;254m")
        val expectedBackground = -0x1000000 or (1 shl 16) or (2 shl 8) or 254
        TestCase.assertEquals(expectedForeground, mTerminal!!.mForeColor)
        TestCase.assertEquals(expectedBackground, mTerminal!!.mBackColor)

        // 24 bit colors, set fg and bg at once:
        enterString("\u001b[0m") // Reset fg and bg colors.
        TestCase.assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, mTerminal!!.mForeColor)
        TestCase.assertEquals(TextStyle.COLOR_INDEX_BACKGROUND, mTerminal!!.mBackColor)
        enterString("\u001b[38;2;255;127;2;48;2;1;2;254m")
        TestCase.assertEquals(expectedForeground, mTerminal!!.mForeColor)
        TestCase.assertEquals(expectedBackground, mTerminal!!.mBackColor)

        // 24 bit colors, invalid input:
        enterString("\u001b[38;2;300;127;2;48;2;1;300;254m")
        TestCase.assertEquals(expectedForeground, mTerminal!!.mForeColor)
        TestCase.assertEquals(expectedBackground, mTerminal!!.mBackColor)
    }

    fun testBackgroundColorErase() {
        val rows = 3
        val cols = 3
        withTerminalSized(cols, rows)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val style = getStyleAt(r, c)
                TestCase.assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, decodeForeColor(style))
                TestCase.assertEquals(TextStyle.COLOR_INDEX_BACKGROUND, decodeBackColor(style))
            }
        }
        // Foreground color to 119:
        enterString("\u001b[38;5;119m")
        // Background color to 129:
        enterString("\u001b[48;5;129m")
        // Clear with ED, Erase in Display:
        enterString("\u001b[2J")
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val style = getStyleAt(r, c)
                TestCase.assertEquals(119, decodeForeColor(style))
                TestCase.assertEquals(129, decodeBackColor(style))
            }
        }
        // Background color to 139:
        enterString("\u001b[48;5;139m")
        // Insert two blank lines.
        enterString("\u001b[2L")
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val style = getStyleAt(r, c)
                TestCase.assertEquals(if (r == 0 || r == 1) 139 else 129, decodeBackColor(style))
            }
        }
        withTerminalSized(cols, rows)
        // Background color to 129:
        enterString("\u001b[48;5;129m")
        // Erase two characters, filling them with background color:
        enterString("\u001b[2X")
        TestCase.assertEquals(129, decodeBackColor(getStyleAt(0, 0)))
        TestCase.assertEquals(129, decodeBackColor(getStyleAt(0, 1)))
        TestCase.assertEquals(TextStyle.COLOR_INDEX_BACKGROUND, decodeBackColor(getStyleAt(0, 2)))
    }

    fun testParseColor() {
        TestCase.assertEquals(-0xffff06, parse("#0000FA"))
        TestCase.assertEquals(-0x1000000, parse("#000000"))
        TestCase.assertEquals(-0x1000000, parse("#000"))
        TestCase.assertEquals(-0x1000000, parse("#000000000"))
        TestCase.assertEquals(-0xace791, parse("#53186f"))
        TestCase.assertEquals(-0xff01, parse("rgb:F/0/F"))
        TestCase.assertEquals(-0xffff06, parse("rgb:00/00/FA"))
        TestCase.assertEquals(-0xace791, parse("rgb:53/18/6f"))
        TestCase.assertEquals(0, parse("invalid_0000FA"))
        TestCase.assertEquals(0, parse("#3456"))
    }

    /** The ncurses library still uses this.  */
    fun testLineDrawing() {
        // 016 - shift out / G1. 017 - shift in / G0. "ESC ) 0" - use line drawing for G1
        withTerminalSized(4, 2).enterString("q\u001b)0q\u000eq\u000fq").assertLinesAre("qq─q", "    ")
        // "\0337", saving cursor should save G0, G1 and invoked charset and "ESC 8" should restore.
        withTerminalSized(4, 2).enterString("\u001b)0\u000eqqq\u001b7\u000f\u001b8q").assertLinesAre("────", "    ")
    }

    fun testSoftTerminalReset() {
        // See http://vt100.net/docs/vt510-rm/DECSTR and https://bugs.debian.org/cgi-bin/bugreport.cgi?bug=650304
        // "\033[?7l" is DECRST to disable wrap-around, and DECSTR ("\033[!p") should reset it.
        withTerminalSized(3, 3).enterString("\u001b[?7lABCD").assertLinesAre("ABD", "   ", "   ")
        enterString("\u001b[!pEF").assertLinesAre("ABE", "F  ", "   ")
    }

    fun testBel() {
        withTerminalSized(3, 3)
        TestCase.assertEquals(0, mOutput!!.bellsRung)
        enterString("\u0007")
        TestCase.assertEquals(1, mOutput!!.bellsRung)
        enterString("hello\u0007")
        TestCase.assertEquals(2, mOutput!!.bellsRung)
        enterString("\u0007hello")
        TestCase.assertEquals(3, mOutput!!.bellsRung)
        enterString("hello\u0007world")
        TestCase.assertEquals(4, mOutput!!.bellsRung)
    }

    @Throws(UnsupportedEncodingException::class)
    fun testAutomargins() {
        withTerminalSized(3, 3).enterString("abc").assertLinesAre("abc", "   ", "   ").assertCursorAt(0, 2)
        enterString("d").assertLinesAre("abc", "d  ", "   ").assertCursorAt(1, 1)
        withTerminalSized(3, 3).enterString("abc\r ").assertLinesAre(" bc", "   ", "   ").assertCursorAt(0, 1)
    }

    fun testTab() {
        withTerminalSized(11, 2).enterString("01234567890\r\tXX").assertLinesAre("01234567XX0", "           ")
        withTerminalSized(11, 2).enterString("01234567890\u001b[44m\r\tXX").assertLinesAre("01234567XX0", "           ")
    }
}
