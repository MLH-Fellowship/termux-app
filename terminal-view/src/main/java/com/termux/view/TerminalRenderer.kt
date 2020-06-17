package com.termux.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Renderer of a [TerminalEmulator] into a [Canvas].
 *
 *
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
class TerminalRenderer(val mTextSize: Int, val mTypeface: Typeface) {
    private val mTextPaint = Paint()

    /** The width of a single mono spaced character obtained by [Paint.measureText] on a single 'X'.  */
    @JvmField
    val mFontWidth: Float

    /** The [Paint.getFontSpacing]. See http://www.fampennings.nl/maarten/android/08numgrid/font.png  */
    @JvmField
    val mFontLineSpacing: Int

    /** The [Paint.ascent]. See http://www.fampennings.nl/maarten/android/08numgrid/font.png  */
    private val mFontAscent: Int

    /** The [.mFontLineSpacing] + [.mFontAscent].  */
    @JvmField
    val mFontLineSpacingAndAscent: Int
    private val asciiMeasures = FloatArray(127)

    init {
        mTextPaint.typeface = mTypeface
        mTextPaint.isAntiAlias = true
        mTextPaint.textSize = mTextSize.toFloat()
        mFontLineSpacing = ceil(mTextPaint.fontSpacing.toDouble()).toInt()
        mFontAscent = ceil(mTextPaint.ascent().toDouble()).toInt()
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent
        mFontWidth = mTextPaint.measureText("X")
        val sb = StringBuilder(" ")
        for (i in asciiMeasures.indices) {
            sb.setCharAt(0, i.toChar())
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1)
        }
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection.  */
    fun render(
        mEmulator: TerminalEmulator, canvas: Canvas, topRow: Int,
        selectionY1: Int, selectionY2: Int, selectionX1: Int, selectionX2: Int
    ) {
        val reverseVideo = mEmulator.isReverseVideo
        val endRow = topRow + mEmulator.mRows
        val columns = mEmulator.mColumns
        val cursorCol = mEmulator.cursorCol
        val cursorRow = mEmulator.cursorRow
        val cursorVisible = mEmulator.isShowingCursor
        val screen = mEmulator.screen
        val palette = mEmulator.mColors.mCurrentColors
        val cursorShape = mEmulator.cursorStyle
        if (reverseVideo) canvas.drawColor(
            palette[TextStyle.COLOR_INDEX_FOREGROUND],
            PorterDuff.Mode.SRC
        )
        var heightOffset = mFontLineSpacingAndAscent.toFloat()
        for (row in topRow until endRow) {
            heightOffset += mFontLineSpacing.toFloat()
            val cursorX = if (row == cursorRow && cursorVisible) cursorCol else -1
            var selx1 = -1
            var selx2 = -1
            if (row in selectionY1..selectionY2) {
                if (row == selectionY1) selx1 = selectionX1
                selx2 = if (row == selectionY2) selectionX2 else mEmulator.mColumns
            }
            val lineObject =
                screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))
            val line = lineObject.mText
            val charsUsedInLine = lineObject.spaceUsed
            var lastRunStyle: Long = 0
            var lastRunInsideCursor = false
            var lastRunStartColumn = -1
            var lastRunStartIndex = 0
            var lastRunFontWidthMismatch = false
            var currentCharIndex = 0
            var measuredWidthForRun = 0f
            var column = 0
            while (column < columns) {
                val charAtIndex = line[currentCharIndex]
                val charIsHighsurrogate =
                    Character.isHighSurrogate(charAtIndex)
                val charsForCodePoint = if (charIsHighsurrogate) 2 else 1
                val codePoint: Int = (if (charIsHighsurrogate) Character.toCodePoint(
                    charAtIndex,
                    line[currentCharIndex + 1]
                ) else charAtIndex).toString().first().toInt()
                val codePointWcWidth = WcWidth.width(codePoint)
                val insideCursor =
                    column in selx1..selx2 || cursorX == column || codePointWcWidth == 2 && cursorX == column + 1
                val style = lineObject.getStyle(column)

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                val measuredCodePointWidth =
                    if (codePoint < asciiMeasures.size) asciiMeasures[codePoint] else mTextPaint.measureText(
                        line,
                        currentCharIndex, charsForCodePoint
                    )
                val fontWidthMismatch =
                    abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01
                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (column == 0) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
                        val columnWidthSinceLastRun = column - lastRunStartColumn
                        val charsSinceLastRun = currentCharIndex - lastRunStartIndex
                        val cursorColor =
                            if (lastRunInsideCursor) mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] else 0
                        drawTextRun(
                            canvas,
                            line,
                            palette,
                            heightOffset,
                            lastRunStartColumn,
                            columnWidthSinceLastRun,
                            lastRunStartIndex,
                            charsSinceLastRun,
                            measuredWidthForRun,
                            cursorColor,
                            cursorShape,
                            lastRunStyle,
                            reverseVideo
                        )
                    }
                    measuredWidthForRun = 0f
                    lastRunStyle = style
                    lastRunInsideCursor = insideCursor
                    lastRunStartColumn = column
                    lastRunStartIndex = currentCharIndex
                    lastRunFontWidthMismatch = fontWidthMismatch
                }
                measuredWidthForRun += measuredCodePointWidth
                column += codePointWcWidth
                currentCharIndex += charsForCodePoint
                while (currentCharIndex < charsUsedInLine && WcWidth.width(
                        line,
                        currentCharIndex
                    ) <= 0
                ) {
                    // Eat combining chars so that they are treated as part of the last non-combining code point,
                    // instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += if (Character.isHighSurrogate(
                            line[currentCharIndex]
                        )
                    ) 2 else 1
                }
            }
            val columnWidthSinceLastRun = columns - lastRunStartColumn
            val charsSinceLastRun = currentCharIndex - lastRunStartIndex
            val cursorColor =
                if (lastRunInsideCursor) mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] else 0
            drawTextRun(
                canvas,
                line,
                palette,
                heightOffset,
                lastRunStartColumn,
                columnWidthSinceLastRun,
                lastRunStartIndex,
                charsSinceLastRun,
                measuredWidthForRun,
                cursorColor,
                cursorShape,
                lastRunStyle,
                reverseVideo
            )
        }
    }

    private fun drawTextRun(
        canvas: Canvas,
        text: CharArray,
        palette: IntArray,
        y: Float,
        startColumn: Int,
        runWidthColumns: Int,
        startCharIndex: Int,
        runWidthChars: Int,
        mes: Float,
        cursor: Int,
        cursorStyle: Int,
        textStyle: Long,
        reverseVideo: Boolean
    ) {
        var mes = mes
        var foreColor = TextStyle.decodeForeColor(textStyle)
        val effect = TextStyle.decodeEffect(textStyle)
        var backColor = TextStyle.decodeBackColor(textStyle)
        val bold =
            effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK) != 0
        val underline =
            effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0
        val italic =
            effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0
        val strikeThrough =
            effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH != 0
        val dim =
            effect and TextStyle.CHARACTER_ATTRIBUTE_DIM != 0
        if (foreColor and -0x1000000 != -0x1000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8
            foreColor = palette[foreColor]
        }
        if (backColor and -0x1000000 != -0x1000000) {
            backColor = palette[backColor]
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        val reverseVideoHere =
            reverseVideo xor ((effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0)
        if (reverseVideoHere) {
            val tmp = foreColor
            foreColor = backColor
            backColor = tmp
        }
        var left = startColumn * mFontWidth
        var right = left + runWidthColumns * mFontWidth
        mes = mes / mFontWidth
        var savedMatrix = false
        if (abs(mes - runWidthColumns) > 0.01) {
            canvas.save()
            canvas.scale(runWidthColumns / mes, 1f)
            left *= mes / runWidthColumns
            right *= mes / runWidthColumns
            savedMatrix = true
        }
        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.color = backColor
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint)
        }
        if (cursor != 0) {
            mTextPaint.color = cursor
            var cursorHeight = mFontLineSpacingAndAscent - mFontAscent.toFloat()
            if (cursorStyle == TerminalEmulator.CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.0f else if (cursorStyle == TerminalEmulator.CURSOR_STYLE_BAR) right -= (right - left) * 3 / 4.0.toFloat()
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint)
        }
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE == 0) {
            if (dim) {
                var red = 0xFF and (foreColor shr 16)
                var green = 0xFF and (foreColor shr 8)
                var blue = 0xFF and foreColor
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3
                green = green * 2 / 3
                blue = blue * 2 / 3
                foreColor = -0x1000000 + (red shl 16) + (green shl 8) + blue
            }
            mTextPaint.isFakeBoldText = bold
            mTextPaint.isUnderlineText = underline
            mTextPaint.textSkewX = if (italic) -0.35f else 0f
            mTextPaint.isStrikeThruText = strikeThrough
            mTextPaint.color = foreColor

            // The text alignment is the default Paint.Align.LEFT.
            canvas.drawText(
                text,
                startCharIndex,
                runWidthChars,
                left,
                y - mFontLineSpacingAndAscent,
                mTextPaint
            )
        }
        if (savedMatrix) canvas.restore()
    }
}
