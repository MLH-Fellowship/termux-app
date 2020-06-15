package com.termux.app

import android.content.Context
import android.content.res.Configuration
import android.preference.PreferenceManager
import android.util.Log
import android.util.TypedValue
import android.widget.Toast
import androidx.annotation.IntDef
import com.termux.terminal.TerminalSession
import org.json.JSONException
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.nio.charset.StandardCharsets
import java.util.*

class TermuxPreferences(context: Context) {
    val shortcuts: MutableList<KeyboardShortcut> = ArrayList()
    private val MIN_FONTSIZE: Int

    @AsciiBellBehaviour
    var mBellBehaviour = BELL_VIBRATE
    var mBackIsEscape = false
    var mDisableVolumeVirtualKeys = false
    var mShowExtraKeys: Boolean
    var mExtraKeys: ExtraKeysInfos? = null
    var isUsingBlackUI = false
        private set
    var isScreenAlwaysOn: Boolean
        private set
    var fontSize: Int
        private set

    fun toggleShowExtraKeys(context: Context?): Boolean {
        mShowExtraKeys = !mShowExtraKeys
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(SHOW_EXTRA_KEYS_KEY, mShowExtraKeys).apply()
        return mShowExtraKeys
    }

    fun changeFontSize(context: Context?, increase: Boolean) {
        fontSize += (if (increase) 1 else -1) * 2
        fontSize = Math.max(MIN_FONTSIZE, Math.min(fontSize, MAX_FONTSIZE))
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString(FONTSIZE_KEY, Integer.toString(fontSize)).apply()
    }

    fun setScreenAlwaysOn(context: Context?, newValue: Boolean) {
        isScreenAlwaysOn = newValue
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(SCREEN_ALWAYS_ON_KEY, newValue).apply()
    }

    fun reloadFromProperties(context: Context) {
        var propsFile = File(TermuxService.Companion.HOME_PATH + "/.termux/termux.properties")
        if (!propsFile.exists()) propsFile = File(TermuxService.Companion.HOME_PATH + "/.config/termux/termux.properties")
        val props = Properties()
        try {
            if (propsFile.isFile && propsFile.canRead()) {
                FileInputStream(propsFile).use { `in` -> props.load(InputStreamReader(`in`, StandardCharsets.UTF_8)) }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open properties file termux.properties: " + e.message, Toast.LENGTH_LONG).show()
            Log.e("termux", "Error loading props", e)
        }
        mBellBehaviour = when (props.getProperty("bell-character", "vibrate")) {
            "beep" -> BELL_BEEP
            "ignore" -> BELL_IGNORE
            else -> BELL_VIBRATE
        }
        when (props.getProperty("use-black-ui", "").toLowerCase()) {
            "true" -> isUsingBlackUI = true
            "false" -> isUsingBlackUI = false
            else -> {
                val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                isUsingBlackUI = nightMode == Configuration.UI_MODE_NIGHT_YES
            }
        }
        val defaultExtraKeys = "[[ESC, TAB, CTRL, ALT, {key: '-', popup: '|'}, DOWN, UP]]"
        mExtraKeys = try {
            val extrakeyProp = props.getProperty("extra-keys", defaultExtraKeys)
            val extraKeysStyle = props.getProperty("extra-keys-style", "default")
            ExtraKeysInfos(extrakeyProp, extraKeysStyle)
        } catch (e: JSONException) {
            Toast.makeText(context, "Could not load the extra-keys property from the config: $e", Toast.LENGTH_LONG).show()
            Log.e("termux", "Error loading props", e)
            try {
                ExtraKeysInfos(defaultExtraKeys, "default")
            } catch (e2: JSONException) {
                e2.printStackTrace()
                Toast.makeText(context, "Can't create default extra keys", Toast.LENGTH_LONG).show()
                null
            }
        }
        mBackIsEscape = "escape" == props.getProperty("back-key", "back")
        mDisableVolumeVirtualKeys = "volume" == props.getProperty("volume-keys", "virtual")
        shortcuts.clear()
        parseAction("shortcut.create-session", SHORTCUT_ACTION_CREATE_SESSION, props)
        parseAction("shortcut.next-session", SHORTCUT_ACTION_NEXT_SESSION, props)
        parseAction("shortcut.previous-session", SHORTCUT_ACTION_PREVIOUS_SESSION, props)
        parseAction("shortcut.rename-session", SHORTCUT_ACTION_RENAME_SESSION, props)
    }

    private fun parseAction(name: String, shortcutAction: Int, props: Properties) {
        val value = props.getProperty(name) ?: return
        val parts = value.toLowerCase().trim { it <= ' ' }.split("\\+").toTypedArray()
        val input = if (parts.size == 2) parts[1].trim { it <= ' ' } else null
        if (!(parts.size == 2 && parts[0].trim { it <= ' ' } == "ctrl") || input!!.isEmpty() || input.length > 2) {
            Log.e("termux", "Keyboard shortcut '$name' is not Ctrl+<something>")
            return
        }
        val c = input!![0]
        var codePoint = c.toInt()
        if (Character.isLowSurrogate(c)) {
            codePoint = if (input.length != 2 || Character.isHighSurrogate(input[1])) {
                Log.e("termux", "Keyboard shortcut '$name' is not Ctrl+<something>")
                return
            } else {
                Character.toCodePoint(input[1], c)
            }
        }
        shortcuts.add(KeyboardShortcut(codePoint, shortcutAction))
    }

    @IntDef(BELL_VIBRATE, BELL_BEEP, BELL_IGNORE)
    @Retention(RetentionPolicy.SOURCE)
    internal annotation class AsciiBellBehaviour
    class KeyboardShortcut(val codePoint: Int, val shortcutAction: Int)

    companion object {
        const val SHORTCUT_ACTION_CREATE_SESSION = 1
        const val SHORTCUT_ACTION_NEXT_SESSION = 2
        const val SHORTCUT_ACTION_PREVIOUS_SESSION = 3
        const val SHORTCUT_ACTION_RENAME_SESSION = 4
        const val BELL_VIBRATE = 1
        const val BELL_BEEP = 2
        const val BELL_IGNORE = 3
        private const val MAX_FONTSIZE = 256
        private const val SHOW_EXTRA_KEYS_KEY = "show_extra_keys"
        private const val FONTSIZE_KEY = "fontsize"
        private const val CURRENT_SESSION_KEY = "current_session"
        private const val SCREEN_ALWAYS_ON_KEY = "screen_always_on"

        /**
         * If value is not in the range [min, max], set it to either min or max.
         */
        fun clamp(value: Int, min: Int, max: Int): Int {
            return Math.min(Math.max(value, min), max)
        }

        fun storeCurrentSession(context: Context?, session: TerminalSession) {
            PreferenceManager.getDefaultSharedPreferences(context).edit().putString(CURRENT_SESSION_KEY, session.mHandle).apply()
        }

        fun getCurrentSession(context: TermuxActivity): TerminalSession? {
            val sessionHandle = PreferenceManager.getDefaultSharedPreferences(context).getString(CURRENT_SESSION_KEY, "")
            var i = 0
            val len = context.mTermService.sessions.size
            while (i < len) {
                val session = context.mTermService.sessions[i]
                if (session!!.mHandle == sessionHandle) return session
                i++
            }
            return null
        }
    }

    init {
        reloadFromProperties(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val dipInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, context.resources.displayMetrics)

        // This is a bit arbitrary and sub-optimal. We want to give a sensible default for minimum font size
        // to prevent invisible text due to zoom be mistake:
        MIN_FONTSIZE = (4f * dipInPixels).toInt()
        mShowExtraKeys = prefs.getBoolean(SHOW_EXTRA_KEYS_KEY, true)
        isScreenAlwaysOn = prefs.getBoolean(SCREEN_ALWAYS_ON_KEY, false)

        // http://www.google.com/design/spec/style/typography.html#typography-line-height
        var defaultFontSize = Math.round(12 * dipInPixels)
        // Make it divisible by 2 since that is the minimal adjustment step:
        if (defaultFontSize % 2 == 1) defaultFontSize--
        try {
            fontSize = prefs.getString(FONTSIZE_KEY, Integer.toString(defaultFontSize)).toInt()
        } catch (e: NumberFormatException) {
            fontSize = defaultFontSize
        } catch (e: ClassCastException) {
            fontSize = defaultFontSize
        }
        fontSize = clamp(fontSize, MIN_FONTSIZE, MAX_FONTSIZE)
    }
}
