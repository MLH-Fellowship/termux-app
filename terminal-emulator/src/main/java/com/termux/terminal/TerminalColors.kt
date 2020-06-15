package com.termux.terminal

/** Current terminal colors (if different from default).  */
class TerminalColors constructor() {
    /**
     * The current terminal colors, which are normally set from the color theme, but may be set dynamically with the OSC
     * 4 control sequence.
     */
    @JvmField
    val mCurrentColors: IntArray = IntArray(TextStyle.NUM_INDEXED_COLORS)

    /** Reset a particular indexed color with the default color from the color theme.  */
    fun reset(index: Int) {
        mCurrentColors.get(index) = COLOR_SCHEME.mDefaultColors.get(index)
    }

    /** Reset all indexed colors with the default color from the color theme.  */
    fun reset() {
        System.arraycopy(COLOR_SCHEME.mDefaultColors, 0, mCurrentColors, 0, TextStyle.NUM_INDEXED_COLORS)
    }

    /** Try parse a color from a text parameter and into a specified index.  */
    fun tryParseColor(intoIndex: Int, textParameter: String) {
        val c: Int = parse(textParameter)
        if (c != 0) mCurrentColors.get(intoIndex) = c
    }

    companion object {
        /** Static data - a bit ugly but ok for now.  */
        @JvmField
        val COLOR_SCHEME: TerminalColorScheme = TerminalColorScheme()

        /**
         * Parse color according to http://manpages.ubuntu.com/manpages/intrepid/man3/XQueryColor.3.html
         *
         *
         * Highest bit is set if successful, so return value is 0xFF${R}${G}${B}. Return 0 if failed.
         */
        @JvmStatic
        fun parse(c: String): Int {
            try {
                val skipInitial: Int
                val skipBetween: Int
                if (c.get(0) == '#') {
                    // #RGB, #RRGGBB, #RRRGGGBBB or #RRRRGGGGBBBB. Most significant bits.
                    skipInitial = 1
                    skipBetween = 0
                } else if (c.startsWith("rgb:")) {
                    // rgb:<red>/<green>/<blue> where <red>, <green>, <blue> := h | hh | hhh | hhhh. Scaled.
                    skipInitial = 4
                    skipBetween = 1
                } else {
                    return 0
                }
                val charsForColors: Int = c.length - skipInitial - (2 * skipBetween)
                if (charsForColors % 3 != 0) return 0 // Unequal lengths.
                val componentLength: Int = charsForColors / 3
                val mult: Double = 255 / (Math.pow(2.0, componentLength * 4.toDouble()) - 1)
                var currentPosition: Int = skipInitial
                val rString: String = c.substring(currentPosition, currentPosition + componentLength)
                currentPosition += componentLength + skipBetween
                val gString: String = c.substring(currentPosition, currentPosition + componentLength)
                currentPosition += componentLength + skipBetween
                val bString: String = c.substring(currentPosition, currentPosition + componentLength)
                val r: Int = (rString.toInt(16) * mult).toInt()
                val g: Int = (gString.toInt(16) * mult).toInt()
                val b: Int = (bString.toInt(16) * mult).toInt()
                return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            } catch (e: NumberFormatException) {
                return 0
            } catch (e: IndexOutOfBoundsException) {
                return 0
            }
        }
    }

    /** Create a new instance with default colors from the theme.  */
    init {
        reset()
    }
}
