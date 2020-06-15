package com.termux.terminal

import java.util.*

/**
 * A circular buffer of [TerminalRow]:s which keeps notes about what is visible on a logical screen and the scroll
 * history.
 *
 *
 * See [.externalToInternalRow] for how to map from logical screen rows to array indices.
 */
class TerminalBuffer constructor(var mColumns: Int,
                                 /** The length of [.mLines].  */
                                 var mTotalRows: Int,
                                 /** The number of rows and columns visible on the screen.  */
                                 var mScreenRows: Int) {
    @JvmField
    var mLines: Array<TerminalRow?>

    /** The number of rows kept in history.  */
    var activeTranscriptRows: Int = 0
        private set

    /** The index in the circular buffer where the visible screen starts.  */
    private var mScreenFirstRow: Int = 0
    val transcriptText: String
        get() {
            return getSelectedText(0, -activeTranscriptRows, mColumns, mScreenRows).trim({ it <= ' ' })
        }

    val transcriptTextWithoutJoinedLines: String
        get() {
            return getSelectedText(0, -activeTranscriptRows, mColumns, mScreenRows, false).trim({ it <= ' ' })
        }

    val transcriptTextWithFullLinesJoined: String
        get() {
            return getSelectedText(0, -activeTranscriptRows, mColumns, mScreenRows, true, true).trim({ it <= ' ' })
        }

    fun getSelectedText(selX1: Int, selY1: Int, selX2: Int, selY2: Int): String {
        return getSelectedText(selX1, selY1, selX2, selY2, true)
    }

    fun getSelectedText(selX1: Int, selY1: Int, selX2: Int, selY2: Int, joinBackLines: Boolean): String {
        return getSelectedText(selX1, selY1, selX2, selY2, true, false)
    }

    fun getSelectedText(selX1: Int, selY1: Int, selX2: Int, selY2: Int, joinBackLines: Boolean, joinFullLines: Boolean): String {
        var selY1: Int = selY1
        var selY2: Int = selY2
        val builder: StringBuilder = StringBuilder()
        val columns: Int = mColumns
        if (selY1 < -activeTranscriptRows) selY1 = -activeTranscriptRows
        if (selY2 >= mScreenRows) selY2 = mScreenRows - 1
        for (row in selY1..selY2) {
            val x1: Int = if ((row == selY1)) selX1 else 0
            var x2: Int
            if (row == selY2) {
                x2 = selX2 + 1
                if (x2 > columns) x2 = columns
            } else {
                x2 = columns
            }
            val lineObject: TerminalRow? = mLines.get(externalToInternalRow(row))
            val x1Index: Int = lineObject!!.findStartOfColumn(x1)
            var x2Index: Int = if ((x2 < mColumns)) lineObject.findStartOfColumn(x2) else lineObject.getSpaceUsed()
            if (x2Index == x1Index) {
                // Selected the start of a wide character.
                x2Index = lineObject.findStartOfColumn(x2 + 1)
            }
            val line: CharArray? = lineObject.mText
            var lastPrintingCharIndex: Int = -1
            var i: Int
            val rowLineWrap: Boolean = getLineWrap(row)
            if (rowLineWrap && x2 == columns) {
                // If the line was wrapped, we shouldn't lose trailing space:
                lastPrintingCharIndex = x2Index - 1
            } else {
                i = x1Index
                while (i < x2Index) {
                    val c: Char = line!!.get(i)
                    if (c != ' ') lastPrintingCharIndex = i
                    ++i
                }
            }
            if (lastPrintingCharIndex != -1) builder.append(line, x1Index, lastPrintingCharIndex - x1Index + 1)
            val lineFillsWidth: Boolean = lastPrintingCharIndex == x2Index - 1
            if (((!joinBackLines || !rowLineWrap) && (!joinFullLines || !lineFillsWidth)
                    && (row < selY2) && (row < mScreenRows - 1))) builder.append('\n')
        }
        return builder.toString()
    }

    val activeRows: Int
        get() {
            return activeTranscriptRows + mScreenRows
        }

    /**
     * Convert a row value from the public external coordinate system to our internal private coordinate system.
     *
     * <pre>
     * - External coordinate system: -mActiveTranscriptRows to mScreenRows-1, with the screen being 0..mScreenRows-1.
     * - Internal coordinate system: the mScreenRows lines starting at mScreenFirstRow comprise the screen, while the
     * mActiveTranscriptRows lines ending at mScreenFirstRow-1 form the transcript (as a circular buffer).
     *
     * External ↔ Internal:
     *
     * [ ...                            ]     [ ...                                     ]
     * [ -mActiveTranscriptRows         ]     [ mScreenFirstRow - mActiveTranscriptRows ]
     * [ ...                            ]     [ ...                                     ]
     * [ 0 (visible screen starts here) ]  ↔  [ mScreenFirstRow                         ]
     * [ ...                            ]     [ ...                                     ]
     * [ mScreenRows-1                  ]     [ mScreenFirstRow + mScreenRows-1         ]
    </pre> *
     *
     * @param externalRow a row in the external coordinate system.
     * @return The row corresponding to the input argument in the private coordinate system.
     */
    fun externalToInternalRow(externalRow: Int): Int {
        if (externalRow < -activeTranscriptRows || externalRow > mScreenRows) throw IllegalArgumentException("extRow=" + externalRow + ", mScreenRows=" + mScreenRows + ", mActiveTranscriptRows=" + activeTranscriptRows)
        val internalRow: Int = mScreenFirstRow + externalRow
        return if ((internalRow < 0)) (mTotalRows + internalRow) else (internalRow % mTotalRows)
    }

    fun setLineWrap(row: Int) {
        mLines.get(externalToInternalRow(row))!!.mLineWrap = true
    }

    fun getLineWrap(row: Int): Boolean {
        return mLines.get(externalToInternalRow(row))!!.mLineWrap
    }

    fun clearLineWrap(row: Int) {
        mLines.get(externalToInternalRow(row))!!.mLineWrap = false
    }

    /**
     * Resize the screen which this transcript backs. Currently, this only works if the number of columns does not
     * change or the rows expand (that is, it only works when shrinking the number of rows).
     *
     * @param newColumns The number of columns the screen should have.
     * @param newRows    The number of rows the screen should have.
     * @param cursor     An int[2] containing the (column, row) cursor location.
     */
    fun resize(newColumns: Int, newRows: Int, newTotalRows: Int, cursor: IntArray, currentStyle: Long, altScreen: Boolean) {
        // newRows > mTotalRows should not normally happen since mTotalRows is TRANSCRIPT_ROWS (10000):
        if (newColumns == mColumns && newRows <= mTotalRows) {
            // Fast resize where just the rows changed.
            var shiftDownOfTopRow: Int = mScreenRows - newRows
            if (shiftDownOfTopRow > 0 && shiftDownOfTopRow < mScreenRows) {
                // Shrinking. Check if we can skip blank rows at bottom below cursor.
                for (i in mScreenRows - 1 downTo 1) {
                    if (cursor.get(1) >= i) break
                    val r: Int = externalToInternalRow(i)
                    if (mLines.get(r) == null || mLines.get(r).isBlank()) {
                        if (--shiftDownOfTopRow == 0) break
                    }
                }
            } else if (shiftDownOfTopRow < 0) {
                // Negative shift down = expanding. Only move screen up if there is transcript to show:
                val actualShift: Int = Math.max(shiftDownOfTopRow, -activeTranscriptRows)
                if (shiftDownOfTopRow != actualShift) {
                    // The new lines revealed by the resizing are not all from the transcript. Blank the below ones.
                    for (i in 0 until actualShift - shiftDownOfTopRow) allocateFullLineIfNecessary((mScreenFirstRow + mScreenRows + i) % mTotalRows).clear(currentStyle)
                    shiftDownOfTopRow = actualShift
                }
            }
            mScreenFirstRow += shiftDownOfTopRow
            mScreenFirstRow = if ((mScreenFirstRow < 0)) (mScreenFirstRow + mTotalRows) else (mScreenFirstRow % mTotalRows)
            mTotalRows = newTotalRows
            activeTranscriptRows = if (altScreen) 0 else Math.max(0, activeTranscriptRows + shiftDownOfTopRow)
            cursor.get(1) -= shiftDownOfTopRow
            mScreenRows = newRows
        } else {
            // Copy away old state and update new:
            val oldLines: Array<TerminalRow?> = mLines
            mLines = arrayOfNulls(newTotalRows)
            for (i in 0 until newTotalRows) mLines.get(i) = TerminalRow(newColumns, currentStyle)
            val oldActiveTranscriptRows: Int = activeTranscriptRows
            val oldScreenFirstRow: Int = mScreenFirstRow
            val oldScreenRows: Int = mScreenRows
            val oldTotalRows: Int = mTotalRows
            mTotalRows = newTotalRows
            mScreenRows = newRows
            mScreenFirstRow = 0
            activeTranscriptRows = mScreenFirstRow
            mColumns = newColumns
            var newCursorRow: Int = -1
            var newCursorColumn: Int = -1
            val oldCursorRow: Int = cursor.get(1)
            val oldCursorColumn: Int = cursor.get(0)
            var newCursorPlaced: Boolean = false
            var currentOutputExternalRow: Int = 0
            var currentOutputExternalColumn: Int = 0

            // Loop over every character in the initial state.
            // Blank lines should be skipped only if at end of transcript (just as is done in the "fast" resize), so we
            // keep track how many blank lines we have skipped if we later on find a non-blank line.
            var skippedBlankLines: Int = 0
            for (externalOldRow in -oldActiveTranscriptRows until oldScreenRows) {
                // Do what externalToInternalRow() does but for the old state:
                var internalOldRow: Int = oldScreenFirstRow + externalOldRow
                internalOldRow = if ((internalOldRow < 0)) (oldTotalRows + internalOldRow) else (internalOldRow % oldTotalRows)
                val oldLine: TerminalRow? = oldLines.get(internalOldRow)
                val cursorAtThisRow: Boolean = externalOldRow == oldCursorRow
                // The cursor may only be on a non-null line, which we should not skip:
                if (oldLine == null || (!(!newCursorPlaced && cursorAtThisRow)) && oldLine.isBlank()) {
                    skippedBlankLines++
                    continue
                } else if (skippedBlankLines > 0) {
                    // After skipping some blank lines we encounter a non-blank line. Insert the skipped blank lines.
                    for (i in 0 until skippedBlankLines) {
                        if (currentOutputExternalRow == mScreenRows - 1) {
                            scrollDownOneLine(0, mScreenRows, currentStyle)
                        } else {
                            currentOutputExternalRow++
                        }
                        currentOutputExternalColumn = 0
                    }
                    skippedBlankLines = 0
                }
                var lastNonSpaceIndex: Int = 0
                var justToCursor: Boolean = false
                if (cursorAtThisRow || oldLine.mLineWrap) {
                    // Take the whole line, either because of cursor on it, or if line wrapping.
                    lastNonSpaceIndex = oldLine.getSpaceUsed()
                    if (cursorAtThisRow) justToCursor = true
                } else {
                    for (i in 0 until oldLine.getSpaceUsed())  // NEWLY INTRODUCED BUG! Should not index oldLine.mStyle with char indices
                        if (oldLine.mText.get(i) != ' ' /* || oldLine.mStyle[i] != currentStyle */) lastNonSpaceIndex = i + 1
                }
                var currentOldCol: Int = 0
                var styleAtCol: Long = 0
                var i: Int = 0
                while (i < lastNonSpaceIndex) {

                    // Note that looping over java character, not cells.
                    val c: Char = oldLine.mText.get(i)
                    val codePoint: Int = (if ((Character.isHighSurrogate(c))) Character.toCodePoint(c, oldLine.mText.get(++i)) else c).toInt()
                    val displayWidth: Int = WcWidth.width(codePoint)
                    // Use the last style if this is a zero-width character:
                    if (displayWidth > 0) styleAtCol = oldLine.getStyle(currentOldCol)

                    // Line wrap as necessary:
                    if (currentOutputExternalColumn + displayWidth > mColumns) {
                        setLineWrap(currentOutputExternalRow)
                        if (currentOutputExternalRow == mScreenRows - 1) {
                            if (newCursorPlaced) newCursorRow--
                            scrollDownOneLine(0, mScreenRows, currentStyle)
                        } else {
                            currentOutputExternalRow++
                        }
                        currentOutputExternalColumn = 0
                    }
                    val offsetDueToCombiningChar: Int = (if ((displayWidth <= 0 && currentOutputExternalColumn > 0)) 1 else 0)
                    val outputColumn: Int = currentOutputExternalColumn - offsetDueToCombiningChar
                    setChar(outputColumn, currentOutputExternalRow, codePoint, styleAtCol)
                    if (displayWidth > 0) {
                        if (oldCursorRow == externalOldRow && oldCursorColumn == currentOldCol) {
                            newCursorColumn = currentOutputExternalColumn
                            newCursorRow = currentOutputExternalRow
                            newCursorPlaced = true
                        }
                        currentOldCol += displayWidth
                        currentOutputExternalColumn += displayWidth
                        if (justToCursor && newCursorPlaced) break
                    }
                    i++
                }
                // Old row has been copied. Check if we need to insert newline if old line was not wrapping:
                if (externalOldRow != (oldScreenRows - 1) && !oldLine.mLineWrap) {
                    if (currentOutputExternalRow == mScreenRows - 1) {
                        if (newCursorPlaced) newCursorRow--
                        scrollDownOneLine(0, mScreenRows, currentStyle)
                    } else {
                        currentOutputExternalRow++
                    }
                    currentOutputExternalColumn = 0
                }
            }
            cursor.get(0) = newCursorColumn
            cursor.get(1) = newCursorRow
        }

        // Handle cursor scrolling off screen:
        if (cursor.get(0) < 0 || cursor.get(1) < 0) {
            cursor.get(1) = 0
            cursor.get(0) = cursor.get(1)
        }
    }

    /**
     * Block copy lines and associated metadata from one location to another in the circular buffer, taking wraparound
     * into account.
     *
     * @param srcInternal The first line to be copied.
     * @param len         The number of lines to be copied.
     */
    private fun blockCopyLinesDown(srcInternal: Int, len: Int) {
        if (len == 0) return
        val totalRows: Int = mTotalRows
        val start: Int = len - 1
        // Save away line to be overwritten:
        val lineToBeOverWritten: TerminalRow? = mLines.get((srcInternal + start + 1) % totalRows)
        // Do the copy from bottom to top.
        for (i in start downTo 0) mLines.get((srcInternal + i + 1) % totalRows) = mLines.get((srcInternal + i) % totalRows)
        // Put back overwritten line, now above the block:
        mLines.get((srcInternal) % totalRows) = lineToBeOverWritten
    }

    /**
     * Scroll the screen down one line. To scroll the whole screen of a 24 line screen, the arguments would be (0, 24).
     *
     * @param topMargin    First line that is scrolled.
     * @param bottomMargin One line after the last line that is scrolled.
     * @param style        the style for the newly exposed line.
     */
    fun scrollDownOneLine(topMargin: Int, bottomMargin: Int, style: Long) {
        if ((topMargin > bottomMargin - 1) || (topMargin < 0) || (bottomMargin > mScreenRows)) throw IllegalArgumentException("topMargin=" + topMargin + ", bottomMargin=" + bottomMargin + ", mScreenRows=" + mScreenRows)

        // Copy the fixed topMargin lines one line down so that they remain on screen in same position:
        blockCopyLinesDown(mScreenFirstRow, topMargin)
        // Copy the fixed mScreenRows-bottomMargin lines one line down so that they remain on screen in same
        // position:
        blockCopyLinesDown(externalToInternalRow(bottomMargin), mScreenRows - bottomMargin)

        // Update the screen location in the ring buffer:
        mScreenFirstRow = (mScreenFirstRow + 1) % mTotalRows
        // Note that the history has grown if not already full:
        if (activeTranscriptRows < mTotalRows - mScreenRows) activeTranscriptRows++

        // Blank the newly revealed line above the bottom margin:
        val blankRow: Int = externalToInternalRow(bottomMargin - 1)
        if (mLines.get(blankRow) == null) {
            mLines.get(blankRow) = TerminalRow(mColumns, style)
        } else {
            mLines.get(blankRow)!!.clear(style)
        }
    }

    /**
     * Block copy characters from one position in the screen to another. The two positions can overlap. All characters
     * of the source and destination must be within the bounds of the screen, or else an InvalidParameterException will
     * be thrown.
     *
     * @param sx source X coordinate
     * @param sy source Y coordinate
     * @param w  width
     * @param h  height
     * @param dx destination X coordinate
     * @param dy destination Y coordinate
     */
    fun blockCopy(sx: Int, sy: Int, w: Int, h: Int, dx: Int, dy: Int) {
        if (w == 0) return
        if ((sx < 0) || (sx + w > mColumns) || (sy < 0) || (sy + h > mScreenRows) || (dx < 0) || (dx + w > mColumns) || (dy < 0) || (dy + h > mScreenRows)) throw IllegalArgumentException()
        val copyingUp: Boolean = sy > dy
        for (y in 0 until h) {
            val y2: Int = if (copyingUp) y else (h - (y + 1))
            val sourceRow: TerminalRow = allocateFullLineIfNecessary(externalToInternalRow(sy + y2))
            allocateFullLineIfNecessary(externalToInternalRow(dy + y2)).copyInterval(sourceRow, sx, sx + w, dx)
        }
    }

    /**
     * Block set characters. All characters must be within the bounds of the screen, or else and
     * InvalidParemeterException will be thrown. Typically this is called with a "val" argument of 32 to clear a block
     * of characters.
     */
    fun blockSet(sx: Int, sy: Int, w: Int, h: Int, `val`: Int, style: Long) {
        if ((sx < 0) || (sx + w > mColumns) || (sy < 0) || (sy + h > mScreenRows)) {
            throw IllegalArgumentException(
                "Illegal arguments! blockSet(" + sx + ", " + sy + ", " + w + ", " + h + ", " + `val` + ", " + mColumns + ", " + mScreenRows + ")")
        }
        for (y in 0 until h) for (x in 0 until w) setChar(sx + x, sy + y, `val`, style)
    }

    fun allocateFullLineIfNecessary(row: Int): TerminalRow {
        return (if ((mLines.get(row) == null)) (TerminalRow(mColumns, 0).also({ mLines.get(row) = it })) else mLines.get(row)!!)
    }

    fun setChar(column: Int, row: Int, codePoint: Int, style: Long) {
        var row: Int = row
        if (row >= mScreenRows || column >= mColumns) throw IllegalArgumentException("row=" + row + ", column=" + column + ", mScreenRows=" + mScreenRows + ", mColumns=" + mColumns)
        row = externalToInternalRow(row)
        allocateFullLineIfNecessary(row).setChar(column, codePoint, style)
    }

    fun getStyleAt(externalRow: Int, column: Int): Long {
        return allocateFullLineIfNecessary(externalToInternalRow(externalRow)).getStyle(column)
    }

    /** Support for http://vt100.net/docs/vt510-rm/DECCARA and http://vt100.net/docs/vt510-rm/DECCARA  */
    fun setOrClearEffect(bits: Int, setOrClear: Boolean, reverse: Boolean, rectangular: Boolean, leftMargin: Int, rightMargin: Int, top: Int, left: Int,
                         bottom: Int, right: Int) {
        for (y in top until bottom) {
            val line: TerminalRow? = mLines.get(externalToInternalRow(y))
            val startOfLine: Int = if ((rectangular || y == top)) left else leftMargin
            val endOfLine: Int = if ((rectangular || y + 1 == bottom)) right else rightMargin
            for (x in startOfLine until endOfLine) {
                val currentStyle: Long = line!!.getStyle(x)
                val foreColor: Int = TextStyle.decodeForeColor(currentStyle)
                val backColor: Int = TextStyle.decodeBackColor(currentStyle)
                var effect: Int = TextStyle.decodeEffect(currentStyle)
                if (reverse) {
                    // Clear out the bits to reverse and add them back in reversed:
                    effect = (effect and bits.inv()) or (bits and effect.inv())
                } else if (setOrClear) {
                    effect = effect or bits
                } else {
                    effect = effect and bits.inv()
                }
                line.mStyle.get(x) = TextStyle.encode(foreColor, backColor, effect)
            }
        }
    }

    fun clearTranscript() {
        if (mScreenFirstRow < activeTranscriptRows) {
            Arrays.fill(mLines, mTotalRows + mScreenFirstRow - activeTranscriptRows, mTotalRows, null)
            Arrays.fill(mLines, 0, mScreenFirstRow, null)
        } else {
            Arrays.fill(mLines, mScreenFirstRow - activeTranscriptRows, mScreenFirstRow, null)
        }
        activeTranscriptRows = 0
    }

    /**
     * Create a transcript screen.
     *
     * @param columns    the width of the screen in characters.
     * @param totalRows  the height of the entire text area, in rows of text.
     * @param screenRows the height of just the screen, not including the transcript that holds lines that have scrolled off
     * the top of the screen.
     */
    init {
        mLines = arrayOfNulls(mTotalRows)
        blockSet(0, 0, mColumns, mScreenRows, ' '.toInt(), TextStyle.NORMAL)
    }
}
