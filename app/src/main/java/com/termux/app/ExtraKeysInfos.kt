package com.termux.app

import com.termux.app.ExtraKeysInfos.CharDisplayMap
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.*
import java.util.stream.Collectors

class ExtraKeysInfos(propertiesInfo: String?, style: String) {
    /**
     * Matrix of buttons displayed
     */
    val matrix: Array<Array<ExtraKeyButton?>>

    /**
     * This corresponds to one of the CharMapDisplay below
     */
    private val style = "default"

    val selectedCharMap: CharDisplayMap
        get() = when (style) {
            "arrows-only" -> arrowsOnlyCharDisplay
            "arrows-all" -> lotsOfArrowsCharDisplay
            "all" -> fullIsoCharDisplay
            "none" -> CharDisplayMap()
            else -> defaultCharDisplay
        }

    /**
     * HashMap that implements Python dict.get(key, default) function.
     * Default java.util .get(key) is then the same as .get(key, null);
     */
    internal open class CleverMap<K, V> : HashMap<K, V>() {
        operator fun get(key: K, defaultValue: V): V? {
            return if (containsKey(key)) get(key) else defaultValue
        }
    }

    open class CharDisplayMap : CleverMap<String?, String?>()
    companion object {
        /**
         * Keys are displayed in a natural looking way, like "→" for "RIGHT"
         */
        val classicArrowsDisplay: CharDisplayMap = object : CharDisplayMap() {
            init {
                // classic arrow keys (for ◀ ▶ ▲ ▼ @see arrowVariationDisplay)
                put("LEFT", "←") // U+2190 ← LEFTWARDS ARROW
                put("RIGHT", "→") // U+2192 → RIGHTWARDS ARROW
                put("UP", "↑") // U+2191 ↑ UPWARDS ARROW
                put("DOWN", "↓") // U+2193 ↓ DOWNWARDS ARROW
            }
        }
        val wellKnownCharactersDisplay: CharDisplayMap = object : CharDisplayMap() {
            init {
                // well known characters // https://en.wikipedia.org/wiki/{Enter_key, Tab_key, Delete_key}
                put("ENTER", "↲") // U+21B2 ↲ DOWNWARDS ARROW WITH TIP LEFTWARDS
                put("TAB", "↹") // U+21B9 ↹ LEFTWARDS ARROW TO BAR OVER RIGHTWARDS ARROW TO BAR
                put("BKSP", "⌫") // U+232B ⌫ ERASE TO THE LEFT sometimes seen and easy to understand
                put("DEL", "⌦") // U+2326 ⌦ ERASE TO THE RIGHT not well known but easy to understand
                put("DRAWER", "☰") // U+2630 ☰ TRIGRAM FOR HEAVEN not well known but easy to understand
                put("KEYBOARD", "⌨") // U+2328 ⌨ KEYBOARD not well known but easy to understand
            }
        }
        val lessKnownCharactersDisplay: CharDisplayMap = object : CharDisplayMap() {
            init {
                // https://en.wikipedia.org/wiki/{Home_key, End_key, Page_Up_and_Page_Down_keys}
                // home key can mean "goto the beginning of line" or "goto first page" depending on context, hence the diagonal
                put("HOME", "⇱") // from IEC 9995 // U+21F1 ⇱ NORTH WEST ARROW TO CORNER
                put("END", "⇲") // from IEC 9995 // ⇲ // U+21F2 ⇲ SOUTH EAST ARROW TO CORNER
                put("PGUP", "⇑") // no ISO character exists, U+21D1 ⇑ UPWARDS DOUBLE ARROW will do the trick
                put("PGDN", "⇓") // no ISO character exists, U+21D3 ⇓ DOWNWARDS DOUBLE ARROW will do the trick
            }
        }
        val arrowTriangleVariationDisplay: CharDisplayMap = object : CharDisplayMap() {
            init {
                // alternative to classic arrow keys
                put("LEFT", "◀") // U+25C0 ◀ BLACK LEFT-POINTING TRIANGLE
                put("RIGHT", "▶") // U+25B6 ▶ BLACK RIGHT-POINTING TRIANGLE
                put("UP", "▲") // U+25B2 ▲ BLACK UP-POINTING TRIANGLE
                put("DOWN", "▼") // U+25BC ▼ BLACK DOWN-POINTING TRIANGLE
            }
        }
        val notKnownIsoCharacters: CharDisplayMap = object : CharDisplayMap() {
            init {
                // Control chars that are more clear as text // https://en.wikipedia.org/wiki/{Function_key, Alt_key, Control_key, Esc_key}
                // put("FN", "FN"); // no ISO character exists
                put("CTRL", "⎈") // ISO character "U+2388 ⎈ HELM SYMBOL" is unknown to people and never printed on computers, however "U+25C7 ◇ WHITE DIAMOND" is a nice presentation, and "^" for terminal app and mac is often used
                put("ALT", "⎇") // ISO character "U+2387 ⎇ ALTERNATIVE KEY SYMBOL'" is unknown to people and only printed as the Option key "⌥" on Mac computer
                put("ESC", "⎋") // ISO character "U+238B ⎋ BROKEN CIRCLE WITH NORTHWEST ARROW" is unknown to people and not often printed on computers
            }
        }
        val nicerLookingDisplay: CharDisplayMap = object : CharDisplayMap() {
            init {
                // nicer looking for most cases
                put("-", "―") // U+2015 ― HORIZONTAL BAR
            }
        }

        /**
         * Some classic symbols everybody knows
         */
        private val defaultCharDisplay: CharDisplayMap = object : CharDisplayMap() {
            init {
                putAll(classicArrowsDisplay)
                putAll(wellKnownCharactersDisplay)
                putAll(nicerLookingDisplay)
                // all other characters are displayed as themselves
            }
        }

        /**
         * Classic symbols and less known symbols
         */
        private val lotsOfArrowsCharDisplay: CharDisplayMap = object : CharDisplayMap() {
            init {
                putAll(classicArrowsDisplay)
                putAll(wellKnownCharactersDisplay)
                putAll(lessKnownCharactersDisplay) // NEW
                putAll(nicerLookingDisplay)
            }
        }

        /**
         * Only arrows
         */
        private val arrowsOnlyCharDisplay: CharDisplayMap = object : CharDisplayMap() {
            init {
                putAll(classicArrowsDisplay)
                // putAll(wellKnownCharactersDisplay); // REMOVED
                // putAll(lessKnownCharactersDisplay); // REMOVED
                putAll(nicerLookingDisplay)
            }
        }

        /**
         * Full Iso
         */
        private val fullIsoCharDisplay: CharDisplayMap = object : CharDisplayMap() {
            init {
                putAll(classicArrowsDisplay)
                putAll(wellKnownCharactersDisplay)
                putAll(lessKnownCharactersDisplay) // NEW
                putAll(nicerLookingDisplay)
                putAll(notKnownIsoCharacters) // NEW
            }
        }

        /**
         * Some people might call our keys differently
         */
        private val controlCharsAliases: CharDisplayMap = object : CharDisplayMap() {
            init {
                put("ESCAPE", "ESC")
                put("CONTROL", "CTRL")
                put("RETURN", "ENTER") // Technically different keys, but most applications won't see the difference
                put("FUNCTION", "FN")
                // no alias for ALT

                // Directions are sometimes written as first and last letter for brevety
                put("LT", "LEFT")
                put("RT", "RIGHT")
                put("DN", "DOWN")
                // put("UP", "UP"); well, "UP" is already two letters
                put("PAGEUP", "PGUP")
                put("PAGE_UP", "PGUP")
                put("PAGE UP", "PGUP")
                put("PAGE-UP", "PGUP")

                // no alias for HOME
                // no alias for END
                put("PAGEDOWN", "PGDN")
                put("PAGE_DOWN", "PGDN")
                put("PAGE-DOWN", "PGDN")
                put("DELETE", "DEL")
                put("BACKSPACE", "BKSP")

                // easier for writing in termux.properties
                put("BACKSLASH", "\\")
                put("QUOTE", "\"")
                put("APOSTROPHE", "'")
            }
        }

        /**
         * "hello" -> {"key": "hello"}
         */
        @Throws(JSONException::class)
        private fun normalizeKeyConfig(key: Any?): JSONObject {
            val jobject: JSONObject
            if (key is String) {
                jobject = JSONObject()
                jobject.put("key", key)
            } else if (key is JSONObject) {
                jobject = key
            } else {
                throw JSONException("An key in the extra-key matrix must be a string or an object")
            }
            return jobject
        }

        /**
         * Applies the 'controlCharsAliases' mapping to all the strings in *buttons*
         * Modifies the array, doesn't return a new one.
         */
        fun replaceAlias(key: String): String {
            return controlCharsAliases[key, key]!!
        }
    }

    /**
     * Multiple maps are available to quickly change
     * the style of the keys.
     */
    init {
        this.style = style

        // Convert String propertiesInfo to Array of Arrays
        val arr = JSONArray(propertiesInfo)
        val matrix: Array<Array<Any?>> = arrayOfNulls(arr.length())
        for (i in 0 until arr.length()) {
            val line = arr.getJSONArray(i)
            matrix[i] = arrayOfNulls(line.length())
            for (j in 0 until line.length()) {
                matrix[i][j] = line[j]
            }
        }

        // convert matrix to buttons
        this.matrix = arrayOfNulls(matrix.size)
        for (i in matrix.indices) {
            this.matrix[i] = arrayOfNulls(matrix[i].length)
            for (j in 0 until matrix[i].length) {
                val key = matrix[i][j]
                val jobject = normalizeKeyConfig(key)
                var button: ExtraKeyButton
                button = if (!jobject.has("popup")) {
                    // no popup
                    ExtraKeyButton(selectedCharMap, jobject)
                } else {
                    // a popup
                    val popupJobject = normalizeKeyConfig(jobject["popup"])
                    val popup = ExtraKeyButton(selectedCharMap, popupJobject)
                    ExtraKeyButton(selectedCharMap, jobject, popup)
                }
                this.matrix[i][j] = button
            }
        }
    }
}

class ExtraKeyButton @JvmOverloads constructor(charDisplayMap: CharDisplayMap, config: JSONObject, popup: ExtraKeyButton? = null) {
    /**
     * The key that will be sent to the terminal, either a control character
     * defined in ExtraKeysView.keyCodesForString (LEFT, RIGHT, PGUP...) or
     * some text.
     */
    val key: String

    /**
     * If the key is a macro, i.e. a sequence of keys separated by space.
     */
    var isMacro = false

    /**
     * The text that will be shown on the button.
     */
    var display: String? = null

    /**
     * The information of the popup (triggered by swipe up).
     */
    val popup: ExtraKeyButton? = null

    init {
        val keyFromConfig = config.optString("key", null)
        val macroFromConfig = config.optString("macro", null)
        val keys: Array<String>
        if (keyFromConfig != null && macroFromConfig != null) {
            throw JSONException("Both key and macro can't be set for the same key")
        } else if (keyFromConfig != null) {
            keys = arrayOf(keyFromConfig)
            isMacro = false
        } else if (macroFromConfig != null) {
            keys = macroFromConfig.split(" ").toTypedArray()
            isMacro = true
        } else {
            throw JSONException("All keys have to specify either key or macro")
        }
        for (i in keys.indices) {
            keys[i] = ExtraKeysInfos.replaceAlias(keys[i])
        }
        key = java.lang.String.join(" ", *keys)
        val displayFromConfig = config.optString("display", null)
        if (displayFromConfig != null) {
            display = displayFromConfig
        } else {
            display = Arrays.stream(keys)
                .map { key: String -> charDisplayMap[key, key] }
                .collect(Collectors.joining(" "))
        }
        this.popup = popup
    }
}
