package com.termux.terminal

import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Renders text into a screen. Contains all the terminal-specific knowledge and state. Emulates a subset of the X Window
 * System xterm terminal, which in turn is an emulator for a subset of the Digital Equipment Corporation vt100 terminal.
 *
 *
 * References:
 *
 *  * http://invisible-island.net/xterm/ctlseqs/ctlseqs.html
 *  * http://en.wikipedia.org/wiki/ANSI_escape_code
 *  * http://man.he.net/man4/console_codes
 *  * http://bazaar.launchpad.net/~leonerd/libvterm/trunk/view/head:/src/state.c
 *  * http://www.columbia.edu/~kermit/k95manual/iso2022.html
 *  * http://www.vt100.net/docs/vt510-rm/chapter4
 *  * http://en.wikipedia.org/wiki/ISO/IEC_2022 - for 7-bit and 8-bit GL GR explanation
 *  * http://bjh21.me.uk/all-escapes/all-escapes.txt - extensive!
 *  * http://woldlab.caltech.edu/~diane/kde4.10/workingdir/kubuntu/konsole/doc/developer/old-documents/VT100/techref.
 * html - document for konsole - accessible!
 *
 */
class TerminalEmulator constructor(
    /** The terminal session this emulator is bound to.  */
    private val mSession: TerminalOutput, columns: Int, rows: Int, transcriptRows: Int) {
    private var mTitle: String? = null
    private val mTitleStack: Stack<String?> = Stack()

    /** The cursor position. Between (0,0) and (mRows-1, mColumns-1).  */
    private var mCursorRow: Int = 0
    private var mCursorCol: Int = 0
    /** [.CURSOR_STYLE_BAR], [.CURSOR_STYLE_BLOCK] or [.CURSOR_STYLE_UNDERLINE]  */
    var cursorStyle: Int = CURSOR_STYLE_BLOCK
        private set

    /** The number of character rows and columns in the terminal screen.  */
    @JvmField
    var mRows: Int
    @JvmField
    var mColumns: Int

    /** The normal screen buffer. Stores the characters that appear on the screen of the emulated terminal.  */
    private val mMainBuffer: TerminalBuffer

    /**
     * The alternate screen buffer, exactly as large as the display and contains no additional saved lines (so that when
     * the alternate screen buffer is active, you cannot scroll back to view saved lines).
     *
     *
     * See http://www.xfree86.org/current/ctlseqs.html#The%20Alternate%20Screen%20Buffer
     */
    @JvmField
    val mAltBuffer: TerminalBuffer

    /** The current screen buffer, pointing at either [.mMainBuffer] or [.mAltBuffer].  */
    var screen: TerminalBuffer
        private set

    /** Keeps track of the current argument of the current escape sequence. Ranges from 0 to MAX_ESCAPE_PARAMETERS-1.  */
    private var mArgIndex: Int = 0

    /** Holds the arguments of the current escape sequence.  */
    private val mArgs: IntArray = IntArray(MAX_ESCAPE_PARAMETERS)

    /** Holds OSC and device control arguments, which can be strings.  */
    private val mOSCOrDeviceControlArgs: StringBuilder = StringBuilder()

    /**
     * True if the current escape sequence should continue, false if the current escape sequence should be terminated.
     * Used when parsing a single character.
     */
    private var mContinueSequence: Boolean = false

    /** The current state of the escape sequence state machine. One of the ESC_* constants.  */
    private var mEscapeState: Int = 0
    private val mSavedStateMain: SavedScreenState = SavedScreenState()
    private val mSavedStateAlt: SavedScreenState = SavedScreenState()

    /** http://www.vt100.net/docs/vt102-ug/table5-15.html  */
    private var mUseLineDrawingG0: Boolean = false
    private var mUseLineDrawingG1: Boolean = false
    private var mUseLineDrawingUsesG0: Boolean = true

    /**
     * @see TerminalEmulator.mapDecSetBitToInternalBit
     */
    private var mCurrentDecSetFlags: Int = 0
    private var mSavedDecSetFlags: Int = 0

    /**
     * If insert mode (as opposed to replace mode) is active. In insert mode new characters are inserted, pushing
     * existing text to the right. Characters moved past the right margin are lost.
     */
    private var mInsertMode: Boolean = false

    /** An array of tab stops. mTabStop[i] is true if there is a tab stop set for column i.  */
    private var mTabStop: BooleanArray

    /**
     * Top margin of screen for scrolling ranges from 0 to mRows-2. Bottom margin ranges from mTopMargin + 2 to mRows
     * (Defines the first row after the scrolling region). Left/right margin in [0, mColumns].
     */
    private var mTopMargin: Int = 0
    private var mBottomMargin: Int = 0
    private var mLeftMargin: Int = 0
    private var mRightMargin: Int = 0

    /**
     * If the next character to be emitted will be automatically wrapped to the next line. Used to disambiguate the case
     * where the cursor is positioned on the last column (mColumns-1). When standing there, a written character will be
     * output in the last column, the cursor not moving but this flag will be set. When outputting another character
     * this will move to the next line.
     */
    private var mAboutToAutoWrap: Boolean = false

    /**
     * Current foreground and background colors. Can either be a color index in [0,259] or a truecolor (24-bit) value.
     * For a 24-bit value the top byte (0xff000000) is set.
     *
     * @see TextStyle
     */
    @JvmField
    var mForeColor: Int = 0
    @JvmField
    var mBackColor: Int = 0

    /** Current [TextStyle] effect.  */
    private var mEffect: Int = 0

    /**
     * The number of scrolled lines since last calling [.clearScrollCounter]. Used for moving selection up along
     * with the scrolling text.
     */
    var scrollCounter: Int = 0
        private set
    private var mUtf8ToFollow: Byte = 0
    private var mUtf8Index: Byte = 0
    private val mUtf8InputBuffer: ByteArray = ByteArray(4)
    private var mLastEmittedCodePoint: Int = -1
    @JvmField
    val mColors: TerminalColors = TerminalColors()
    private fun isDecsetInternalBitSet(bit: Int): Boolean {
        return (mCurrentDecSetFlags and bit) != 0
    }

    private fun setDecsetinternalBit(internalBit: Int, set: Boolean) {
        if (set) {
            // The mouse modes are mutually exclusive.
            if (internalBit == DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE) {
                setDecsetinternalBit(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT, false)
            } else if (internalBit == DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT) {
                setDecsetinternalBit(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE, false)
            }
        }
        if (set) {
            mCurrentDecSetFlags = mCurrentDecSetFlags or internalBit
        } else {
            mCurrentDecSetFlags = mCurrentDecSetFlags and internalBit.inv()
        }
    }

    val isAlternateBufferActive: Boolean
        get() {
            return screen == mAltBuffer
        }

    /**
     * @param mouseButton one of the MOUSE_* constants of this class.
     */
    fun sendMouseEvent(mouseButton: Int, column: Int, row: Int, pressed: Boolean) {
        var mouseButton: Int = mouseButton
        var column: Int = column
        var row: Int = row
        if (column < 1) column = 1
        if (column > mColumns) column = mColumns
        if (row < 1) row = 1
        if (row > mRows) row = mRows
        if (mouseButton == MOUSE_LEFT_BUTTON_MOVED && !isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT)) {
            // Do not send tracking.
        } else if (isDecsetInternalBitSet(DECSET_BIT_MOUSE_PROTOCOL_SGR)) {
            mSession.write(String.format("\u001b[<%d;%d;%d" + (if (pressed) 'M' else 'm'), mouseButton, column, row))
        } else {
            mouseButton = if (pressed) mouseButton else 3 // 3 for release of all buttons.
            // Clip to screen, and clip to the limits of 8-bit data.
            val out_of_bounds: Boolean = column > 255 - 32 || row > 255 - 32
            if (!out_of_bounds) {
                val data: ByteArray = byteArrayOf('\u001b'.toByte(), '['.toByte(), 'M'.toByte(), (32 + mouseButton).toByte(), (32 + column).toByte(), (32 + row).toByte())
                mSession.write(data, 0, data.size)
            }
        }
    }

    fun resize(columns: Int, rows: Int) {
        if (mRows == rows && mColumns == columns) {
            return
        } else if (columns < 2 || rows < 2) {
            throw IllegalArgumentException("rows=" + rows + ", columns=" + columns)
        }
        if (mRows != rows) {
            mRows = rows
            mTopMargin = 0
            mBottomMargin = mRows
        }
        if (mColumns != columns) {
            val oldColumns: Int = mColumns
            mColumns = columns
            val oldTabStop: BooleanArray = mTabStop
            mTabStop = BooleanArray(mColumns)
            setDefaultTabStops()
            val toTransfer: Int = Math.min(oldColumns, columns)
            System.arraycopy(oldTabStop, 0, mTabStop, 0, toTransfer)
            mLeftMargin = 0
            mRightMargin = mColumns
        }
        resizeScreen()
    }

    private fun resizeScreen() {
        val cursor: IntArray = intArrayOf(mCursorCol, mCursorRow)
        val newTotalRows: Int = if ((screen == mAltBuffer)) mRows else mMainBuffer.mTotalRows
        screen.resize(mColumns, mRows, newTotalRows, cursor, style, isAlternateBufferActive)
        mCursorCol = cursor.get(0)
        mCursorRow = cursor.get(1)
    }

    var cursorRow: Int
        get() {
            return mCursorRow
        }
        private set(row) {
            mCursorRow = row
            mAboutToAutoWrap = false
        }

    var cursorCol: Int
        get() {
            return mCursorCol
        }
        private set(col) {
            mCursorCol = col
            mAboutToAutoWrap = false
        }

    val isReverseVideo: Boolean
        get() {
            return isDecsetInternalBitSet(DECSET_BIT_REVERSE_VIDEO)
        }

    val isShowingCursor: Boolean
        get() {
            return isDecsetInternalBitSet(DECSET_BIT_SHOWING_CURSOR)
        }

    val isKeypadApplicationMode: Boolean
        get() {
            return isDecsetInternalBitSet(DECSET_BIT_APPLICATION_KEYPAD)
        }

    val isCursorKeysApplicationMode: Boolean
        get() {
            return isDecsetInternalBitSet(DECSET_BIT_APPLICATION_CURSOR_KEYS)
        }

    /** If mouse events are being sent as escape codes to the terminal.  */
    val isMouseTrackingActive: Boolean
        get() {
            return isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE) || isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT)
        }

    private fun setDefaultTabStops() {
        for (i in 0 until mColumns) mTabStop.get(i) = (i and 7) == 0 && i != 0
    }

    /**
     * Accept bytes (typically from the pseudo-teletype) and process them.
     *
     * @param buffer a byte array containing the bytes to be processed
     * @param length the number of bytes in the array to process
     */
    fun append(buffer: ByteArray, length: Int) {
        for (i in 0 until length) processByte(buffer.get(i))
    }

    private fun processByte(byteToProcess: Byte) {
        if (mUtf8ToFollow > 0) {
            if ((byteToProcess and 192) == 128) {
                // 10xxxxxx, a continuation byte.
                mUtf8InputBuffer.get(mUtf8Index++.toInt()) = byteToProcess
                if (--mUtf8ToFollow.toInt() == 0) {
                    val firstByteMask: Byte = (if (mUtf8Index.toInt() == 2) 31 else (if (mUtf8Index.toInt() == 3) 15 else 7)).toByte()
                    var codePoint: Int = (mUtf8InputBuffer.get(0) and firstByteMask)
                    for (i in 1 until mUtf8Index) codePoint = ((codePoint shl 6) or (mUtf8InputBuffer.get(i) and 63))
                    if ((((codePoint <= 127) && mUtf8Index > 1) || (codePoint < 2047 && mUtf8Index > 2)
                            || (codePoint < 65535 && mUtf8Index > 3))) {
                        // Overlong encoding.
                        codePoint = UNICODE_REPLACEMENT_CHAR
                    }
                    mUtf8ToFollow = 0
                    mUtf8Index = mUtf8ToFollow
                    if (codePoint >= 0x80 && codePoint <= 0x9F) {
                        // Sequence decoded to a C1 control character which we ignore. They are
                        // not used nowadays and increases the risk of messing up the terminal state
                        // on binary input. XTerm does not allow them in utf-8:
                        // "It is not possible to use a C1 control obtained from decoding the
                        // UTF-8 text" - http://invisible-island.net/xterm/ctlseqs/ctlseqs.html
                    } else {
                        when (Character.getType(codePoint)) {
                            Character.UNASSIGNED, Character.SURROGATE -> codePoint = UNICODE_REPLACEMENT_CHAR
                        }
                        processCodePoint(codePoint)
                    }
                }
            } else {
                // Not a UTF-8 continuation byte so replace the entire sequence up to now with the replacement char:
                mUtf8ToFollow = 0
                mUtf8Index = mUtf8ToFollow
                emitCodePoint(UNICODE_REPLACEMENT_CHAR)
                // The Unicode Standard Version 6.2 – Core Specification
                // (http://www.unicode.org/versions/Unicode6.2.0/ch03.pdf):
                // "If the converter encounters an ill-formed UTF-8 code unit sequence which starts with a valid first
                // byte, but which does not continue with valid successor bytes (see Table 3-7), it must not consume the
                // successor bytes as part of the ill-formed subsequence
                // whenever those successor bytes themselves constitute part of a well-formed UTF-8 code unit
                // subsequence."
                processByte(byteToProcess)
            }
        } else {
            if ((byteToProcess and 128) == 0) { // The leading bit is not set so it is a 7-bit ASCII character.
                processCodePoint(byteToProcess.toInt())
                return
            } else if ((byteToProcess and 224) == 192) { // 110xxxxx, a two-byte sequence.
                mUtf8ToFollow = 1
            } else if ((byteToProcess and 240) == 224) { // 1110xxxx, a three-byte sequence.
                mUtf8ToFollow = 2
            } else if ((byteToProcess and 248) == 240) { // 11110xxx, a four-byte sequence.
                mUtf8ToFollow = 3
            } else {
                // Not a valid UTF-8 sequence start, signal invalid data:
                processCodePoint(UNICODE_REPLACEMENT_CHAR)
                return
            }
            mUtf8InputBuffer.get(mUtf8Index++.toInt()) = byteToProcess
        }
    }

    fun processCodePoint(b: Int) {
        when (b) {
            0 -> {
            }
            7 -> if (mEscapeState == ESC_OSC) doOsc(b) else mSession.onBell()
            8 -> if (mLeftMargin == mCursorCol) {
                // Jump to previous line if it was auto-wrapped.
                val previousRow: Int = mCursorRow - 1
                if (previousRow >= 0 && screen.getLineWrap(previousRow)) {
                    screen.clearLineWrap(previousRow)
                    setCursorRowCol(previousRow, mRightMargin - 1)
                }
            } else {
                cursorCol = mCursorCol - 1
            }
            9 ->                 // XXX: Should perhaps use color if writing to new cells. Try with
                //       printf "\033[41m\tXX\033[0m\n"
                // The OSX Terminal.app colors the spaces from the tab red, but xterm does not.
                // Note that Terminal.app only colors on new cells, in e.g.
                //       printf "\033[41m\t\r\033[42m\tXX\033[0m\n"
                // the first cells are created with a red background, but when tabbing over
                // them again with a green background they are not overwritten.
                mCursorCol = nextTabStop(1)
            10, 11, 12 -> doLinefeed()
            13 -> cursorCol = mLeftMargin
            14 -> mUseLineDrawingUsesG0 = false
            15 -> mUseLineDrawingUsesG0 = true
            24, 26 -> if (mEscapeState != ESC_NONE) {
                // FIXME: What is this??
                mEscapeState = ESC_NONE
                emitCodePoint(127)
            }
            27 ->                 // Starts an escape sequence unless we're parsing a string
                if (mEscapeState == ESC_P) {
                    // XXX: Ignore escape when reading device control sequence, since it may be part of string terminator.
                    return
                } else if (mEscapeState != ESC_OSC) {
                    startEscapeSequence()
                } else {
                    doOsc(b)
                }
            else -> {
                mContinueSequence = false
                when (mEscapeState) {
                    ESC_NONE -> if (b >= 32) emitCodePoint(b)
                    ESC -> doEsc(b)
                    ESC_POUND -> doEscPound(b)
                    ESC_SELECT_LEFT_PAREN -> mUseLineDrawingG0 = (b == '0'.toInt())
                    ESC_SELECT_RIGHT_PAREN -> mUseLineDrawingG1 = (b == '0'.toInt())
                    ESC_CSI -> doCsi(b)
                    ESC_CSI_EXCLAMATION -> if (b == 'p'.toInt()) { // Soft terminal reset (DECSTR, http://vt100.net/docs/vt510-rm/DECSTR).
                        reset()
                    } else {
                        unknownSequence(b)
                    }
                    ESC_CSI_QUESTIONMARK -> doCsiQuestionMark(b)
                    ESC_CSI_BIGGERTHAN -> doCsiBiggerThan(b)
                    ESC_CSI_DOLLAR -> {
                        val originMode: Boolean = isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)
                        val effectiveTopMargin: Int = if (originMode) mTopMargin else 0
                        val effectiveBottomMargin: Int = if (originMode) mBottomMargin else mRows
                        val effectiveLeftMargin: Int = if (originMode) mLeftMargin else 0
                        val effectiveRightMargin: Int = if (originMode) mRightMargin else mColumns
                        when (b) {
                            'v' -> {
                                // Copy rectangular area (DECCRA - http://vt100.net/docs/vt510-rm/DECCRA):
                                // "If Pbs is greater than Pts, or Pls is greater than Prs, the terminal ignores DECCRA.
                                // The coordinates of the rectangular area are affected by the setting of origin mode (DECOM).
                                // DECCRA is not affected by the page margins.
                                // The copied text takes on the line attributes of the destination area.
                                // If the value of Pt, Pl, Pb, or Pr exceeds the width or height of the active page, then the value
                                // is treated as the width or height of that page.
                                // If the destination area is partially off the page, then DECCRA clips the off-page data.
                                // DECCRA does not change the active cursor position."
                                val topSource: Int = Math.min(getArg(0, 1, true) - 1 + effectiveTopMargin, mRows)
                                val leftSource: Int = Math.min(getArg(1, 1, true) - 1 + effectiveLeftMargin, mColumns)
                                // Inclusive, so do not subtract one:
                                val bottomSource: Int = Math.min(Math.max(getArg(2, mRows, true) + effectiveTopMargin, topSource), mRows)
                                val rightSource: Int = Math.min(Math.max(getArg(3, mColumns, true) + effectiveLeftMargin, leftSource), mColumns)
                                // int sourcePage = getArg(4, 1, true);
                                val destionationTop: Int = Math.min(getArg(5, 1, true) - 1 + effectiveTopMargin, mRows)
                                val destinationLeft: Int = Math.min(getArg(6, 1, true) - 1 + effectiveLeftMargin, mColumns)
                                // int destinationPage = getArg(7, 1, true);
                                val heightToCopy: Int = Math.min(mRows - destionationTop, bottomSource - topSource)
                                val widthToCopy: Int = Math.min(mColumns - destinationLeft, rightSource - leftSource)
                                screen.blockCopy(leftSource, topSource, widthToCopy, heightToCopy, destinationLeft, destionationTop)
                            }
                            '{', 'x', 'z' -> {
                                // Erase rectangular area (DECERA - http://www.vt100.net/docs/vt510-rm/DECERA).
                                val erase: Boolean = b != 'x'.toInt()
                                val selective: Boolean = b == '{'.toInt()
                                // Only DECSERA keeps visual attributes, DECERA does not:
                                val keepVisualAttributes: Boolean = erase && selective
                                var argIndex: Int = 0
                                val fillChar: Int = (if (erase) ' ' else getArg(argIndex++, -1, true)).toInt()
                                // "Pch can be any value from 32 to 126 or from 160 to 255. If Pch is not in this range, then the
                                // terminal ignores the DECFRA command":
                                if ((fillChar >= 32 && fillChar <= 126) || (fillChar >= 160 && fillChar <= 255)) {
                                    // "If the value of Pt, Pl, Pb, or Pr exceeds the width or height of the active page, the value
                                    // is treated as the width or height of that page."
                                    val top: Int = Math.min(getArg(argIndex++, 1, true) + effectiveTopMargin, effectiveBottomMargin + 1)
                                    val left: Int = Math.min(getArg(argIndex++, 1, true) + effectiveLeftMargin, effectiveRightMargin + 1)
                                    val bottom: Int = Math.min(getArg(argIndex++, mRows, true) + effectiveTopMargin, effectiveBottomMargin)
                                    val right: Int = Math.min(getArg(argIndex, mColumns, true) + effectiveLeftMargin, effectiveRightMargin)
                                    val style: Long = style
                                    var row: Int = top - 1
                                    while (row < bottom) {
                                        var col: Int = left - 1
                                        while (col < right) {
                                            if (!selective || (TextStyle.decodeEffect(screen.getStyleAt(row, col)) and TextStyle.CHARACTER_ATTRIBUTE_PROTECTED) == 0) screen.setChar(col, row, fillChar, if (keepVisualAttributes) screen.getStyleAt(row, col) else style)
                                            col++
                                        }
                                        row++
                                    }
                                }
                            }
                            'r', 't' -> {
                                // Reverse attributes in rectangular area (DECRARA - http://www.vt100.net/docs/vt510-rm/DECRARA).
                                val reverse: Boolean = b == 't'.toInt()
                                // FIXME: "coordinates of the rectangular area are affected by the setting of origin mode (DECOM)".
                                val top: Int = Math.min(getArg(0, 1, true) - 1, effectiveBottomMargin) + effectiveTopMargin
                                val left: Int = Math.min(getArg(1, 1, true) - 1, effectiveRightMargin) + effectiveLeftMargin
                                val bottom: Int = Math.min(getArg(2, mRows, true) + 1, effectiveBottomMargin - 1) + effectiveTopMargin
                                val right: Int = Math.min(getArg(3, mColumns, true) + 1, effectiveRightMargin - 1) + effectiveLeftMargin
                                if (mArgIndex >= 4) {
                                    if (mArgIndex >= mArgs.size) mArgIndex = mArgs.size - 1
                                    var i: Int = 4
                                    while (i <= mArgIndex) {
                                        var bits: Int = 0
                                        var setOrClear: Boolean = true // True if setting, false if clearing.
                                        when (getArg(i, 0, false)) {
                                            0 -> {
                                                bits = ((TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE or TextStyle.CHARACTER_ATTRIBUTE_BLINK
                                                    or TextStyle.CHARACTER_ATTRIBUTE_INVERSE))
                                                if (!reverse) setOrClear = false
                                            }
                                            1 -> bits = TextStyle.CHARACTER_ATTRIBUTE_BOLD
                                            4 -> bits = TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
                                            5 -> bits = TextStyle.CHARACTER_ATTRIBUTE_BLINK
                                            7 -> bits = TextStyle.CHARACTER_ATTRIBUTE_INVERSE
                                            22 -> {
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_BOLD
                                                setOrClear = false
                                            }
                                            24 -> {
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
                                                setOrClear = false
                                            }
                                            25 -> {
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_BLINK
                                                setOrClear = false
                                            }
                                            27 -> {
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_INVERSE
                                                setOrClear = false
                                            }
                                        }
                                        if (reverse && !setOrClear) {
                                            // Reverse attributes in rectangular area ignores non-(1,4,5,7) bits.
                                        } else {
                                            screen.setOrClearEffect(bits, setOrClear, reverse, isDecsetInternalBitSet(DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE),
                                                effectiveLeftMargin, effectiveRightMargin, top, left, bottom, right)
                                        }
                                        i++
                                    }
                                } else {
                                    // Do nothing.
                                }
                            }
                            else -> unknownSequence(b)
                        }
                    }
                    ESC_CSI_DOUBLE_QUOTE -> if (b == 'q'.toInt()) {
                        // http://www.vt100.net/docs/vt510-rm/DECSCA
                        val arg: Int = getArg0(0)
                        if (arg == 0 || arg == 2) {
                            // DECSED and DECSEL can erase characters.
                            mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_PROTECTED.inv()
                        } else if (arg == 1) {
                            // DECSED and DECSEL cannot erase characters.
                            mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_PROTECTED
                        } else {
                            unknownSequence(b)
                        }
                    } else {
                        unknownSequence(b)
                    }
                    ESC_CSI_SINGLE_QUOTE -> if (b == '}'.toInt()) { // Insert Ps Column(s) (default = 1) (DECIC), VT420 and up.
                        val columnsAfterCursor: Int = mRightMargin - mCursorCol
                        val columnsToInsert: Int = Math.min(getArg0(1), columnsAfterCursor)
                        val columnsToMove: Int = columnsAfterCursor - columnsToInsert
                        screen.blockCopy(mCursorCol, 0, columnsToMove, mRows, mCursorCol + columnsToInsert, 0)
                        blockClear(mCursorCol, 0, columnsToInsert, mRows)
                    } else if (b == '~'.toInt()) { // Delete Ps Column(s) (default = 1) (DECDC), VT420 and up.
                        val columnsAfterCursor: Int = mRightMargin - mCursorCol
                        val columnsToDelete: Int = Math.min(getArg0(1), columnsAfterCursor)
                        val columnsToMove: Int = columnsAfterCursor - columnsToDelete
                        screen.blockCopy(mCursorCol + columnsToDelete, 0, columnsToMove, mRows, mCursorCol, 0)
                        blockClear(mCursorRow + columnsToMove, 0, columnsToDelete, mRows)
                    } else {
                        unknownSequence(b)
                    }
                    ESC_PERCENT -> {
                    }
                    ESC_OSC -> doOsc(b)
                    ESC_OSC_ESC -> doOscEsc(b)
                    ESC_P -> doDeviceControl(b)
                    ESC_CSI_QUESTIONMARK_ARG_DOLLAR -> if (b == 'p'.toInt()) {
                        // Request DEC private mode (DECRQM).
                        val mode: Int = getArg0(0)
                        val value: Int
                        if ((mode == 47) || (mode == 1047) || (mode == 1049)) {
                            // This state is carried by mScreen pointer.
                            value = if ((screen == mAltBuffer)) 1 else 2
                        } else {
                            val internalBit: Int = mapDecSetBitToInternalBit(mode)
                            if (internalBit == -1) {
                                value = if (isDecsetInternalBitSet(internalBit)) 1 else 2 // 1=set, 2=reset.
                            } else {
                                Log.e(EmulatorDebug.LOG_TAG, "Got DECRQM for unrecognized private DEC mode=" + mode)
                                value = 0 // 0=not recognized, 3=permanently set, 4=permanently reset
                            }
                        }
                        mSession.write(String.format(Locale.US, "\u001b[?%d;%d\$y", mode, value))
                    } else {
                        unknownSequence(b)
                    }
                    ESC_CSI_ARGS_SPACE -> {
                        val arg: Int = getArg0(0)
                        when (b) {
                            'q' -> when (arg) {
                                0, 1, 2 -> cursorStyle = CURSOR_STYLE_BLOCK
                                3, 4 -> cursorStyle = CURSOR_STYLE_UNDERLINE
                                5, 6 -> cursorStyle = CURSOR_STYLE_BAR
                            }
                            't', 'u' -> {
                            }
                            else -> unknownSequence(b)
                        }
                    }
                    ESC_CSI_ARGS_ASTERIX -> {
                        val attributeChangeExtent: Int = getArg0(0)
                        if (b == 'x'.toInt() && (attributeChangeExtent >= 0 && attributeChangeExtent <= 2)) {
                            // Select attribute change extent (DECSACE - http://www.vt100.net/docs/vt510-rm/DECSACE).
                            setDecsetinternalBit(DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE, attributeChangeExtent == 2)
                        } else {
                            unknownSequence(b)
                        }
                    }
                    else -> unknownSequence(b)
                }
                if (!mContinueSequence) mEscapeState = ESC_NONE
            }
        }
    }

    /** When in [.ESC_P] ("device control") sequence.  */
    private fun doDeviceControl(b: Int) {
        when (b) {
            '\\'.toByte() -> {
                val dcs: String = mOSCOrDeviceControlArgs.toString()
                // DCS $ q P t ST. Request Status String (DECRQSS)
                if (dcs.startsWith("\$q")) {
                    if ((dcs == "\$q\"p")) {
                        // DECSCL, conformance level, http://www.vt100.net/docs/vt510-rm/DECSCL:
                        val csiString: String = "64;1\"p"
                        mSession.write("\u001bP1\$r" + csiString + "\u001b\\")
                    } else {
                        finishSequenceAndLogError("Unrecognized DECRQSS string: '" + dcs + "'")
                    }
                } else if (dcs.startsWith("+q")) {
                    // Request Termcap/Terminfo String. The string following the "q" is a list of names encoded in
                    // hexadecimal (2 digits per character) separated by ; which correspond to termcap or terminfo key
                    // names.
                    // Two special features are also recognized, which are not key names: Co for termcap colors (or colors
                    // for terminfo colors), and TN for termcap name (or name for terminfo name).
                    // xterm responds with DCS 1 + r P t ST for valid requests, adding to P t an = , and the value of the
                    // corresponding string that xterm would send, or DCS 0 + r P t ST for invalid requests. The strings are
                    // encoded in hexadecimal (2 digits per character).
                    // Example:
                    // :kr=\EOC: ks=\E[?1h\E=: ku=\EOA: le=^H:mb=\E[5m:md=\E[1m:\
                    // where
                    // kd=down-arrow key
                    // kl=left-arrow key
                    // kr=right-arrow key
                    // ku=up-arrow key
                    // #2=key_shome, "shifted home"
                    // #4=key_sleft, "shift arrow left"
                    // %i=key_sright, "shift arrow right"
                    // *7=key_send, "shifted end"
                    // k1=F1 function key

                    // Example: Request for ku is "ESC P + q 6 b 7 5 ESC \", where 6b7d=ku in hexadecimal.
                    // Xterm response in normal cursor mode:
                    // "<27> P 1 + r 6 b 7 5 = 1 B 5 B 4 1" where 0x1B 0x5B 0x41 = 27 91 65 = ESC [ A
                    // Xterm response in application cursor mode:
                    // "<27> P 1 + r 6 b 7 5 = 1 B 5 B 4 1" where 0x1B 0x4F 0x41 = 27 91 65 = ESC 0 A

                    // #4 is "shift arrow left":
                    // *** Device Control (DCS) for '#4'- 'ESC P + q 23 34 ESC \'
                    // Response: <27> P 1 + r 2 3 3 4 = 1 B 5 B 3 1 3 B 3 2 4 4 <27> \
                    // where 0x1B 0x5B 0x31 0x3B 0x32 0x44 = ESC [ 1 ; 2 D
                    // which we find in: TermKeyListener.java: KEY_MAP.put(KEYMOD_SHIFT | KEYCODE_DPAD_LEFT, "\033[1;2D");

                    // See http://h30097.www3.hp.com/docs/base_doc/DOCUMENTATION/V40G_HTML/MAN/MAN4/0178____.HTM for what to
                    // respond, as well as http://www.freebsd.org/cgi/man.cgi?query=termcap&sektion=5#CAPABILITIES for
                    // the meaning of e.g. "ku", "kd", "kr", "kl"
                    for (part: String in dcs.substring(2).split(";").toTypedArray()) {
                        if (part.length % 2 == 0) {
                            val transBuffer: StringBuilder = StringBuilder()
                            var i: Int = 0
                            while (i < part.length) {
                                val c: Char = java.lang.Long.decode("0x" + part.get(i) + "" + part.get(i + 1)).toLong().toChar()
                                transBuffer.append(c)
                                i += 2
                            }
                            val trans: String = transBuffer.toString()
                            var responseValue: String?
                            when (trans) {
                                "Co", "colors" -> responseValue = "256" // Number of colors.
                                "TN", "name" -> responseValue = "xterm"
                                else -> responseValue = KeyHandler.getCodeFromTermcap(trans, isDecsetInternalBitSet(DECSET_BIT_APPLICATION_CURSOR_KEYS),
                                    isDecsetInternalBitSet(DECSET_BIT_APPLICATION_KEYPAD))
                            }
                            if (responseValue == null) {
                                when (trans) {
                                    "%1", "&8" -> {
                                    }
                                    else -> Log.w(EmulatorDebug.LOG_TAG, "Unhandled termcap/terminfo name: '" + trans + "'")
                                }
                                // Respond with invalid request:
                                mSession.write("\u001bP0+r" + part + "\u001b\\")
                            } else {
                                val hexEncoded: StringBuilder = StringBuilder()
                                var j: Int = 0
                                while (j < responseValue.length) {
                                    hexEncoded.append(String.format("%02X", responseValue.get(j).toInt()))
                                    j++
                                }
                                mSession.write("\u001bP1+r" + part + "=" + hexEncoded + "\u001b\\")
                            }
                        } else {
                            Log.e(EmulatorDebug.LOG_TAG, "Invalid device termcap/terminfo name of odd length: " + part)
                        }
                    }
                } else {
                    if (LOG_ESCAPE_SEQUENCES) Log.e(EmulatorDebug.LOG_TAG, "Unrecognized device control string: " + dcs)
                }
                finishSequence()
            }
            else -> if (mOSCOrDeviceControlArgs.length > MAX_OSC_STRING_LENGTH) {
                // Too long.
                mOSCOrDeviceControlArgs.setLength(0)
                finishSequence()
            } else {
                mOSCOrDeviceControlArgs.appendCodePoint(b)
                continueSequence(mEscapeState)
            }
        }
    }

    private fun nextTabStop(numTabs: Int): Int {
        var numTabs: Int = numTabs
        for (i in mCursorCol + 1 until mColumns) if (mTabStop.get(i) && --numTabs == 0) return Math.min(i, mRightMargin)
        return mRightMargin - 1
    }

    /** Process byte while in the [.ESC_CSI_QUESTIONMARK] escape state.  */
    private fun doCsiQuestionMark(b: Int) {
        when (b) {
            'J', 'K' -> {
                mAboutToAutoWrap = false
                val fillChar: Int = ' '.toInt()
                var startCol: Int = -1
                var startRow: Int = -1
                var endCol: Int = -1
                var endRow: Int = -1
                val justRow: Boolean = (b == 'K'.toInt())
                when (getArg0(0)) {
                    0 -> {
                        startCol = mCursorCol
                        startRow = mCursorRow
                        endCol = mColumns
                        endRow = if (justRow) (mCursorRow + 1) else mRows
                    }
                    1 -> {
                        startCol = 0
                        startRow = if (justRow) mCursorRow else 0
                        endCol = mCursorCol + 1
                        endRow = mCursorRow + 1
                    }
                    2 -> {
                        startCol = 0
                        startRow = if (justRow) mCursorRow else 0
                        endCol = mColumns
                        endRow = if (justRow) (mCursorRow + 1) else mRows
                    }
                    else -> unknownSequence(b)
                }
                val style: Long = style
                var row: Int = startRow
                while (row < endRow) {
                    var col: Int = startCol
                    while (col < endCol) {
                        if ((TextStyle.decodeEffect(screen.getStyleAt(row, col)) and TextStyle.CHARACTER_ATTRIBUTE_PROTECTED) == 0) screen.setChar(col, row, fillChar, style)
                        col++
                    }
                    row++
                }
            }
            'h', 'l' -> {
                if (mArgIndex >= mArgs.size) mArgIndex = mArgs.size - 1
                var i: Int = 0
                while (i <= mArgIndex) {
                    doDecSetOrReset(b == 'h'.toInt(), mArgs.get(i))
                    i++
                }
            }
            'n' -> when (getArg0(-1)) {
                6 ->                         // Extended Cursor Position (DECXCPR - http://www.vt100.net/docs/vt510-rm/DECXCPR). Page=1.
                    mSession.write(String.format(Locale.US, "\u001b[?%d;%d;1R", mCursorRow + 1, mCursorCol + 1))
                else -> {
                    finishSequence()
                    return
                }
            }
            'r', 's' -> {
                if (mArgIndex >= mArgs.size) mArgIndex = mArgs.size - 1
                var i: Int = 0
                while (i <= mArgIndex) {
                    val externalBit: Int = mArgs.get(i)
                    val internalBit: Int = mapDecSetBitToInternalBit(externalBit)
                    if (internalBit == -1) {
                        Log.w(EmulatorDebug.LOG_TAG, "Ignoring request to save/recall decset bit=" + externalBit)
                    } else {
                        if (b == 's'.toInt()) {
                            mSavedDecSetFlags = mSavedDecSetFlags or internalBit
                        } else {
                            doDecSetOrReset((mSavedDecSetFlags and internalBit) != 0, externalBit)
                        }
                    }
                    i++
                }
            }
            '$' -> {
                continueSequence(ESC_CSI_QUESTIONMARK_ARG_DOLLAR)
                return
            }
            else -> parseArg(b)
        }
    }

    fun doDecSetOrReset(setting: Boolean, externalBit: Int) {
        val internalBit: Int = mapDecSetBitToInternalBit(externalBit)
        if (internalBit != -1) {
            setDecsetinternalBit(internalBit, setting)
        }
        when (externalBit) {
            1 -> {
            }
            3 -> {
                run({
                    mTopMargin = 0
                    mLeftMargin = mTopMargin
                })
                mBottomMargin = mRows
                mRightMargin = mColumns
                // "DECCOLM resets vertical split screen mode (DECLRMM) to unavailable":
                setDecsetinternalBit(DECSET_BIT_LEFTRIGHT_MARGIN_MODE, false)
                // "Erases all data in page memory":
                blockClear(0, 0, mColumns, mRows)
                setCursorRowCol(0, 0)
            }
            4 -> {
            }
            5 -> {
            }
            6 -> if (setting) setCursorPosition(0, 0)
            7, 8, 9, 12, 25, 40, 45, 66 -> {
            }
            69 -> if (!setting) {
                mLeftMargin = 0
                mRightMargin = mColumns
            }
            1000, 1001, 1002, 1003, 1004, 1005, 1006, 1015, 1034 -> {
            }
            1048 -> if (setting) saveCursor() else restoreCursor()
            47, 1047, 1049 -> {

                // Set: Save cursor as in DECSC and use Alternate Screen Buffer, clearing it first.
                // Reset: Use Normal Screen Buffer and restore cursor as in DECRC.
                val newScreen: TerminalBuffer = if (setting) mAltBuffer else mMainBuffer
                if (newScreen != screen) {
                    val resized: Boolean = !(newScreen.mColumns == mColumns && newScreen.mScreenRows == mRows)
                    if (setting) saveCursor()
                    screen = newScreen
                    if (!setting) {
                        val col: Int = mSavedStateMain.mSavedCursorCol
                        val row: Int = mSavedStateMain.mSavedCursorRow
                        restoreCursor()
                        if (resized) {
                            // Restore cursor position _not_ clipped to current screen (let resizeScreen() handle that):
                            mCursorCol = col
                            mCursorRow = row
                        }
                    }
                    // Check if buffer size needs to be updated:
                    if (resized) resizeScreen()
                    // Clear new screen if alt buffer:
                    if (newScreen == mAltBuffer) newScreen.blockSet(0, 0, mColumns, mRows, ' '.toInt(), style)
                }
            }
            2004 -> {
            }
            else -> unknownParameter(externalBit)
        }
    }

    private fun doCsiBiggerThan(b: Int) {
        when (b) {
            'c' ->                 // Originally this was used for the terminal to respond with "identification code, firmware version level,
                // and hardware options" (http://vt100.net/docs/vt510-rm/DA2), with the first "41" meaning the VT420
                // terminal type. This is not used anymore, but the second version level field has been changed by xterm
                // to mean it's release number ("patch numbers" listed at http://invisible-island.net/xterm/xterm.log.html),
                // and some applications use it as a feature check:
                // * tmux used to have a "xterm won't reach version 500 for a while so set that as the upper limit" check,
                // and then check "xterm_version > 270" if rectangular area operations such as DECCRA could be used.
                // * vim checks xterm version number >140 for "Request termcap/terminfo string" functionality >276 for SGR
                // mouse report.
                // The third number is a keyboard identifier not used nowadays.
                mSession.write("\u001b[>41;320;0c")
            'm' ->                 // https://bugs.launchpad.net/gnome-terminal/+bug/96676/comments/25
                // Depending on the first number parameter, this can set one of the xterm resources
                // modifyKeyboard, modifyCursorKeys, modifyFunctionKeys and modifyOtherKeys.
                // http://invisible-island.net/xterm/manpage/xterm.html#RESOURCES

                // * modifyKeyboard (parameter=1):
                // Normally xterm makes a special case regarding modifiers (shift, control, etc.) to handle special keyboard
                // layouts (legacy and vt220). This is done to provide compatible keyboards for DEC VT220 and related
                // terminals that implement user-defined keys (UDK).
                // The bits of the resource value selectively enable modification of the given category when these keyboards
                // are selected. The default is "0":
                // (0) The legacy/vt220 keyboards interpret only the Control-modifier when constructing numbered
                // function-keys. Other special keys are not modified.
                // (1) allows modification of the numeric keypad
                // (2) allows modification of the editing keypad
                // (4) allows modification of function-keys, overrides use of Shift-modifier for UDK.
                // (8) allows modification of other special keys

                // * modifyCursorKeys (parameter=2):
                // Tells how to handle the special case where Control-, Shift-, Alt- or Meta-modifiers are used to add a
                // parameter to the escape sequence returned by a cursor-key. The default is "2".
                // - Set it to -1 to disable it.
                // - Set it to 0 to use the old/obsolete behavior.
                // - Set it to 1 to prefix modified sequences with CSI.
                // - Set it to 2 to force the modifier to be the second parameter if it would otherwise be the first.
                // - Set it to 3 to mark the sequence with a ">" to hint that it is private.

                // * modifyFunctionKeys (parameter=3):
                // Tells how to handle the special case where Control-, Shift-, Alt- or Meta-modifiers are used to add a
                // parameter to the escape sequence returned by a (numbered) function-
                // key. The default is "2". The resource values are similar to modifyCursorKeys:
                // Set it to -1 to permit the user to use shift- and control-modifiers to construct function-key strings
                // using the normal encoding scheme.
                // - Set it to 0 to use the old/obsolete behavior.
                // - Set it to 1 to prefix modified sequences with CSI.
                // - Set it to 2 to force the modifier to be the second parameter if it would otherwise be the first.
                // - Set it to 3 to mark the sequence with a ">" to hint that it is private.
                // If modifyFunctionKeys is zero, xterm uses Control- and Shift-modifiers to allow the user to construct
                // numbered function-keys beyond the set provided by the keyboard:
                // (Control) adds the value given by the ctrlFKeys resource.
                // (Shift) adds twice the value given by the ctrlFKeys resource.
                // (Control/Shift) adds three times the value given by the ctrlFKeys resource.
                //
                // As a special case, legacy (when oldFunctionKeys is true) or vt220 (when sunKeyboard is true)
                // keyboards interpret only the Control-modifier when constructing numbered function-keys.
                // This is done to provide compatible keyboards for DEC VT220 and related terminals that
                // implement user-defined keys (UDK).

                // * modifyOtherKeys (parameter=4):
                // Like modifyCursorKeys, tells xterm to construct an escape sequence for other keys (such as "2") when
                // modified by Control-, Alt- or Meta-modifiers. This feature does not apply to function keys and
                // well-defined keys such as ESC or the control keys. The default is "0".
                // (0) disables this feature.
                // (1) enables this feature for keys except for those with well-known behavior, e.g., Tab, Backarrow and
                // some special control character cases, e.g., Control-Space to make a NUL.
                // (2) enables this feature for keys including the exceptions listed.
                Log.e(EmulatorDebug.LOG_TAG, "(ignored) CSI > MODIFY RESOURCE: " + getArg0(-1) + " to " + getArg1(-1))
            else -> parseArg(b)
        }
    }

    private fun startEscapeSequence() {
        mEscapeState = ESC
        mArgIndex = 0
        Arrays.fill(mArgs, -1)
    }

    private fun doLinefeed() {
        val belowScrollingRegion: Boolean = mCursorRow >= mBottomMargin
        var newCursorRow: Int = mCursorRow + 1
        if (belowScrollingRegion) {
            // Move down (but not scroll) as long as we are above the last row.
            if (mCursorRow != mRows - 1) {
                cursorRow = newCursorRow
            }
        } else {
            if (newCursorRow == mBottomMargin) {
                scrollDownOneLine()
                newCursorRow = mBottomMargin - 1
            }
            cursorRow = newCursorRow
        }
    }

    private fun continueSequence(state: Int) {
        mEscapeState = state
        mContinueSequence = true
    }

    private fun doEscPound(b: Int) {
        when (b) {
            '8' -> screen.blockSet(0, 0, mColumns, mRows, 'E'.toInt(), style)
            else -> unknownSequence(b)
        }
    }

    /** Encountering a character in the [.ESC] state.  */
    private fun doEsc(b: Int) {
        when (b) {
            '#' -> continueSequence(ESC_POUND)
            '(' -> continueSequence(ESC_SELECT_LEFT_PAREN)
            ')' -> continueSequence(ESC_SELECT_RIGHT_PAREN)
            '6' -> if (mCursorCol > mLeftMargin) {
                mCursorCol--
            } else {
                val rows: Int = mBottomMargin - mTopMargin
                screen.blockCopy(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin - 1, rows, mLeftMargin + 1, mTopMargin)
                screen.blockSet(mLeftMargin, mTopMargin, 1, rows, ' '.toInt(), TextStyle.encode(mForeColor, mBackColor, 0))
            }
            '7' -> saveCursor()
            '8' -> restoreCursor()
            '9' -> if (mCursorCol < mRightMargin - 1) {
                mCursorCol++
            } else {
                val rows: Int = mBottomMargin - mTopMargin
                screen.blockCopy(mLeftMargin + 1, mTopMargin, mRightMargin - mLeftMargin - 1, rows, mLeftMargin, mTopMargin)
                screen.blockSet(mRightMargin - 1, mTopMargin, 1, rows, ' '.toInt(), TextStyle.encode(mForeColor, mBackColor, 0))
            }
            'c' -> {
                reset()
                mMainBuffer.clearTranscript()
                blockClear(0, 0, mColumns, mRows)
                setCursorPosition(0, 0)
            }
            'D' -> doLinefeed()
            'E' -> {
                cursorCol = if (isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)) mLeftMargin else 0
                doLinefeed()
            }
            'F' -> setCursorRowCol(0, mBottomMargin - 1)
            'H' -> mTabStop.get(mCursorCol) = true
            'M' ->                 // http://www.vt100.net/docs/vt100-ug/chapter3.html: "Move the active position to the same horizontal
                // position on the preceding line. If the active position is at the top margin, a scroll down is performed".
                if (mCursorRow <= mTopMargin) {
                    screen.blockCopy(0, mTopMargin, mColumns, mBottomMargin - (mTopMargin + 1), 0, mTopMargin + 1)
                    blockClear(0, mTopMargin, mColumns)
                } else {
                    mCursorRow--
                }
            'N', '0' -> {
            }
            'P' -> {
                mOSCOrDeviceControlArgs.setLength(0)
                continueSequence(ESC_P)
            }
            '[' -> continueSequence(ESC_CSI)
            '=' -> setDecsetinternalBit(DECSET_BIT_APPLICATION_KEYPAD, true)
            ']' -> {
                mOSCOrDeviceControlArgs.setLength(0)
                continueSequence(ESC_OSC)
            }
            '>' -> setDecsetinternalBit(DECSET_BIT_APPLICATION_KEYPAD, false)
            else -> unknownSequence(b)
        }
    }

    /** DECSC save cursor - http://www.vt100.net/docs/vt510-rm/DECSC . See [.restoreCursor].  */
    private fun saveCursor() {
        val state: SavedScreenState = if ((screen == mMainBuffer)) mSavedStateMain else mSavedStateAlt
        state.mSavedCursorRow = mCursorRow
        state.mSavedCursorCol = mCursorCol
        state.mSavedEffect = mEffect
        state.mSavedForeColor = mForeColor
        state.mSavedBackColor = mBackColor
        state.mSavedDecFlags = mCurrentDecSetFlags
        state.mUseLineDrawingG0 = mUseLineDrawingG0
        state.mUseLineDrawingG1 = mUseLineDrawingG1
        state.mUseLineDrawingUsesG0 = mUseLineDrawingUsesG0
    }

    /** DECRS restore cursor - http://www.vt100.net/docs/vt510-rm/DECRC. See [.saveCursor].  */
    private fun restoreCursor() {
        val state: SavedScreenState = if ((screen == mMainBuffer)) mSavedStateMain else mSavedStateAlt
        setCursorRowCol(state.mSavedCursorRow, state.mSavedCursorCol)
        mEffect = state.mSavedEffect
        mForeColor = state.mSavedForeColor
        mBackColor = state.mSavedBackColor
        val mask: Int = (DECSET_BIT_AUTOWRAP or DECSET_BIT_ORIGIN_MODE)
        mCurrentDecSetFlags = (mCurrentDecSetFlags and mask.inv()) or (state.mSavedDecFlags and mask)
        mUseLineDrawingG0 = state.mUseLineDrawingG0
        mUseLineDrawingG1 = state.mUseLineDrawingG1
        mUseLineDrawingUsesG0 = state.mUseLineDrawingUsesG0
    }

    /** Following a CSI - Control Sequence Introducer, "\033[". [.ESC_CSI].  */
    private fun doCsi(b: Int) {
        when (b) {
            '!' -> continueSequence(ESC_CSI_EXCLAMATION)
            '"' -> continueSequence(ESC_CSI_DOUBLE_QUOTE)
            '\'' -> continueSequence(ESC_CSI_SINGLE_QUOTE)
            '$' -> continueSequence(ESC_CSI_DOLLAR)
            '*' -> continueSequence(ESC_CSI_ARGS_ASTERIX)
            '@' -> {

                // "CSI{n}@" - Insert ${n} space characters (ICH) - http://www.vt100.net/docs/vt510-rm/ICH.
                mAboutToAutoWrap = false
                val columnsAfterCursor: Int = mColumns - mCursorCol
                val spacesToInsert: Int = Math.min(getArg0(1), columnsAfterCursor)
                val charsToMove: Int = columnsAfterCursor - spacesToInsert
                screen.blockCopy(mCursorCol, mCursorRow, charsToMove, 1, mCursorCol + spacesToInsert, mCursorRow)
                blockClear(mCursorCol, mCursorRow, spacesToInsert)
            }
            'A' -> cursorRow = Math.max(0, mCursorRow - getArg0(1))
            'B' -> cursorRow = Math.min(mRows - 1, mCursorRow + getArg0(1))
            'C', 'a' -> cursorCol = Math.min(mRightMargin - 1, mCursorCol + getArg0(1))
            'D' -> cursorCol = Math.max(mLeftMargin, mCursorCol - getArg0(1))
            'E' -> setCursorPosition(0, mCursorRow + getArg0(1))
            'F' -> setCursorPosition(0, mCursorRow - getArg0(1))
            'G' -> cursorCol = Math.min(Math.max(1, getArg0(1)), mColumns) - 1
            'H', 'f' -> setCursorPosition(getArg1(1) - 1, getArg0(1) - 1)
            'I' -> cursorCol = nextTabStop(getArg0(1))
            'J' -> {
                when (getArg0(0)) {
                    0 -> {
                        blockClear(mCursorCol, mCursorRow, mColumns - mCursorCol)
                        blockClear(0, mCursorRow + 1, mColumns, mRows - (mCursorRow + 1))
                    }
                    1 -> {
                        blockClear(0, 0, mColumns, mCursorRow)
                        blockClear(0, mCursorRow, mCursorCol + 1)
                    }
                    2 ->                         // move..
                        blockClear(0, 0, mColumns, mRows)
                    3 -> mMainBuffer.clearTranscript()
                    else -> {
                        unknownSequence(b)
                        return
                    }
                }
                mAboutToAutoWrap = false
            }
            'K' -> {
                when (getArg0(0)) {
                    0 -> blockClear(mCursorCol, mCursorRow, mColumns - mCursorCol)
                    1 -> blockClear(0, mCursorRow, mCursorCol + 1)
                    2 -> blockClear(0, mCursorRow, mColumns)
                    else -> {
                        unknownSequence(b)
                        return
                    }
                }
                mAboutToAutoWrap = false
            }
            'L' -> {
                val linesAfterCursor: Int = mBottomMargin - mCursorRow
                val linesToInsert: Int = Math.min(getArg0(1), linesAfterCursor)
                val linesToMove: Int = linesAfterCursor - linesToInsert
                screen.blockCopy(0, mCursorRow, mColumns, linesToMove, 0, mCursorRow + linesToInsert)
                blockClear(0, mCursorRow, mColumns, linesToInsert)
            }
            'M' -> {
                mAboutToAutoWrap = false
                val linesAfterCursor: Int = mBottomMargin - mCursorRow
                val linesToDelete: Int = Math.min(getArg0(1), linesAfterCursor)
                val linesToMove: Int = linesAfterCursor - linesToDelete
                screen.blockCopy(0, mCursorRow + linesToDelete, mColumns, linesToMove, 0, mCursorRow)
                blockClear(0, mCursorRow + linesToMove, mColumns, linesToDelete)
            }
            'P' -> {

                // http://www.vt100.net/docs/vt510-rm/DCH: "If ${N} is greater than the number of characters between the
                // cursor and the right margin, then DCH only deletes the remaining characters.
                // As characters are deleted, the remaining characters between the cursor and right margin move to the left.
                // Character attributes move with the characters. The terminal adds blank spaces with no visual character
                // attributes at the right margin. DCH has no effect outside the scrolling margins."
                mAboutToAutoWrap = false
                val cellsAfterCursor: Int = mColumns - mCursorCol
                val cellsToDelete: Int = Math.min(getArg0(1), cellsAfterCursor)
                val cellsToMove: Int = cellsAfterCursor - cellsToDelete
                screen.blockCopy(mCursorCol + cellsToDelete, mCursorRow, cellsToMove, 1, mCursorCol, mCursorRow)
                blockClear(mCursorCol + cellsToMove, mCursorRow, cellsToDelete)
            }
            'S' -> {
                // "${CSI}${N}S" - scroll up ${N} lines (default = 1) (SU).
                val linesToScroll: Int = getArg0(1)
                var i: Int = 0
                while (i < linesToScroll) {
                    scrollDownOneLine()
                    i++
                }
            }
            'T' -> if (mArgIndex == 0) {
                // "${CSI}${N}T" - Scroll down N lines (default = 1) (SD).
                // http://vt100.net/docs/vt510-rm/SD: "N is the number of lines to move the user window up in page
                // memory. N new lines appear at the top of the display. N old lines disappear at the bottom of the
                // display. You cannot pan past the top margin of the current page".
                val linesToScrollArg: Int = getArg0(1)
                val linesBetweenTopAndBottomMargins: Int = mBottomMargin - mTopMargin
                val linesToScroll: Int = Math.min(linesBetweenTopAndBottomMargins, linesToScrollArg)
                screen.blockCopy(0, mTopMargin, mColumns, linesBetweenTopAndBottomMargins - linesToScroll, 0, mTopMargin + linesToScroll)
                blockClear(0, mTopMargin, mColumns, linesToScroll)
            } else {
                // "${CSI}${func};${startx};${starty};${firstrow};${lastrow}T" - initiate highlight mouse tracking.
                unimplementedSequence(b)
            }
            'X' -> {
                mAboutToAutoWrap = false
                screen.blockSet(mCursorCol, mCursorRow, Math.min(getArg0(1), mColumns - mCursorCol), 1, ' '.toInt(), style)
            }
            'Z' -> {
                var numberOfTabs: Int = getArg0(1)
                var newCol: Int = mLeftMargin
                var i: Int = mCursorCol - 1
                while (i >= 0) {
                    if (mTabStop.get(i)) {
                        if (--numberOfTabs == 0) {
                            newCol = Math.max(i, mLeftMargin)
                            break
                        }
                    }
                    i--
                }
                mCursorCol = newCol
            }
            '?' -> continueSequence(ESC_CSI_QUESTIONMARK)
            '>' -> continueSequence(ESC_CSI_BIGGERTHAN)
            '`' -> setCursorColRespectingOriginMode(getArg0(1) - 1)
            'b' -> {
                if (mLastEmittedCodePoint == -1) break
                val numRepeat: Int = getArg0(1)
                var i: Int = 0
                while (i < numRepeat) {
                    emitCodePoint(mLastEmittedCodePoint)
                    i++
                }
            }
            'c' ->                 // The important part that may still be used by some (tmux stores this value but does not currently use it)
                // is the first response parameter identifying the terminal service class, where we send 64 for "vt420".
                // This is followed by a list of attributes which is probably unused by applications. Send like xterm.
                if (getArg0(0) == 0) mSession.write("\u001b[?64;1;2;6;9;15;18;21;22c")
            'd' -> cursorRow = Math.min(Math.max(1, getArg0(1)), mRows) - 1
            'e' -> setCursorPosition(mCursorCol, mCursorRow + getArg0(1))
            'g' -> when (getArg0(0)) {
                0 -> mTabStop.get(mCursorCol) = false
                3 -> {
                    var i: Int = 0
                    while (i < mColumns) {
                        mTabStop.get(i) = false
                        i++
                    }
                }
                else -> {
                }
            }
            'h' -> doSetMode(true)
            'l' -> doSetMode(false)
            'm' -> selectGraphicRendition()
            'n' -> when (getArg0(0)) {
                5 -> {
                    // Answer is ESC [ 0 n (Terminal OK).
                    val dsr: ByteArray = byteArrayOf(27.toByte(), '['.toByte(), '0'.toByte(), 'n'.toByte())
                    mSession.write(dsr, 0, dsr.size)
                }
                6 ->                         // Answer is ESC [ y ; x R, where x,y is
                    // the cursor location.
                    mSession.write(String.format(Locale.US, "\u001b[%d;%dR", mCursorRow + 1, mCursorCol + 1))
                else -> {
                }
            }
            'r' -> {

                // https://vt100.net/docs/vt510-rm/DECSTBM.html
                // The top margin defaults to 1, the bottom margin defaults to mRows.
                // The escape sequence numbers top 1..23, but we number top 0..22.
                // The escape sequence numbers bottom 2..24, and so do we (because we use a zero based numbering
                // scheme, but we store the first line below the bottom-most scrolling line.
                // As a result, we adjust the top line by -1, but we leave the bottom line alone.
                // Also require that top + 2 <= bottom.
                mTopMargin = Math.max(0, Math.min(getArg0(1) - 1, mRows - 2))
                mBottomMargin = Math.max(mTopMargin + 2, Math.min(getArg1(mRows), mRows))

                // DECSTBM moves the cursor to column 1, line 1 of the page respecting origin mode.
                setCursorPosition(0, 0)
            }
            's' -> if (isDecsetInternalBitSet(DECSET_BIT_LEFTRIGHT_MARGIN_MODE)) {
                // Set left and right margins (DECSLRM - http://www.vt100.net/docs/vt510-rm/DECSLRM).
                mLeftMargin = Math.min(getArg0(1) - 1, mColumns - 2)
                mRightMargin = Math.max(mLeftMargin + 1, Math.min(getArg1(mColumns), mColumns))
                // DECSLRM moves the cursor to column 1, line 1 of the page.
                setCursorPosition(0, 0)
            } else {
                // Save cursor (ANSI.SYS), available only when DECLRMM is disabled.
                saveCursor()
            }
            't' -> when (getArg0(0)) {
                11 -> mSession.write("\u001b[1t")
                13 -> mSession.write("\u001b[3;0;0t")
                14 ->                         // We just report characters time 12 here.
                    mSession.write(String.format(Locale.US, "\u001b[4;%d;%dt", mRows * 12, mColumns * 12))
                18 -> mSession.write(String.format(Locale.US, "\u001b[8;%d;%dt", mRows, mColumns))
                19 ->                         // We report the same size as the view, since it's the view really isn't resizable from the shell.
                    mSession.write(String.format(Locale.US, "\u001b[9;%d;%dt", mRows, mColumns))
                20 -> mSession.write("\u001b]LIconLabel\u001b\\")
                21 -> mSession.write("\u001b]l\u001b\\")
                22 -> {
                    // 22;0 -> Save xterm icon and window title on stack.
                    // 22;1 -> Save xterm icon title on stack.
                    // 22;2 -> Save xterm window title on stack.
                    mTitleStack.push(mTitle)
                    if (mTitleStack.size > 20) {
                        // Limit size
                        mTitleStack.removeAt(0)
                    }
                }
                23 -> if (!mTitleStack.isEmpty()) title = mTitleStack.pop()
                else -> {
                }
            }
            'u' -> restoreCursor()
            ' ' -> continueSequence(ESC_CSI_ARGS_SPACE)
            else -> parseArg(b)
        }
    }

    /** Select Graphic Rendition (SGR) - see http://en.wikipedia.org/wiki/ANSI_escape_code#graphics.  */
    private fun selectGraphicRendition() {
        if (mArgIndex >= mArgs.size) mArgIndex = mArgs.size - 1
        var i: Int = 0
        while (i <= mArgIndex) {
            var code: Int = mArgs.get(i)
            if (code < 0) {
                if (mArgIndex > 0) {
                    i++
                    continue
                } else {
                    code = 0
                }
            }
            if (code == 0) { // reset
                mForeColor = TextStyle.COLOR_INDEX_FOREGROUND
                mBackColor = TextStyle.COLOR_INDEX_BACKGROUND
                mEffect = 0
            } else if (code == 1) {
                mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_BOLD
            } else if (code == 2) {
                mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_DIM
            } else if (code == 3) {
                mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_ITALIC
            } else if (code == 4) {
                mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
            } else if (code == 5) {
                mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_BLINK
            } else if (code == 7) {
                mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_INVERSE
            } else if (code == 8) {
                mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE
            } else if (code == 9) {
                mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH
            } else if (code == 10) {
                // Exit alt charset (TERM=linux) - ignore.
            } else if (code == 11) {
                // Enter alt charset (TERM=linux) - ignore.
            } else if (code == 22) { // Normal color or intensity, neither bright, bold nor faint.
                mEffect = mEffect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_DIM).inv()
            } else if (code == 23) { // not italic, but rarely used as such; clears standout with TERM=screen
                mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC.inv()
            } else if (code == 24) { // underline: none
                mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE.inv()
            } else if (code == 25) { // blink: none
                mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_BLINK.inv()
            } else if (code == 27) { // image: positive
                mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE.inv()
            } else if (code == 28) {
                mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE.inv()
            } else if (code == 29) {
                mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH.inv()
            } else if (code >= 30 && code <= 37) {
                mForeColor = code - 30
            } else if (code == 38 || code == 48) {
                // Extended set foreground(38)/background (48) color.
                // This is followed by either "2;$R;$G;$B" to set a 24-bit color or
                // "5;$INDEX" to set an indexed color.
                if (i + 2 > mArgIndex) {
                    i++
                    continue
                }
                val firstArg: Int = mArgs.get(i + 1)
                if (firstArg == 2) {
                    if (i + 4 > mArgIndex) {
                        Log.w(EmulatorDebug.LOG_TAG, "Too few CSI" + code + ";2 RGB arguments")
                    } else {
                        val red: Int = mArgs.get(i + 2)
                        val green: Int = mArgs.get(i + 3)
                        val blue: Int = mArgs.get(i + 4)
                        if ((red < 0) || (green < 0) || (blue < 0) || (red > 255) || (green > 255) || (blue > 255)) {
                            finishSequenceAndLogError("Invalid RGB: " + red + "," + green + "," + blue)
                        } else {
                            val argbColor: Int = -0x1000000 or (red shl 16) or (green shl 8) or blue
                            if (code == 38) {
                                mForeColor = argbColor
                            } else {
                                mBackColor = argbColor
                            }
                        }
                        i += 4 // "2;P_r;P_g;P_r"
                    }
                } else if (firstArg == 5) {
                    val color: Int = mArgs.get(i + 2)
                    i += 2 // "5;P_s"
                    if (color >= 0 && color < TextStyle.NUM_INDEXED_COLORS) {
                        if (code == 38) {
                            mForeColor = color
                        } else {
                            mBackColor = color
                        }
                    } else {
                        if (LOG_ESCAPE_SEQUENCES) Log.w(EmulatorDebug.LOG_TAG, "Invalid color index: " + color)
                    }
                } else {
                    finishSequenceAndLogError("Invalid ISO-8613-3 SGR first argument: " + firstArg)
                }
            } else if (code == 39) { // Set default foreground color.
                mForeColor = TextStyle.COLOR_INDEX_FOREGROUND
            } else if (code >= 40 && code <= 47) { // Set background color.
                mBackColor = code - 40
            } else if (code == 49) { // Set default background color.
                mBackColor = TextStyle.COLOR_INDEX_BACKGROUND
            } else if (code >= 90 && code <= 97) { // Bright foreground colors (aixterm codes).
                mForeColor = code - 90 + 8
            } else if (code >= 100 && code <= 107) { // Bright background color (aixterm codes).
                mBackColor = code - 100 + 8
            } else {
                if (LOG_ESCAPE_SEQUENCES) Log.w(EmulatorDebug.LOG_TAG, String.format("SGR unknown code %d", code))
            }
            i++
        }
    }

    private fun doOsc(b: Int) {
        when (b) {
            7 -> doOscSetTextParameters("\u0007")
            27 -> continueSequence(ESC_OSC_ESC)
            else -> collectOSCArgs(b)
        }
    }

    private fun doOscEsc(b: Int) {
        when (b) {
            '\\' -> doOscSetTextParameters("\u001b\\")
            else -> {
                // The ESC character was not followed by a \, so insert the ESC and
                // the current character in arg buffer.
                collectOSCArgs(27)
                collectOSCArgs(b)
                continueSequence(ESC_OSC)
            }
        }
    }

    /** An Operating System Controls (OSC) Set Text Parameters. May come here from BEL or ST.  */
    private fun doOscSetTextParameters(bellOrStringTerminator: String) {
        var value: Int = -1
        var textParameter: String = ""
        // Extract initial $value from initial "$value;..." string.
        for (mOSCArgTokenizerIndex in 0 until mOSCOrDeviceControlArgs.length) {
            val b: Char = mOSCOrDeviceControlArgs.get(mOSCArgTokenizerIndex)
            if (b == ';') {
                textParameter = mOSCOrDeviceControlArgs.substring(mOSCArgTokenizerIndex + 1)
                break
            } else if (b >= '0' && b <= '9') {
                value = (if ((value < 0)) 0 else value * 10) + (b - '0')
            } else {
                unknownSequence(b.toInt())
                return
            }
        }
        when (value) {
            0, 1, 2 -> title = textParameter
            4 -> {
                // P s = 4 ; c ; spec → Change Color Number c to the color specified by spec. This can be a name or RGB
                // specification as per XParseColor. Any number of c name pairs may be given. The color numbers correspond
                // to the ANSI colors 0-7, their bright versions 8-15, and if supported, the remainder of the 88-color or
                // 256-color table.
                // If a "?" is given rather than a name or RGB specification, xterm replies with a control sequence of the
                // same form which can be used to set the corresponding color. Because more than one pair of color number
                // and specification can be given in one control sequence, xterm can make more than one reply.
                var colorIndex: Int = -1
                var parsingPairStart: Int = -1
                var i: Int = 0
                while (true) {
                    val endOfInput: Boolean = i == textParameter.length
                    val b: Char = if (endOfInput) ';' else textParameter.get(i)
                    if (b == ';') {
                        if (parsingPairStart < 0) {
                            parsingPairStart = i + 1
                        } else {
                            if (colorIndex < 0 || colorIndex > 255) {
                                unknownSequence(b.toInt())
                                return
                            } else {
                                mColors.tryParseColor(colorIndex, textParameter.substring(parsingPairStart, i))
                                mSession.onColorsChanged()
                                colorIndex = -1
                                parsingPairStart = -1
                            }
                        }
                    } else if (parsingPairStart >= 0) {
                        // We have passed a color index and are now going through color spec.
                    } else if (parsingPairStart < 0 && (b >= '0' && b <= '9')) {
                        colorIndex = (if ((colorIndex < 0)) 0 else colorIndex * 10) + (b - '0')
                    } else {
                        unknownSequence(b.toInt())
                        return
                    }
                    if (endOfInput) break
                    i++
                }
            }
            10, 11, 12 -> {
                var specialIndex: Int = TextStyle.COLOR_INDEX_FOREGROUND + (value - 10)
                var lastSemiIndex: Int = 0
                var charIndex: Int = 0
                while (true) {
                    val endOfInput: Boolean = charIndex == textParameter.length
                    if (endOfInput || textParameter.get(charIndex) == ';') {
                        try {
                            val colorSpec: String = textParameter.substring(lastSemiIndex, charIndex)
                            if (("?" == colorSpec)) {
                                // Report current color in the same format xterm and gnome-terminal does.
                                val rgb: Int = mColors.mCurrentColors.get(specialIndex)
                                val r: Int = (65535 * ((rgb and 0x00FF0000) shr 16)) / 255
                                val g: Int = (65535 * ((rgb and 0x0000FF00) shr 8)) / 255
                                val b: Int = (65535 * ((rgb and 0x000000FF))) / 255
                                mSession.write(("\u001b]" + value + ";rgb:" + String.format(Locale.US, "%04x", r) + "/" + String.format(Locale.US, "%04x", g) + "/"
                                    + String.format(Locale.US, "%04x", b) + bellOrStringTerminator))
                            } else {
                                mColors.tryParseColor(specialIndex, colorSpec)
                                mSession.onColorsChanged()
                            }
                            specialIndex++
                            if (endOfInput || (specialIndex > TextStyle.COLOR_INDEX_CURSOR) || (++charIndex >= textParameter.length)) break
                            lastSemiIndex = charIndex
                        } catch (e: NumberFormatException) {
                            // Ignore.
                        }
                    }
                    charIndex++
                }
            }
            52 -> {
                val startIndex: Int = textParameter.indexOf(";") + 1
                try {
                    val clipboardText: String = String(Base64.decode(textParameter.substring(startIndex), 0), StandardCharsets.UTF_8)
                    mSession.clipboardText(clipboardText)
                } catch (e: Exception) {
                    Log.e(EmulatorDebug.LOG_TAG, "OSC Manipulate selection, invalid string '" + textParameter + "")
                }
            }
            104 ->                 // "104;$c" → Reset Color Number $c. It is reset to the color specified by the corresponding X
                // resource. Any number of c parameters may be given. These parameters correspond to the ANSI colors 0-7,
                // their bright versions 8-15, and if supported, the remainder of the 88-color or 256-color table. If no
                // parameters are given, the entire table will be reset.
                if (textParameter.isEmpty()) {
                    mColors.reset()
                    mSession.onColorsChanged()
                } else {
                    var lastIndex: Int = 0
                    var charIndex: Int = 0
                    while (true) {
                        val endOfInput: Boolean = charIndex == textParameter.length
                        if (endOfInput || textParameter.get(charIndex) == ';') {
                            try {
                                val colorToReset: Int = textParameter.substring(lastIndex, charIndex).toInt()
                                mColors.reset(colorToReset)
                                mSession.onColorsChanged()
                                if (endOfInput) break
                                charIndex++
                                lastIndex = charIndex
                            } catch (e: NumberFormatException) {
                                // Ignore.
                            }
                        }
                        charIndex++
                    }
                }
            110, 111, 112 -> {
                mColors.reset(TextStyle.COLOR_INDEX_FOREGROUND + (value - 110))
                mSession.onColorsChanged()
            }
            119 -> {
            }
            else -> unknownParameter(value)
        }
        finishSequence()
    }

    private fun blockClear(sx: Int, sy: Int, w: Int, h: Int = 1) {
        screen.blockSet(sx, sy, w, h, ' '.toInt(), style)
    }

    private val style: Long
        private get() {
            return TextStyle.encode(mForeColor, mBackColor, mEffect)
        }

    /** "CSI P_m h" for set or "CSI P_m l" for reset ANSI mode.  */
    private fun doSetMode(newValue: Boolean) {
        val modeBit: Int = getArg0(0)
        when (modeBit) {
            4 -> mInsertMode = newValue
            20 -> unknownParameter(modeBit)
            34 -> {
            }
            else -> unknownParameter(modeBit)
        }
    }

    /**
     * NOTE: The parameters of this function respect the [.DECSET_BIT_ORIGIN_MODE]. Use
     * [.setCursorRowCol] for absolute pos.
     */
    private fun setCursorPosition(x: Int, y: Int) {
        val originMode: Boolean = isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)
        val effectiveTopMargin: Int = if (originMode) mTopMargin else 0
        val effectiveBottomMargin: Int = if (originMode) mBottomMargin else mRows
        val effectiveLeftMargin: Int = if (originMode) mLeftMargin else 0
        val effectiveRightMargin: Int = if (originMode) mRightMargin else mColumns
        val newRow: Int = Math.max(effectiveTopMargin, Math.min(effectiveTopMargin + y, effectiveBottomMargin - 1))
        val newCol: Int = Math.max(effectiveLeftMargin, Math.min(effectiveLeftMargin + x, effectiveRightMargin - 1))
        setCursorRowCol(newRow, newCol)
    }

    private fun scrollDownOneLine() {
        scrollCounter++
        if (mLeftMargin != 0 || mRightMargin != mColumns) {
            // Horizontal margin: Do not put anything into scroll history, just non-margin part of screen up.
            screen.blockCopy(mLeftMargin, mTopMargin + 1, mRightMargin - mLeftMargin, mBottomMargin - mTopMargin - 1, mLeftMargin, mTopMargin)
            // .. and blank bottom row between margins:
            screen.blockSet(mLeftMargin, mBottomMargin - 1, mRightMargin - mLeftMargin, 1, ' '.toInt(), mEffect.toLong())
        } else {
            screen.scrollDownOneLine(mTopMargin, mBottomMargin, style)
        }
    }

    /** Process the next ASCII character of a parameter.  */
    private fun parseArg(b: Int) {
        if (b >= '0'.toInt() && b <= '9'.toInt()) {
            if (mArgIndex < mArgs.size) {
                val oldValue: Int = mArgs.get(mArgIndex)
                val thisDigit: Int = b - '0'.toInt()
                val value: Int
                if (oldValue >= 0) {
                    value = oldValue * 10 + thisDigit
                } else {
                    value = thisDigit
                }
                mArgs.get(mArgIndex) = value
            }
            continueSequence(mEscapeState)
        } else if (b == ';'.toInt()) {
            if (mArgIndex < mArgs.size) {
                mArgIndex++
            }
            continueSequence(mEscapeState)
        } else {
            unknownSequence(b)
        }
    }

    private fun getArg0(defaultValue: Int): Int {
        return getArg(0, defaultValue, true)
    }

    private fun getArg1(defaultValue: Int): Int {
        return getArg(1, defaultValue, true)
    }

    private fun getArg(index: Int, defaultValue: Int, treatZeroAsDefault: Boolean): Int {
        var result: Int = mArgs.get(index)
        if (result < 0 || (result == 0 && treatZeroAsDefault)) {
            result = defaultValue
        }
        return result
    }

    private fun collectOSCArgs(b: Int) {
        if (mOSCOrDeviceControlArgs.length < MAX_OSC_STRING_LENGTH) {
            mOSCOrDeviceControlArgs.appendCodePoint(b)
            continueSequence(mEscapeState)
        } else {
            unknownSequence(b)
        }
    }

    private fun unimplementedSequence(b: Int) {
        logError("Unimplemented sequence char '" + b.toChar() + "' (U+" + String.format("%04x", b) + ")")
        finishSequence()
    }

    private fun unknownSequence(b: Int) {
        logError("Unknown sequence char '" + b.toChar() + "' (numeric value=" + b + ")")
        finishSequence()
    }

    private fun unknownParameter(parameter: Int) {
        logError("Unknown parameter: " + parameter)
        finishSequence()
    }

    private fun logError(errorType: String) {
        if (LOG_ESCAPE_SEQUENCES) {
            val buf: StringBuilder = StringBuilder()
            buf.append(errorType)
            buf.append(", escapeState=")
            buf.append(mEscapeState)
            var firstArg: Boolean = true
            if (mArgIndex >= mArgs.size) mArgIndex = mArgs.size - 1
            for (i in 0..mArgIndex) {
                val value: Int = mArgs.get(i)
                if (value >= 0) {
                    if (firstArg) {
                        firstArg = false
                        buf.append(", args={")
                    } else {
                        buf.append(',')
                    }
                    buf.append(value)
                }
            }
            if (!firstArg) buf.append('}')
            finishSequenceAndLogError(buf.toString())
        }
    }

    private fun finishSequenceAndLogError(error: String) {
        if (LOG_ESCAPE_SEQUENCES) Log.w(EmulatorDebug.LOG_TAG, error)
        finishSequence()
    }

    private fun finishSequence() {
        mEscapeState = ESC_NONE
    }

    /**
     * Send a Unicode code point to the screen.
     *
     * @param codePoint The code point of the character to display
     */
    private fun emitCodePoint(codePoint: Int) {
        var codePoint: Int = codePoint
        mLastEmittedCodePoint = codePoint
        if (if (mUseLineDrawingUsesG0) mUseLineDrawingG0 else mUseLineDrawingG1) {
            // http://www.vt100.net/docs/vt102-ug/table5-15.html.
            when (codePoint) {
                '_' -> codePoint = ' '.toInt() // Blank.
                '`' -> codePoint = '◆'.toInt() // Diamond.
                '0' -> codePoint = '█'.toInt() // Solid block;
                'a' -> codePoint = '▒'.toInt() // Checker board.
                'b' -> codePoint = '␉'.toInt() // Horizontal tab.
                'c' -> codePoint = '␌'.toInt() // Form feed.
                'd' -> codePoint = '\r'.toInt() // Carriage return.
                'e' -> codePoint = '␊'.toInt() // Linefeed.
                'f' -> codePoint = '°'.toInt() // Degree.
                'g' -> codePoint = '±'.toInt() // Plus-minus.
                'h' -> codePoint = '\n'.toInt() // Newline.
                'i' -> codePoint = '␋'.toInt() // Vertical tab.
                'j' -> codePoint = '┘'.toInt() // Lower right corner.
                'k' -> codePoint = '┐'.toInt() // Upper right corner.
                'l' -> codePoint = '┌'.toInt() // Upper left corner.
                'm' -> codePoint = '└'.toInt() // Left left corner.
                'n' -> codePoint = '┼'.toInt() // Crossing lines.
                'o' -> codePoint = '⎺'.toInt() // Horizontal line - scan 1.
                'p' -> codePoint = '⎻'.toInt() // Horizontal line - scan 3.
                'q' -> codePoint = '─'.toInt() // Horizontal line - scan 5.
                'r' -> codePoint = '⎼'.toInt() // Horizontal line - scan 7.
                's' -> codePoint = '⎽'.toInt() // Horizontal line - scan 9.
                't' -> codePoint = '├'.toInt() // T facing rightwards.
                'u' -> codePoint = '┤'.toInt() // T facing leftwards.
                'v' -> codePoint = '┴'.toInt() // T facing upwards.
                'w' -> codePoint = '┬'.toInt() // T facing downwards.
                'x' -> codePoint = '│'.toInt() // Vertical line.
                'y' -> codePoint = '≤'.toInt() // Less than or equal to.
                'z' -> codePoint = '≥'.toInt() // Greater than or equal to.
                '{' -> codePoint = 'π'.toInt() // Pi.
                '|' -> codePoint = '≠'.toInt() // Not equal to.
                '}' -> codePoint = '£'.toInt() // UK pound.
                '~' -> codePoint = '·'.toInt() // Centered dot.
            }
        }
        val autoWrap: Boolean = isDecsetInternalBitSet(DECSET_BIT_AUTOWRAP)
        val displayWidth: Int = WcWidth.width(codePoint)
        val cursorInLastColumn: Boolean = mCursorCol == mRightMargin - 1
        if (autoWrap) {
            if (cursorInLastColumn && ((mAboutToAutoWrap && displayWidth == 1) || displayWidth == 2)) {
                screen.setLineWrap(mCursorRow)
                mCursorCol = mLeftMargin
                if (mCursorRow + 1 < mBottomMargin) {
                    mCursorRow++
                } else {
                    scrollDownOneLine()
                }
            }
        } else if (cursorInLastColumn && displayWidth == 2) {
            // The behaviour when a wide character is output with cursor in the last column when
            // autowrap is disabled is not obvious - it's ignored here.
            return
        }
        if (mInsertMode && displayWidth > 0) {
            // Move character to right one space.
            val destCol: Int = mCursorCol + displayWidth
            if (destCol < mRightMargin) screen.blockCopy(mCursorCol, mCursorRow, mRightMargin - destCol, 1, destCol, mCursorRow)
        }
        val offsetDueToCombiningChar: Int = (if (((displayWidth <= 0) && (mCursorCol > 0) && !mAboutToAutoWrap)) 1 else 0)
        screen.setChar(mCursorCol - offsetDueToCombiningChar, mCursorRow, codePoint, style)
        if (autoWrap && displayWidth > 0) mAboutToAutoWrap = (mCursorCol == mRightMargin - displayWidth)
        mCursorCol = Math.min(mCursorCol + displayWidth, mRightMargin - 1)
    }

    /** Set the cursor mode, but limit it to margins if [.DECSET_BIT_ORIGIN_MODE] is enabled.  */
    private fun setCursorColRespectingOriginMode(col: Int) {
        setCursorPosition(col, mCursorRow)
    }

    /** TODO: Better name, distinguished from [.setCursorPosition] by not regarding origin mode.  */
    private fun setCursorRowCol(row: Int, col: Int) {
        mCursorRow = Math.max(0, Math.min(row, mRows - 1))
        mCursorCol = Math.max(0, Math.min(col, mColumns - 1))
        mAboutToAutoWrap = false
    }

    fun clearScrollCounter() {
        scrollCounter = 0
    }

    /** Reset terminal state so user can interact with it regardless of present state.  */
    fun reset() {
        cursorStyle = CURSOR_STYLE_BLOCK
        mArgIndex = 0
        mContinueSequence = false
        mEscapeState = ESC_NONE
        mInsertMode = false
        mLeftMargin = 0
        mTopMargin = mLeftMargin
        mBottomMargin = mRows
        mRightMargin = mColumns
        mAboutToAutoWrap = false
        mSavedStateAlt.mSavedForeColor = TextStyle.COLOR_INDEX_FOREGROUND
        mSavedStateMain.mSavedForeColor = mSavedStateAlt.mSavedForeColor
        mForeColor = mSavedStateMain.mSavedForeColor
        mSavedStateAlt.mSavedBackColor = TextStyle.COLOR_INDEX_BACKGROUND
        mSavedStateMain.mSavedBackColor = mSavedStateAlt.mSavedBackColor
        mBackColor = mSavedStateMain.mSavedBackColor
        setDefaultTabStops()
        mUseLineDrawingG1 = false
        mUseLineDrawingG0 = mUseLineDrawingG1
        mUseLineDrawingUsesG0 = true
        mSavedStateMain.mSavedDecFlags = 0
        mSavedStateMain.mSavedEffect = mSavedStateMain.mSavedDecFlags
        mSavedStateMain.mSavedCursorCol = mSavedStateMain.mSavedEffect
        mSavedStateMain.mSavedCursorRow = mSavedStateMain.mSavedCursorCol
        mSavedStateAlt.mSavedDecFlags = 0
        mSavedStateAlt.mSavedEffect = mSavedStateAlt.mSavedDecFlags
        mSavedStateAlt.mSavedCursorCol = mSavedStateAlt.mSavedEffect
        mSavedStateAlt.mSavedCursorRow = mSavedStateAlt.mSavedCursorCol
        mCurrentDecSetFlags = 0
        // Initial wrap-around is not accurate but makes terminal more useful, especially on a small screen:
        setDecsetinternalBit(DECSET_BIT_AUTOWRAP, true)
        setDecsetinternalBit(DECSET_BIT_SHOWING_CURSOR, true)
        mSavedStateAlt.mSavedDecFlags = mCurrentDecSetFlags
        mSavedStateMain.mSavedDecFlags = mSavedStateAlt.mSavedDecFlags
        mSavedDecSetFlags = mSavedStateMain.mSavedDecFlags

        // XXX: Should we set terminal driver back to IUTF8 with termios?
        mUtf8ToFollow = 0
        mUtf8Index = mUtf8ToFollow
        mColors.reset()
        mSession.onColorsChanged()
    }

    fun getSelectedText(x1: Int, y1: Int, x2: Int, y2: Int): String? {
        return screen.getSelectedText(x1, y1, x2, y2)
    }

    /** Get the terminal session's title (null if not set).  */
    /** Change the terminal session's title.  */
    var title: String?
        get() {
            return mTitle
        }
        private set(newTitle) {
            val oldTitle: String? = mTitle
            mTitle = newTitle
            if (!Objects.equals(oldTitle, newTitle)) {
                mSession.titleChanged(oldTitle, newTitle)
            }
        }

    /** If DECSET 2004 is set, prefix paste with "\033[200~" and suffix with "\033[201~".  */
    fun paste(text: String) {
        // First: Always remove escape key and C1 control characters [0x80,0x9F]:
        var text: String = text
        text = text.replace("(\u001B|[\u0080-\u009F])".toRegex(), "")
        // Second: Replace all newlines (\n) or CRLF (\r\n) with carriage returns (\r).
        text = text.replace("\r?\n".toRegex(), "\r")

        // Then: Implement bracketed paste mode if enabled:
        val bracketed: Boolean = isDecsetInternalBitSet(DECSET_BIT_BRACKETED_PASTE_MODE)
        if (bracketed) mSession.write("\u001b[200~")
        mSession.write(text)
        if (bracketed) mSession.write("\u001b[201~")
    }

    /** http://www.vt100.net/docs/vt510-rm/DECSC  */
    internal class SavedScreenState constructor() {
        /** Saved state of the cursor position, Used to implement the save/restore cursor position escape sequences.  */
        var mSavedCursorRow: Int = 0
        var mSavedCursorCol: Int = 0
        var mSavedEffect: Int = 0
        var mSavedForeColor: Int = 0
        var mSavedBackColor: Int = 0
        var mSavedDecFlags: Int = 0
        var mUseLineDrawingG0: Boolean = false
        var mUseLineDrawingG1: Boolean = false
        var mUseLineDrawingUsesG0: Boolean = true
    }

    public override fun toString(): String {
        return ("TerminalEmulator[size=" + screen.mColumns + "x" + screen.mScreenRows + ", margins={" + mTopMargin + "," + mRightMargin + "," + mBottomMargin
            + "," + mLeftMargin + "}]")
    }

    companion object {
        /** Log unknown or unimplemented escape sequences received from the shell process.  */
        private val LOG_ESCAPE_SEQUENCES: Boolean = false
        @JvmField
        val MOUSE_LEFT_BUTTON: Int = 0

        /** Mouse moving while having left mouse button pressed.  */
        @JvmField
        val MOUSE_LEFT_BUTTON_MOVED: Int = 32
        @JvmField
        val MOUSE_WHEELUP_BUTTON: Int = 64
        @JvmField
        val MOUSE_WHEELDOWN_BUTTON: Int = 65
        @JvmField
        val CURSOR_STYLE_BLOCK: Int = 0
        @JvmField
        val CURSOR_STYLE_UNDERLINE: Int = 1
        @JvmField
        val CURSOR_STYLE_BAR: Int = 2

        /** Used for invalid data - http://en.wikipedia.org/wiki/Replacement_character#Replacement_character  */
        @JvmField
        val UNICODE_REPLACEMENT_CHAR: Int = 0xFFFD

        /** Escape processing: Not currently in an escape sequence.  */
        private val ESC_NONE: Int = 0

        /** Escape processing: Have seen an ESC character - proceed to [.doEsc]  */
        private val ESC: Int = 1

        /** Escape processing: Have seen ESC POUND  */
        private val ESC_POUND: Int = 2

        /** Escape processing: Have seen ESC and a character-set-select ( char  */
        private val ESC_SELECT_LEFT_PAREN: Int = 3

        /** Escape processing: Have seen ESC and a character-set-select ) char  */
        private val ESC_SELECT_RIGHT_PAREN: Int = 4

        /** Escape processing: "ESC [" or CSI (Control Sequence Introducer).  */
        private val ESC_CSI: Int = 6

        /** Escape processing: ESC [ ?  */
        private val ESC_CSI_QUESTIONMARK: Int = 7

        /** Escape processing: ESC [ $  */
        private val ESC_CSI_DOLLAR: Int = 8

        /** Escape processing: ESC %  */
        private val ESC_PERCENT: Int = 9

        /** Escape processing: ESC ] (AKA OSC - Operating System Controls)  */
        private val ESC_OSC: Int = 10

        /** Escape processing: ESC ] (AKA OSC - Operating System Controls) ESC  */
        private val ESC_OSC_ESC: Int = 11

        /** Escape processing: ESC [ >  */
        private val ESC_CSI_BIGGERTHAN: Int = 12

        /** Escape procession: "ESC P" or Device Control String (DCS)  */
        private val ESC_P: Int = 13

        /** Escape processing: CSI >  */
        private val ESC_CSI_QUESTIONMARK_ARG_DOLLAR: Int = 14

        /** Escape processing: CSI $ARGS ' '  */
        private val ESC_CSI_ARGS_SPACE: Int = 15

        /** Escape processing: CSI $ARGS '*'  */
        private val ESC_CSI_ARGS_ASTERIX: Int = 16

        /** Escape processing: CSI "  */
        private val ESC_CSI_DOUBLE_QUOTE: Int = 17

        /** Escape processing: CSI '  */
        private val ESC_CSI_SINGLE_QUOTE: Int = 18

        /** Escape processing: CSI !  */
        private val ESC_CSI_EXCLAMATION: Int = 19

        /** The number of parameter arguments. This name comes from the ANSI standard for terminal escape codes.  */
        private val MAX_ESCAPE_PARAMETERS: Int = 16

        /** Needs to be large enough to contain reasonable OSC 52 pastes.  */
        private val MAX_OSC_STRING_LENGTH: Int = 8192

        /** DECSET 1 - application cursor keys.  */
        private val DECSET_BIT_APPLICATION_CURSOR_KEYS: Int = 1
        private val DECSET_BIT_REVERSE_VIDEO: Int = 1 shl 1

        /**
         * http://www.vt100.net/docs/vt510-rm/DECOM: "When DECOM is set, the home cursor position is at the upper-left
         * corner of the screen, within the margins. The starting point for line numbers depends on the current top margin
         * setting. The cursor cannot move outside of the margins. When DECOM is reset, the home cursor position is at the
         * upper-left corner of the screen. The starting point for line numbers is independent of the margins. The cursor
         * can move outside of the margins."
         */
        private val DECSET_BIT_ORIGIN_MODE: Int = 1 shl 2

        /**
         * http://www.vt100.net/docs/vt510-rm/DECAWM: "If the DECAWM function is set, then graphic characters received when
         * the cursor is at the right border of the page appear at the beginning of the next line. Any text on the page
         * scrolls up if the cursor is at the end of the scrolling region. If the DECAWM function is reset, then graphic
         * characters received when the cursor is at the right border of the page replace characters already on the page."
         */
        private val DECSET_BIT_AUTOWRAP: Int = 1 shl 3

        /** DECSET 25 - if the cursor should be visible, [.isShowingCursor].  */
        private val DECSET_BIT_SHOWING_CURSOR: Int = 1 shl 4
        private val DECSET_BIT_APPLICATION_KEYPAD: Int = 1 shl 5

        /** DECSET 1000 - if to report mouse press&release events.  */
        private val DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE: Int = 1 shl 6

        /** DECSET 1002 - like 1000, but report moving mouse while pressed.  */
        private val DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT: Int = 1 shl 7

        /** DECSET 1004 - NOT implemented.  */
        private val DECSET_BIT_SEND_FOCUS_EVENTS: Int = 1 shl 8

        /** DECSET 1006 - SGR-like mouse protocol (the modern sane choice).  */
        private val DECSET_BIT_MOUSE_PROTOCOL_SGR: Int = 1 shl 9

        /** DECSET 2004 - see [.paste]  */
        private val DECSET_BIT_BRACKETED_PASTE_MODE: Int = 1 shl 10

        /** Toggled with DECLRMM - http://www.vt100.net/docs/vt510-rm/DECLRMM  */
        private val DECSET_BIT_LEFTRIGHT_MARGIN_MODE: Int = 1 shl 11

        /** Not really DECSET bit... - http://www.vt100.net/docs/vt510-rm/DECSACE  */
        private val DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE: Int = 1 shl 12
        fun mapDecSetBitToInternalBit(decsetBit: Int): Int {
            when (decsetBit) {
                1 -> return DECSET_BIT_APPLICATION_CURSOR_KEYS
                5 -> return DECSET_BIT_REVERSE_VIDEO
                6 -> return DECSET_BIT_ORIGIN_MODE
                7 -> return DECSET_BIT_AUTOWRAP
                25 -> return DECSET_BIT_SHOWING_CURSOR
                66 -> return DECSET_BIT_APPLICATION_KEYPAD
                69 -> return DECSET_BIT_LEFTRIGHT_MARGIN_MODE
                1000 -> return DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE
                1002 -> return DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT
                1004 -> return DECSET_BIT_SEND_FOCUS_EVENTS
                1006 -> return DECSET_BIT_MOUSE_PROTOCOL_SGR
                2004 -> return DECSET_BIT_BRACKETED_PASTE_MODE
                else -> return -1
            }
        }
    }

    init {
        mMainBuffer = TerminalBuffer(columns, transcriptRows, rows)
        screen = mMainBuffer
        mAltBuffer = TerminalBuffer(columns, rows, rows)
        mRows = rows
        mColumns = columns
        mTabStop = BooleanArray(mColumns)
        reset()
    }
}
