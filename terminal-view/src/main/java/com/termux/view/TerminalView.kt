package com.termux.view

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnTouchModeChangeListener
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.PopupWindow
import android.widget.Scroller
import com.termux.terminal.EmulatorDebug
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.WcWidth

const val LEFT = 0
const val RIGHT = 2

/** View displaying and interacting with a [TerminalSession].  */
class TerminalView(
    context: Context,
    attributes: AttributeSet?
) : View(context, attributes) {
    /** The currently displayed terminal session, whose emulator is [.mEmulator].  */
    var currentSession: TerminalSession? = null

    /** Our terminal emulator whose session is [.mTermSession].  */
    var mEmulator: TerminalEmulator? = null
    var mRenderer: TerminalRenderer? = null
    var mClient: TerminalViewClient? = null

    /** The top row of text to display. Ranges from -activeTranscriptRows to 0.  */
    var mTopRow = 0
    var mIsSelectingText = false
    var mSelX1 = -1
    var mSelX2 = -1
    var mSelY1 = -1
    var mSelY2 = -1
    private var mActionMode: ActionMode? = null
    var mSelectHandleLeft: Drawable? = null
    var mSelectHandleRight: Drawable? = null
    val mTempCoords = IntArray(2)
    var mTempRect: Rect? = null
    private var mSelectionModifierCursorController: SelectionModifierCursorController? = null
    var mScaleFactor = 1f
    var mGestureRecognizer: GestureAndScaleRecognizer? = null

    /** Keep track of where mouse touch event started which we report as mouse scroll.  */
    private var mMouseScrollStartX = -1
    private var mMouseScrollStartY = -1

    /** Keep track of the time when a touch event leading to sending mouse scroll events started.  */
    private var mMouseStartDownTime: Long = -1
    val mScroller: Scroller

    /** What was left in from scrolling movement.  */
    var mScrollRemainder = 0f

    /** If non-zero, this is the last unicode code point received if that was a combining character.  */
    var mCombiningAccent = 0
    private val mAccessibilityEnabled: Boolean

    /**
     * @param onKeyListener Listener for all kinds of key events, both hardware and IME (which makes it different from that
     * available with [View.setOnKeyListener].
     */
    fun setOnKeyListener(onKeyListener: TerminalViewClient?) {
        mClient = onKeyListener
    }

    /**
     * Attach a [TerminalSession] to this view.
     *
     * @param session The [TerminalSession] this view will be displaying.
     */
    fun attachSession(session: TerminalSession): Boolean {
        if (session == currentSession) return false
        mTopRow = 0
        currentSession = session
        mEmulator = null
        mCombiningAccent = 0
        updateSize()

        // Wait with enabling the scrollbar until we have a terminal to get scroll position from.
        isVerticalScrollBarEnabled = true
        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Using InputType.NULL is the most correct input type and avoids issues with other hacks.
        //
        // Previous keyboard issues:
        // https://github.com/termux/termux-packages/issues/25
        // https://github.com/termux/termux-app/issues/87.
        // https://github.com/termux/termux-app/issues/126.
        // https://github.com/termux/termux-app/issues/137 (japanese chars and TYPE_NULL).
        outAttrs.inputType = InputType.TYPE_NULL

        // Note that IME_ACTION_NONE cannot be used as that makes it impossible to input newlines using the on-screen
        // keyboard on Android TV (see https://github.com/termux/termux-app/issues/221).
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, true) {
            override fun finishComposingText(): Boolean {
                if (LOG_KEY_EVENTS) Log.i(
                    EmulatorDebug.LOG_TAG,
                    "IME: finishComposingText()"
                )
                super.finishComposingText()
                sendTextToTerminal(editable)
                editable.clear()
                return true
            }

            override fun commitText(
                text: CharSequence,
                newCursorPosition: Int
            ): Boolean {
                if (LOG_KEY_EVENTS) {
                    Log.i(
                        EmulatorDebug.LOG_TAG,
                        "IME: commitText(\"$text\", $newCursorPosition)"
                    )
                }
                super.commitText(text, newCursorPosition)
                if (mEmulator == null) return true
                val content = editable
                sendTextToTerminal(content)
                content.clear()
                return true
            }

            override fun deleteSurroundingText(
                leftLength: Int,
                rightLength: Int
            ): Boolean {
                if (LOG_KEY_EVENTS) {
                    Log.i(
                        EmulatorDebug.LOG_TAG,
                        "IME: deleteSurroundingText($leftLength, $rightLength)"
                    )
                }
                // The stock Samsung keyboard with 'Auto check spelling' enabled sends leftLength > 1.
                val deleteKey = KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_DEL
                )
                for (i in 0 until leftLength) sendKeyEvent(deleteKey)
                return super.deleteSurroundingText(leftLength, rightLength)
            }

            fun sendTextToTerminal(text: CharSequence) {
                stopTextSelectionMode()
                val textLengthInChars = text.length
                var i = 0
                while (i < textLengthInChars) {
                    val firstChar = text[i]
                    var codePoint: Int
                    codePoint = if (Character.isHighSurrogate(firstChar)) {
                        if (++i < textLengthInChars) {
                            Character.toCodePoint(firstChar, text[i])
                        } else {
                            // At end of string, with no low surrogate following the high:
                            TerminalEmulator.UNICODE_REPLACEMENT_CHAR
                        }
                    } else {
                        firstChar.toInt()
                    }
                    var ctrlHeld = false
                    if (codePoint <= 31 && codePoint != 27) {
                        if (codePoint == '\n'.toInt()) {
                            // The AOSP keyboard and descendants seems to send \n as text when the enter key is pressed,
                            // instead of a key event like most other keyboard apps. A terminal expects \r for the enter
                            // key (although when icrnl is enabled this doesn't make a difference - run 'stty -icrnl' to
                            // check the behaviour).
                            codePoint = '\r'.toInt()
                        }

                        // E.g. penti keyboard for ctrl input.
                        ctrlHeld = true
                        when (codePoint) {
                            31 -> codePoint = '_'.toInt()
                            30 -> codePoint = '^'.toInt()
                            29 -> codePoint = ']'.toInt()
                            28 -> codePoint = '\\'.toInt()
                            else -> codePoint += 96
                        }
                    }
                    inputCodePoint(codePoint, ctrlHeld, false)
                    i++
                }
            }
        }
    }

    override fun computeVerticalScrollRange(): Int {
        return if (mEmulator == null) 1 else mEmulator!!.screen.activeRows
    }

    override fun computeVerticalScrollExtent(): Int {
        return if (mEmulator == null) 1 else mEmulator!!.mRows
    }

    override fun computeVerticalScrollOffset(): Int {
        return if (mEmulator == null) 1 else mEmulator!!.screen
            .activeRows + mTopRow - mEmulator!!.mRows
    }

    fun onScreenUpdated() {
        if (mEmulator == null) return
        val rowsInHistory = mEmulator!!.screen.activeTranscriptRows
        if (mTopRow < -rowsInHistory) mTopRow = -rowsInHistory
        var skipScrolling = false
        if (mIsSelectingText) {
            // Do not scroll when selecting text.
            val rowShift = mEmulator!!.scrollCounter
            if (-mTopRow + rowShift > rowsInHistory) {
                // .. unless we're hitting the end of history transcript, in which
                // case we abort text selection and scroll to end.
                stopTextSelectionMode()
            } else {
                skipScrolling = true
                mTopRow -= rowShift
                mSelY1 -= rowShift
                mSelY2 -= rowShift
            }
        }
        if (!skipScrolling && mTopRow != 0) {
            // Scroll down if not already there.
            if (mTopRow < -3) {
                // Awaken scroll bars only if scrolling a noticeable amount
                // - we do not want visible scroll bars during normal typing
                // of one row at a time.
                awakenScrollBars()
            }
            mTopRow = 0
        }
        mEmulator!!.clearScrollCounter()
        invalidate()
        if (mAccessibilityEnabled) contentDescription = text
    }

    /**
     * Sets the text size, which in turn sets the number of rows and columns.
     *
     * @param textSize the new font size, in density-independent pixels.
     */
    fun setTextSize(textSize: Int) {
        mRenderer = TerminalRenderer(
            textSize,
            if (mRenderer == null) Typeface.MONOSPACE else mRenderer!!.mTypeface
        )
        updateSize()
    }

    fun setTypeface(newTypeface: Typeface?) {
        mRenderer = TerminalRenderer(mRenderer!!.mTextSize, newTypeface!!)
        updateSize()
        invalidate()
    }

    override fun onCheckIsTextEditor(): Boolean {
        return true
    }

    override fun isOpaque(): Boolean {
        return true
    }

    /** Send a single mouse event code to the terminal.  */
    fun sendMouseEventCode(
        e: MotionEvent?,
        button: Int,
        pressed: Boolean
    ) {
        var x = (e!!.x / mRenderer!!.mFontWidth).toInt() + 1
        var y =
            ((e.y - mRenderer!!.mFontLineSpacingAndAscent) / mRenderer!!.mFontLineSpacing).toInt() + 1
        if (pressed && (button == TerminalEmulator.MOUSE_WHEELDOWN_BUTTON || button == TerminalEmulator.MOUSE_WHEELUP_BUTTON)) {
            if (mMouseStartDownTime == e.downTime) {
                x = mMouseScrollStartX
                y = mMouseScrollStartY
            } else {
                mMouseStartDownTime = e.downTime
                mMouseScrollStartX = x
                mMouseScrollStartY = y
            }
        }
        mEmulator!!.sendMouseEvent(button, x, y, pressed)
    }

    /** Perform a scroll, either from dragging the screen or by scrolling a mouse wheel.  */
    fun doScroll(event: MotionEvent?, rowsDown: Int) {
        val up = rowsDown < 0
        val amount = Math.abs(rowsDown)
        for (i in 0 until amount) {
            if (mEmulator!!.isMouseTrackingActive) {
                sendMouseEventCode(
                    event,
                    if (up) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON,
                    true
                )
            } else if (mEmulator!!.isAlternateBufferActive) {
                // Send up and down key events for scrolling, which is what some terminals do to make scroll work in
                // e.g. less, which shifts to the alt screen without mouse handling.
                handleKeyCode(
                    if (up) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN,
                    0
                )
            } else {
                mTopRow = Math.min(
                    0,
                    Math.max(
                        -mEmulator!!.screen.activeTranscriptRows,
                        mTopRow + if (up) -1 else 1
                    )
                )
                if (!awakenScrollBars()) invalidate()
            }
        }
    }

    /** Overriding [View.onGenericMotionEvent].  */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (mEmulator != null && event.isFromSource(InputDevice.SOURCE_MOUSE) && event.action == MotionEvent.ACTION_SCROLL) {
            // Handle mouse wheel scrolling.
            val up =
                event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0.0f
            doScroll(event, if (up) -3 else 3)
            return true
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    @TargetApi(23)
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (mEmulator == null) return true
        val action = ev.action
        if (mIsSelectingText) {
            updateFloatingToolbarVisibility(ev)
            mGestureRecognizer?.onTouchEvent(ev)
            return true
        } else if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (ev.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                if (action == MotionEvent.ACTION_DOWN) showContextMenu()
                return true
            } else if (ev.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = clipboard.primaryClip
                if (clipData != null) {
                    val paste =
                        clipData.getItemAt(0).coerceToText(context)
                    if (!TextUtils.isEmpty(paste)) mEmulator!!.paste(paste.toString())
                }
            } else if (mEmulator!!.isMouseTrackingActive) { // BUTTON_PRIMARY.
                when (ev.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> sendMouseEventCode(
                        ev,
                        TerminalEmulator.MOUSE_LEFT_BUTTON,
                        ev.action == MotionEvent.ACTION_DOWN
                    )
                    MotionEvent.ACTION_MOVE -> sendMouseEventCode(
                        ev,
                        TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED,
                        true
                    )
                }
                return true
            }
        }
        mGestureRecognizer?.onTouchEvent(ev)
        return true
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (LOG_KEY_EVENTS) Log.i(
            EmulatorDebug.LOG_TAG,
            "onKeyPreIme(keyCode=$keyCode, event=$event)"
        )
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mIsSelectingText) {
                stopTextSelectionMode()
                return true
            } else if (mClient!!.shouldBackButtonBeMappedToEscape()) {
                // Intercept back button to treat it as escape:
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> return onKeyDown(keyCode, event)
                    KeyEvent.ACTION_UP -> return onKeyUp(keyCode, event)
                }
            }
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (LOG_KEY_EVENTS) Log.i(
            EmulatorDebug.LOG_TAG,
            "onKeyDown(keyCode=" + keyCode + ", isSystem()=" + event.isSystem + ", event=" + event + ")"
        )
        if (mEmulator == null) return true
        stopTextSelectionMode()
        if (mClient!!.onKeyDown(keyCode, event, currentSession)) {
            invalidate()
            return true
        } else if (event.isSystem && (!mClient!!.shouldBackButtonBeMappedToEscape() || keyCode != KeyEvent.KEYCODE_BACK)) {
            return super.onKeyDown(keyCode, event)
        } else if (event.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            currentSession!!.write(event.characters)
            return true
        }
        val metaState = event.metaState
        val controlDown = event.isCtrlPressed || mClient!!.readControlKey()
        val leftAltDown =
            metaState and KeyEvent.META_ALT_LEFT_ON != 0 || mClient!!.readAltKey()
        val rightAltDownFromEvent =
            metaState and KeyEvent.META_ALT_RIGHT_ON != 0
        var keyMod = 0
        if (controlDown) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed || leftAltDown) keyMod =
            keyMod or KeyHandler.KEYMOD_ALT
        if (event.isShiftPressed) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        if (!event.isFunctionPressed && handleKeyCode(keyCode, keyMod)) {
            if (LOG_KEY_EVENTS) Log.i(
                EmulatorDebug.LOG_TAG,
                "handleKeyCode() took key event"
            )
            return true
        }

        // Clear Ctrl since we handle that ourselves:
        var bitsToClear = KeyEvent.META_CTRL_MASK
        if (rightAltDownFromEvent) {
            // Let right Alt/Alt Gr be used to compose characters.
        } else {
            // Use left alt to send to terminal (e.g. Left Alt+B to jump back a word), so remove:
            bitsToClear =
                bitsToClear or (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        }
        val effectiveMetaState = event.metaState and bitsToClear.inv()
        var result = event.getUnicodeChar(effectiveMetaState)
        if (LOG_KEY_EVENTS) Log.i(
            EmulatorDebug.LOG_TAG,
            "KeyEvent#getUnicodeChar($effectiveMetaState) returned: $result"
        )
        if (result == 0) {
            return false
        }
        val oldCombiningAccent = mCombiningAccent
        if (result and KeyCharacterMap.COMBINING_ACCENT != 0) {
            // If entered combining accent previously, write it out:
            if (mCombiningAccent != 0) inputCodePoint(mCombiningAccent, controlDown, leftAltDown)
            mCombiningAccent = result and KeyCharacterMap.COMBINING_ACCENT_MASK
        } else {
            if (mCombiningAccent != 0) {
                val combinedChar = KeyCharacterMap.getDeadChar(mCombiningAccent, result)
                if (combinedChar > 0) result = combinedChar
                mCombiningAccent = 0
            }
            inputCodePoint(result, controlDown, leftAltDown)
        }
        if (mCombiningAccent != oldCombiningAccent) invalidate()
        return true
    }

    fun inputCodePoint(
        codePoint: Int,
        controlDownFromEvent: Boolean,
        leftAltDownFromEvent: Boolean
    ) {
        var codePoint = codePoint
        if (LOG_KEY_EVENTS) {
            Log.i(
                EmulatorDebug.LOG_TAG,
                "inputCodePoint(codePoint=" + codePoint + ", controlDownFromEvent=" + controlDownFromEvent + ", leftAltDownFromEvent="
                    + leftAltDownFromEvent + ")"
            )
        }
        if (currentSession == null) return
        val controlDown = controlDownFromEvent || mClient!!.readControlKey()
        val altDown = leftAltDownFromEvent || mClient!!.readAltKey()
        if (mClient!!.onCodePoint(codePoint, controlDown, currentSession)) return
        if (controlDown) {
            if (codePoint >= 'a'.toInt() && codePoint <= 'z'.toInt()) {
                codePoint = codePoint - 'a'.toInt() + 1
            } else if (codePoint >= 'A'.toInt() && codePoint <= 'Z'.toInt()) {
                codePoint = codePoint - 'A'.toInt() + 1
            } else if (codePoint == ' '.toInt() || codePoint == '2'.toInt()) {
                codePoint = 0
            } else if (codePoint == '['.toInt() || codePoint == '3'.toInt()) {
                codePoint = 27 // ^[ (Esc)
            } else if (codePoint == '\\'.toInt() || codePoint == '4'.toInt()) {
                codePoint = 28
            } else if (codePoint == ']'.toInt() || codePoint == '5'.toInt()) {
                codePoint = 29
            } else if (codePoint == '^'.toInt() || codePoint == '6'.toInt()) {
                codePoint = 30 // control-^
            } else if (codePoint == '_'.toInt() || codePoint == '7'.toInt() || codePoint == '/'.toInt()) {
                // "Ctrl-/ sends 0x1f which is equivalent of Ctrl-_ since the days of VT102"
                // - http://apple.stackexchange.com/questions/24261/how-do-i-send-c-that-is-control-slash-to-the-terminal
                codePoint = 31
            } else if (codePoint == '8'.toInt()) {
                codePoint = 127 // DEL
            }
        }
        if (codePoint > -1) {
            // Work around bluetooth keyboards sending funny unicode characters instead
            // of the more normal ones from ASCII that terminal programs expect - the
            // desire to input the original characters should be low.
            when (codePoint) {
                0x02DC -> codePoint = 0x007E // TILDE (~).
                0x02CB -> codePoint = 0x0060 // GRAVE ACCENT (`).
                0x02C6 -> codePoint = 0x005E // CIRCUMFLEX ACCENT (^).
            }

            // If left alt, send escape before the code point to make e.g. Alt+B and Alt+F work in readline:
            currentSession!!.writeCodePoint(altDown, codePoint)
        }
    }

    /** Input the specified keyCode if applicable and return if the input was consumed.  */
    fun handleKeyCode(keyCode: Int, keyMod: Int): Boolean {
        val term = currentSession!!.emulator
        val code = KeyHandler.getCode(
            keyCode,
            keyMod,
            term.isCursorKeysApplicationMode,
            term.isKeypadApplicationMode
        )
            ?: return false
        currentSession!!.write(code)
        return true
    }

    /**
     * Called when a key is released in the view.
     *
     * @param keyCode The keycode of the key which was released.
     * @param event   A [KeyEvent] describing the event.
     * @return Whether the event was handled.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (LOG_KEY_EVENTS) Log.i(
            EmulatorDebug.LOG_TAG,
            "onKeyUp(keyCode=$keyCode, event=$event)"
        )
        if (mEmulator == null) return true
        if (mClient!!.onKeyUp(keyCode, event)) {
            invalidate()
            return true
        } else if (event.isSystem) {
            // Let system key events through.
            return super.onKeyUp(keyCode, event)
        }
        return true
    }

    /**
     * This is called during layout when the size of this view has changed. If you were just added to the view
     * hierarchy, you're called with the old values of 0.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateSize()
    }

    /** Check if the terminal size in rows and columns should be updated.  */
    fun updateSize() {
        val viewWidth = width
        val viewHeight = height
        if (viewWidth == 0 || viewHeight == 0 || currentSession == null) return

        // Set to 80 and 24 if you want to enable vttest.
        val newColumns = Math.max(4, (viewWidth / mRenderer!!.mFontWidth).toInt())
        val newRows = Math.max(
            4,
            (viewHeight - mRenderer!!.mFontLineSpacingAndAscent) / mRenderer!!.mFontLineSpacing
        )
        if (mEmulator == null || newColumns != mEmulator!!.mColumns || newRows != mEmulator!!.mRows) {
            currentSession!!.updateSize(newColumns, newRows)
            mEmulator = currentSession!!.emulator
            mTopRow = 0
            scrollTo(0, 0)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (mEmulator == null) {
            canvas.drawColor(-0x1000000)
        } else {
            mRenderer!!.render(mEmulator!!, canvas, mTopRow, mSelY1, mSelY2, mSelX1, mSelX2)
            val selectionController: SelectionModifierCursorController? = selectionController
            if (selectionController != null && selectionController.isActive) {
                selectionController.updatePosition()
            }
        }
    }

    /** Toggle text selection mode in the view.  */
    @TargetApi(23)
    fun startSelectingText(ev: MotionEvent?) {
        val cx = (ev!!.x / mRenderer!!.mFontWidth).toInt()
        val eventFromMouse = ev.isFromSource(InputDevice.SOURCE_MOUSE)
        // Offset for finger:
        val SELECT_TEXT_OFFSET_Y = if (eventFromMouse) 0 else -40
        val cy =
            ((ev.y + SELECT_TEXT_OFFSET_Y) / mRenderer!!.mFontLineSpacing).toInt() + mTopRow
        mSelX2 = cx
        mSelX1 = mSelX2
        mSelY2 = cy
        mSelY1 = mSelY2
        val screen = mEmulator!!.screen
        if (" " != screen.getSelectedText(mSelX1, mSelY1, mSelX1, mSelY1)) {
            // Selecting something other than whitespace. Expand to word.
            while (mSelX1 > 0 && "" != screen.getSelectedText(
                    mSelX1 - 1,
                    mSelY1,
                    mSelX1 - 1,
                    mSelY1
                )
            ) {
                mSelX1--
            }
            while (mSelX2 < mEmulator!!.mColumns - 1 && "" != screen.getSelectedText(
                    mSelX2 + 1,
                    mSelY1,
                    mSelX2 + 1,
                    mSelY1
                )
            ) {
                mSelX2++
            }
        }
        startTextSelectionMode()
    }

    private val text: CharSequence
        private get() = mEmulator!!.screen
            .getSelectedText(0, mTopRow, mEmulator!!.mColumns, mTopRow + mEmulator!!.mRows)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mSelectionModifierCursorController != null) {
            viewTreeObserver.addOnTouchModeChangeListener(mSelectionModifierCursorController)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (mSelectionModifierCursorController != null) {
            viewTreeObserver.removeOnTouchModeChangeListener(mSelectionModifierCursorController)
            mSelectionModifierCursorController!!.onDetached()
        }
    }

    private fun getCursorX(x: Float): Int {
        return (x / mRenderer!!.mFontWidth).toInt()
    }

    private fun getCursorY(y: Float): Int {
        return ((y - 40) / mRenderer!!.mFontLineSpacing + mTopRow).toInt()
    }

    private fun getPointX(cx: Int): Int {
        var cx = cx
        if (cx > mEmulator!!.mColumns) {
            cx = mEmulator!!.mColumns
        }
        return Math.round(cx * mRenderer!!.mFontWidth)
    }

    private fun getPointY(cy: Int): Int {
        return Math.round((cy - mTopRow) * mRenderer!!.mFontLineSpacing.toFloat())
    }

    /**
     * A CursorController instance can be used to control a cursor in the text.
     * It is not used outside of [TerminalView].
     */
    interface CursorController : OnTouchModeChangeListener {
        /**
         * Makes the cursor controller visible on screen. Will be drawn by [.draw].
         * See also [.hide].
         */
        fun show()

        /**
         * Hide the cursor controller from screen.
         * See also [.show].
         */
        fun hide()

        /**
         * @return true if the CursorController is currently visible
         */
        val isActive: Boolean

        /**
         * Update the controller's position.
         */
        fun updatePosition(handle: HandleView, x: Int, y: Int)
        fun updatePosition()

        /**
         * This method is called by [.onTouchEvent] and gives the controller
         * a chance to become active and/or visible.
         *
         * @param event The touch event
         */
        fun onTouchEvent(event: MotionEvent?): Boolean

        /**
         * Called when the view is detached from window. Perform house keeping task, such as
         * stopping Runnable thread that would otherwise keep a reference on the context, thus
         * preventing the activity to be recycled.
         */
        fun onDetached()
    }

    inner class HandleView(private val mController: CursorController, orientation: Int) :
        View(this@TerminalView.context) {

        private var mDrawable: Drawable? = null
        private val mContainer: PopupWindow
        private var mPointX = 0
        private var mPointY = 0
        var isDragging = false
            private set
        private var mTouchToWindowOffsetX = 0f
        private var mTouchToWindowOffsetY = 0f
        private var mHotspotX = 0f
        private var mHotspotY = 0f
        private var mTouchOffsetY = 0f
        private var mLastParentX = 0
        private var mLastParentY = 0
        var mHandleWidth = 0
        private val mOrigOrient: Int
        private var mOrientation = 0
        var mHandleHeight = 0
        private var mLastTime: Long = 0
        fun setOrientation(orientation: Int) {
            mOrientation = orientation
            var handleWidth = 0
            when (orientation) {
                LEFT -> {
                    if (mSelectHandleLeft == null) {
                        mSelectHandleLeft = context.getDrawable(
                            R.drawable.text_select_handle_left_material
                        )
                    }
                    //
                    mDrawable = mSelectHandleLeft
                    handleWidth = mDrawable!!.intrinsicWidth
                    mHotspotX = handleWidth * 3 / 4.toFloat()
                }
                RIGHT -> {
                    if (mSelectHandleRight == null) {
                        mSelectHandleRight = context.getDrawable(
                            R.drawable.text_select_handle_right_material
                        )
                    }
                    mDrawable = mSelectHandleRight
                    handleWidth = mDrawable!!.intrinsicWidth
                    mHotspotX = handleWidth / 4.toFloat()
                }
            }
            mHandleHeight = mDrawable!!.intrinsicHeight
            mHandleWidth = handleWidth
            mTouchOffsetY = -mHandleHeight * 0.3f
            mHotspotY = 0f
            invalidate()
        }

        fun changeOrientation(orientation: Int) {
            if (mOrientation != orientation) {
                setOrientation(orientation)
            }
        }

        public override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(
                mDrawable!!.intrinsicWidth,
                mDrawable!!.intrinsicHeight
            )
        }

        fun show() {
            if (!isPositionVisible) {
                hide()
                return
            }
            mContainer.contentView = this
            val coords = mTempCoords
            this@TerminalView.getLocationInWindow(coords)
            coords[0] += mPointX
            coords[1] += mPointY
            mContainer.showAtLocation(this@TerminalView, 0, coords[0], coords[1])
        }

        fun hide() {
            isDragging = false
            mContainer.dismiss()
        }

        val isShowing: Boolean
            get() = mContainer.isShowing

        private fun checkChangedOrientation(posX: Int, force: Boolean) {
            if (!isDragging && !force) {
                return
            }
            val millis = SystemClock.currentThreadTimeMillis()
            if (millis - mLastTime < 50 && !force) {
                return
            }
            mLastTime = millis
            val hostView = this@TerminalView
            val left = hostView.left
            val right = hostView.width
            val top = hostView.top
            val bottom = hostView.height
            if (mTempRect == null) {
                mTempRect = Rect()
            }
            val clip = mTempRect!!
            clip.left = left + this@TerminalView.paddingLeft
            clip.top = top + this@TerminalView.paddingTop
            clip.right = right - this@TerminalView.paddingRight
            clip.bottom = bottom - this@TerminalView.paddingBottom
            val parent = hostView.parent
            if (parent == null || !parent.getChildVisibleRect(hostView, clip, null)) {
                return
            }
            if (posX - mHandleWidth < clip.left) {
                changeOrientation(RIGHT)
            } else if (posX + mHandleWidth > clip.right) {
                changeOrientation(LEFT)
            } else {
                changeOrientation(mOrigOrient)
            }
        }

        // Always show a dragging handle.
        private val isPositionVisible: Boolean
            private get() {
                // Always show a dragging handle.
                if (isDragging) {
                    return true
                }
                val hostView = this@TerminalView
                val left = 0
                val right = hostView.width
                val top = 0
                val bottom = hostView.height
                if (mTempRect == null) {
                    mTempRect = Rect()
                }
                val clip = mTempRect!!
                clip.left = left + this@TerminalView.paddingLeft
                clip.top = top + this@TerminalView.paddingTop
                clip.right = right - this@TerminalView.paddingRight
                clip.bottom = bottom - this@TerminalView.paddingBottom
                val parent = hostView.parent
                if (parent == null || !parent.getChildVisibleRect(hostView, clip, null)) {
                    return false
                }
                val coords = mTempCoords
                hostView.getLocationInWindow(coords)
                val posX = coords[0] + mPointX + mHotspotX.toInt()
                val posY = coords[1] + mPointY + mHotspotY.toInt()
                return posX >= clip.left && posX <= clip.right && posY >= clip.top && posY <= clip.bottom
            }

        private fun moveTo(x: Int, y: Int, forceOrientationCheck: Boolean) {
            val oldHotspotX = mHotspotX
            checkChangedOrientation(x, forceOrientationCheck)
            mPointX = (x - if (isShowing) oldHotspotX else mHotspotX).toInt()
            mPointY = y
            if (isPositionVisible) {
                var coords: IntArray? = null
                if (isShowing) {
                    coords = mTempCoords
                    this@TerminalView.getLocationInWindow(coords)
                    val x1 = coords[0] + mPointX
                    val y1 = coords[1] + mPointY
                    mContainer.update(
                        x1, y1,
                        width, height
                    )
                } else {
                    show()
                }
                if (isDragging) {
                    if (coords == null) {
                        coords = mTempCoords
                        this@TerminalView.getLocationInWindow(coords)
                    }
                    if (coords[0] != mLastParentX || coords[1] != mLastParentY) {
                        mTouchToWindowOffsetX += coords[0] - mLastParentX.toFloat()
                        mTouchToWindowOffsetY += coords[1] - mLastParentY.toFloat()
                        mLastParentX = coords[0]
                        mLastParentY = coords[1]
                    }
                }
            } else {
                if (isShowing) {
                    hide()
                }
            }
        }

        public override fun onDraw(c: Canvas) {
            val drawWidth = mDrawable!!.intrinsicWidth
            val height = mDrawable!!.intrinsicHeight
            mDrawable!!.setBounds(0, 0, drawWidth, height)
            mDrawable!!.draw(c)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            updateFloatingToolbarVisibility(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val rawX = ev.rawX
                    val rawY = ev.rawY
                    mTouchToWindowOffsetX = rawX - mPointX
                    mTouchToWindowOffsetY = rawY - mPointY
                    val coords = mTempCoords
                    this@TerminalView.getLocationInWindow(coords)
                    mLastParentX = coords[0]
                    mLastParentY = coords[1]
                    isDragging = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val rawX = ev.rawX
                    val rawY = ev.rawY
                    val newPosX = rawX - mTouchToWindowOffsetX + mHotspotX
                    val newPosY =
                        rawY - mTouchToWindowOffsetY + mHotspotY + mTouchOffsetY
                    mController.updatePosition(
                        this,
                        Math.round(newPosX),
                        Math.round(newPosY)
                    )
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging =
                    false
            }
            return true
        }

        fun positionAtCursor(cx: Int, cy: Int, forceOrientationCheck: Boolean) {
            val left = getPointX(cx)
            val bottom = getPointY(cy + 1)
            moveTo(left, bottom, forceOrientationCheck)
        }

        init {
            mContainer = PopupWindow(
                this@TerminalView.context, null,
                android.R.attr.textSelectHandleWindowStyle
            )
            mContainer.isSplitTouchEnabled = true
            mContainer.isClippingEnabled = false
            mContainer.windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
            mContainer.width = ViewGroup.LayoutParams.WRAP_CONTENT
            mContainer.height = ViewGroup.LayoutParams.WRAP_CONTENT
            mOrigOrient = orientation
            setOrientation(orientation)
        }
    }

    inner class SelectionModifierCursorController internal constructor() :
        CursorController {
        private val mHandleHeight: Int

        // The cursor controller images
        private val mStartHandle: HandleView
        private val mEndHandle: HandleView

        // Whether selection anchors are active
        override var isActive = false
            private set

        override fun show() {
            isActive = true
            mStartHandle.positionAtCursor(mSelX1, mSelY1, true)
            mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, true)
            val callback: ActionMode.Callback = object : ActionMode.Callback {
                override fun onCreateActionMode(
                    mode: ActionMode,
                    menu: Menu
                ): Boolean {
                    val show =
                        MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    menu.add(Menu.NONE, 1, Menu.NONE, R.string.copy_text)
                        .setShowAsAction(show)
                    menu.add(Menu.NONE, 2, Menu.NONE, R.string.paste_text)
                        .setEnabled(clipboard.hasPrimaryClip()).setShowAsAction(show)
                    menu.add(
                        Menu.NONE,
                        3,
                        Menu.NONE,
                        R.string.text_selection_more
                    )
                    return true
                }

                override fun onPrepareActionMode(
                    mode: ActionMode,
                    menu: Menu
                ): Boolean {
                    return false
                }

                override fun onActionItemClicked(
                    mode: ActionMode,
                    item: MenuItem
                ): Boolean {
                    if (!mIsSelectingText) {
                        // Fix issue where the dialog is pressed while being dismissed.
                        return true
                    }
                    when (item.itemId) {
                        1 -> {
                            val selectedText =
                                mEmulator!!.getSelectedText(mSelX1, mSelY1, mSelX2, mSelY2)
                                    .trim { it <= ' ' }
                            currentSession!!.clipboardText(selectedText)
                        }
                        2 -> {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipData = clipboard.primaryClip
                            if (clipData != null) {
                                val paste =
                                    clipData.getItemAt(0).coerceToText(context)
                                if (!TextUtils.isEmpty(paste)) mEmulator!!.paste(paste.toString())
                            }
                        }
                        3 -> showContextMenu()
                    }
                    stopTextSelectionMode()
                    return true
                }

                override fun onDestroyActionMode(mode: ActionMode) {}
            }
            mActionMode = startActionMode(object : ActionMode.Callback2() {
                override fun onCreateActionMode(
                    mode: ActionMode,
                    menu: Menu
                ): Boolean {
                    return callback.onCreateActionMode(mode, menu)
                }

                override fun onPrepareActionMode(
                    mode: ActionMode,
                    menu: Menu
                ): Boolean {
                    return false
                }

                override fun onActionItemClicked(
                    mode: ActionMode,
                    item: MenuItem
                ): Boolean {
                    return callback.onActionItemClicked(mode, item)
                }

                override fun onDestroyActionMode(mode: ActionMode) {
                    // Ignore.
                }

                override fun onGetContentRect(
                    mode: ActionMode,
                    view: View,
                    outRect: Rect
                ) {
                    var x1 = Math.round(mSelX1 * mRenderer!!.mFontWidth)
                    var x2 = Math.round(mSelX2 * mRenderer!!.mFontWidth)
                    val y1 =
                        Math.round((mSelY1 - 1 - mTopRow) * mRenderer!!.mFontLineSpacing.toFloat())
                    val y2 =
                        Math.round((mSelY2 + 1 - mTopRow) * mRenderer!!.mFontLineSpacing.toFloat())
                    if (x1 > x2) {
                        val tmp = x1
                        x1 = x2
                        x2 = tmp
                    }
                    outRect[x1, y1 + mHandleHeight, x2] = y2 + mHandleHeight
                }
            }, ActionMode.TYPE_FLOATING)
        }

        override fun hide() {
            mStartHandle.hide()
            mEndHandle.hide()
            isActive = false
            if (mActionMode != null) {
                // This will hide the mSelectionModifierCursorController
                mActionMode!!.finish()
            }
        }

        override fun updatePosition(handle: HandleView, x: Int, y: Int) {
            val screen = mEmulator!!.screen
            val scrollRows = screen.activeRows - mEmulator!!.mRows
            if (handle === mStartHandle) {
                mSelX1 = getCursorX(x.toFloat())
                mSelY1 = getCursorY(y.toFloat())
                if (mSelX1 < 0) {
                    mSelX1 = 0
                }
                if (mSelY1 < -scrollRows) {
                    mSelY1 = -scrollRows
                } else if (mSelY1 > mEmulator!!.mRows - 1) {
                    mSelY1 = mEmulator!!.mRows - 1
                }
                if (mSelY1 > mSelY2) {
                    mSelY1 = mSelY2
                }
                if (mSelY1 == mSelY2 && mSelX1 > mSelX2) {
                    mSelX1 = mSelX2
                }
                if (!mEmulator!!.isAlternateBufferActive) {
                    if (mSelY1 <= mTopRow) {
                        mTopRow--
                        if (mTopRow < -scrollRows) {
                            mTopRow = -scrollRows
                        }
                    } else if (mSelY1 >= mTopRow + mEmulator!!.mRows) {
                        mTopRow++
                        if (mTopRow > 0) {
                            mTopRow = 0
                        }
                    }
                }
                mSelX1 = getValidCurX(screen, mSelY1, mSelX1)
            } else {
                mSelX2 = getCursorX(x.toFloat())
                mSelY2 = getCursorY(y.toFloat())
                if (mSelX2 < 0) {
                    mSelX2 = 0
                }
                if (mSelY2 < -scrollRows) {
                    mSelY2 = -scrollRows
                } else if (mSelY2 > mEmulator!!.mRows - 1) {
                    mSelY2 = mEmulator!!.mRows - 1
                }
                if (mSelY1 > mSelY2) {
                    mSelY2 = mSelY1
                }
                if (mSelY1 == mSelY2 && mSelX1 > mSelX2) {
                    mSelX2 = mSelX1
                }
                if (!mEmulator!!.isAlternateBufferActive) {
                    if (mSelY2 <= mTopRow) {
                        mTopRow--
                        if (mTopRow < -scrollRows) {
                            mTopRow = -scrollRows
                        }
                    } else if (mSelY2 >= mTopRow + mEmulator!!.mRows) {
                        mTopRow++
                        if (mTopRow > 0) {
                            mTopRow = 0
                        }
                    }
                }
                mSelX2 = getValidCurX(screen, mSelY2, mSelX2)
            }
            invalidate()
        }

        private fun getValidCurX(screen: TerminalBuffer, cy: Int, cx: Int): Int {
            val line = screen.getSelectedText(0, cy, cx, cy)
            if (!TextUtils.isEmpty(line)) {
                var col = 0
                var i = 0
                val len = line.length
                while (i < len) {
                    val ch1 = line[i]
                    if (ch1.toInt() == 0) {
                        break
                    }
                    var wc: Int
                    wc = if (Character.isHighSurrogate(ch1) && i + 1 < len) {
                        val ch2 = line[++i]
                        WcWidth.width(Character.toCodePoint(ch1, ch2))
                    } else {
                        WcWidth.width(ch1.toInt())
                    }
                    val cend = col + wc
                    if (cx > col && cx < cend) {
                        return cend
                    }
                    if (cend == col) {
                        return col
                    }
                    col = cend
                    i++
                }
            }
            return cx
        }

        override fun updatePosition() {
            if (!isActive) {
                return
            }
            mStartHandle.positionAtCursor(mSelX1, mSelY1, false)
            mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, false)
            if (mActionMode != null) {
                mActionMode!!.invalidate()
            }
        }

        override fun onTouchEvent(event: MotionEvent?): Boolean {
            return false
        }

        /**
         * @return true iff this controller is currently used to move the selection start.
         */
        val isSelectionStartDragged: Boolean
            get() = mStartHandle.isDragging

        val isSelectionEndDragged: Boolean
            get() = mEndHandle.isDragging

        override fun onTouchModeChanged(isInTouchMode: Boolean) {
            if (!isInTouchMode) {
                hide()
            }
        }

        override fun onDetached() {}

        init {
            mStartHandle = HandleView(this, LEFT)
            mEndHandle = HandleView(this, RIGHT)
            mHandleHeight = Math.max(mStartHandle.mHandleHeight, mEndHandle.mHandleHeight)
        }
    }

    val selectionController: SelectionModifierCursorController
        get() {
            if (mSelectionModifierCursorController == null) {
                mSelectionModifierCursorController = SelectionModifierCursorController()
                val observer = viewTreeObserver
                observer?.addOnTouchModeChangeListener(mSelectionModifierCursorController)
            }
            return mSelectionModifierCursorController!!
        }

    private fun hideSelectionModifierCursorController() {
        if (mSelectionModifierCursorController != null && mSelectionModifierCursorController!!.isActive) {
            mSelectionModifierCursorController!!.hide()
        }
    }

    private fun startTextSelectionMode() {
        if (!requestFocus()) {
            return
        }
        selectionController.show()
        mIsSelectingText = true
        mClient!!.copyModeChanged(mIsSelectingText)
        invalidate()
    }

    private fun stopTextSelectionMode() {
        if (mIsSelectingText) {
            hideSelectionModifierCursorController()
            mSelY2 = -1
            mSelX2 = mSelY2
            mSelY1 = mSelX2
            mSelX1 = mSelY1
            mIsSelectingText = false
            mClient!!.copyModeChanged(mIsSelectingText)
            invalidate()
        }
    }

    private val mShowFloatingToolbar = Runnable {
        if (mActionMode != null) {
            mActionMode!!.hide(0) // hide off.
        }
    }

    fun hideFloatingToolbar(duration: Int) {
        if (mActionMode != null) {
            removeCallbacks(mShowFloatingToolbar)
            mActionMode!!.hide(duration.toLong())
        }
    }

    private fun showFloatingToolbar() {
        if (mActionMode != null) {
            val delay = ViewConfiguration.getDoubleTapTimeout()
            postDelayed(mShowFloatingToolbar, delay.toLong())
        }
    }

    private fun updateFloatingToolbarVisibility(event: MotionEvent) {
        if (mActionMode != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> hideFloatingToolbar(-1)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> showFloatingToolbar()
            }
        }
    }

    companion object {
        /** Log view key and IME events.  */
        private const val LOG_KEY_EVENTS = false
    }

    init { // NO_UCD (unused code)
        mScroller = Scroller(context)

        mGestureRecognizer =
            GestureAndScaleRecognizer(context, object : GestureAndScaleRecognizer.Listener {
                var scrolledWithFinger = false
                override fun onUp(e: MotionEvent?): Boolean {
                    mScrollRemainder = 0.0f
                    if (mEmulator != null && mEmulator!!.isMouseTrackingActive && !mIsSelectingText && !scrolledWithFinger) {
                        // Quick event processing when mouse tracking is active - do not wait for check of double tapping
                        // for zooming.
                        sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON, true)
                        sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON, false)
                        return true
                    }
                    scrolledWithFinger = false
                    return false
                }

                override fun onSingleTapUp(e: MotionEvent?): Boolean {
                    if (mEmulator == null) return true
                    if (mIsSelectingText) {
                        stopTextSelectionMode()
                        return true
                    }
                    requestFocus()
                    if (!mEmulator!!.isMouseTrackingActive) {
                        if (!e!!.isFromSource(InputDevice.SOURCE_MOUSE)) {
                            mClient!!.onSingleTapUp(e)
                            return true
                        }
                    }
                    return false
                }

                override fun onScroll(
                    e: MotionEvent?,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    var distanceY = distanceY
                    if (mEmulator == null) return true
                    if (mEmulator!!.isMouseTrackingActive && e!!.isFromSource(InputDevice.SOURCE_MOUSE)) {
                        // If moving with mouse pointer while pressing button, report that instead of scroll.
                        // This means that we never report moving with button press-events for touch input,
                        // since we cannot just start sending these events without a starting press event,
                        // which we do not do for touch input, only mouse in onTouchEvent().
                        sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true)
                    } else {
                        scrolledWithFinger = true
                        distanceY += mScrollRemainder
                        val deltaRows = (distanceY / mRenderer!!.mFontLineSpacing).toInt()
                        mScrollRemainder = distanceY - deltaRows * mRenderer!!.mFontLineSpacing
                        doScroll(e, deltaRows)
                    }
                    return true
                }

                override fun onScale(
                    focusX: Float,
                    focusY: Float,
                    scale: Float
                ): Boolean {
                    if (mEmulator == null || mIsSelectingText) return true
                    mScaleFactor *= scale
                    mScaleFactor = mClient!!.onScale(mScaleFactor)
                    return true
                }

                override fun onFling(
                    e2: MotionEvent?,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (mEmulator == null) return true
                    // Do not start scrolling until last fling has been taken care of:
                    if (!mScroller.isFinished) return true
                    val mouseTrackingAtStartOfFling =
                        mEmulator!!.isMouseTrackingActive
                    val SCALE = 0.25f
                    if (mouseTrackingAtStartOfFling) {
                        mScroller.fling(
                            0,
                            0,
                            0,
                            (-(velocityY * SCALE)).toInt(),
                            0,
                            0,
                            -mEmulator!!.mRows / 2,
                            mEmulator!!.mRows / 2
                        )
                    } else {
                        mScroller.fling(
                            0,
                            mTopRow,
                            0,
                            (-(velocityY * SCALE)).toInt(),
                            0,
                            0,
                            -mEmulator!!.screen.activeTranscriptRows,
                            0
                        )
                    }
                    post(object : Runnable {
                        private var mLastY = 0
                        override fun run() {
                            if (mouseTrackingAtStartOfFling != mEmulator!!.isMouseTrackingActive) {
                                mScroller.abortAnimation()
                                return
                            }
                            if (mScroller.isFinished) return
                            val more = mScroller.computeScrollOffset()
                            val newY = mScroller.currY
                            val diff =
                                if (mouseTrackingAtStartOfFling) newY - mLastY else newY - mTopRow
                            doScroll(e2, diff)
                            mLastY = newY
                            if (more) post(this)
                        }
                    })
                    return true
                }

                override fun onDown(x: Float, y: Float): Boolean {
                    return false
                }

                override fun onDoubleTap(e: MotionEvent?): Boolean {
                    // Do not treat is as a single confirmed tap - it may be followed by zoom.
                    return false
                }

                override fun onLongPress(e: MotionEvent?) {
                    if (mGestureRecognizer?.isInProgress == true) return
                    if (mClient!!.onLongPress(e)) return
                    if (!mIsSelectingText) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        startSelectingText(e)
                    }
                }
            })
        val am =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        mAccessibilityEnabled = am.isEnabled
    }
}
