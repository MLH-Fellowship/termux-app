package com.termux.terminal

import com.termux.terminal.TextStyle.decodeBackColor
import com.termux.terminal.TextStyle.decodeEffect
import com.termux.terminal.TextStyle.decodeForeColor
import com.termux.terminal.TextStyle.encode
import junit.framework.TestCase

class TextStyleTest : TestCase() {
    fun testEncodingSingle() {
        for (fx in ALL_EFFECTS) {
            for (fg in 0 until TextStyle.NUM_INDEXED_COLORS) {
                for (bg in 0 until TextStyle.NUM_INDEXED_COLORS) {
                    val encoded = encode(fg, bg, fx)
                    assertEquals(fg, decodeForeColor(encoded))
                    assertEquals(bg, decodeBackColor(encoded))
                    assertEquals(fx, decodeEffect(encoded))
                }
            }
        }
    }

    fun testEncoding24Bit() {
        val values = intArrayOf(255, 240, 127, 1, 0)
        for (red in values) {
            for (green in values) {
                for (blue in values) {
                    val argb = -0x1000000 or (red shl 16) or (green shl 8) or blue
                    var encoded = encode(argb, 0, 0)
                    assertEquals(argb, decodeForeColor(encoded))
                    encoded = encode(0, argb, 0)
                    assertEquals(argb, decodeBackColor(encoded))
                }
            }
        }
    }

    fun testEncodingCombinations() {
        for (f1 in ALL_EFFECTS) {
            for (f2 in ALL_EFFECTS) {
                val combined = f1 or f2
                assertEquals(combined, decodeEffect(encode(0, 0, combined)))
            }
        }
    }

    fun testEncodingStrikeThrough() {
        val encoded = encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND,
            TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH)
        assertTrue(decodeEffect(encoded) and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH != 0)
    }

    fun testEncodingProtected() {
        var encoded = encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND,
            TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH)
        assertEquals(0, decodeEffect(encoded) and TextStyle.CHARACTER_ATTRIBUTE_PROTECTED)
        encoded = encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND,
            TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH or TextStyle.CHARACTER_ATTRIBUTE_PROTECTED)
        assertTrue(decodeEffect(encoded) and TextStyle.CHARACTER_ATTRIBUTE_PROTECTED != 0)
    }

    companion object {
        private val ALL_EFFECTS = intArrayOf(0, TextStyle.CHARACTER_ATTRIBUTE_BOLD, TextStyle.CHARACTER_ATTRIBUTE_ITALIC,
            TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, TextStyle.CHARACTER_ATTRIBUTE_BLINK, TextStyle.CHARACTER_ATTRIBUTE_INVERSE,
            TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE, TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH, TextStyle.CHARACTER_ATTRIBUTE_PROTECTED,
            TextStyle.CHARACTER_ATTRIBUTE_DIM)
    }
}
