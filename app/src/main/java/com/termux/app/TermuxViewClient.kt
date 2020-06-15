package com.termux.app

import android.content.Context
import android.media.AudioManager
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.drawerlayout.widget.DrawerLayout
import com.termux.app.ExtraKeysView.SpecialButton
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient

class TermuxViewClient(val mActivity: TermuxActivity) : TerminalViewClient {

    /**
     * Keeping track of the special keys acting as Ctrl and Fn for the soft keyboard and other hardware keys.
     */
    var mVirtualControlKeyDown = false
    var mVirtualFnKeyDown = false
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val increase = scale > 1f
            mActivity.changeFontSize(increase)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val mgr = mActivity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        mgr.showSoftInput(mActivity.mTerminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean {
        return mActivity.mSettings!!.mBackIsEscape
    }

    override fun copyModeChanged(copyMode: Boolean) {
        // Disable drawer while copying.
        mActivity.drawer.setDrawerLockMode(if (copyMode) DrawerLayout.LOCK_MODE_LOCKED_CLOSED else DrawerLayout.LOCK_MODE_UNLOCKED)
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent, currentSession: TerminalSession): Boolean {
        if (handleVirtualKeys(keyCode, e, true)) return true
        if (keyCode == KeyEvent.KEYCODE_ENTER && !currentSession.isRunning) {
            mActivity.removeFinishedSession(currentSession)
            return true
        } else if (e.isCtrlPressed && e.isAltPressed) {
            // Get the unmodified code point:
            val unicodeChar = e.getUnicodeChar(0)
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || unicodeChar == 'n'.toInt() /* next */) {
                mActivity.switchToSession(true)
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || unicodeChar == 'p'.toInt() /* previous */) {
                mActivity.switchToSession(false)
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                mActivity.drawer.openDrawer(Gravity.LEFT)
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                mActivity.drawer.closeDrawers()
            } else if (unicodeChar == 'k'.toInt()) {
                val imm = mActivity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
            } else if (unicodeChar == 'm'.toInt()) {
                mActivity.mTerminalView.showContextMenu()
            } else if (unicodeChar == 'r'.toInt()) {
                mActivity.renameSession(currentSession)
            } else if (unicodeChar == 'c'.toInt()) {
                mActivity.addNewSession(false, null)
            } else if (unicodeChar == 'u'.toInt()) {
                mActivity.showUrlSelection()
            } else if (unicodeChar == 'v'.toInt()) {
                mActivity.doPaste()
            } else if (unicodeChar == '+'.toInt() || e.getUnicodeChar(KeyEvent.META_SHIFT_ON) == '+'.toInt()) {
                // We also check for the shifted char here since shift may be required to produce '+',
                // see https://github.com/termux/termux-api/issues/2
                mActivity.changeFontSize(true)
            } else if (unicodeChar == '-'.toInt()) {
                mActivity.changeFontSize(false)
            } else if (unicodeChar >= '1'.toInt() && unicodeChar <= '9'.toInt()) {
                val num = unicodeChar - '1'.toInt()
                val service = mActivity.mTermService
                if (service.sessions.size > num) mActivity.switchToSession(service.sessions[num])
            }
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        return handleVirtualKeys(keyCode, e, false)
    }

    override fun readControlKey(): Boolean {
        return mActivity.mExtraKeysView != null && mActivity.mExtraKeysView!!.readSpecialButton(SpecialButton.CTRL) || mVirtualControlKeyDown
    }

    override fun readAltKey(): Boolean {
        return mActivity.mExtraKeysView != null && mActivity.mExtraKeysView!!.readSpecialButton(SpecialButton.ALT)
    }

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        if (mVirtualFnKeyDown) {
            var resultingKeyCode = -1
            var resultingCodePoint = -1
            var altDown = false
            val lowerCase = Character.toLowerCase(codePoint)
            when (lowerCase) {
                'w' -> resultingKeyCode = KeyEvent.KEYCODE_DPAD_UP
                'a' -> resultingKeyCode = KeyEvent.KEYCODE_DPAD_LEFT
                's' -> resultingKeyCode = KeyEvent.KEYCODE_DPAD_DOWN
                'd' -> resultingKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT
                'p' -> resultingKeyCode = KeyEvent.KEYCODE_PAGE_UP
                'n' -> resultingKeyCode = KeyEvent.KEYCODE_PAGE_DOWN
                't' -> resultingKeyCode = KeyEvent.KEYCODE_TAB
                'i' -> resultingKeyCode = KeyEvent.KEYCODE_INSERT
                'h' -> resultingCodePoint = '~'.toInt()
                'u' -> resultingCodePoint = '_'.toInt()
                'l' -> resultingCodePoint = '|'.toInt()
                '1', '2', '3', '4', '5', '6', '7', '8', '9' -> resultingKeyCode = codePoint - '1'.toInt() + KeyEvent.KEYCODE_F1
                '0' -> resultingKeyCode = KeyEvent.KEYCODE_F10
                'e' -> resultingCodePoint =  /*Escape*/27
                '.' -> resultingCodePoint =  /*^.*/28
                'b', 'f', 'x' -> {
                    resultingCodePoint = lowerCase
                    altDown = true
                }
                'v' -> {
                    resultingCodePoint = -1
                    val audio = mActivity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audio.adjustSuggestedStreamVolume(AudioManager.ADJUST_SAME, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
                }
                'q', 'k' -> mActivity.toggleShowExtraKeys()
            }
            if (resultingKeyCode != -1) {
                val term = session.emulator
                session.write(KeyHandler.getCode(resultingKeyCode, 0, term.isCursorKeysApplicationMode, term.isKeypadApplicationMode))
            } else if (resultingCodePoint != -1) {
                session.writeCodePoint(altDown, resultingCodePoint)
            }
            return true
        } else if (ctrlDown) {
            if (codePoint == 106 /* Ctrl+j or \n */ && !session.isRunning) {
                mActivity.removeFinishedSession(session)
                return true
            }
            val shortcuts = mActivity.mSettings!!.shortcuts
            if (!shortcuts.isEmpty()) {
                val codePointLowerCase = Character.toLowerCase(codePoint)
                for (i in shortcuts.indices.reversed()) {
                    val shortcut = shortcuts[i]
                    if (codePointLowerCase == shortcut.codePoint) {
                        when (shortcut.shortcutAction) {
                            TermuxPreferences.Companion.SHORTCUT_ACTION_CREATE_SESSION -> {
                                mActivity.addNewSession(false, null)
                                return true
                            }
                            TermuxPreferences.Companion.SHORTCUT_ACTION_PREVIOUS_SESSION -> {
                                mActivity.switchToSession(false)
                                return true
                            }
                            TermuxPreferences.Companion.SHORTCUT_ACTION_NEXT_SESSION -> {
                                mActivity.switchToSession(true)
                                return true
                            }
                            TermuxPreferences.Companion.SHORTCUT_ACTION_RENAME_SESSION -> {
                                mActivity.renameSession(mActivity.currentTermSession)
                                return true
                            }
                        }
                    }
                }
            }
        }
        return false
    }

    override fun onLongPress(event: MotionEvent): Boolean {
        return false
    }

    /**
     * Handle dedicated volume buttons as virtual keys if applicable.
     */
    private fun handleVirtualKeys(keyCode: Int, event: KeyEvent, down: Boolean): Boolean {
        val inputDevice = event.device
        if (mActivity.mSettings!!.mDisableVolumeVirtualKeys) {
            return false
        } else if (inputDevice != null && inputDevice.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            // Do not steal dedicated buttons from a full external keyboard.
            return false
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            mVirtualControlKeyDown = down
            return true
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            mVirtualFnKeyDown = down
            return true
        }
        return false
    }

}
