package com.termux.terminal

/**
 *
 *
 * Encodes effects, foreground and background colors into a 64 bit long, which are stored for each cell in a terminal
 * row in [TerminalRow.mStyle].
 *
 *
 *
 * The bit layout is:
 *
 * - 16 flags (11 currently used).
 * - 24 for foreground color (only 9 first bits if a color index).
 * - 24 for background color (only 9 first bits if a color index).
 */
object TextStyle {
    const val CHARACTER_ATTRIBUTE_BOLD = 1
    const val CHARACTER_ATTRIBUTE_ITALIC = 1 shl 1
    const val CHARACTER_ATTRIBUTE_UNDERLINE = 1 shl 2
    const val CHARACTER_ATTRIBUTE_BLINK = 1 shl 3
    const val CHARACTER_ATTRIBUTE_INVERSE = 1 shl 4
    const val CHARACTER_ATTRIBUTE_INVISIBLE = 1 shl 5
    const val CHARACTER_ATTRIBUTE_STRIKETHROUGH = 1 shl 6

    /**
     * The selective erase control functions (DECSED and DECSEL) can only erase characters defined as erasable.
     *
     *
     * This bit is set if DECSCA (Select Character Protection Attribute) has been used to define the characters that
     * come after it as erasable from the screen.
     *
     */
    const val CHARACTER_ATTRIBUTE_PROTECTED = 1 shl 7

    /** Dim colors. Also known as faint or half intensity.  */
    const val CHARACTER_ATTRIBUTE_DIM = 1 shl 8

    /** If true (24-bit) color is used for the cell for foreground.  */
    private const val CHARACTER_ATTRIBUTE_TRUECOLOR_FOREGROUND = 1 shl 9

    /** If true (24-bit) color is used for the cell for foreground.  */
    private const val CHARACTER_ATTRIBUTE_TRUECOLOR_BACKGROUND = 1 shl 10
    const val COLOR_INDEX_FOREGROUND = 256
    const val COLOR_INDEX_BACKGROUND = 257
    const val COLOR_INDEX_CURSOR = 258

    /** The 256 standard color entries and the three special (foreground, background and cursor) ones.  */
    const val NUM_INDEXED_COLORS = 259

    /** Normal foreground and background colors and no effects.  */
    @JvmField
    val NORMAL = encode(COLOR_INDEX_FOREGROUND, COLOR_INDEX_BACKGROUND, 0)
    @JvmStatic
    fun encode(foreColor: Int, backColor: Int, effect: Int): Long {
        var result = (effect and 511.toLong().toInt()).toLong()
        result = if (-0x1000000 and foreColor == -0x1000000) {
            // 24-bit color.
            result or (CHARACTER_ATTRIBUTE_TRUECOLOR_FOREGROUND or (foreColor and 0x00ffffffL shl 40L)).toLong()
        } else {
            // Indexed color.
            result or (foreColor and 511L shl 40).toLong()
        }
        result = if (-0x1000000 and backColor == -0x1000000) {
            // 24-bit color.
            result or (CHARACTER_ATTRIBUTE_TRUECOLOR_BACKGROUND or (backColor and 0x00ffffffL shl 16L)).toLong()
        } else {
            // Indexed color.
            result or (backColor and 511L shl 16L).toLong()
        }
        return result
    }

    @JvmStatic
    fun decodeForeColor(style: Long): Int {
        return if (style and CHARACTER_ATTRIBUTE_TRUECOLOR_FOREGROUND.toLong() == 0L) {
            (style ushr 40 and 511L).toInt()
        } else {
            -0x1000000 or (style ushr 40 and 0x00ffffffL).toInt()
        }
    }

    @JvmStatic
    fun decodeBackColor(style: Long): Int {
        return if (style and CHARACTER_ATTRIBUTE_TRUECOLOR_BACKGROUND.toLong() == 0L) {
            (style ushr 16 and 511L).toInt()
        } else {
            -0x1000000 or (style ushr 16 and 0x00ffffffL).toInt()
        }
    }

    @JvmStatic
    fun decodeEffect(style: Long): Int {
        return (style and 2047).toInt()
    }
}
