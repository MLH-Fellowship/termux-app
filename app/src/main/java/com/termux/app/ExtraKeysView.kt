package com.termux.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.AttributeSet
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.PopupWindow
import android.widget.ToggleButton
import androidx.drawerlayout.widget.DrawerLayout
import com.termux.R
import com.termux.view.TerminalView
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * A view showing extra keys (such as Escape, Ctrl, Alt) not normally available on an Android soft
 * keyboard.
 */
class ExtraKeysView(context: Context?, attrs: AttributeSet?) : GridLayout(context, attrs) {
    private val specialButtons: Map<SpecialButton, SpecialButtonState> = object : HashMap<SpecialButton?, SpecialButtonState?>() {
        init {
            put(SpecialButton.CTRL, SpecialButtonState())
            put(SpecialButton.ALT, SpecialButtonState())
            put(SpecialButton.FN, SpecialButtonState())
        }
    }
    private var scheduledExecutor: ScheduledExecutorService? = null
    private var popupWindow: PopupWindow? = null
    private var longPressCount = 0
    private fun sendKey(view: View, keyName: String?, forceCtrlDown: Boolean, forceLeftAltDown: Boolean) {
        val terminalView: TerminalView = view.findViewById(R.id.terminal_view)
        if ("KEYBOARD" == keyName) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.toggleSoftInput(0, 0)
        } else if ("DRAWER" == keyName) {
            val drawer: DrawerLayout = view.findViewById(R.id.drawer_layout)
            drawer.openDrawer(Gravity.LEFT)
        } else if (keyCodesForString.containsKey(keyName)) {
            val keyCode = keyCodesForString[keyName]!!
            var metaState = 0
            if (forceCtrlDown) {
                metaState = metaState or (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
            }
            if (forceLeftAltDown) {
                metaState = metaState or (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
            }
            val keyEvent = KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState)
            terminalView.onKeyDown(keyCode, keyEvent)
        } else {
            // not a control char
            keyName!!.codePoints().forEach { codePoint: Int -> terminalView.inputCodePoint(codePoint, forceCtrlDown, forceLeftAltDown) }
        }
    }

    private fun sendKey(view: View, buttonInfo: ExtraKeyButton?) {
        if (buttonInfo!!.isMacro) {
            val keys = buttonInfo.key.split(" ").toTypedArray()
            var ctrlDown = false
            var altDown = false
            for (key in keys) {
                if ("CTRL" == key) {
                    ctrlDown = true
                } else if ("ALT" == key) {
                    altDown = true
                } else {
                    sendKey(view, key, ctrlDown, altDown)
                    ctrlDown = false
                    altDown = false
                }
            }
        } else {
            sendKey(view, buttonInfo.key, false, false)
        }
    }

    fun readSpecialButton(name: SpecialButton): Boolean {
        val state = specialButtons[name]
            ?: throw RuntimeException("Must be a valid special button (see source)")
        if (!state.isOn) return false
        if (state.button == null) {
            return false
        }
        if (state.button!!.isPressed) return true
        if (!state.button!!.isChecked) return false
        state.button!!.isChecked = false
        state.button!!.setTextColor(TEXT_COLOR)
        return true
    }

    fun popup(view: View, text: String?) {
        val width = view.measuredWidth
        val height = view.measuredHeight
        val button = Button(context, null, android.R.attr.buttonBarButtonStyle)
        button.text = text
        button.setTextColor(TEXT_COLOR)
        button.setPadding(0, 0, 0, 0)
        button.minHeight = 0
        button.minWidth = 0
        button.minimumWidth = 0
        button.minimumHeight = 0
        button.width = width
        button.height = height
        button.setBackgroundColor(BUTTON_PRESSED_COLOR)
        popupWindow = PopupWindow(this)
        popupWindow!!.width = LayoutParams.WRAP_CONTENT
        popupWindow!!.height = LayoutParams.WRAP_CONTENT
        popupWindow!!.contentView = button
        popupWindow!!.isOutsideTouchable = true
        popupWindow!!.isFocusable = false
        popupWindow!!.showAsDropDown(view, 0, -2 * height)
    }

    /**
     * Reload the view given parameters in termux.properties
     *
     * @param infos matrix as defined in termux.properties extrakeys
     * Can Contain The Strings CTRL ALT TAB FN ENTER LEFT RIGHT UP DOWN or normal strings
     * Some aliases are possible like RETURN for ENTER, LT for LEFT and more (@see controlCharsAliases for the whole list).
     * Any string of length > 1 in total Uppercase will print a warning
     *
     *
     * Examples:
     * "ENTER" will trigger the ENTER keycode
     * "LEFT" will trigger the LEFT keycode and be displayed as "←"
     * "→" will input a "→" character
     * "−" will input a "−" character
     * "-_-" will input the string "-_-"
     */
    @SuppressLint("ClickableViewAccessibility")
    fun reload(infos: ExtraKeysInfos?) {
        if (infos == null) return
        for (state in specialButtons.values) state.button = null
        removeAllViews()
        val buttons = infos.matrix
        rowCount = buttons.size
        columnCount = maximumLength(buttons)
        for (row in buttons.indices) {
            for (col in 0 until buttons[row].length) {
                val buttonInfo = buttons[row][col]
                var button: Button
                if (Arrays.asList("CTRL", "ALT", "FN").contains(buttonInfo.key)) {
                    val state = specialButtons[SpecialButton.valueOf(buttonInfo.key)] // for valueOf: https://stackoverflow.com/a/604426/1980630
                    state!!.isOn = true
                    state.button = ToggleButton(context, null, android.R.attr.buttonBarButtonStyle)
                    button = state.button
                    button.isClickable = true
                } else {
                    button = Button(context, null, android.R.attr.buttonBarButtonStyle)
                }
                button.text = buttonInfo.display
                button.setTextColor(TEXT_COLOR)
                button.setPadding(0, 0, 0, 0)
                val finalButton = button
                button.setOnClickListener { v: View? ->
                    if (Settings.System.getInt(context.contentResolver,
                            Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) != 0) {
                        if (Build.VERSION.SDK_INT >= 28) {
                            finalButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        } else {
                            // Perform haptic feedback only if no total silence mode enabled.
                            if (Settings.Global.getInt(context.contentResolver, "zen_mode", 0) != 2) {
                                finalButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                        }
                    }
                    val root = rootView
                    if (Arrays.asList("CTRL", "ALT", "FN").contains(buttonInfo.key)) {
                        val self = finalButton as ToggleButton
                        self.isChecked = self.isChecked
                        self.setTextColor(if (self.isChecked) INTERESTING_COLOR else TEXT_COLOR)
                    } else {
                        sendKey(root, buttonInfo)
                    }
                }
                button.setOnTouchListener { v: View, event: MotionEvent ->
                    val root = rootView
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            longPressCount = 0
                            v.setBackgroundColor(BUTTON_PRESSED_COLOR)
                            if (Arrays.asList("UP", "DOWN", "LEFT", "RIGHT", "BKSP", "DEL").contains(buttonInfo.key)) {
                                // autorepeat
                                scheduledExecutor = Executors.newSingleThreadScheduledExecutor()
                                scheduledExecutor.scheduleWithFixedDelay(Runnable {
                                    longPressCount++
                                    sendKey(root, buttonInfo)
                                }, 400, 80, TimeUnit.MILLISECONDS)
                            }
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (buttonInfo.popup != null) {
                                if (popupWindow == null && event.y < 0) {
                                    if (scheduledExecutor != null) {
                                        scheduledExecutor!!.shutdownNow()
                                        scheduledExecutor = null
                                    }
                                    v.setBackgroundColor(BUTTON_COLOR)
                                    val extraButtonDisplayedText = buttonInfo.popup.getDisplay()
                                    popup(v, extraButtonDisplayedText)
                                }
                                if (popupWindow != null && event.y > 0) {
                                    v.setBackgroundColor(BUTTON_PRESSED_COLOR)
                                    popupWindow!!.dismiss()
                                    popupWindow = null
                                }
                            }
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            v.setBackgroundColor(BUTTON_COLOR)
                            if (scheduledExecutor != null) {
                                scheduledExecutor!!.shutdownNow()
                                scheduledExecutor = null
                            }
                            return@setOnTouchListener true
                        }
                        MotionEvent.ACTION_UP -> {
                            v.setBackgroundColor(BUTTON_COLOR)
                            if (scheduledExecutor != null) {
                                scheduledExecutor!!.shutdownNow()
                                scheduledExecutor = null
                            }
                            if (longPressCount == 0 || popupWindow != null) {
                                if (popupWindow != null) {
                                    popupWindow!!.contentView = null
                                    popupWindow!!.dismiss()
                                    popupWindow = null
                                    if (buttonInfo.popup != null) {
                                        sendKey(root, buttonInfo.popup)
                                    }
                                } else {
                                    v.performClick()
                                }
                            }
                            return@setOnTouchListener true
                        }
                        else -> return@setOnTouchListener true
                    }
                }
                val param = LayoutParams()
                param.width = 0
                param.height = 0
                param.setMargins(0, 0, 0, 0)
                param.columnSpec = spec(col, FILL, 1f)
                param.rowSpec = spec(row, FILL, 1f)
                button.layoutParams = param
                addView(button)
            }
        }
    }

    enum class SpecialButton {
        CTRL, ALT, FN
    }

    private class SpecialButtonState {
        var isOn = false
        var button: ToggleButton? = null
    }

    companion object {
        val keyCodesForString: Map<String?, Int> = object : HashMap<String?, Int?>() {
            init {
                put("SPACE", KeyEvent.KEYCODE_SPACE)
                put("ESC", KeyEvent.KEYCODE_ESCAPE)
                put("TAB", KeyEvent.KEYCODE_TAB)
                put("HOME", KeyEvent.KEYCODE_MOVE_HOME)
                put("END", KeyEvent.KEYCODE_MOVE_END)
                put("PGUP", KeyEvent.KEYCODE_PAGE_UP)
                put("PGDN", KeyEvent.KEYCODE_PAGE_DOWN)
                put("INS", KeyEvent.KEYCODE_INSERT)
                put("DEL", KeyEvent.KEYCODE_FORWARD_DEL)
                put("BKSP", KeyEvent.KEYCODE_DEL)
                put("UP", KeyEvent.KEYCODE_DPAD_UP)
                put("LEFT", KeyEvent.KEYCODE_DPAD_LEFT)
                put("RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT)
                put("DOWN", KeyEvent.KEYCODE_DPAD_DOWN)
                put("ENTER", KeyEvent.KEYCODE_ENTER)
                put("F1", KeyEvent.KEYCODE_F1)
                put("F2", KeyEvent.KEYCODE_F2)
                put("F3", KeyEvent.KEYCODE_F3)
                put("F4", KeyEvent.KEYCODE_F4)
                put("F5", KeyEvent.KEYCODE_F5)
                put("F6", KeyEvent.KEYCODE_F6)
                put("F7", KeyEvent.KEYCODE_F7)
                put("F8", KeyEvent.KEYCODE_F8)
                put("F9", KeyEvent.KEYCODE_F9)
                put("F10", KeyEvent.KEYCODE_F10)
                put("F11", KeyEvent.KEYCODE_F11)
                put("F12", KeyEvent.KEYCODE_F12)
            }
        }
        private const val TEXT_COLOR = -0x1
        private const val BUTTON_COLOR = 0x00000000
        private const val INTERESTING_COLOR = -0x7f2116
        private const val BUTTON_PRESSED_COLOR = -0x808081

        /**
         * General util function to compute the longest column length in a matrix.
         */
        fun maximumLength(matrix: Array<Array<Any?>?>?): Int {
            var m = 0
            for (row in matrix!!) m = Math.max(m, row!!.size)
            return m
        }
    }
}
