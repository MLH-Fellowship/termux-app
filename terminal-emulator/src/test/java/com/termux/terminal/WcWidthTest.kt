package com.termux.terminal

import com.termux.terminal.WcWidth.width
import junit.framework.TestCase

class WcWidthTest : TestCase() {
    fun testPrintableAscii() {
        for (i in 0x20..0x7E) {
            assertWidthIs(1, i)
        }
    }

    fun testSomeWidthOne() {
        assertWidthIs(1, 'å'.toInt())
        assertWidthIs(1, 'ä'.toInt())
        assertWidthIs(1, 'ö'.toInt())
        assertWidthIs(1, 0x23F2)
    }

    fun testSomeWide() {
        assertWidthIs(2, 'Ａ'.toInt())
        assertWidthIs(2, 'Ｂ'.toInt())
        assertWidthIs(2, 'Ｃ'.toInt())
        assertWidthIs(2, '中'.toInt())
        assertWidthIs(2, '文'.toInt())
        assertWidthIs(2, 0x679C)
        assertWidthIs(2, 0x679D)
        assertWidthIs(2, 0x2070E)
        assertWidthIs(2, 0x20731)
        assertWidthIs(1, 0x1F781)
    }

    fun testSomeNonWide() {
        assertWidthIs(1, 0x1D11E)
        assertWidthIs(1, 0x1D11F)
    }

    fun testCombining() {
        assertWidthIs(0, 0x0302)
        assertWidthIs(0, 0x0308)
        assertWidthIs(0, 0xFE0F)
    }

    fun testWordJoiner() {
        // https://en.wikipedia.org/wiki/Word_joiner
        // The word joiner (WJ) is a code point in Unicode used to separate words when using scripts
        // that do not use explicit spacing. It is encoded since Unicode version 3.2
        // (released in 2002) as U+2060 WORD JOINER (HTML &#8288;).
        // The word joiner does not produce any space, and prohibits a line break at its position.
        assertWidthIs(0, 0x2060)
    }

    fun testSofthyphen() {
        // http://osdir.com/ml/internationalization.linux/2003-05/msg00006.html:
        // "Existing implementation practice in terminals is that the SOFT HYPHEN is
        // a spacing graphical character, and the purpose of my wcwidth() was to
        // predict the advancement of the cursor position after a string is sent to
        // a terminal. Hence, I have no choice but to keep wcwidth(SOFT HYPHEN) = 1.
        // VT100-style terminals do not hyphenate."
        assertWidthIs(1, 0x00AD)
    }

    fun testHangul() {
        assertWidthIs(1, 0x11A3)
    }

    fun testEmojis() {
        assertWidthIs(2, 0x1F428) // KOALA.
        assertWidthIs(2, 0x231a) // WATCH.
        assertWidthIs(2, 0x1F643) // UPSIDE-DOWN FACE (Unicode 8).
    }

    companion object {
        private fun assertWidthIs(expectedWidth: Int, codePoint: Int) {
            val wcWidth = width(codePoint)
            assertEquals(expectedWidth, wcWidth)
        }
    }
}
