package com.termux.terminal

import android.util.Base64
import junit.framework.TestCase
import java.util.*

/** "ESC ]" is the Operating System Command.  */
class OperatingSystemControlTest : TerminalTestCase() {
    @Throws(Exception::class)
    fun testSetTitle() {
        val expectedTitleChanges: MutableList<ChangedTitle> = ArrayList()
        withTerminalSized(10, 10)
        enterString("\u001b]0;Hello, world\u0007")
        TestCase.assertEquals("Hello, world", mTerminal!!.title)
        expectedTitleChanges.add(ChangedTitle(null, "Hello, world"))
        TestCase.assertEquals(expectedTitleChanges, mOutput!!.titleChanges)
        enterString("\u001b]0;Goodbye, world\u0007")
        TestCase.assertEquals("Goodbye, world", mTerminal!!.title)
        expectedTitleChanges.add(ChangedTitle("Hello, world", "Goodbye, world"))
        TestCase.assertEquals(expectedTitleChanges, mOutput!!.titleChanges)
        enterString("\u001b]0;Goodbye, \u00F1 world\u0007")
        TestCase.assertEquals("Goodbye, \uu00F1 world", mTerminal!!.title)
        expectedTitleChanges.add(ChangedTitle("Goodbye, world", "Goodbye, \uu00F1 world"))
        TestCase.assertEquals(expectedTitleChanges, mOutput!!.titleChanges)

        // 2 should work as well (0 sets both title and icon).
        enterString("\u001b]2;Updated\u0007")
        TestCase.assertEquals("Updated", mTerminal!!.title)
        expectedTitleChanges.add(ChangedTitle("Goodbye, \uu00F1 world", "Updated"))
        TestCase.assertEquals(expectedTitleChanges, mOutput!!.titleChanges)
        enterString("\u001b[22;0t")
        enterString("\u001b]0;FIRST\u0007")
        expectedTitleChanges.add(ChangedTitle("Updated", "FIRST"))
        TestCase.assertEquals("FIRST", mTerminal!!.title)
        TestCase.assertEquals(expectedTitleChanges, mOutput!!.titleChanges)
        enterString("\u001b[22;0t")
        enterString("\u001b]0;SECOND\u0007")
        TestCase.assertEquals("SECOND", mTerminal!!.title)
        expectedTitleChanges.add(ChangedTitle("FIRST", "SECOND"))
        TestCase.assertEquals(expectedTitleChanges, mOutput!!.titleChanges)
        enterString("\u001b[23;0t")
        TestCase.assertEquals("FIRST", mTerminal!!.title)
        expectedTitleChanges.add(ChangedTitle("SECOND", "FIRST"))
        TestCase.assertEquals(expectedTitleChanges, mOutput!!.titleChanges)
        enterString("\u001b[23;0t")
        expectedTitleChanges.add(ChangedTitle("FIRST", "Updated"))
        TestCase.assertEquals(expectedTitleChanges, mOutput!!.titleChanges)
        enterString("\u001b[22;0t")
        enterString("\u001b[22;0t")
        enterString("\u001b[22;0t")
        // Popping to same title should not cause changes.
        enterString("\u001b[23;0t")
        enterString("\u001b[23;0t")
        enterString("\u001b[23;0t")
        TestCase.assertEquals(expectedTitleChanges, mOutput!!.titleChanges)
    }

    @Throws(Exception::class)
    fun testTitleStack() {
        // echo -ne '\e]0;BEFORE\007' # set title
        // echo -ne '\e[22t' # push to stack
        // echo -ne '\e]0;AFTER\007' # set new title
        // echo -ne '\e[23t' # retrieve from stack
        withTerminalSized(10, 10)
        enterString("\u001b]0;InitialTitle\u0007")
        TestCase.assertEquals("InitialTitle", mTerminal!!.title)
        enterString("\u001b[22t")
        TestCase.assertEquals("InitialTitle", mTerminal!!.title)
        enterString("\u001b]0;UpdatedTitle\u0007")
        TestCase.assertEquals("UpdatedTitle", mTerminal!!.title)
        enterString("\u001b[23t")
        TestCase.assertEquals("InitialTitle", mTerminal!!.title)
        enterString("\u001b[23t\u001b[23t\u001b[23t")
        TestCase.assertEquals("InitialTitle", mTerminal!!.title)
    }

    @Throws(Exception::class)
    fun testSetColor() {
        // "OSC 4; $INDEX; $COLORSPEC BEL" => Change color $INDEX to the color specified by $COLORSPEC.
        withTerminalSized(4, 4).enterString("\u001b]4;5;#00FF00\u0007")
        TestCase.assertEquals(Integer.toHexString(-0xff0100), Integer.toHexString(mTerminal!!.mColors.mCurrentColors[5]))
        enterString("\u001b]4;5;#00FFAB\u0007")
        TestCase.assertEquals(mTerminal!!.mColors.mCurrentColors[5], -0xff0055)
        enterString("\u001b]4;255;#ABFFAB\u0007")
        TestCase.assertEquals(mTerminal!!.mColors.mCurrentColors[255], -0x540055)
        // Two indexed colors at once:
        enterString("\u001b]4;7;#00FF00;8;#0000FF\u0007")
        TestCase.assertEquals(mTerminal!!.mColors.mCurrentColors[7], -0xff0100)
        TestCase.assertEquals(mTerminal!!.mColors.mCurrentColors[8], -0xffff01)
    }

    fun assertIndexColorsMatch(expected: IntArray) {
        for (i in 0..254) TestCase.assertEquals("index=$i", expected[i], mTerminal!!.mColors.mCurrentColors[i])
    }

    @Throws(Exception::class)
    fun testResetColor() {
        withTerminalSized(4, 4)
        val initialColors = IntArray(TextStyle.NUM_INDEXED_COLORS)
        System.arraycopy(mTerminal!!.mColors.mCurrentColors, 0, initialColors, 0, initialColors.size)
        val expectedColors = IntArray(initialColors.size)
        System.arraycopy(mTerminal!!.mColors.mCurrentColors, 0, expectedColors, 0, expectedColors.size)
        val rand = Random()
        for (endType in 0..2) {
            // Both BEL (7) and ST (ESC \) can end an OSC sequence.
            val ender = if (endType == 0) "\u0007" else "\u001b\\"
            for (i in 0..254) {
                expectedColors[i] = -0x1000000 + (rand.nextInt() and 0xFFFFFF)
                val r = expectedColors[i] shr 16 and 0xFF
                val g = expectedColors[i] shr 8 and 0xFF
                val b = expectedColors[i] and 0xFF
                val rgbHex = String.format("%02x", r) + String.format("%02x", g) + String.format("%02x", b)
                enterString("\u001b]4;$i;#$rgbHex$ender")
                TestCase.assertEquals(expectedColors[i], mTerminal!!.mColors.mCurrentColors[i])
            }
        }
        enterString("\u001b]104;0\u0007")
        expectedColors[0] = TerminalColors.COLOR_SCHEME.mDefaultColors[0]
        assertIndexColorsMatch(expectedColors)
        enterString("\u001b]104;1;2\u0007")
        expectedColors[1] = TerminalColors.COLOR_SCHEME.mDefaultColors[1]
        expectedColors[2] = TerminalColors.COLOR_SCHEME.mDefaultColors[2]
        assertIndexColorsMatch(expectedColors)
        enterString("\u001b]104\u0007") // Reset all colors.
        assertIndexColorsMatch(TerminalColors.COLOR_SCHEME.mDefaultColors)
    }

    fun disabledTestSetClipboard() {
        // Cannot run this as a unit test since Base64 is a android.util class.
        enterString("\u001b]52;c;" + Base64.encodeToString("Hello, world".toByteArray(), 0) + "\u0007")
    }

    @Throws(Exception::class)
    fun testResettingTerminalResetsColor() {
        // "OSC 4; $INDEX; $COLORSPEC BEL" => Change color $INDEX to the color specified by $COLORSPEC.
        withTerminalSized(4, 4).enterString("\u001b]4;5;#00FF00\u0007")
        enterString("\u001b]4;5;#00FFAB\u0007").assertColor(5, -0xff0055)
        enterString("\u001b]4;255;#ABFFAB\u0007").assertColor(255, -0x540055)
        mTerminal!!.reset()
        assertIndexColorsMatch(TerminalColors.COLOR_SCHEME.mDefaultColors)
    }

    fun testSettingDynamicColors() {
        // "${OSC}${DYNAMIC};${COLORSPEC}${BEL_OR_STRINGTERMINATOR}" => Change ${DYNAMIC} color to the color specified by $COLORSPEC where:
        // DYNAMIC=10: Text foreground color.
        // DYNAMIC=11: Text background color.
        // DYNAMIC=12: Text cursor color.
        withTerminalSized(3, 3).enterString("\u001b]10;#ABCD00\u0007").assertColor(TextStyle.COLOR_INDEX_FOREGROUND, -0x543300)
        enterString("\u001b]11;#0ABCD0\u0007").assertColor(TextStyle.COLOR_INDEX_BACKGROUND, -0xf54330)
        enterString("\u001b]12;#00ABCD\u0007").assertColor(TextStyle.COLOR_INDEX_CURSOR, -0xff5433)
        // Two special colors at once
        // ("Each successive parameter changes the next color in the list. The value of P s tells the starting point in the list"):
        enterString("\u001b]10;#FF0000;#00FF00\u0007").assertColor(TextStyle.COLOR_INDEX_FOREGROUND, -0x10000)
        assertColor(TextStyle.COLOR_INDEX_BACKGROUND, -0xff0100)
        // Three at once:
        enterString("\u001b]10;#0000FF;#00FF00;#FF0000\u0007").assertColor(TextStyle.COLOR_INDEX_FOREGROUND, -0xffff01)
        assertColor(TextStyle.COLOR_INDEX_BACKGROUND, -0xff0100).assertColor(TextStyle.COLOR_INDEX_CURSOR, -0x10000)

        // Without ending semicolon:
        enterString("\u001b]10;#FF0000\u0007").assertColor(TextStyle.COLOR_INDEX_FOREGROUND, -0x10000)
        // For background and cursor:
        enterString("\u001b]11;#FFFF00;\u0007").assertColor(TextStyle.COLOR_INDEX_BACKGROUND, -0x100)
        enterString("\u001b]12;#00FFFF;\u0007").assertColor(TextStyle.COLOR_INDEX_CURSOR, -0xff0001)

        // Using string terminator:
        val stringTerminator = "\u001b\\"
        enterString("\u001b]10;#FF0000$stringTerminator").assertColor(TextStyle.COLOR_INDEX_FOREGROUND, -0x10000)
        // For background and cursor:
        enterString("\u001b]11;#FFFF00;$stringTerminator").assertColor(TextStyle.COLOR_INDEX_BACKGROUND, -0x100)
        enterString("\u001b]12;#00FFFF;$stringTerminator").assertColor(TextStyle.COLOR_INDEX_CURSOR, -0xff0001)
    }

    fun testReportSpecialColors() {
        // "${OSC}${DYNAMIC};?${BEL}" => Terminal responds with the control sequence which would set the current color.
        // Both xterm and libvte (gnome-terminal and others) use the longest color representation, which means that
        // the response is "${OSC}rgb:RRRR/GGGG/BBBB"
        withTerminalSized(3, 3).enterString("\u001b]10;#ABCD00\u0007").assertColor(TextStyle.COLOR_INDEX_FOREGROUND, -0x543300)
        assertEnteringStringGivesResponse("\u001b]10;?\u0007", "\u001b]10;rgb:abab/cdcd/0000\u0007")
        // Same as above but with string terminator. xterm uses the same string terminator in the response, which
        // e.g. script posted at http://superuser.com/questions/157563/programmatic-access-to-current-xterm-background-color
        // relies on:
        assertEnteringStringGivesResponse("\u001b]10;?\u001b\\", "\u001b]10;rgb:abab/cdcd/0000\u001b\\")
    }
}
