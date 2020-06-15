package com.termux.terminal

import junit.framework.TestCase

class ScreenBufferTest : TerminalTestCase() {
    fun testBasics() {
        val screen = TerminalBuffer(5, 3, 3)
        TestCase.assertEquals("", screen.transcriptText)
        screen.setChar(0, 0, 'a'.toInt(), 0)
        TestCase.assertEquals("a", screen.transcriptText)
        screen.setChar(0, 0, 'b'.toInt(), 0)
        TestCase.assertEquals("b", screen.transcriptText)
        screen.setChar(2, 0, 'c'.toInt(), 0)
        TestCase.assertEquals("b c", screen.transcriptText)
        screen.setChar(2, 2, 'f'.toInt(), 0)
        TestCase.assertEquals("b c\n\n  f", screen.transcriptText)
        screen.blockSet(0, 0, 2, 2, 'X'.toInt(), 0)
    }

    fun testBlockSet() {
        val screen = TerminalBuffer(5, 3, 3)
        screen.blockSet(0, 0, 2, 2, 'X'.toInt(), 0)
        TestCase.assertEquals("XX\nXX", screen.transcriptText)
        screen.blockSet(1, 1, 2, 2, 'Y'.toInt(), 0)
        TestCase.assertEquals("XX\nXYY\n YY", screen.transcriptText)
    }

    fun testGetSelectedText() {
        withTerminalSized(5, 3).enterString("ABCDEFGHIJ").assertLinesAre("ABCDE", "FGHIJ", "     ")
        TestCase.assertEquals("AB", mTerminal!!.getSelectedText(0, 0, 1, 0))
        TestCase.assertEquals("BC", mTerminal!!.getSelectedText(1, 0, 2, 0))
        TestCase.assertEquals("CDE", mTerminal!!.getSelectedText(2, 0, 4, 0))
        TestCase.assertEquals("FG", mTerminal!!.getSelectedText(0, 1, 1, 1))
        TestCase.assertEquals("GH", mTerminal!!.getSelectedText(1, 1, 2, 1))
        TestCase.assertEquals("HIJ", mTerminal!!.getSelectedText(2, 1, 4, 1))
        TestCase.assertEquals("ABCDEFG", mTerminal!!.getSelectedText(0, 0, 1, 1))
        withTerminalSized(5, 3).enterString("ABCDE\r\nFGHIJ").assertLinesAre("ABCDE", "FGHIJ", "     ")
        TestCase.assertEquals("ABCDE\nFG", mTerminal!!.getSelectedText(0, 0, 1, 1))
    }

    fun testGetSelectedTextJoinFullLines() {
        withTerminalSized(5, 3).enterString("ABCDE\r\nFG")
        TestCase.assertEquals("ABCDEFG", mTerminal!!.screen.getSelectedText(0, 0, 1, 1, true, true))
        withTerminalSized(5, 3).enterString("ABC\r\nFG")
        TestCase.assertEquals("ABC\nFG", mTerminal!!.screen.getSelectedText(0, 0, 1, 1, true, true))
    }
}
