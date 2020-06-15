package com.termux.terminal

import com.termux.terminal.TextStyle.decodeEffect
import com.termux.terminal.TextStyle.decodeForeColor
import com.termux.terminal.WcWidth.width
import junit.framework.AssertionFailedError
import junit.framework.TestCase
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.*

abstract class TerminalTestCase : TestCase() {
    class MockTerminalOutput : TerminalOutput() {
        val baos = ByteArrayOutputStream()
        val titleChanges: MutableList<ChangedTitle> = ArrayList()
        val clipboardPuts: MutableList<String?> = ArrayList()
        var bellsRung = 0
        var colorsChanged = 0
        override fun write(data: ByteArray, offset: Int, count: Int) {
            baos.write(data, offset, count)
        }

        val outputAndClear: String
            get() {
                val result = String(baos.toByteArray(), StandardCharsets.UTF_8)
                baos.reset()
                return result
            }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            titleChanges.add(ChangedTitle(oldTitle, newTitle))
        }

        override fun clipboardText(text: String?) {
            clipboardPuts.add(text)
        }

        override fun onBell() {
            bellsRung++
        }

        override fun onColorsChanged() {
            colorsChanged++
        }
    }

    var mTerminal: TerminalEmulator? = null
    var mOutput: MockTerminalOutput? = null

    class ChangedTitle(val oldTitle: String?, val newTitle: String?) {
        override fun equals(o: Any?): Boolean {
            if (o !is ChangedTitle) return false
            val other = o
            return oldTitle == other.oldTitle && newTitle == other.newTitle
        }

        override fun hashCode(): Int {
            return Objects.hash(oldTitle, newTitle)
        }

        override fun toString(): String {
            return "ChangedTitle[oldTitle=$oldTitle, newTitle=$newTitle]"
        }

    }

    fun enterString(s: String): TerminalTestCase {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        mTerminal!!.append(bytes, bytes.size)
        assertInvariants()
        return this
    }

    fun assertEnteringStringGivesResponse(input: String, expectedResponse: String?) {
        enterString(input)
        val response = mOutput!!.outputAndClear
        assertEquals(expectedResponse, response)
    }

    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
        mOutput = MockTerminalOutput()
    }

    protected fun withTerminalSized(columns: Int, rows: Int): TerminalTestCase {
        mTerminal = TerminalEmulator(mOutput!!, columns, rows, rows * 2)
        return this
    }

    fun assertHistoryStartsWith(vararg rows: String) {
        assertTrue("About to check " + rows.size + " lines, but only " + mTerminal!!.screen.activeTranscriptRows + " in history",
            mTerminal!!.screen.activeTranscriptRows >= rows.size)
        for (i in 0 until rows.size) {
            assertLineIs(-i - 1, rows[i])
        }
    }

    private class LineWrapper(val mLine: TerminalRow?) {
        override fun hashCode(): Int {
            return System.identityHashCode(mLine)
        }

        override fun equals(o: Any?): Boolean {
            return o is LineWrapper && o.mLine == mLine
        }

    }

    protected fun assertInvariants(): TerminalTestCase {
        val screen = mTerminal!!.screen
        val lines = screen.mLines
        val linesSet: MutableSet<LineWrapper> = HashSet()
        for (i in lines.indices) {
            if (lines[i] == null) continue
            assertTrue("Line exists at multiple places: $i", linesSet.add(LineWrapper(lines[i])))
            val text = lines[i]!!.mText
            val usedChars = lines[i]!!.spaceUsed
            var currentColumn = 0
            var j = 0
            while (j < usedChars) {
                val c = text[j]
                var codePoint: Int
                codePoint = if (Character.isHighSurrogate(c)) {
                    val lowSurrogate = text[++j]
                    assertTrue("High surrogate without following low surrogate", Character.isLowSurrogate(lowSurrogate))
                    Character.toCodePoint(c, lowSurrogate)
                } else {
                    assertFalse("Low surrogate without preceding high surrogate", Character.isLowSurrogate(c))
                    c.toInt()
                }
                assertFalse("Screen should never contain unassigned characters", Character.getType(codePoint) == Character.UNASSIGNED.toInt())
                val width = width(codePoint)
                assertFalse("The first column should not start with combining character", currentColumn == 0 && width < 0)
                if (width > 0) currentColumn += width
                j++
            }
            assertEquals("Line whose width does not match screens. line=" + String(lines[i]!!.mText, 0, lines[i]!!.spaceUsed),
                screen.mColumns, currentColumn)
        }
        assertEquals("The alt buffer should have have no history", mTerminal!!.mAltBuffer.mTotalRows, mTerminal!!.mAltBuffer.mScreenRows)
        if (mTerminal!!.isAlternateBufferActive) {
            assertEquals("The alt buffer should be the same size as the screen", mTerminal!!.mRows, mTerminal!!.mAltBuffer.mTotalRows)
        }
        return this
    }

    protected fun assertLineIs(line: Int, expected: String) {
        val l = mTerminal!!.screen.allocateFullLineIfNecessary(mTerminal!!.screen.externalToInternalRow(line))
        val chars = l.mText
        val textLen = l.spaceUsed
        if (textLen != expected.length) fail("Expected '" + expected + "' (len=" + expected.length + "), was='"
            + String(chars, 0, textLen) + "' (len=" + textLen + ")")
        for (i in 0 until textLen) {
            if (expected[i] != chars[i]) fail("Expected '" + expected + "', was='" + String(chars, 0, textLen) + "' - first different at index=" + i)
        }
    }

    fun assertLinesAre(vararg lines: String): TerminalTestCase {
        assertEquals(lines.size, mTerminal!!.screen.mScreenRows)
        for (i in 0 until lines.size) try {
            assertLineIs(i, lines[i])
        } catch (e: AssertionFailedError) {
            throw AssertionFailedError("Line: " + i + " - " + e.message)
        }
        return this
    }

    fun resize(cols: Int, rows: Int): TerminalTestCase {
        mTerminal!!.resize(cols, rows)
        assertInvariants()
        return this
    }

    fun assertLineWraps(vararg lines: Boolean): TerminalTestCase {
        for (i in 0 until lines.size) assertEquals("line=$i", lines[i], mTerminal!!.screen.mLines[mTerminal!!.screen.externalToInternalRow(i)]!!.mLineWrap)
        return this
    }

    protected fun assertLineStartsWith(line: Int, vararg codePoints: Int): TerminalTestCase {
        val chars = mTerminal!!.screen.mLines[mTerminal!!.screen.externalToInternalRow(line)]!!.mText
        var charIndex = 0
        for (i in 0 until codePoints.size) {
            var lineCodePoint = chars[charIndex++].toInt()
            if (Character.isHighSurrogate(lineCodePoint.toChar())) {
                lineCodePoint = Character.toCodePoint(lineCodePoint.toChar(), chars[charIndex++])
            }
            assertEquals("Differing a code point index=$i", codePoints[i], lineCodePoint)
        }
        return this
    }

    fun placeCursorAndAssert(row: Int, col: Int): TerminalTestCase {
        // +1 due to escape sequence being one based.
        enterString("\u001b[" + (row + 1) + ";" + (col + 1) + "H")
        assertCursorAt(row, col)
        return this
    }

    fun assertCursorAt(row: Int, col: Int): TerminalTestCase {
        val actualRow = mTerminal!!.cursorRow
        val actualCol = mTerminal!!.cursorCol
        if (!(row == actualRow && col == actualCol)) fail("Expected cursor at (row,col)=($row, $col) but was ($actualRow, $actualCol)")
        return this
    }

    /** For testing only. Encoded style according to [TextStyle].  */
    fun getStyleAt(externalRow: Int, column: Int): Long {
        return mTerminal!!.screen.getStyleAt(externalRow, column)
    }

    class EffectLine(val styles: IntArray)

    protected fun effectLine(vararg bits: Int): EffectLine {
        return EffectLine(bits)
    }

    fun assertEffectAttributesSet(vararg lines: EffectLine): TerminalTestCase {
        assertEquals(lines.size, mTerminal!!.screen.mScreenRows)
        for (i in 0 until lines.size) {
            val line = lines[i].styles
            for (j in line.indices) {
                val effectsAtCell = decodeEffect(getStyleAt(i, j))
                val attributes = line[j]
                if (effectsAtCell and attributes != attributes) fail("Line=" + i + ", column=" + j + ", expected "
                    + describeStyle(attributes) + " set, was " + describeStyle(effectsAtCell))
            }
        }
        return this
    }

    fun assertForegroundIndices(vararg lines: EffectLine): TerminalTestCase {
        assertEquals(lines.size, mTerminal!!.screen.mScreenRows)
        for (i in 0 until lines.size) {
            val line = lines[i].styles
            for (j in line.indices) {
                val actualColor = decodeForeColor(getStyleAt(i, j))
                val expectedColor = line[j]
                if (actualColor != expectedColor) fail("Line=" + i + ", column=" + j + ", expected color "
                    + Integer.toHexString(expectedColor) + " set, was " + Integer.toHexString(actualColor))
            }
        }
        return this
    }

    fun assertForegroundColorAt(externalRow: Int, column: Int, color: Int) {
        val style = mTerminal!!.screen.mLines[mTerminal!!.screen.externalToInternalRow(externalRow)]!!.getStyle(column)
        assertEquals(color, decodeForeColor(style))
    }

    fun assertColor(colorIndex: Int, expected: Int): TerminalTestCase {
        val actual = mTerminal!!.mColors.mCurrentColors[colorIndex]
        if (expected != actual) {
            fail("Color index=" + colorIndex + ", expected=" + Integer.toHexString(expected) + ", was=" + Integer.toHexString(actual))
        }
        return this
    }

    companion object {
        private fun describeStyle(styleBits: Int): String {
            return ("'" + (if (styleBits and TextStyle.CHARACTER_ATTRIBUTE_BLINK != 0) ":BLINK:" else "")
                + (if (styleBits and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0) ":BOLD:" else "")
                + (if (styleBits and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0) ":INVERSE:" else "")
                + (if (styleBits and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE != 0) ":INVISIBLE:" else "")
                + (if (styleBits and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0) ":ITALIC:" else "")
                + (if (styleBits and TextStyle.CHARACTER_ATTRIBUTE_PROTECTED != 0) ":PROTECTED:" else "")
                + (if (styleBits and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH != 0) ":STRIKETHROUGH:" else "")
                + (if (styleBits and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0) ":UNDERLINE:" else "") + "'")
        }
    }
}
