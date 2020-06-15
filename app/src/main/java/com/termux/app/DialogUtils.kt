package com.termux.app

import android.R
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import android.content.DialogInterface
import android.text.Selection
import android.util.TypedValue
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object DialogUtils {
    @JvmStatic
    fun textInput(activity: androidx.appcompat.app.AppCompatActivity, titleText: Int, initialText: String?,
                  positiveButtonText: Int, onPositive: TextSetListener,
                  neutralButtonText: Int, onNeutral: TextSetListener?,
                  negativeButtonText: Int, onNegative: TextSetListener?,
                  onDismiss: DialogInterface.OnDismissListener?) {
        val input = EditText(activity)
        input.setSingleLine()
        if (initialText != null) {
            input.setText(initialText)
            Selection.setSelection(input.text, initialText.length)
        }
        val dialogHolder = arrayOfNulls<androidx.appcompat.app.AlertDialog>(1)
        input.setImeActionLabel(activity.resources.getString(positiveButtonText), KeyEvent.KEYCODE_ENTER)
        input.setOnEditorActionListener { v: TextView?, actionId: Int, event: KeyEvent? ->
            onPositive.onTextSet(input.text.toString())
            dialogHolder[0]!!.dismiss()
            true
        }
        val dipInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, activity.resources.displayMetrics)
        // https://www.google.com/design/spec/components/dialogs.html#dialogs-specs
        val paddingTopAndSides = Math.round(16 * dipInPixels)
        val paddingBottom = Math.round(24 * dipInPixels)
        val layout = LinearLayout(activity)
        layout.orientation = LinearLayout.VERTICAL
        layout.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        layout.setPadding(paddingTopAndSides, paddingTopAndSides, paddingTopAndSides, paddingBottom)
        layout.addView(input)
        val builder = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(titleText).setView(layout)
            .setPositiveButton(positiveButtonText) { d: DialogInterface?, whichButton: Int -> onPositive.onTextSet(input.text.toString()) }
        if (onNeutral != null) {
            builder.setNeutralButton(neutralButtonText) { dialog: DialogInterface?, which: Int -> onNeutral.onTextSet(input.text.toString()) }
        }
        if (onNegative == null) {
            builder.setNegativeButton(R.string.cancel, null)
        } else {
            builder.setNegativeButton(negativeButtonText) { dialog: DialogInterface?, which: Int -> onNegative.onTextSet(input.text.toString()) }
        }
        if (onDismiss != null) builder.setOnDismissListener(onDismiss)
        dialogHolder[0] = builder.create()
        dialogHolder[0].setCanceledOnTouchOutside(false)
        dialogHolder[0].show()
    }

    interface TextSetListener {
        fun onTextSet(text: String?)
    }
}
