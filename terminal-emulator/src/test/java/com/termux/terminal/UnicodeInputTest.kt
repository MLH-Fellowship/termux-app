package com.termux.terminal

import junit.framework.TestCase
import java.io.UnsupportedEncodingException

class UnicodeInputTest : TerminalTestCase() {
    @Throws(Exception::class)
    fun testIllFormedUtf8SuccessorByteNotConsumed() {
        // The Unicode Standard Version 6.2 – Core Specification (http://www.unicode.org/versions/Unicode6.2.0/ch03.pdf):
        // "If the converter encounters an ill-formed UTF-8 code unit sequence which starts with a valid first byte, but which does not
        // continue with valid successor bytes (see Table 3-7), it must not consume the successor bytes as part of the ill-formed
        // subsequence whenever those successor bytes themselves constitute part of a well-formed UTF-8 code unit subsequence."
        withTerminalSized(5, 5)
        mTerminal!!.append(byteArrayOf(239.toByte(), 'a'.toByte()), 2)
        assertLineIs(0, TerminalEmulator.UNICODE_REPLACEMENT_CHAR.toChar().toString() + "a   ")

        // https://code.google.com/p/chromium/issues/detail?id=212704
        var input = byteArrayOf(
            0x61.toByte(), 0xF1.toByte(),
            0x80.toByte(), 0x80.toByte(),
            0xe1.toByte(), 0x80.toByte(),
            0xc2.toByte(), 0x62.toByte(),
            0x80.toByte(), 0x63.toByte(),
            0x80.toByte(), 0xbf.toByte(),
            0x64.toByte()
        )
        withTerminalSized(10, 2)
        mTerminal!!.append(input, input.size)
        assertLinesAre("a\uFFFD\uFFFD\uFFFDb\uFFFDc\uFFFD\uFFFDd", "          ")

        // Surrogate pairs.
        withTerminalSized(5, 2)
        input = byteArrayOf(
            0xed.toByte(), 0xa0.toByte(),
            0x80.toByte(), 0xed.toByte(),
            0xad.toByte(), 0xbf.toByte(),
            0xed.toByte(), 0xae.toByte(),
            0x80.toByte(), 0xed.toByte(),
            0xbf.toByte(), 0xbf.toByte()
        )
        mTerminal!!.append(input, input.size)
        assertLinesAre("\uFFFD\uFFFD\uFFFD\uFFFD ", "     ")

        // https://bugzilla.mozilla.org/show_bug.cgi?id=746900: "with this patch 0xe0 0x80 is decoded as two U+FFFDs,
        // but 0xe0 0xa0 is decoded as a single U+FFFD, and this is correct according to the "Best Practices", but IE
        // and Chrome (Version 22.0.1229.94) decode both of them as two U+FFFDs. Opera 12.11 decodes both of them as
        // one U+FFFD".
        withTerminalSized(5, 2)
        input = byteArrayOf(0xe0.toByte(), 0xa0.toByte(), ' '.toByte())
        mTerminal!!.append(input, input.size)
        assertLinesAre("\uFFFD    ", "     ")

        // withTerminalSized(5, 2);
        // input = new byte[]{(byte) 0xe0, (byte) 0x80, 'a'};
        // mTerminal.append(input, input.length);
        // assertLinesAre("\uFFFD\uFFFDa  ", "     ");
    }

    @Throws(UnsupportedEncodingException::class)
    fun testUnassignedCodePoint() {
        withTerminalSized(3, 3)
        // UTF-8 for U+C2541, an unassigned code point:
        val b = byteArrayOf(0xf3.toByte(), 0x82.toByte(), 0x95.toByte(), 0x81.toByte())
        mTerminal!!.append(b, b.size)
        enterString("Y")
        TestCase.assertEquals(1, Character.charCount(TerminalEmulator.UNICODE_REPLACEMENT_CHAR))
        assertLineStartsWith(0, TerminalEmulator.UNICODE_REPLACEMENT_CHAR, 'Y'.toInt(), ' '.toInt())
    }

    fun testStuff() {
        withTerminalSized(80, 24)
        val b = byteArrayOf(0xf3.toByte(), 0x82.toByte(), 0x95.toByte(), 0x81.toByte(), 0x61.toByte(), 0x38.toByte(), 0xe7.toByte(), 0x8f.toByte(),
            0xae.toByte(), 0xc2.toByte(), 0x9f.toByte(), 0xe8.toByte(), 0xa0.toByte(), 0x9f.toByte(), 0xe8.toByte(), 0x8c.toByte(), 0xa4.toByte(),
            0xed.toByte(), 0x93.toByte(), 0x89.toByte(), 0xef.toByte(), 0xbf.toByte(), 0xbd.toByte(), 0x42.toByte(), 0xc2.toByte(), 0x9b.toByte(),
            0xe6.toByte(), 0x87.toByte(), 0x89.toByte(), 0x5a.toByte())
        mTerminal!!.append(b, b.size)
    }

    @Throws(Exception::class)
    fun testSimpleCombining() {
        withTerminalSized(3, 2).enterString(" a\u0302 ").assertLinesAre(" a\u0302 ", "   ")
    }

    @Throws(Exception::class)
    fun testCombiningCharacterInFirstColumn() {
        withTerminalSized(5, 3).enterString("test\r\nhi\r\n").assertLinesAre("test ", "hi   ", "     ")

        // U+0302 is COMBINING CIRCUMFLEX ACCENT. Test case from mosh (http://mosh.mit.edu/).
        withTerminalSized(5, 5).enterString("test\r\nabc\r\n\u0302\r\ndef\r\n")
        assertLinesAre("test ", "abc  ", " \u0302    ", "def  ", "     ")
    }

    @Throws(Exception::class)
    fun testCombiningCharacterInLastColumn() {
        withTerminalSized(3, 2).enterString("  a\u0302").assertLinesAre("  a\u0302", "   ")
        withTerminalSized(3, 2).enterString("  à̲").assertLinesAre("  à̲", "   ")
        withTerminalSized(3, 2).enterString("Aà̲F").assertLinesAre("Aà̲F", "   ")
    }

    @Throws(Exception::class)
    fun testWideCharacterInLastColumn() {
        withTerminalSized(3, 2).enterString("  枝\u0302").assertLinesAre("   ", "枝\u0302 ")
        withTerminalSized(3, 2).enterString(" 枝").assertLinesAre(" 枝", "   ").assertCursorAt(0, 2)
        enterString("a").assertLinesAre(" 枝", "a  ")
    }

    @Throws(Exception::class)
    fun testWideCharacterDeletion() {
        // CSI Ps D Cursor Backward Ps Times
        withTerminalSized(3, 2).enterString("枝\u001b[Da").assertLinesAre(" a ", "   ")
        withTerminalSized(3, 2).enterString("枝\u001b[2Da").assertLinesAre("a  ", "   ")
        withTerminalSized(3, 2).enterString("枝\u001b[2D枝").assertLinesAre("枝 ", "   ")
        withTerminalSized(3, 2).enterString("枝\u001b[1D枝").assertLinesAre(" 枝", "   ")
        withTerminalSized(5, 2).enterString(" 枝 \u001b[Da").assertLinesAre(" 枝a ", "     ")
        withTerminalSized(5, 2).enterString("a \u001b[D\u0302").assertLinesAre("a\u0302    ", "     ")
        withTerminalSized(5, 2).enterString("枝 \u001b[D\u0302").assertLinesAre("枝\u0302   ", "     ")
        enterString("Z").assertLinesAre("枝\u0302Z  ", "     ")
        enterString("\u001b[D ").assertLinesAre("枝\u0302   ", "     ")
        // Go back two columns, standing at the second half of the wide character:
        enterString("\u001b[2DU").assertLinesAre(" U   ", "     ")
    }

    fun testWideCharOverwriting() {
        withTerminalSized(3, 2).enterString("abc\u001b[3D枝").assertLinesAre("枝c", "   ")
    }

    @Throws(Exception::class)
    fun testOverlongUtf8Encoding() {
        // U+0020 should be encoded as 0x20, 0xc0 0xa0 is an overlong encoding
        // so should be replaced with the replacement char U+FFFD.
        withTerminalSized(5, 5).mTerminal!!.append(byteArrayOf(0xc0.toByte(), 0xa0.toByte(), 'Y'.toByte()), 3)
        assertLineIs(0, "\uFFFDY   ")
    }

    @Throws(Exception::class)
    fun testWideCharacterWithoutWrapping() {
        // With wraparound disabled. The behaviour when a wide character is output with cursor in
        // the last column when autowrap is disabled is not obvious, but we expect the wide
        // character to be ignored here.
        withTerminalSized(3, 3).enterString("\u001b[?7l").enterString("枝枝枝").assertLinesAre("枝 ", "   ", "   ")
        enterString("a枝").assertLinesAre("枝a", "   ", "   ")
    }
}
