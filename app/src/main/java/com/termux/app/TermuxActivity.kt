package com.termux.app

import android.Manifest
import android.annotation.SuppressLint
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.util.Log
import android.view.*
import android.view.ContextMenu.ContextMenuInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.widget.AdapterView.OnItemClickListener
import android.widget.AdapterView.OnItemLongClickListener
import androidx.drawerlayout.widget.DrawerLayout
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener
import com.termux.R
import com.termux.app.DialogUtils.TextSetListener
import com.termux.terminal.EmulatorDebug
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSession.SessionChangedCallback
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import java.io.File
import java.io.FileInputStream
import java.util.*
import java.util.regex.Pattern

/**
 * A terminal emulator activity.
 *
 *
 * See
 *
 *  * http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android
 *  * https://code.google.com/p/android/issues/detail?id=6426
 *
 * about memory leaks.
 */
class TermuxActivity : androidx.appcompat.app.AppCompatActivity(), ServiceConnection {
    val mBellSoundPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
        AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build()

    /**
     * The main view of the activity showing the terminal. Initialized in onCreate().
     */
    var mTerminalView: TerminalView = null
    var mExtraKeysView: ExtraKeysView? = null
    var mSettings: TermuxPreferences? = null

    /**
     * The connection to the [TermuxService]. Requested in [.onCreate] with a call to
     * [.bindService], and obtained and stored in
     * [.onServiceConnected].
     */
    var mTermService: TermuxService? = null

    /**
     * Initialized in [.onServiceConnected].
     */
    var mListViewAdapter: ArrayAdapter<TerminalSession?>? = null

    /**
     * The last toast shown, used cancel current toast before showing new in [.showToast].
     */
    var mLastToast: Toast? = null

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    var mIsVisible = false
    private val mBroadcastReceiever: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (mIsVisible) {
                val whatToReload = intent.getStringExtra(RELOAD_STYLE_ACTION)
                if ("storage" == whatToReload) {
                    if (ensureStoragePermissionGranted()) TermuxInstaller.setupStorageSymlinks(this@TermuxActivity)
                    return
                }
                checkForFontAndColors()
                mSettings!!.reloadFromProperties(this@TermuxActivity)
                if (mExtraKeysView != null) {
                    mExtraKeysView!!.reload(mSettings!!.mExtraKeys)
                }
            }
        }
    }
    var mIsUsingBlackUI = false
    var mBellSoundId = 0
    fun checkForFontAndColors() {
        try {
            @SuppressLint("SdCardPath") val fontFile = File("/data/data/com.termux/files/home/.termux/font.ttf")
            @SuppressLint("SdCardPath") val colorsFile = File("/data/data/com.termux/files/home/.termux/colors.properties")
            val props = Properties()
            if (colorsFile.isFile) {
                FileInputStream(colorsFile).use { `in` -> props.load(`in`) }
            }
            TerminalColors.COLOR_SCHEME.updateWith(props)
            val session = currentTermSession
            if (session != null && session.emulator != null) {
                session.emulator.mColors.reset()
            }
            updateBackgroundColor()
            val newTypeface = if (fontFile.exists() && fontFile.length() > 0) Typeface.createFromFile(fontFile) else Typeface.MONOSPACE
            mTerminalView.setTypeface(newTypeface)
        } catch (e: Exception) {
            Log.e(EmulatorDebug.LOG_TAG, "Error in checkForFontAndColors()", e)
        }
    }

    fun updateBackgroundColor() {
        val session = currentTermSession
        if (session != null && session.emulator != null) {
            window.decorView.setBackgroundColor(session.emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
        }
    }

    /**
     * For processes to access shared internal storage (/sdcard) we need this permission.
     */
    fun ensureStoragePermissionGranted(): Boolean {
        return if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUESTCODE_PERMISSION_STORAGE)
            false
        }
    }

    public override fun onCreate(bundle: Bundle) {
        mSettings = TermuxPreferences(this)
        mIsUsingBlackUI = mSettings!!.isUsingBlackUI
        if (mIsUsingBlackUI) {
            setTheme(R.style.Theme_Termux_Black)
        } else {
            setTheme(R.style.Theme_Termux)
        }
        super.onCreate(bundle)
        setContentView(R.layout.drawer_layout)
        if (mIsUsingBlackUI) {
            findViewById<View>(R.id.left_drawer).setBackgroundColor(
                resources.getColor(android.R.color.background_dark)
            )
        }
        mTerminalView = findViewById(R.id.terminal_view)
        mTerminalView.setOnKeyListener(TermuxViewClient(this))
        mTerminalView.setTextSize(mSettings.getFontSize())
        mTerminalView.keepScreenOn = mSettings!!.isScreenAlwaysOn
        mTerminalView.requestFocus()
        val viewPager: ViewPager = findViewById(R.id.viewpager)
        if (mSettings!!.mShowExtraKeys) viewPager.visibility = View.VISIBLE
        val layoutParams = viewPager.layoutParams
        layoutParams.height = layoutParams.height * if (mSettings!!.mExtraKeys == null) 0 else mSettings!!.mExtraKeys.matrix.size
        viewPager.layoutParams = layoutParams
        viewPager.adapter = object : PagerAdapter() {
            override fun getCount(): Int {
                return 2
            }

            override fun isViewFromObject(view: View, `object`: Any): Boolean {
                return view === `object`
            }

            override fun instantiateItem(collection: ViewGroup, position: Int): Any {
                val inflater = LayoutInflater.from(this@TermuxActivity)
                val layout: View
                if (position == 0) {
                    mExtraKeysView = inflater.inflate(R.layout.extra_keys_main, collection, false) as ExtraKeysView
                    layout = mExtraKeysView!!
                    mExtraKeysView!!.reload(mSettings!!.mExtraKeys)
                } else {
                    layout = inflater.inflate(R.layout.extra_keys_right, collection, false)
                    val editText = layout.findViewById<EditText>(R.id.text_input)
                    editText.setOnEditorActionListener { v: TextView?, actionId: Int, event: KeyEvent? ->
                        val session = currentTermSession
                        if (session != null) {
                            if (session.isRunning) {
                                var textToSend = editText.text.toString()
                                if (textToSend.length == 0) textToSend = "\r"
                                session.write(textToSend)
                            } else {
                                removeFinishedSession(session)
                            }
                            editText.setText("")
                        }
                        true
                    }
                }
                collection.addView(layout)
                return layout
            }

            override fun destroyItem(collection: ViewGroup, position: Int, view: Any) {
                collection.removeView(view as View)
            }
        }
        viewPager.addOnPageChangeListener(object : SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                if (position == 0) {
                    mTerminalView.requestFocus()
                } else {
                    val editText = viewPager.findViewById<EditText>(R.id.text_input)
                    editText?.requestFocus()
                }
            }
        })
        val newSessionButton = findViewById<View>(R.id.new_session_button)
        newSessionButton.setOnClickListener { v: View? -> addNewSession(false, null) }
        newSessionButton.setOnLongClickListener { v: View? ->
            DialogUtils.textInput(this@TermuxActivity, R.string.session_new_named_title, null, R.string.session_new_named_positive_button,
                TextSetListener { text: String? -> addNewSession(false, text) }, R.string.new_session_failsafe, TextSetListener { text: String? -> addNewSession(true, text) }
                , -1, null, null)
            true
        }
        findViewById<View>(R.id.toggle_keyboard_button).setOnClickListener { v: View? ->
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
            drawer.closeDrawers()
        }
        findViewById<View>(R.id.toggle_keyboard_button).setOnLongClickListener { v: View? ->
            toggleShowExtraKeys()
            true
        }
        registerForContextMenu(mTerminalView)
        val serviceIntent = Intent(this, TermuxService::class.java)
        // Start the service and make it run regardless of who is bound to it:
        startService(serviceIntent)
        if (!bindService(serviceIntent, this, 0)) throw RuntimeException("bindService() failed")
        checkForFontAndColors()
        mBellSoundId = mBellSoundPool.load(this, R.raw.bell, 1)
    }

    fun toggleShowExtraKeys() {
        val viewPager: ViewPager = findViewById(R.id.viewpager)
        val showNow = mSettings!!.toggleShowExtraKeys(this@TermuxActivity)
        viewPager.visibility = if (showNow) View.VISIBLE else View.GONE
        if (showNow && viewPager.currentItem == 1) {
            // Focus the text input view if just revealed.
            findViewById<View>(R.id.text_input).requestFocus()
        }
    }

    /**
     * Part of the [ServiceConnection] interface. The service is bound with
     * [.bindService] in [.onCreate] which will cause a call to this
     * callback method.
     */
    override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
        mTermService = (service as TermuxService.LocalBinder).service
        mTermService!!.mSessionChangeCallback = object : SessionChangedCallback {
            override fun onTextChanged(changedSession: TerminalSession) {
                if (!mIsVisible) return
                if (currentTermSession == changedSession) mTerminalView.onScreenUpdated()
            }

            override fun onTitleChanged(updatedSession: TerminalSession) {
                if (!mIsVisible) return
                if (updatedSession != currentTermSession) {
                    // Only show toast for other sessions than the current one, since the user
                    // probably consciously caused the title change to change in the current session
                    // and don't want an annoying toast for that.
                    showToast(toToastTitle(updatedSession), false)
                }
                mListViewAdapter!!.notifyDataSetChanged()
            }

            override fun onSessionFinished(finishedSession: TerminalSession) {
                if (mTermService!!.mWantsToStop) {
                    // The service wants to stop as soon as possible.
                    finish()
                    return
                }
                if (mIsVisible && finishedSession != currentTermSession) {
                    // Show toast for non-current sessions that exit.
                    val indexOfSession = mTermService.getSessions().indexOf(finishedSession)
                    // Verify that session was not removed before we got told about it finishing:
                    if (indexOfSession >= 0) showToast(toToastTitle(finishedSession) + " - exited", true)
                }
                if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                    // On Android TV devices we need to use older behaviour because we may
                    // not be able to have multiple launcher icons.
                    if (mTermService.getSessions().size > 1) {
                        removeFinishedSession(finishedSession)
                    }
                } else {
                    // Once we have a separate launcher icon for the failsafe session, it
                    // should be safe to auto-close session on exit code '0' or '130'.
                    if (finishedSession.exitStatus == 0 || finishedSession.exitStatus == 130) {
                        removeFinishedSession(finishedSession)
                    }
                }
                mListViewAdapter!!.notifyDataSetChanged()
            }

            override fun onClipboardText(session: TerminalSession, text: String) {
                if (!mIsVisible) return
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.primaryClip = ClipData(null, arrayOf("text/plain"), ClipData.Item(text))
            }

            override fun onBell(session: TerminalSession) {
                if (!mIsVisible) return
                when (mSettings!!.mBellBehaviour) {
                    TermuxPreferences.Companion.BELL_BEEP -> mBellSoundPool.play(mBellSoundId, 1f, 1f, 1, 0, 1f)
                    TermuxPreferences.Companion.BELL_VIBRATE -> BellUtil.Companion.getInstance(this@TermuxActivity)!!.doBell()
                    TermuxPreferences.Companion.BELL_IGNORE -> {
                    }
                }
            }

            override fun onColorsChanged(changedSession: TerminalSession) {
                if (currentTermSession == changedSession) updateBackgroundColor()
            }
        }
        val listView = findViewById<ListView>(R.id.left_drawer_list)
        mListViewAdapter = object : ArrayAdapter<TerminalSession?>(applicationContext, R.layout.line_in_drawer, mTermService.getSessions()) {
            val boldSpan = StyleSpan(Typeface.BOLD)
            val italicSpan = StyleSpan(Typeface.ITALIC)
            override fun getView(position: Int, convertView: View, parent: ViewGroup): View {
                var row = convertView
                if (row == null) {
                    val inflater = layoutInflater
                    row = inflater.inflate(R.layout.line_in_drawer, parent, false)
                }
                val sessionAtRow = getItem(position)
                val sessionRunning = sessionAtRow!!.isRunning
                val firstLineView = row.findViewById<TextView>(R.id.row_line)
                if (mIsUsingBlackUI) {
                    firstLineView.background = resources.getDrawable(R.drawable.selected_session_background_black)
                }
                val name = sessionAtRow.mSessionName
                val sessionTitle = sessionAtRow.title
                val numberPart = "[" + (position + 1) + "] "
                val sessionNamePart = if (TextUtils.isEmpty(name)) "" else name
                val sessionTitlePart = if (TextUtils.isEmpty(sessionTitle)) "" else (if (sessionNamePart.isEmpty()) "" else "\n") + sessionTitle
                val text = numberPart + sessionNamePart + sessionTitlePart
                val styledText = SpannableString(text)
                styledText.setSpan(boldSpan, 0, numberPart.length + sessionNamePart.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                styledText.setSpan(italicSpan, numberPart.length + sessionNamePart.length, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                firstLineView.text = styledText
                if (sessionRunning) {
                    firstLineView.paintFlags = firstLineView.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                } else {
                    firstLineView.paintFlags = firstLineView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                }
                val defaultColor = if (mIsUsingBlackUI) Color.WHITE else Color.BLACK
                val color = if (sessionRunning || sessionAtRow.exitStatus == 0) defaultColor else Color.RED
                firstLineView.setTextColor(color)
                return row
            }
        }
        listView.adapter = mListViewAdapter
        listView.onItemClickListener = OnItemClickListener { parent: AdapterView<*>?, view: View?, position: Int, id: Long ->
            val clickedSession = mListViewAdapter.getItem(position)
            switchToSession(clickedSession)
            drawer.closeDrawers()
        }
        listView.onItemLongClickListener = OnItemLongClickListener { parent: AdapterView<*>?, view: View?, position: Int, id: Long ->
            val selectedSession = mListViewAdapter.getItem(position)
            renameSession(selectedSession)
            true
        }
        if (mTermService.getSessions().isEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupIfNeeded(this@TermuxActivity) {
                    if (mTermService == null) return@setupIfNeeded   // Activity might have been destroyed.
                    try {
                        val bundle = intent.extras
                        var launchFailsafe = false
                        if (bundle != null) {
                            launchFailsafe = bundle.getBoolean(TERMUX_FAILSAFE_SESSION_ACTION, false)
                        }
                        addNewSession(launchFailsafe, null)
                    } catch (e: WindowManager.BadTokenException) {
                        // Activity finished - ignore.
                    }
                }
            } else {
                // The service connected while not in foreground - just bail out.
                finish()
            }
        } else {
            val i = intent
            if (i != null && Intent.ACTION_RUN == i.action) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                val failSafe = i.getBooleanExtra(TERMUX_FAILSAFE_SESSION_ACTION, false)
                addNewSession(failSafe, null)
            } else {
                switchToSession(storedCurrentSessionOrLast)
            }
        }
    }

    fun switchToSession(forward: Boolean) {
        val currentSession = currentTermSession
        var index = mTermService.getSessions().indexOf(currentSession)
        if (forward) {
            if (++index >= mTermService.getSessions().size) index = 0
        } else {
            if (--index < 0) index = mTermService.getSessions().size - 1
        }
        switchToSession(mTermService.getSessions()[index])
    }

    @SuppressLint("InflateParams")
    fun renameSession(sessionToRename: TerminalSession?) {
        DialogUtils.textInput(this, R.string.session_rename_title, sessionToRename!!.mSessionName, R.string.session_rename_positive_button, TextSetListener { text: String? ->
            sessionToRename.mSessionName = text
            mListViewAdapter!!.notifyDataSetChanged()
        }, -1, null, -1, null, null)
    }

    override fun onServiceDisconnected(name: ComponentName) {
        // Respect being stopped from the TermuxService notification action.
        finish()
    }

    val currentTermSession: TerminalSession?
        get() = mTerminalView.currentSession

    public override fun onStart() {
        super.onStart()
        mIsVisible = true
        if (mTermService != null) {
            // The service has connected, but data may have changed since we were last in the foreground.
            switchToSession(storedCurrentSessionOrLast)
            mListViewAdapter!!.notifyDataSetChanged()
        }
        registerReceiver(mBroadcastReceiever, IntentFilter(RELOAD_STYLE_ACTION))

        // The current terminal session may have changed while being away, force
        // a refresh of the displayed terminal:
        mTerminalView.onScreenUpdated()
    }

    override fun onStop() {
        super.onStop()
        mIsVisible = false
        val currentSession = currentTermSession
        if (currentSession != null) TermuxPreferences.Companion.storeCurrentSession(this, currentSession)
        unregisterReceiver(mBroadcastReceiever)
        drawer.closeDrawers()
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(Gravity.LEFT)) {
            drawer.closeDrawers()
        } else {
            finish()
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        if (mTermService != null) {
            // Do not leave service with references to activity.
            mTermService!!.mSessionChangeCallback = null
            mTermService = null
        }
        unbindService(this)
    }

    val drawer: DrawerLayout
        get() = findViewById<View>(R.id.drawer_layout) as DrawerLayout

    fun addNewSession(failSafe: Boolean, sessionName: String?) {
        if (mTermService.getSessions().size >= MAX_SESSIONS) {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.max_terminals_reached_title).setMessage(R.string.max_terminals_reached_message)
                .setPositiveButton(android.R.string.ok, null).show()
        } else {
            val currentSession = currentTermSession
            val workingDirectory = currentSession?.cwd
            val newSession = mTermService!!.createTermSession(null, null, workingDirectory, failSafe)
            if (sessionName != null) {
                newSession.mSessionName = sessionName
            }
            switchToSession(newSession)
            drawer.closeDrawers()
        }
    }

    /**
     * Try switching to session and note about it, but do nothing if already displaying the session.
     */
    fun switchToSession(session: TerminalSession?) {
        if (mTerminalView.attachSession(session)) {
            noteSessionInfo()
            updateBackgroundColor()
        }
    }

    fun toToastTitle(session: TerminalSession?): String {
        val indexOfSession = mTermService.getSessions().indexOf(session)
        val toastTitle = StringBuilder("[" + (indexOfSession + 1) + "]")
        if (!TextUtils.isEmpty(session!!.mSessionName)) {
            toastTitle.append(" ").append(session.mSessionName)
        }
        val title = session.title
        if (!TextUtils.isEmpty(title)) {
            // Space to "[${NR}] or newline after session name:
            toastTitle.append(if (session.mSessionName == null) " " else "\n")
            toastTitle.append(title)
        }
        return toastTitle.toString()
    }

    fun noteSessionInfo() {
        if (!mIsVisible) return
        val session = currentTermSession
        val indexOfSession = mTermService.getSessions().indexOf(session)
        showToast(toToastTitle(session), false)
        mListViewAdapter!!.notifyDataSetChanged()
        val lv = findViewById<ListView>(R.id.left_drawer_list)
        lv.setItemChecked(indexOfSession, true)
        lv.smoothScrollToPosition(indexOfSession)
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenuInfo) {
        val currentSession = currentTermSession ?: return
        menu.add(Menu.NONE, CONTEXTMENU_SELECT_URL_ID, Menu.NONE, R.string.select_url)
        menu.add(Menu.NONE, CONTEXTMENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.select_all_and_share)
        menu.add(Menu.NONE, CONTEXTMENU_RESET_TERMINAL_ID, Menu.NONE, R.string.reset_terminal)
        menu.add(Menu.NONE, CONTEXTMENU_KILL_PROCESS_ID, Menu.NONE, resources.getString(R.string.kill_process, currentTermSession!!.pid)).isEnabled = currentSession.isRunning
        menu.add(Menu.NONE, CONTEXTMENU_STYLING_ID, Menu.NONE, R.string.style_terminal)
        menu.add(Menu.NONE, CONTEXTMENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.toggle_keep_screen_on).setCheckable(true).isChecked = mSettings!!.isScreenAlwaysOn
        menu.add(Menu.NONE, CONTEXTMENU_HELP_ID, Menu.NONE, R.string.help)
    }

    /**
     * Hook system menu to show context menu instead.
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        mTerminalView.showContextMenu()
        return false
    }

    fun showUrlSelection() {
        val text = currentTermSession!!.emulator.screen.transcriptTextWithFullLinesJoined
        val urlSet = extractUrls(text)
        if (urlSet.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(this).setMessage(R.string.select_url_no_found).show()
            return
        }
        val urls = urlSet.toTypedArray()
        Collections.reverse(Arrays.asList(*urls)) // Latest first.

        // Click to copy url to clipboard:
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this@TermuxActivity).setItems(urls) { di: DialogInterface?, which: Int ->
            val url = urls[which] as String
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip = ClipData(null, arrayOf("text/plain"), ClipData.Item(url))
            Toast.makeText(this@TermuxActivity, R.string.select_url_copied_to_clipboard, Toast.LENGTH_LONG).show()
        }.setTitle(R.string.select_url_dialog_title).create()

        // Long press to open URL:
        dialog.setOnShowListener { di: DialogInterface? ->
            val lv = dialog.listView // this is a ListView with your "buds" in it
            lv.onItemLongClickListener = OnItemLongClickListener { parent: AdapterView<*>?, view: View?, position: Int, id: Long ->
                dialog.dismiss()
                val url = urls[position] as String
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                try {
                    startActivity(i, null)
                } catch (e: ActivityNotFoundException) {
                    // If no applications match, Android displays a system message.
                    startActivity(Intent.createChooser(i, null))
                }
                true
            }
        }
        dialog.show()
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val session = currentTermSession
        return when (item.itemId) {
            CONTEXTMENU_SELECT_URL_ID -> {
                showUrlSelection()
                true
            }
            CONTEXTMENU_SHARE_TRANSCRIPT_ID -> {
                if (session != null) {
                    val intent = Intent(Intent.ACTION_SEND)
                    intent.type = "text/plain"
                    var transcriptText = session.emulator.screen.transcriptTextWithoutJoinedLines.trim { it <= ' ' }
                    // See https://github.com/termux/termux-app/issues/1166.
                    val MAX_LENGTH = 100000
                    if (transcriptText.length > MAX_LENGTH) {
                        var cutOffIndex = transcriptText.length - MAX_LENGTH
                        val nextNewlineIndex = transcriptText.indexOf('\n', cutOffIndex)
                        if (nextNewlineIndex != -1 && nextNewlineIndex != transcriptText.length - 1) {
                            cutOffIndex = nextNewlineIndex + 1
                        }
                        transcriptText = transcriptText.substring(cutOffIndex).trim { it <= ' ' }
                    }
                    intent.putExtra(Intent.EXTRA_TEXT, transcriptText)
                    intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_transcript_title))
                    startActivity(Intent.createChooser(intent, getString(R.string.share_transcript_chooser_title)))
                }
                true
            }
            CONTEXTMENU_PASTE_ID -> {
                doPaste()
                true
            }
            CONTEXTMENU_KILL_PROCESS_ID -> {
                val b = androidx.appcompat.app.AlertDialog.Builder(this)
                b.setIcon(android.R.drawable.ic_dialog_alert)
                b.setMessage(R.string.confirm_kill_process)
                b.setPositiveButton(android.R.string.yes) { dialog: DialogInterface, id: Int ->
                    dialog.dismiss()
                    currentTermSession!!.finishIfRunning()
                }
                b.setNegativeButton(android.R.string.no, null)
                b.show()
                true
            }
            CONTEXTMENU_RESET_TERMINAL_ID -> {
                if (session != null) {
                    session.reset()
                    showToast(resources.getString(R.string.reset_toast_notification), true)
                }
                true
            }
            CONTEXTMENU_STYLING_ID -> {
                val stylingIntent = Intent()
                stylingIntent.setClassName("com.termux.styling", "com.termux.styling.TermuxStyleActivity")
                try {
                    startActivity(stylingIntent)
                } catch (e: ActivityNotFoundException) {
                    // The startActivity() call is not documented to throw IllegalArgumentException.
                    // However, crash reporting shows that it sometimes does, so catch it here.
                    androidx.appcompat.app.AlertDialog.Builder(this).setMessage(R.string.styling_not_installed)
                        .setPositiveButton(R.string.styling_install) { dialog: DialogInterface?, which: Int -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://play.google.com/store/apps/details?id=com.termux.styling"))) }.setNegativeButton(android.R.string.cancel, null).show()
                } catch (e: IllegalArgumentException) {
                    androidx.appcompat.app.AlertDialog.Builder(this).setMessage(R.string.styling_not_installed)
                        .setPositiveButton(R.string.styling_install) { dialog: DialogInterface?, which: Int -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://play.google.com/store/apps/details?id=com.termux.styling"))) }.setNegativeButton(android.R.string.cancel, null).show()
                }
                true
            }
            CONTEXTMENU_HELP_ID -> {
                startActivity(Intent(this, TermuxHelpActivity::class.java))
                true
            }
            CONTEXTMENU_TOGGLE_KEEP_SCREEN_ON -> {
                if (mTerminalView.keepScreenOn) {
                    mTerminalView.keepScreenOn = false
                    mSettings!!.setScreenAlwaysOn(this, false)
                } else {
                    mTerminalView.keepScreenOn = true
                    mSettings!!.setScreenAlwaysOn(this, true)
                }
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUESTCODE_PERMISSION_STORAGE && grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            TermuxInstaller.setupStorageSymlinks(this)
        }
    }

    fun changeFontSize(increase: Boolean) {
        mSettings!!.changeFontSize(this, increase)
        mTerminalView.setTextSize(mSettings.getFontSize())
    }

    fun doPaste() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip ?: return
        val paste = clipData.getItemAt(0).coerceToText(this)
        if (!TextUtils.isEmpty(paste)) currentTermSession!!.emulator.paste(paste.toString())
    }

    /**
     * The current session as stored or the last one if that does not exist.
     */
    val storedCurrentSessionOrLast: TerminalSession?
        get() {
            val stored: TerminalSession = TermuxPreferences.Companion.getCurrentSession(this)
            if (stored != null) return stored
            val sessions = mTermService.getSessions()
            return if (sessions!!.isEmpty()) null else sessions!![sessions!!.size - 1]
        }

    /**
     * Show a toast and dismiss the last one if still visible.
     */
    fun showToast(text: String?, longDuration: Boolean) {
        if (mLastToast != null) mLastToast!!.cancel()
        mLastToast = Toast.makeText(this@TermuxActivity, text, if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
        mLastToast.setGravity(Gravity.TOP, 0, 0)
        mLastToast.show()
    }

    fun removeFinishedSession(finishedSession: TerminalSession?) {
        // Return pressed with finished session - remove it.
        val service = mTermService
        var index = service!!.removeTermSession(finishedSession)
        mListViewAdapter!!.notifyDataSetChanged()
        if (mTermService.getSessions().isEmpty()) {
            // There are no sessions to show, so finish the activity.
            finish()
        } else {
            if (index >= service.sessions.size) {
                index = service.sessions.size - 1
            }
            switchToSession(service.sessions[index])
        }
    }

    companion object {
        const val TERMUX_FAILSAFE_SESSION_ACTION = "com.termux.app.failsafe_session"
        private const val CONTEXTMENU_SELECT_URL_ID = 0
        private const val CONTEXTMENU_SHARE_TRANSCRIPT_ID = 1
        private const val CONTEXTMENU_PASTE_ID = 3
        private const val CONTEXTMENU_KILL_PROCESS_ID = 4
        private const val CONTEXTMENU_RESET_TERMINAL_ID = 5
        private const val CONTEXTMENU_STYLING_ID = 6
        private const val CONTEXTMENU_HELP_ID = 8
        private const val CONTEXTMENU_TOGGLE_KEEP_SCREEN_ON = 9
        private const val MAX_SESSIONS = 8
        private const val REQUESTCODE_PERMISSION_STORAGE = 1234
        private const val RELOAD_STYLE_ACTION = "com.termux.app.reload_style"

        @JvmStatic
        fun extractUrls(text: String): LinkedHashSet<CharSequence> {
            val regex_sb = StringBuilder()
            regex_sb.append("(") // Begin first matching group.
            regex_sb.append("(?:") // Begin scheme group.
            regex_sb.append("dav|") // The DAV proto.
            regex_sb.append("dict|") // The DICT proto.
            regex_sb.append("dns|") // The DNS proto.
            regex_sb.append("file|") // File path.
            regex_sb.append("finger|") // The Finger proto.
            regex_sb.append("ftp(?:s?)|") // The FTP proto.
            regex_sb.append("git|") // The Git proto.
            regex_sb.append("gopher|") // The Gopher proto.
            regex_sb.append("http(?:s?)|") // The HTTP proto.
            regex_sb.append("imap(?:s?)|") // The IMAP proto.
            regex_sb.append("irc(?:[6s]?)|") // The IRC proto.
            regex_sb.append("ip[fn]s|") // The IPFS proto.
            regex_sb.append("ldap(?:s?)|") // The LDAP proto.
            regex_sb.append("pop3(?:s?)|") // The POP3 proto.
            regex_sb.append("redis(?:s?)|") // The Redis proto.
            regex_sb.append("rsync|") // The Rsync proto.
            regex_sb.append("rtsp(?:[su]?)|") // The RTSP proto.
            regex_sb.append("sftp|") // The SFTP proto.
            regex_sb.append("smb(?:s?)|") // The SAMBA proto.
            regex_sb.append("smtp(?:s?)|") // The SMTP proto.
            regex_sb.append("svn(?:(?:\\+ssh)?)|") // The Subversion proto.
            regex_sb.append("tcp|") // The TCP proto.
            regex_sb.append("telnet|") // The Telnet proto.
            regex_sb.append("tftp|") // The TFTP proto.
            regex_sb.append("udp|") // The UDP proto.
            regex_sb.append("vnc|") // The VNC proto.
            regex_sb.append("ws(?:s?)") // The Websocket proto.
            regex_sb.append(")://") // End scheme group.
            regex_sb.append(")") // End first matching group.


            // Begin second matching group.
            regex_sb.append("(")

            // User name and/or password in format 'user:pass@'.
            regex_sb.append("(?:\\S+(?::\\S*)?@)?")

            // Begin host group.
            regex_sb.append("(?:")

            // IP address (from http://www.regular-expressions.info/examples.html).
            regex_sb.append("(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)|")

            // Host name or domain.
            regex_sb.append("(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)(?:(?:\\.(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)*(?:\\.(?:[a-z\\u00a1-\\uffff]{2,})))?|")

            // Just path. Used in case of 'file://' scheme.
            regex_sb.append("/(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)")

            // End host group.
            regex_sb.append(")")

            // Port number.
            regex_sb.append("(?::\\d{1,5})?")

            // Resource path with optional query string.
            regex_sb.append("(?:/[a-zA-Z0-9:@%\\-._~!$&()*+,;=?/]*)?")

            // Fragment.
            regex_sb.append("(?:#[a-zA-Z0-9:@%\\-._~!$&()*+,;=?/]*)?")

            // End second matching group.
            regex_sb.append(")")
            val urlPattern = Pattern.compile(
                regex_sb.toString(),
                Pattern.CASE_INSENSITIVE or Pattern.MULTILINE or Pattern.DOTALL)
            val urlSet = LinkedHashSet<CharSequence>()
            val matcher = urlPattern.matcher(text)
            while (matcher.find()) {
                val matchStart = matcher.start(1)
                val matchEnd = matcher.end()
                val url = text.substring(matchStart, matchEnd)
                urlSet.add(url)
            }
            return urlSet
        }
    }
}
