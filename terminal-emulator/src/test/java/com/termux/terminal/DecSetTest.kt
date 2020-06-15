package com.termux.terminal

import junit.framework.TestCase

/**
 * <pre>
 * "CSI ? Pm h", DEC Private Mode Set (DECSET)
</pre> *
 *
 *
 * and
 *
 *
 * <pre>
 * "CSI ? Pm l", DEC Private Mode Reset (DECRST)
</pre> *
 *
 *
 * controls various aspects of the terminal
 */
class DecSetTest : TerminalTestCase() {
    /** DECSET 25, DECTCEM, controls visibility of the cursor.  */
    fun testShowHideCursor() {
        withTerminalSized(3, 3)
        TestCase.assertTrue("Initially the cursor should be visible", mTerminal!!.isShowingCursor)
        enterString("\u001b[?25l") // Hide Cursor (DECTCEM).
        TestCase.assertFalse(mTerminal!!.isShowingCursor)
        enterString("\u001b[?25h") // Show Cursor (DECTCEM).
        TestCase.assertTrue(mTerminal!!.isShowingCursor)
        enterString("\u001b[?25l") // Hide Cursor (DECTCEM), again.
        TestCase.assertFalse(mTerminal!!.isShowingCursor)
        mTerminal!!.reset()
        TestCase.assertTrue("Resetting the terminal should show the cursor", mTerminal!!.isShowingCursor)
        enterString("\u001b[?25l")
        TestCase.assertFalse(mTerminal!!.isShowingCursor)
        enterString("\u001bc") // RIS resetting should reveal cursor.
        TestCase.assertTrue(mTerminal!!.isShowingCursor)
    }

    /** DECSET 2004, controls bracketed paste mode.  */
    fun testBracketedPasteMode() {
        withTerminalSized(3, 3)
        mTerminal!!.paste("a")
        TestCase.assertEquals("Pasting 'a' should output 'a' when bracketed paste mode is disabled", "a", mOutput.outputAndClear)
        enterString("\u001b[?2004h") // Enable bracketed paste mode.
        mTerminal!!.paste("a")
        TestCase.assertEquals("Pasting when in bracketed paste mode should be bracketed", "\u001b[200~a\u001b[201~", mOutput.outputAndClear)
        enterString("\u001b[?2004l") // Disable bracketed paste mode.
        mTerminal!!.paste("a")
        TestCase.assertEquals("Pasting 'a' should output 'a' when bracketed paste mode is disabled", "a", mOutput.outputAndClear)
        enterString("\u001b[?2004h") // Enable bracketed paste mode, again.
        mTerminal!!.paste("a")
        TestCase.assertEquals("Pasting when in bracketed paste mode again should be bracketed", "\u001b[200~a\u001b[201~", mOutput.outputAndClear)
        mTerminal!!.paste("\u001bab\u001bcd\u001b")
        TestCase.assertEquals("Pasting an escape character should not input it", "\u001b[200~abcd\u001b[201~", mOutput.outputAndClear)
        mTerminal!!.paste("\u0081ab\u0081cd\u009F")
        TestCase.assertEquals("Pasting C1 control codes should not input it", "\u001b[200~abcd\u001b[201~", mOutput.outputAndClear)
        mTerminal!!.reset()
        mTerminal!!.paste("a")
        TestCase.assertEquals("Terminal reset() should disable bracketed paste mode", "a", mOutput.outputAndClear)
    }

    /** DECSET 7, DECAWM, controls wraparound mode.  */
    fun testWrapAroundMode() {
        // Default with wraparound:
        withTerminalSized(3, 3).enterString("abcd").assertLinesAre("abc", "d  ", "   ")
        // With wraparound disabled:
        withTerminalSized(3, 3).enterString("\u001b[?7labcd").assertLinesAre("abd", "   ", "   ")
        enterString("efg").assertLinesAre("abg", "   ", "   ")
        // Re-enabling wraparound:
        enterString("\u001b[?7hhij").assertLinesAre("abh", "ij ", "   ")
    }
}
