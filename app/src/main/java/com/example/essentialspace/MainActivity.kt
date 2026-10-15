package com.example.essentialspace

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREF_CAPTURE_SCREENSHOT = "capture_screenshot_enabled"
        const val PREF_SHORTCUT_MODE = "shortcut_mode"

        const val MODE_HOLD_VOL_DOWN = "hold_vol_down"
        const val MODE_DOUBLE_TAP_HOLD = "double_tap_hold"
        const val MODE_SEQ_UP_DOWN = "seq_up_down"
        const val MODE_SCREENSHOT_COMBO = "screenshot_combo"
        const val MODE_ASSIST_POWER = "assist_power"

        const val ACTION_START_ASSIST_CAPTURE = "com.example.essentialspace.START_ASSIST_CAPTURE"

        const val PREF_CARD_LAYOUT = "card_layout_style"
        const val LAYOUT_CLASSIC = "classic"   // Void Classic (Full-width stack)
        const val LAYOUT_BENTO = "bento"       // Bento Grid (2-column masonry)
        const val LAYOUT_CAPSULE = "capsule"   // Capsule Split (Horizontal thumbnail + text)
        const val LAYOUT_LEDGER = "ledger"     // Focus Ledger (Minimalist compact list)
    }

    private lateinit var prefs: android.content.SharedPreferences
    private var searchQuery = ""
    private var currentFilter = "All"

    // High-performance LruCache for decoded note image thumbnails & audio player management
    private val thumbnailCache = object : androidx.collection.LruCache<String, android.graphics.Bitmap>(30) {}
    private var activeMediaPlayer: MediaPlayer? = null

    // Your lightning-fast in-memory database
    private var masterNotesArray = JSONArray()

    private var pendingImagePickedCallback: ((String) -> Unit)? = null

    private val pickImageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            try {
                val destFile = java.io.File(filesDir, "img_${System.currentTimeMillis()}.jpg")
                contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (destFile.exists() && destFile.length() > 0L) {
                    pendingImagePickedCallback?.invoke(destFile.absolutePath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Could not attach image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playAudioNote(audioPath: String) {
        try {
            activeMediaPlayer?.stop()
            activeMediaPlayer?.release()
            activeMediaPlayer = null

            activeMediaPlayer = MediaPlayer().apply {
                setDataSource(audioPath)
                prepare()
                start()
                setOnCompletionListener {
                    release()
                    if (activeMediaPlayer == this) activeMediaPlayer = null
                }
            }
            Toast.makeText(this, "Playing note...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Could not play audio", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStop() {
        super.onStop()
        activeMediaPlayer?.stop()
        activeMediaPlayer?.release()
        activeMediaPlayer = null
    }

    private val noteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.essentialspace.NOTE_SAVED") {
                loadDataToMemory()
                refreshUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Must be called AFTER super.onCreate() so installSplashScreen() doesn't overwrite the theme!
        prefs = ThemeManager.getPrefs(this)
        ThemeManager.initActivityTheme(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        ThemeManager.applyThemeColorsToMain(this)

        val topBar = findViewById<RelativeLayout>(R.id.topBar)
        val bottomDockContainer = findViewById<LinearLayout>(R.id.bottomDockContainer)

        ViewCompat.setOnApplyWindowInsetsListener(window.decorView.rootView) { _, windowInsets ->
            val sysBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = Math.max(sysBars.bottom, ime.bottom)
            val density = resources.displayMetrics.density

            val topParams = topBar.layoutParams as ViewGroup.MarginLayoutParams
            topParams.topMargin = sysBars.top + (8 * density).toInt()
            topBar.layoutParams = topParams

            val pad16 = (16 * density).toInt()
            topBar.setPadding(pad16, 0, pad16, 0)

            bottomDockContainer.setPadding(0, 0, 0, bottomInset)

            val topBarTotalHeight = sysBars.top + (52 * density).toInt() + (8 * density).toInt() + (12 * density).toInt()
            val mainScrollView = findViewById<ScrollView>(R.id.mainScrollView)
            mainScrollView?.setPadding(0, topBarTotalHeight, 0, bottomInset + (90 * density).toInt())

            WindowInsetsCompat.CONSUMED
        }

        loadDataToMemory()

        NotificationHelper.createChannels(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    102
                )
            }
        }

        setupAppleTouchFeedback()
        setupMenu()
        setupExpandableSearch()
        setupSwipeRefresh()
        setupImmersiveScroll()

        findViewById<ImageButton>(R.id.btnCreateNote)?.setOnClickListener {
            showCreateNoteDialog()
        }

        loadDynamicTags()
        refreshUI()

        ContextCompat.registerReceiver(
            this,
            noteReceiver,
            IntentFilter("com.example.essentialspace.NOTE_SAVED"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val isFirstLaunch = prefs.getBoolean("is_first_launch", true)
        if (isFirstLaunch) {
            prefs.edit().putBoolean("is_first_launch", false).apply()
            window.decorView.postDelayed({
                showWalkthroughDialog()
            }, 400)
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            if (it.action == Intent.ACTION_ASSIST || it.action == Intent.ACTION_VOICE_COMMAND) {
                it.action = null
                val assistBroadcast = Intent(ACTION_START_ASSIST_CAPTURE).apply {
                    setPackage(packageName)
                }
                sendBroadcast(assistBroadcast)
                moveTaskToBack(true)
                return
            }

            val openIndex = it.getIntExtra("open_note_index", -1)
            if (openIndex != -1) {
                it.removeExtra("open_note_index")
                window.decorView.postDelayed({
                    loadDataToMemory()
                    if (openIndex in 0 until masterNotesArray.length()) {
                        openFullNoteEditor(editIndex = openIndex)
                    }
                }, 350)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadDataToMemory()
        ThemeManager.applyThemeColorsToMain(this)
        refreshUI(animateEntrance = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(noteReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadDataToMemory() {
        masterNotesArray = try {
            JSONArray(prefs.getString("notes_array", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    // Helper: Safely reads all image paths for a note (supports both legacy "image_path" and multi "image_paths")
    private fun extractImagePaths(noteObj: JSONObject?): MutableList<String> {
        val list = mutableListOf<String>()
        if (noteObj == null) return list
        val arr = noteObj.optJSONArray("image_paths")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val p = arr.optString(i, "")
                if (p.isNotEmpty() && java.io.File(p).exists() && !list.contains(p)) {
                    list.add(p)
                }
            }
        }
        val legacy = noteObj.optString("image_path", "")
        if (legacy.isNotEmpty() && java.io.File(legacy).exists() && !list.contains(legacy)) {
            list.add(0, legacy)
        }
        return list
    }

    // Helper: Safely reads all audio paths for a note (supports both legacy "audio_path" and multi "audio_paths")
    private fun extractAudioPaths(noteObj: JSONObject?): MutableList<String> {
        val list = mutableListOf<String>()
        if (noteObj == null) return list
        val arr = noteObj.optJSONArray("audio_paths")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val p = arr.optString(i, "")
                if (p.isNotEmpty() && java.io.File(p).exists() && !list.contains(p)) {
                    list.add(p)
                }
            }
        }
        val legacy = noteObj.optString("audio_path", "")
        if (legacy.isNotEmpty() && java.io.File(legacy).exists() && !list.contains(legacy)) {
            list.add(0, legacy)
        }
        return list
    }

    private fun showZoomableImage(imgPath: String) {
        val currentTheme = ThemeManager.getCurrentThemeKey(this)
        val themeRes = ThemeManager.getThemeDialogRes(this)
        val dialog = ComponentDialog(this, themeRes)
        dialog.setContentView(R.layout.dialog_zoom)

        dialog.window?.let { window ->
            window.setWindowAnimations(R.style.IosZoomAnimation)
            if (currentTheme == ThemeManager.THEME_GLASS) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#CC000000")))
            } else {
                window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            }

            WindowCompat.setDecorFitsSystemWindows(window, false)
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val zoomImageView = dialog.findViewById<com.github.chrisbanes.photoview.PhotoView>(R.id.zoomImageView)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseZoom)

        zoomImageView?.scaleX = 0.85f
        zoomImageView?.scaleY = 0.85f
        zoomImageView?.alpha = 0f
        zoomImageView?.animate()
            ?.scaleX(1.0f)
            ?.scaleY(1.0f)
            ?.alpha(1.0f)
            ?.setDuration(320)
            ?.setInterpolator(appleOvershoot)
            ?.start()

        btnClose?.addApplePressEffect()

        if (imgPath.isNotEmpty() && java.io.File(imgPath).exists()) {
            val bitmap = BitmapFactory.decodeFile(imgPath)
            zoomImageView?.setImageBitmap(bitmap)
        }

        btnClose?.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private val appleCurve = android.view.animation.DecelerateInterpolator()
    private val appleOvershoot = android.view.animation.OvershootInterpolator(1.8f)

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun View.addApplePressEffect() {
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    try {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    // Keep center pivot for rotated icons (like 180° back/close buttons) so they never jump!
                    if (v.rotation != 0f || v.rotationX != 0f || v.rotationY != 0f) {
                        v.pivotX = v.width / 2f
                        v.pivotY = v.height / 2f
                    } else {
                        v.pivotX = event.x.coerceIn(0f, v.width.toFloat())
                        v.pivotY = event.y.coerceIn(0f, v.height.toFloat())
                    }
                    v.animate()
                        .scaleX(0.93f)
                        .scaleY(0.93f)
                        .setDuration(120)
                        .setInterpolator(appleCurve)
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(380)
                        .setInterpolator(appleOvershoot)
                        .start()
                }
            }
            false
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = android.graphics.Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)

                    val expandedLayout = findViewById<View>(R.id.expandedSearchLayout)
                    if (expandedLayout != null && expandedLayout.visibility == View.VISIBLE) {
                        closeSearch()
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun closeSearch() {
        val expandedLayout = findViewById<LinearLayout>(R.id.expandedSearchLayout) ?: return
        if (expandedLayout.visibility != View.VISIBLE) return

        val dockCard = findViewById<View>(R.id.dockCard) as? ViewGroup
        val categoryScroll = findViewById<View>(R.id.categoryScroll)
        val btnSearch = findViewById<ImageButton>(R.id.btnSearchBubble)
        val searchBar = findViewById<EditText>(R.id.searchBar)

        if (dockCard != null) {
            val transition = AutoTransition().apply {
                duration = 250
                interpolator = appleCurve
            }
            TransitionManager.beginDelayedTransition(dockCard, transition)
        }

        expandedLayout.visibility = View.GONE
        categoryScroll?.visibility = View.VISIBLE
        btnSearch?.visibility = View.VISIBLE

        searchBar?.clearFocus()
        if (searchBar != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(searchBar.windowToken, 0)
        }

        if (searchQuery.isNotEmpty()) {
            searchQuery = ""
            searchBar?.text?.clear()
            refreshUI(animateEntrance = false)
        } else {
            searchBar?.text?.clear()
        }
    }

    private fun setupAppleTouchFeedback() {
        findViewById<View>(R.id.btnMenu)?.addApplePressEffect()
        findViewById<View>(R.id.btnSearchBubble)?.addApplePressEffect()
        findViewById<View>(R.id.btnCreateNote)?.addApplePressEffect()
    }

    private fun setupImmersiveScroll() {
        val scrollView = findViewById<ScrollView>(R.id.mainScrollView)
        val topBar = findViewById<View>(R.id.topBar)
        val topFade = findViewById<View>(R.id.topFadeOverlay)
        val bottomDock = findViewById<View>(R.id.bottomDockContainer)
        val bottomFade = findViewById<View>(R.id.bottomFadeOverlay)

        var isDockVisible = true

        scrollView?.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val dy = scrollY - oldScrollY

            if (dy > 12 && isDockVisible && scrollY > 80) {
                isDockVisible = false

                topBar?.animate()
                    ?.translationY(-topBar.height.toFloat() - 80f)
                    ?.setDuration(260)
                    ?.setInterpolator(appleCurve)
                    ?.start()

                topFade?.animate()
                    ?.alpha(0f)
                    ?.setDuration(260)
                    ?.start()

                bottomDock?.animate()
                    ?.translationY(bottomDock.height.toFloat() + 100f)
                    ?.setDuration(260)
                    ?.setInterpolator(appleCurve)
                    ?.start()

                bottomFade?.animate()
                    ?.alpha(0f)
                    ?.setDuration(260)
                    ?.start()

            } else if ((dy < -12 || scrollY <= 20) && !isDockVisible) {
                isDockVisible = true

                topBar?.animate()
                    ?.translationY(0f)
                    ?.setDuration(280)
                    ?.setInterpolator(appleOvershoot)
                    ?.start()

                topFade?.animate()
                    ?.alpha(1f)
                    ?.setDuration(280)
                    ?.start()

                bottomDock?.animate()
                    ?.translationY(0f)
                    ?.setDuration(280)
                    ?.setInterpolator(appleOvershoot)
                    ?.start()

                bottomFade?.animate()
                    ?.alpha(1f)
                    ?.setDuration(280)
                    ?.start()
            }
        }
    }

    private fun setupExpandableSearch() {
        val bottomDockContainer = findViewById<LinearLayout>(R.id.bottomDockContainer)
        val categoryScroll = findViewById<View>(R.id.categoryScroll)
        val btnSearch = findViewById<ImageButton>(R.id.btnSearchBubble)
        val expandedLayout = findViewById<LinearLayout>(R.id.expandedSearchLayout)
        val searchBar = findViewById<EditText>(R.id.searchBar)

        btnSearch.setOnClickListener {
            val transition = AutoTransition().apply {
                duration = 280
                interpolator = appleCurve
            }
            TransitionManager.beginDelayedTransition(bottomDockContainer, transition)

            categoryScroll.visibility = View.GONE
            btnSearch.visibility = View.GONE
            expandedLayout.visibility = View.VISIBLE
            searchBar.requestFocus()

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchBar, InputMethodManager.SHOW_IMPLICIT)
        }

        searchBar.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                closeSearch()
                true
            } else {
                false
            }
        }

        searchBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val newQuery = s.toString().trim()
                if (searchQuery != newQuery) {
                    searchQuery = newQuery
                    refreshUI(animateEntrance = false)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupMenu() {
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            showFullScreenMenu()
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val expectedComponentName = android.content.ComponentName(context, service)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = android.content.ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun getShortcutDisplayName(mode: String): String {
        return when (mode) {
            MODE_DOUBLE_TAP_HOLD -> "Double-Tap & Hold (Vol Up/Down)"
            MODE_SEQ_UP_DOWN -> "Sequence (Vol Up ➔ Hold Vol Down)"
            MODE_SCREENSHOT_COMBO -> "Screenshot on Start (Hold Vol Down)"
            MODE_ASSIST_POWER -> "Power Hold (Digital Assistant)"
            else -> "Hold Volume Down (Default)"
        }
    }

    private fun showFullScreenMenu() {
        val currentTheme = ThemeManager.getCurrentThemeKey(this)
        val themeRes = ThemeManager.getThemeDialogRes(this)
        val dialog = ComponentDialog(this, themeRes)
        dialog.setContentView(R.layout.dialog_fullscreen_menu)

        dialog.window?.let { window ->
            window.attributes?.windowAnimations = R.style.SideBarAnimationLeft
            if (currentTheme == ThemeManager.THEME_GLASS) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            }

            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(window, false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val sidebarPanel = dialog.findViewById<ViewGroup>(R.id.sidebarPanel)
        val primaryColor = ThemeManager.getPrimaryColor(this)
        val secondaryColor = if (currentTheme == ThemeManager.THEME_LIGHT) Color.parseColor("#5C6270") else Color.parseColor("#98A2B3")

        if (sidebarPanel != null) {
            val panelColor = when (currentTheme) {
                ThemeManager.THEME_LIGHT -> Color.WHITE
                ThemeManager.THEME_GLASS -> Color.parseColor("#E6161920")
                else -> Color.parseColor("#0A0A0A")
            }
            sidebarPanel.setBackgroundColor(panelColor)
        }

        val backdrop = dialog.findViewById<View>(R.id.sidebarBackdrop)
        val btnCloseSideBar = dialog.findViewById<ImageButton>(R.id.btnCloseSideBar)
        val btnSettingsIcon = dialog.findViewById<ImageButton>(R.id.btnSettingsIcon)

        val itemHowToUse = dialog.findViewById<TextView>(R.id.fsMenuItemHowToUse)
        val itemAppearance = dialog.findViewById<TextView>(R.id.fsMenuItemAppearance)

        val font = getAppTypeface()
        itemHowToUse?.typeface = font
        itemAppearance?.typeface = font

        dialog.findViewById<ImageView>(R.id.menuLogo)?.apply {
            setImageResource(getAppIconRes())
            setColorFilter(primaryColor)
        }
        btnCloseSideBar?.setColorFilter(primaryColor)
        btnSettingsIcon?.setColorFilter(primaryColor)
        itemHowToUse?.setTextColor(primaryColor)
        itemAppearance?.setTextColor(primaryColor)

        // Style headers and title
        dialog.findViewById<TextView>(R.id.menuTitleText)?.setTextColor(primaryColor)
        dialog.findViewById<TextView>(R.id.sectionHeaderGeneral)?.setTextColor(secondaryColor)
        dialog.findViewById<TextView>(R.id.sectionHeaderPreferences)?.setTextColor(secondaryColor)

        // 1. Wire up Capture Screenshot Toggle from XML
        val rowScreenshotToggle = dialog.findViewById<View>(R.id.rowScreenshotToggle)
        val tvScreenshotTitle = dialog.findViewById<TextView>(R.id.tvScreenshotToggleTitle)
        val tvScreenshotSub = dialog.findViewById<TextView>(R.id.tvScreenshotToggleSub)
        val switchScreenshot = dialog.findViewById<SwitchCompat>(R.id.switchCaptureScreenshot)

        tvScreenshotTitle?.setTextColor(primaryColor)
        tvScreenshotSub?.setTextColor(secondaryColor)

        if (switchScreenshot != null) {
            ThemeManager.applySwitchAccentColor(this, switchScreenshot)
            switchScreenshot.isChecked = prefs.getBoolean(PREF_CAPTURE_SCREENSHOT, true)
            switchScreenshot.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(PREF_CAPTURE_SCREENSHOT, isChecked).apply()
            }
        }

        rowScreenshotToggle?.addApplePressEffect()
        rowScreenshotToggle?.setOnClickListener {
            if (switchScreenshot != null) {
                switchScreenshot.isChecked = !switchScreenshot.isChecked
            }
        }

        // 2. Wire up Trigger Shortcut Selector Row from XML
        val rowShortcutSelector = dialog.findViewById<View>(R.id.rowShortcutSelector)
        val tvShortcutTitle = dialog.findViewById<TextView>(R.id.tvShortcutTitle)
        val tvCurrentShortcutValue = dialog.findViewById<TextView>(R.id.tvCurrentShortcutValue)

        tvShortcutTitle?.setTextColor(primaryColor)
        val savedShortcut = prefs.getString(PREF_SHORTCUT_MODE, MODE_HOLD_VOL_DOWN) ?: MODE_HOLD_VOL_DOWN
        tvCurrentShortcutValue?.text = getShortcutDisplayName(savedShortcut)
        tvCurrentShortcutValue?.setTextColor(getAccentColor())

        rowShortcutSelector?.addApplePressEffect()
        rowShortcutSelector?.setOnClickListener {
            showShortcutSelectorDialog { newMode ->
                tvCurrentShortcutValue?.text = getShortcutDisplayName(newMode)
            }
        }

        btnCloseSideBar?.addApplePressEffect()
        btnSettingsIcon?.addApplePressEffect()
        itemHowToUse?.addApplePressEffect()
        itemAppearance?.addApplePressEffect()

        backdrop?.setOnClickListener { dialog.dismiss() }
        btnCloseSideBar?.setOnClickListener { dialog.dismiss() }

        itemHowToUse?.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_help, 0, 0, 0)
        itemHowToUse?.compoundDrawables?.forEach { it?.setTint(primaryColor) }
        itemHowToUse?.setOnClickListener { dialog.dismiss(); showHowToUseDialog() }

        itemAppearance?.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_appearance, 0, 0, 0)
        itemAppearance?.compoundDrawables?.forEach { it?.setTint(primaryColor) }
        itemAppearance?.setOnClickListener { dialog.dismiss(); showAppearanceDialog() }

        // Dynamic bottom padding adjustment so settings icon lifts up above 3-button navigation bar without clipping
        if (sidebarPanel != null) {
            btnSettingsIcon?.translationZ = 20f
            ViewCompat.setOnApplyWindowInsetsListener(sidebarPanel) { v, insets ->
                val navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                val density = resources.displayMetrics.density
                val basePaddingBottom = (20 * density).toInt()

                v.setPadding(
                    v.paddingLeft,
                    v.paddingTop,
                    v.paddingRight,
                    basePaddingBottom + navBarInset
                )
                insets
            }
        }

        btnSettingsIcon?.setOnClickListener {
            showSettingsPopup()
        }

        dialog.show()
    }

    private data class ShortcutOption(
        val modeKey: String,
        val title: String,
        val instruction: String
    )

    private fun showShortcutSelectorDialog(onSelected: (String) -> Unit) {
        val options = listOf(
            ShortcutOption(
                MODE_HOLD_VOL_DOWN,
                "Hold Volume Down (Default)",
                "Press & hold Vol Down to record. Release to capture & save."
            ),
            ShortcutOption(
                MODE_DOUBLE_TAP_HOLD,
                "Double-Tap & Hold (Vol Up/Down)",
                "Tap once, then press & hold on the 2nd tap. Normal presses adjust volume."
            ),
            ShortcutOption(
                MODE_SEQ_UP_DOWN,
                "Sequence (Vol Up ➔ Hold Vol Down)",
                "Tap Vol Up once, then immediately press & hold Vol Down to record."
            ),
            ShortcutOption(
                MODE_SCREENSHOT_COMBO,
                "Screenshot on Start (Hold Vol Down)",
                "Captures screen immediately when held, records voice, and saves on release."
            ),
            ShortcutOption(
                MODE_ASSIST_POWER,
                "Power Hold (Digital Assistant)",
                "Set MemoryVoid as Default Assistant. Hold Power to start; hold Power again to stop & save."
            )
        )

        val dialog = ComponentDialog(this, ThemeManager.getDialogThemeRes(this))
        val density = resources.displayMetrics.density
        val pad20 = (20 * density).toInt()
        val pad8 = (8 * density).toInt()
        val pad10 = (10 * density).toInt()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad20, pad20, pad20, pad20)
        }

        val titleView = TextView(this).apply {
            text = "Choose Trigger Shortcut"
            textSize = 18f
            typeface = getAppTypeface()
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        rootLayout.addView(titleView)

        val currentMode = prefs.getString(PREF_SHORTCUT_MODE, MODE_HOLD_VOL_DOWN) ?: MODE_HOLD_VOL_DOWN
        val accentColor = getAccentColor()
        val primaryTextColor = ThemeManager.getPrimaryColor(this)
        val secondaryTextColor = if (ThemeManager.getCurrentThemeKey(this) == ThemeManager.THEME_LIGHT) {
            Color.parseColor("#5C6270")
        } else {
            Color.parseColor("#98A2B3")
        }

        val titleViews = mutableListOf<Pair<TextView, String>>()

        options.forEach { option ->
            val itemContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad8, pad10, pad8, pad10)
                isClickable = true
                isFocusable = true
                addApplePressEffect()
                setOnClickListener {
                    prefs.edit().putString(PREF_SHORTCUT_MODE, option.modeKey).apply()
                    onSelected(option.modeKey)
                    dialog.dismiss()

                    if (option.modeKey == MODE_ASSIST_POWER) {
                        showPowerHoldSetupDialog()
                    }
                }
            }

            val itemTitle = TextView(this).apply {
                text = if (option.modeKey == currentMode) "●  ${option.title}" else "○  ${option.title}"
                textSize = 15f
                typeface = getAppTypeface()
            }
            titleViews.add(itemTitle to option.modeKey)

            val itemInstruction = TextView(this).apply {
                text = option.instruction
                textSize = 12f
                setPadding((22 * density).toInt(), (2 * density).toInt(), 0, 0)
                setTextColor(secondaryTextColor)
            }

            itemContainer.addView(itemTitle)
            itemContainer.addView(itemInstruction)
            rootLayout.addView(itemContainer)
        }

        dialog.setContentView(rootLayout)
        ThemeManager.applyPopupSurfaceStyle(this, rootLayout)

        titleViews.forEach { (tv, modeKey) ->
            tv.setTextColor(if (modeKey == currentMode) accentColor else primaryTextColor)
        }

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private fun showPowerHoldSetupDialog() {
        val dialog = ComponentDialog(this, ThemeManager.getDialogThemeRes(this))
        dialog.setContentView(R.layout.dialog_glass)
        applyDialogSurface(dialog)

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.findViewById<TextView>(R.id.dialogTitle)?.text = "Setup Power Button Hold"
        dialog.findViewById<TextView>(R.id.dialogMessage)?.text = """
            To use Power Hold instead of the Power Off menu:
            
            1. Tap 'SET ASSISTANT' below -> tap 'Device assistant app' -> select MemoryVoid.
            2. In your phone's Settings > System (or Additional Settings) > Power Button, set 'Press and hold the power button' to 'Voice Assistant' (instead of Power Menu).
        """.trimIndent()

        val btnNegative = dialog.findViewById<TextView>(R.id.btnNegative)
        val btnPositive = dialog.findViewById<TextView>(R.id.btnPositive)

        btnNegative?.addApplePressEffect()
        btnPositive?.addApplePressEffect()

        btnNegative?.text = "LATER"
        btnNegative?.setOnClickListener { dialog.dismiss() }

        btnPositive?.text = "SET ASSISTANT"
        btnPositive?.setOnClickListener {
            dialog.dismiss()
            try {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        }

        dialog.show()
    }

    private fun applyDialogSurface(dialog: android.app.Dialog) {
        val root = (dialog.findViewById<ViewGroup>(android.R.id.content))?.getChildAt(0)
        if (root != null) {
            ThemeManager.applyPopupSurfaceStyle(this, root)
        }
    }

    private fun showSettingsPopup() {
        val dialog = ComponentDialog(this, ThemeManager.getDialogThemeRes(this))
        dialog.setContentView(R.layout.dialog_settings_popup)
        applyDialogSurface(dialog)

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val item1Switch = dialog.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.popup_fsMenuItem1_switch)
        val item1MicSwitch = dialog.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.popup_fsMenuItem1_mic_switch)
        val item1NotifSwitch = dialog.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.popup_fsMenuItem1_notif_switch)
        val item1OverlaySwitch = dialog.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.popup_fsMenuItem1_overlay_switch)

        item1Switch?.addApplePressEffect()
        item1MicSwitch?.addApplePressEffect()
        item1NotifSwitch?.addApplePressEffect()
        item1OverlaySwitch?.addApplePressEffect()

        item1Switch?.isChecked = isAccessibilityServiceEnabled(this, KeyMappingService::class.java)
        item1Switch?.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        val isMicGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        item1MicSwitch?.isChecked = isMicGranted
        item1MicSwitch?.setOnClickListener {
            dialog.dismiss()
            if (!isMicGranted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.RECORD_AUDIO),
                    101
                )
            } else {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = android.net.Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
        }

        val isNotifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        }
        item1NotifSwitch?.isChecked = isNotifGranted
        item1NotifSwitch?.setOnClickListener {
            dialog.dismiss()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isNotifGranted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    102
                )
            } else {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            }
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val isIgnoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager != null) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
        item1OverlaySwitch?.isChecked = isIgnoringBatteryOptimizations
        item1OverlaySwitch?.setOnClickListener {
            dialog.dismiss()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(fallbackIntent)
                    } catch (ex: Exception) {
                        val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", packageName, null)
                        }
                        startActivity(detailsIntent)
                    }
                }
            } else {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
        }

        dialog.show()
    }

    private fun getAppTypeface(): Typeface {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun getAccentColor(): Int {
        return ThemeManager.getAccentColor(this)
    }

    private fun getAppIconRes(): Int {
        return R.drawable.ic_custom_void_no_bg
    }

    private fun showAppearanceDialog() {
        val dialog = ComponentDialog(this, ThemeManager.getDialogThemeRes(this))
        dialog.setContentView(R.layout.dialog_appearance)
        applyDialogSurface(dialog)

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setWindowAnimations(R.style.BouncyDialogAnimation)

        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseAppearance)
        btnClose?.addApplePressEffect()
        btnClose?.setOnClickListener { dialog.dismiss() }

        val cardLight = dialog.findViewById<View>(R.id.themeCardLight)
        val cardDark = dialog.findViewById<View>(R.id.themeCardDark)
        val cardGlass = dialog.findViewById<View>(R.id.themeCardGlass)

        val radioLight = dialog.findViewById<RadioButton>(R.id.radioLight)
        val radioDark = dialog.findViewById<RadioButton>(R.id.radioDark)
        val radioGlass = dialog.findViewById<RadioButton>(R.id.radioGlass)

        val currentTheme = ThemeManager.getCurrentThemeKey(this)

        fun updateThemeSelection(theme: String) {
            radioLight?.isChecked = theme == ThemeManager.THEME_LIGHT
            radioDark?.isChecked = theme == ThemeManager.THEME_DARK || theme == ThemeManager.THEME_BLACK
            radioGlass?.isChecked = theme == ThemeManager.THEME_GLASS

            cardLight?.isSelected = radioLight?.isChecked == true
            cardDark?.isSelected = radioDark?.isChecked == true
            cardGlass?.isSelected = radioGlass?.isChecked == true
        }
        updateThemeSelection(currentTheme)

        cardLight?.addApplePressEffect()
        cardLight?.setOnClickListener {
            updateThemeSelection(ThemeManager.THEME_LIGHT)
            dialog.dismiss()
            ThemeManager.applyTheme(this, ThemeManager.THEME_LIGHT)
        }

        cardDark?.addApplePressEffect()
        cardDark?.setOnClickListener {
            updateThemeSelection(ThemeManager.THEME_BLACK)
            dialog.dismiss()
            ThemeManager.applyTheme(this, ThemeManager.THEME_BLACK)
        }

        cardGlass?.addApplePressEffect()
        cardGlass?.setOnClickListener {
            updateThemeSelection(ThemeManager.THEME_GLASS)
            dialog.dismiss()
            ThemeManager.applyTheme(this, ThemeManager.THEME_GLASS)
        }

        val primaryColor = ThemeManager.getPrimaryColor(this)
        val colors = intArrayOf(
            primaryColor,                // Primary Theme Color
            Color.parseColor("#2196F3"), // Blue
            Color.parseColor("#9C27B0"), // Purple
            Color.parseColor("#E91E63"), // Pink
            Color.parseColor("#F44336"), // Red
            Color.parseColor("#FF9800"), // Orange
            Color.parseColor("#FFEB3B"), // Yellow
            Color.parseColor("#4CAF50"), // Green
            Color.parseColor("#00BCD4")  // Cyan
        )
        val colorContainer = dialog.findViewById<LinearLayout>(R.id.accentColorsContainer)

        fun updateColorContainerIndicators() {
            if (colorContainer == null) return
            val activeColor = ThemeManager.getAccentColor(this)

            for (i in 0 until colorContainer.childCount) {
                val itemFrame = colorContainer.getChildAt(i) as? FrameLayout ?: continue
                val circleColor = itemFrame.tag as? Int ?: continue
                val ringView = itemFrame.getChildAt(0)
                val checkView = itemFrame.getChildAt(2) as? ImageView

                val isThisSelected = activeColor == circleColor
                ringView.visibility = if (isThisSelected) View.VISIBLE else View.INVISIBLE
                checkView?.visibility = if (isThisSelected) View.VISIBLE else View.GONE
                checkView?.setColorFilter(ThemeManager.getContrastingTextColor(circleColor))
            }
        }

        colors.forEach { color ->
            val density = resources.displayMetrics.density
            val frameSize = (48 * density).toInt()
            val innerSize = (38 * density).toInt()
            val checkSize = (22 * density).toInt()

            val itemFrame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(frameSize, frameSize).apply {
                    marginEnd = (12 * density).toInt()
                }
                tag = color
            }

            val ringView = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(frameSize, frameSize)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke((3 * density).toInt(), color)
                }
                visibility = View.INVISIBLE
            }

            val circleView = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(innerSize, innerSize).apply {
                    gravity = Gravity.CENTER
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            }

            val checkView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(checkSize, checkSize).apply {
                    gravity = Gravity.CENTER
                }
                tag = "accent_check"
                setImageResource(R.drawable.ic_check)
                setColorFilter(ThemeManager.getContrastingTextColor(color))
                visibility = View.GONE
            }

            itemFrame.addView(ringView)
            itemFrame.addView(circleView)
            itemFrame.addView(checkView)

            itemFrame.addApplePressEffect()

            if (ThemeManager.getAccentColor(this) == color) {
                ringView.visibility = View.VISIBLE
                checkView.visibility = View.VISIBLE
            }

            itemFrame.setOnClickListener {
                val currentSaved = ThemeManager.getAccentColor(this)
                if (currentSaved == color && color != primaryColor) {
                    ThemeManager.setAccentColor(this, null)
                } else {
                    ThemeManager.setAccentColor(this, color)
                }
                updateColorContainerIndicators()
                refreshThemeColors()
            }

            colorContainer?.addView(itemFrame)
        }

        // Card Layout Selector Cards
        val layoutsContainer = dialog.findViewById<LinearLayout>(R.id.cardLayoutsContainer)
        if (layoutsContainer != null) {
            val density = resources.displayMetrics.density
            val layoutOptions = listOf(
                Triple(LAYOUT_CLASSIC, "Void Classic", "Editorial Stack"),
                Triple(LAYOUT_BENTO, "Bento Grid", "2-Col Masonry"),
                Triple(LAYOUT_CAPSULE, "Capsule Split", "Side Thumbnail"),
                Triple(LAYOUT_LEDGER, "Focus Ledger", "Compact List")
            )

            val secondaryTextColor = if (currentTheme == ThemeManager.THEME_LIGHT) {
                Color.parseColor("#5C6270")
            } else {
                Color.parseColor("#98A2B3")
            }

            fun rebuildLayoutCards() {
                layoutsContainer.removeAllViews()
                val activeLayout = prefs.getString(PREF_CARD_LAYOUT, LAYOUT_CLASSIC) ?: LAYOUT_CLASSIC
                val activeAccent = ThemeManager.getAccentColor(this)

                layoutOptions.forEach { (layoutKey, title, subtitle) ->
                    val isSelected = layoutKey == activeLayout
                    val cardWidth = (128 * density).toInt()
                    val pad12 = (12 * density).toInt()

                    val optionCard = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginEnd = (10 * density).toInt()
                        }
                        setPadding(pad12, pad12, pad12, pad12)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 18f * density
                            val fill = if (currentTheme == ThemeManager.THEME_LIGHT) Color.parseColor("#F2F4F7") else Color.parseColor("#1E1E24")
                            setColor(fill)
                            setStroke(
                                ((if (isSelected) 2f else 1f) * density).toInt(),
                                if (isSelected) activeAccent else Color.parseColor("#33888888")
                            )
                        }
                        addApplePressEffect()
                        setOnClickListener {
                            prefs.edit().putString(PREF_CARD_LAYOUT, layoutKey).apply()
                            rebuildLayoutCards()
                            refreshUI(animateEntrance = true)
                        }
                    }

                    val tvTitle = TextView(this).apply {
                        text = if (isSelected) "● $title" else title
                        textSize = 13f
                        typeface = getAppTypeface()
                        gravity = Gravity.CENTER
                        isSingleLine = true
                        setTextColor(if (isSelected) activeAccent else primaryColor)
                    }

                    val tvSub = TextView(this).apply {
                        text = subtitle
                        textSize = 11f
                        gravity = Gravity.CENTER
                        isSingleLine = true
                        setPadding(0, (3 * density).toInt(), 0, 0)
                        setTextColor(secondaryTextColor)
                    }

                    optionCard.addView(tvTitle)
                    optionCard.addView(tvSub)
                    layoutsContainer.addView(optionCard)
                }
            }
            rebuildLayoutCards()
        }

        dialog.show()
    }

    private fun refreshThemeColors() {
        ThemeManager.applyThemeColorsToMain(this)
        loadDynamicTags()
        refreshUI(animateEntrance = false)
    }

    private fun showWalkthroughDialog() {
        val dialog = ComponentDialog(this, ThemeManager.getDialogThemeRes(this))
        dialog.setContentView(R.layout.dialog_walkthrough)
        applyDialogSurface(dialog)

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setWindowAnimations(R.style.BouncyDialogAnimation)

        val btnSettings = dialog.findViewById<TextView>(R.id.btnWalkthroughSettings)
        val btnGetStarted = dialog.findViewById<TextView>(R.id.btnWalkthroughGetStarted)

        btnSettings?.addApplePressEffect()
        btnGetStarted?.addApplePressEffect()

        btnSettings?.setOnClickListener {
            dialog.dismiss()
            showSettingsPopup()
        }

        btnGetStarted?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showPermissionsRedirectDialog() {
        val dialog = ComponentDialog(this, ThemeManager.getDialogThemeRes(this))
        dialog.setContentView(R.layout.dialog_glass)
        applyDialogSurface(dialog)

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.findViewById<TextView>(R.id.dialogTitle)?.text = "Permissions Required"
        dialog.findViewById<TextView>(R.id.dialogMessage)?.text = "MemoryVoid needs access to your files to save and retrieve your screenshots."

        val btnNegative = dialog.findViewById<TextView>(R.id.btnNegative)
        btnNegative?.text = "CANCEL"
        btnNegative?.setOnClickListener { dialog.dismiss() }

        val btnPositive = dialog.findViewById<TextView>(R.id.btnPositive)
        btnPositive?.text = "OPEN SETTINGS"
        btnPositive?.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.fromParts("package", packageName, null)
            startActivity(intent)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showHowToUseDialog() {
        val dialog = ComponentDialog(this, ThemeManager.getDialogThemeRes(this))
        dialog.setContentView(R.layout.dialog_glass)
        applyDialogSurface(dialog)

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.findViewById<TextView>(R.id.dialogTitle)?.text = "How to Use MemoryVoid"

        val activeMode = prefs.getString(PREF_SHORTCUT_MODE, MODE_HOLD_VOL_DOWN) ?: MODE_HOLD_VOL_DOWN
        val activeShortcutLabel = getShortcutDisplayName(activeMode)

        val activeHint = when (activeMode) {
            MODE_DOUBLE_TAP_HOLD -> "Quickly tap Vol Up or Down once, then press & hold on the 2nd tap to record. Release to save."
            MODE_SEQ_UP_DOWN -> "Tap Vol Up once, then immediately press & hold Vol Down to record. Release to save."
            MODE_SCREENSHOT_COMBO -> "Press & hold Vol Down to immediately snap a screenshot and record audio. Release to save."
            MODE_ASSIST_POWER -> "Set MemoryVoid as your Default Digital Assistant. Hold Power button to start recording, then tap the floating pill to stop & save."
            else -> "Press & hold Vol Down to record your voice note. Release to snap a screenshot and save."
        }

        val tutorialText = """
            Setup:
            1. Open Sidebar Menu > Settings (bottom-right gear icon).
            2. Grant Accessibility, Microphone & Background permissions.
            3. Toggle 'Capture Screenshot' or change your 'Trigger Shortcut' under Preferences.
            
            Active Shortcut — $activeShortcutLabel:
            • $activeHint
            • Normal volume adjustments work without interruption.
            • Tap '✏️' (top right) to open the full-screen note & checklist editor.
        """.trimIndent()

        dialog.findViewById<TextView>(R.id.dialogMessage)?.text = tutorialText

        val btnNegative = dialog.findViewById<TextView>(R.id.btnNegative)
        val btnPositive = dialog.findViewById<TextView>(R.id.btnPositive)

        btnNegative?.addApplePressEffect()
        btnPositive?.addApplePressEffect()

        btnNegative?.visibility = View.GONE

        btnPositive?.text = "GOT IT"
        btnPositive?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun setupSwipeRefresh() {
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)
        val density = resources.displayMetrics.density
        val startOffset = (64 * density).toInt()
        val endOffset = (120 * density).toInt()
        swipe.setProgressViewOffset(false, startOffset, endOffset)

        swipe.setOnRefreshListener {
            loadDynamicTags()
            refreshUI()
            swipe.isRefreshing = false
        }
    }

    private fun loadDynamicTags() {
        val chipTextContainer = findViewById<LinearLayout>(R.id.chipTextContainer)
        val pillBg = findViewById<View>(R.id.activePillBg)
        val chipContainer = findViewById<ViewGroup>(R.id.chipContainer)

        chipTextContainer.removeAllViews()

        val defaultTags = setOf("All", "Work", "Personal")
        val savedTags = prefs.getStringSet("custom_tags", defaultTags)?.toMutableSet() ?: defaultTags.toMutableSet()

        val pillHeight = (40 * resources.displayMetrics.density).toInt()
        var activeView: View? = null
        val standardFont = getAppTypeface()
        val accentColor = getAccentColor()

        val activeTextColor = ThemeManager.getContrastingTextColor(accentColor)
        val inactiveColor = if (ThemeManager.getCurrentThemeKey(this) == ThemeManager.THEME_LIGHT) {
            Color.parseColor("#5C6270")
        } else {
            Color.parseColor("#98A2B3")
        }

        pillBg.background = ContextCompat.getDrawable(this, R.drawable.bg_active_pill)
        pillBg.backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)

        savedTags.forEach { tag ->
            val textView = TextView(this).apply {
                text = tag
                textSize = 14f
                typeface = standardFont
                isSingleLine = true
                includeFontPadding = false
                gravity = Gravity.CENTER

                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, pillHeight).apply {
                    marginEnd = (8 * resources.displayMetrics.density).toInt()
                    gravity = Gravity.CENTER_VERTICAL
                }
                setPadding(36, 0, 36, 0)

                setTextColor(if (tag == currentFilter) activeTextColor else inactiveColor)

                addApplePressEffect()

                setOnClickListener { clickedView ->
                    if (currentFilter != tag) {
                        currentFilter = tag

                        val transition = AutoTransition().apply {
                            duration = 320
                            interpolator = appleCurve
                        }
                        TransitionManager.beginDelayedTransition(chipContainer, transition)

                        for (i in 0 until chipTextContainer.childCount) {
                            val child = chipTextContainer.getChildAt(i)
                            if (child is TextView && child.text != "+") {
                                child.setTextColor(if (child.text == currentFilter) activeTextColor else inactiveColor)
                            }
                        }

                        val params = pillBg.layoutParams
                        params.width = clickedView.width
                        if (params is ViewGroup.MarginLayoutParams) {
                            params.leftMargin = clickedView.left
                        }
                        pillBg.layoutParams = params

                        refreshUI()
                    }
                }

                if (tag != "All") {
                    setOnLongClickListener {
                        val dialog = ComponentDialog(this@MainActivity, ThemeManager.getDialogThemeRes(this@MainActivity))
                        dialog.setContentView(R.layout.dialog_glass)
                        applyDialogSurface(dialog)

                        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

                        dialog.findViewById<TextView>(R.id.dialogTitle)?.text = "Remove Category"
                        dialog.findViewById<TextView>(R.id.dialogMessage)?.text = "Delete '$tag' from categories?"

                        val btnNegative = dialog.findViewById<TextView>(R.id.btnNegative)
                        btnNegative?.text = "CANCEL"
                        btnNegative?.setOnClickListener { dialog.dismiss() }

                        val btnPositive = dialog.findViewById<TextView>(R.id.btnPositive)
                        btnPositive?.text = "DELETE"
                        btnPositive?.setOnClickListener {
                            savedTags.remove(tag)
                            prefs.edit().putStringSet("custom_tags", savedTags).apply()
                            if (currentFilter == tag) currentFilter = "All"
                            loadDynamicTags()
                            refreshUI()
                            dialog.dismiss()
                        }

                        dialog.show()
                        true
                    }
                }
            }
            chipTextContainer.addView(textView)
            if (tag == currentFilter) activeView = textView
        }

        val btnAdd = TextView(this).apply {
            text = "+"
            textSize = 18f
            typeface = standardFont
            includeFontPadding = false
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(pillHeight, pillHeight).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = (12 * resources.displayMetrics.density).toInt()
            }

            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.glass_dock)
            setTextColor(accentColor)

            addApplePressEffect()
            setOnClickListener { showAddCategoryDialog(savedTags) }
        }
        chipTextContainer.addView(btnAdd)

        chipTextContainer.post {
            activeView?.let { view ->
                val params = pillBg.layoutParams
                params.width = view.width
                if (params is ViewGroup.MarginLayoutParams) {
                    params.leftMargin = view.left
                }
                pillBg.layoutParams = params
            }
        }
    }

    private fun showAddCategoryDialog(savedTags: MutableSet<String>) {
        val dialog = ComponentDialog(this, ThemeManager.getDialogThemeRes(this))
        dialog.setContentView(R.layout.dialog_glass_input)
        applyDialogSurface(dialog)

        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.BOTTOM)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            val params = window.attributes
            params?.y = (72 * resources.displayMetrics.density).toInt()
            window.attributes = params
        }

        dialog.findViewById<TextView>(R.id.dialogTitle)?.text = "Add Category"

        val input = dialog.findViewById<EditText>(R.id.dialogInput)
        input?.hint = "New Category Name"
        input?.setText("")

        val btnNegative = dialog.findViewById<TextView>(R.id.btnNegative)
        val btnPositive = dialog.findViewById<TextView>(R.id.btnPositive)

        btnNegative?.addApplePressEffect()
        btnPositive?.addApplePressEffect()

        btnNegative?.text = "CANCEL"
        btnNegative?.setOnClickListener { dialog.dismiss() }

        btnPositive?.text = "ADD"
        btnPositive?.setOnClickListener {
            val newCategory = input?.text?.toString()?.trim() ?: ""
            if (newCategory.isNotEmpty()) {
                savedTags.add(newCategory)
                prefs.edit().putStringSet("custom_tags", savedTags).apply()
                loadDynamicTags()
                refreshUI()
            }
            dialog.dismiss()
        }

        dialog.show()
        input?.post {
            input.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun refreshUI(animateEntrance: Boolean = true) {
        val container = findViewById<LinearLayout>(R.id.notesContainer)
        container.removeAllViews()

        val jsonArray = masterNotesArray
        val inflater = layoutInflater
        val density = resources.displayMetrics.density

        val font = getAppTypeface()
        val accentColor = getAccentColor()
        val themeKey = ThemeManager.getCurrentThemeKey(this)
        val layoutStyle = prefs.getString(PREF_CARD_LAYOUT, LAYOUT_CLASSIC) ?: LAYOUT_CLASSIC
        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy • hh:mm a", java.util.Locale.getDefault())

        val titleColor = if (themeKey == ThemeManager.THEME_LIGHT) Color.parseColor("#121316") else Color.WHITE
        val dateColor = if (themeKey == ThemeManager.THEME_LIGHT) Color.parseColor("#5C6270") else Color.parseColor("#98A2B3")

        val orderedIndices = (jsonArray.length() - 1 downTo 0).sortedByDescending { idx ->
            jsonArray.optJSONObject(idx)?.optBoolean("is_pinned", false) == true
        }

        // For Bento Grid (2-Column Masonry), create two vertical columns inside a horizontal wrapper
        var leftColumn: LinearLayout? = null
        var rightColumn: LinearLayout? = null
        if (layoutStyle == LAYOUT_BENTO) {
            val pad14 = (14 * density).toInt()
            val pad6 = (6 * density).toInt()
            val masonryRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(pad14, 0, pad14, 0)
                isBaselineAligned = false
            }
            leftColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = pad6
                }
            }
            rightColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = pad6
                }
            }
            masonryRow.addView(leftColumn)
            masonryRow.addView(rightColumn)
            container.addView(masonryRow)
        }

        lifecycleScope.launch {
            var visibleCardIndex = 0
            for (i in orderedIndices) {
                val noteObj = jsonArray.getJSONObject(i)
                val summary = noteObj.getString("summary")
                val bodyText = noteObj.optString("body", "").trim()
                val isPinned = noteObj.optBoolean("is_pinned", false)
                val customColorHex = noteObj.optString("card_color", "")
                val tags = noteObj.optString("tag", "Personal")

                if (currentFilter != "All" && currentFilter != tags) continue
                if (searchQuery.isNotEmpty()) {
                    val q = searchQuery.lowercase()
                    if (!summary.lowercase().contains(q) && !bodyText.lowercase().contains(q)) continue
                }

                val targetParent: ViewGroup = if (layoutStyle == LAYOUT_BENTO) {
                    if (visibleCardIndex % 2 == 0) leftColumn!! else rightColumn!!
                } else {
                    container
                }

                val view = inflater.inflate(R.layout.note_item, targetParent, false)
                val cardView = view as? com.google.android.material.card.MaterialCardView
                cardView?.setCardBackgroundColor(ThemeManager.resolveNoteCardBackground(this@MainActivity, customColorHex))
                if (isPinned) {
                    cardView?.strokeWidth = (1.5f * density).toInt()
                    cardView?.strokeColor = accentColor
                } else if (customColorHex.isNotEmpty() || themeKey == ThemeManager.THEME_GLASS) {
                    cardView?.strokeWidth = (1 * density).toInt()
                    cardView?.strokeColor = ThemeManager.getCardStrokeColor(this@MainActivity, customColorHex)
                } else {
                    cardView?.strokeWidth = 0
                }

                val noteRootLinear = view.findViewById<LinearLayout>(R.id.noteRootLinear)
                val noteContentRow = view.findViewById<LinearLayout>(R.id.noteContentRow)
                val noteTextColumn = view.findViewById<LinearLayout>(R.id.noteTextColumn)
                val imageContainer = view.findViewById<View>(R.id.imageContainer)
                val imageView = view.findViewById<ImageView>(R.id.itemImage)
                val itemTitle = view.findViewById<TextView>(R.id.itemTitle)
                val itemBody = view.findViewById<TextView>(R.id.itemBody)
                val dateView = view.findViewById<TextView>(R.id.itemDate)
                val tagView = view.findViewById<TextView>(R.id.itemTag)
                val playBtn = view.findViewById<ImageButton>(R.id.itemPlay)
                val itemMenu = view.findViewById<ImageButton>(R.id.itemMenu)

                val allImages = extractImagePaths(noteObj)
                val allAudios = extractAudioPaths(noteObj)
                val firstImgPath = allImages.firstOrNull() ?: ""
                val firstAudioPath = allAudios.firstOrNull() ?: ""

                itemTitle.text = if (isPinned) "📌  $summary" else summary
                itemTitle.typeface = font
                itemTitle.setTextColor(titleColor)

                if (itemBody != null) {
                    if (bodyText.isNotEmpty() && layoutStyle != LAYOUT_LEDGER) {
                        itemBody.visibility = View.VISIBLE
                        itemBody.text = bodyText.replace("[ ] ", "☐  ").replace("[x] ", "☑  ")
                        itemBody.setTextColor(dateColor)
                    } else {
                        itemBody.visibility = View.GONE
                    }
                }

                val timestamp = noteObj.optLong("timestamp", 0L)
                dateView.typeface = font
                dateView.setTextColor(dateColor)
                val extraBadge = buildString {
                    if (layoutStyle == LAYOUT_LEDGER) {
                        if (allImages.isNotEmpty()) append(" • 📷 ${allImages.size}")
                        if (allAudios.isNotEmpty()) append(" • 🎙️ ${allAudios.size}")
                        if (bodyText.isNotEmpty()) append(" • 📝")
                    } else {
                        if (allImages.size > 1) append(" • 📷 ${allImages.size}")
                        if (allAudios.size > 1) append(" • 🎙️ ${allAudios.size}")
                    }
                }
                if (timestamp > 0L) {
                    val shortSdf = if (layoutStyle == LAYOUT_BENTO) {
                        java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault())
                    } else sdf
                    dateView.text = shortSdf.format(java.util.Date(timestamp)) + extraBadge
                } else {
                    dateView.text = "Just now$extraBadge"
                }

                val noteActionsRow = view.findViewById<LinearLayout>(R.id.noteActionsRow)

                // Apply Layout-Specific Geometry (Dedicated Title/Date space + Bottom-Right actions)
                when (layoutStyle) {
                    LAYOUT_BENTO -> {
                        val lp = cardView?.layoutParams as? ViewGroup.MarginLayoutParams
                        lp?.setMargins(0, (6 * density).toInt(), 0, (6 * density).toInt())
                        cardView?.radius = 20f * density
                        imageView?.layoutParams?.height = (130 * density).toInt()
                        val pad12 = (12 * density).toInt()
                        val pad10 = (10 * density).toInt()
                        noteContentRow?.setPadding(pad12, pad12, pad10, pad10)
                        itemTitle.textSize = 15f
                        itemTitle.maxLines = 2
                        itemBody?.maxLines = 3
                        itemBody?.textSize = 13f
                        tagView.visibility = View.VISIBLE
                        (noteActionsRow?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = (8 * density).toInt()

                        // Bento Grid specific: move category tag to its own line above voice and options buttons
                        noteActionsRow?.orientation = LinearLayout.VERTICAL
                        noteActionsRow?.gravity = Gravity.END
                        val tagLp = tagView.layoutParams as? ViewGroup.MarginLayoutParams
                        tagLp?.marginEnd = 0
                        tagLp?.bottomMargin = (6 * density).toInt()
                        tagView.layoutParams = tagLp
                    }
                    LAYOUT_CAPSULE -> {
                        cardView?.radius = 22f * density
                        noteRootLinear?.orientation = LinearLayout.HORIZONTAL
                        noteRootLinear?.gravity = Gravity.FILL_VERTICAL
                        if (firstImgPath.isNotEmpty()) {
                            val thumbWidth = (96 * density).toInt()
                            imageContainer?.layoutParams = LinearLayout.LayoutParams(thumbWidth, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                                setMargins(0, 0, 0, 0)
                            }
                            imageContainer?.clipToOutline = true
                            imageContainer?.background = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadii = floatArrayOf(
                                    22f * density, 22f * density, // top-left
                                    0f, 0f,                      // top-right
                                    0f, 0f,                      // bottom-right
                                    22f * density, 22f * density  // bottom-left
                                )
                            }
                            imageView?.scaleType = ImageView.ScaleType.CENTER_CROP
                            imageView?.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        }
                        noteContentRow?.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        val pad14 = (14 * density).toInt()
                        val pad10 = (10 * density).toInt()
                        noteContentRow?.setPadding(pad14, (12 * density).toInt(), pad10, pad10)
                        itemTitle.maxLines = 2
                        itemBody?.maxLines = 2
                        (noteActionsRow?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = (6 * density).toInt()

                        noteActionsRow?.orientation = LinearLayout.HORIZONTAL
                        noteActionsRow?.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        val tagLp = tagView.layoutParams as? ViewGroup.MarginLayoutParams
                        tagLp?.marginEnd = (8 * density).toInt()
                        tagLp?.bottomMargin = 0
                        tagView.layoutParams = tagLp
                    }
                    LAYOUT_LEDGER -> {
                        val lp = cardView?.layoutParams as? ViewGroup.MarginLayoutParams
                        lp?.setMargins((20 * density).toInt(), (5 * density).toInt(), (20 * density).toInt(), (5 * density).toInt())
                        cardView?.radius = 18f * density
                        val padH = (16 * density).toInt()
                        val padV = (12 * density).toInt()
                        noteContentRow?.setPadding(padH, padV, (12 * density).toInt(), (10 * density).toInt())
                        itemTitle.textSize = 16f
                        itemTitle.maxLines = 1
                        imageContainer?.visibility = View.GONE
                        (noteActionsRow?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = (6 * density).toInt()

                        noteActionsRow?.orientation = LinearLayout.HORIZONTAL
                        noteActionsRow?.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        val tagLp = tagView.layoutParams as? ViewGroup.MarginLayoutParams
                        tagLp?.marginEnd = (8 * density).toInt()
                        tagLp?.bottomMargin = 0
                        tagView.layoutParams = tagLp
                    }
                    else -> { // LAYOUT_CLASSIC
                        cardView?.radius = 24f * density
                        imageView?.layoutParams?.height = (220 * density).toInt()

                        noteActionsRow?.orientation = LinearLayout.HORIZONTAL
                        noteActionsRow?.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        val tagLp = tagView.layoutParams as? ViewGroup.MarginLayoutParams
                        tagLp?.marginEnd = (8 * density).toInt()
                        tagLp?.bottomMargin = 0
                        tagView.layoutParams = tagLp
                    }
                }

                // Load Image Thumbnail (for Classic, Bento, and Capsule)
                if (firstImgPath.isNotEmpty() && layoutStyle != LAYOUT_LEDGER) {
                    imageContainer?.visibility = View.VISIBLE
                    val cachedBitmap = thumbnailCache.get(firstImgPath)
                    if (cachedBitmap != null && !cachedBitmap.isRecycled) {
                        imageView.setImageBitmap(cachedBitmap)
                    } else {
                        launch(Dispatchers.IO) {
                            val options = BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                                BitmapFactory.decodeFile(firstImgPath, this)
                                inSampleSize = calculateInSampleSize(this, 400, 300)
                                inJustDecodeBounds = false
                            }
                            val bitmap = BitmapFactory.decodeFile(firstImgPath, options)
                            if (bitmap != null) {
                                thumbnailCache.put(firstImgPath, bitmap)
                                withContext(Dispatchers.Main) {
                                    imageView.setImageBitmap(bitmap)
                                }
                            }
                        }
                    }
                } else {
                    imageContainer?.visibility = View.GONE
                }

                playBtn.setColorFilter(accentColor)
                if (firstAudioPath.isNotEmpty()) {
                    playBtn.visibility = View.VISIBLE
                    playBtn.setOnClickListener {
                        playAudioNote(firstAudioPath)
                    }
                } else {
                    playBtn.visibility = View.GONE
                }

                tagView.text = tags
                tagView.typeface = font
                ThemeManager.applyCategoryBadgeStyle(this@MainActivity, tagView)
                tagView.setOnClickListener {
                    val savedTags = prefs.getStringSet("custom_tags", setOf("All", "Work", "Personal"))?.toMutableSet() ?: mutableSetOf()
                    val tagList = savedTags.filter { it != "All" }.toTypedArray()
                    showCategoryFlyout(tagView, noteObj, i, jsonArray, tagList)
                }

                itemMenu?.setColorFilter(titleColor)
                itemMenu?.addApplePressEffect()
                itemMenu?.setOnClickListener {
                    showNoteOptionsFlyout(itemMenu, noteObj, i, jsonArray)
                }

                view.setOnClickListener {
                    openFullNoteEditor(editIndex = i)
                }

                view.addApplePressEffect()

                if (animateEntrance) {
                    view.translationY = 120f
                    view.scaleX = 0.95f
                    view.scaleY = 0.95f
                    view.alpha = 0f

                    view.animate()
                        .translationY(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setStartDelay((visibleCardIndex * 40).toLong())
                        .setDuration(450)
                        .setInterpolator(appleOvershoot)
                        .start()
                }

                targetParent.addView(view)
                visibleCardIndex++
            }
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun showCategoryFlyout(anchorView: View, noteObj: JSONObject?, index: Int, jsonArray: JSONArray?, tagList: Array<String>, onTagChosen: ((String) -> Unit)? = null) {
        val listView = ListView(this)

        ThemeManager.applyPopupSurfaceStyle(this, listView)
        listView.divider = ColorDrawable(Color.TRANSPARENT)
        listView.dividerHeight = 0
        listView.setPadding(0, 16, 0, 16)

        val dynamicTextColor = ThemeManager.getPrimaryColor(this)

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, tagList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(dynamicTextColor)
                view.gravity = Gravity.CENTER
                view.typeface = getAppTypeface()
                view.isSingleLine = true
                return view
            }
        }
        listView.adapter = adapter

        val minWidth = (140 * resources.displayMetrics.density).toInt()
        val popupWidth = Math.max(anchorView.width, minWidth)

        val popupWindow = PopupWindow(
            listView,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popupWindow.elevation = 20f

        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedTag = tagList[position]
            (anchorView as? TextView)?.text = selectedTag

            if (onTagChosen != null) {
                onTagChosen(selectedTag)
            } else if (noteObj != null && jsonArray != null) {
                try {
                    noteObj.put("tag", selectedTag)
                    prefs.edit().putString("notes_array", jsonArray.toString()).apply()
                    loadDataToMemory()
                    refreshUI(animateEntrance = false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            popupWindow.dismiss()
        }

        popupWindow.animationStyle = android.R.style.Animation_Dialog

        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)

        listView.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        var popupHeight = listView.measuredHeight

        if (popupHeight == 0) {
            popupHeight = (tagList.size * 120) + 32
        }

        val finalX = location[0] - ((popupWidth - anchorView.width) / 2)

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val spaceAbove = location[1]
        val spaceBelow = screenHeight - location[1] - anchorView.height

        val finalY = if (spaceBelow < popupHeight + 100 && spaceAbove > spaceBelow) {
            location[1] - popupHeight - 24
        } else {
            location[1] + anchorView.height + 24
        }

        val safeX = finalX.coerceIn(32, screenWidth - popupWidth - 32)

        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, safeX, finalY)
    }

    private fun showNoteOptionsFlyout(anchorView: View, noteObj: JSONObject, index: Int, jsonArray: JSONArray) {
        val popupView = layoutInflater.inflate(R.layout.popup_glass_menu, null)
        val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popupWindow.elevation = 20f

        val item1 = popupView.findViewById<TextView>(R.id.menuItem1)
        val item2 = popupView.findViewById<TextView>(R.id.menuItem2)
        val item3 = popupView.findViewById<TextView>(R.id.menuItem3)

        val isPinned = noteObj.optBoolean("is_pinned", false)

        item1.text = if (isPinned) "Unpin Note" else "Pin to Top"
        item1.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_edit, 0, 0, 0)
        item1.compoundDrawablePadding = (12 * resources.displayMetrics.density).toInt()

        item2.text = "Share / Copy"
        item2.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_help, 0, 0, 0)
        item2.compoundDrawablePadding = (12 * resources.displayMetrics.density).toInt()

        item3.visibility = View.VISIBLE
        item3.text = "Delete"
        item3.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_delete, 0, 0, 0)
        item3.compoundDrawablePadding = (12 * resources.displayMetrics.density).toInt()

        ThemeManager.applyPopupSurfaceStyle(this, popupView)

        item1.setOnClickListener {
            popupWindow.dismiss()
            noteObj.put("is_pinned", !isPinned)
            jsonArray.put(index, noteObj)
            prefs.edit().putString("notes_array", jsonArray.toString()).apply()
            loadDataToMemory()
            refreshUI(animateEntrance = false)
            Toast.makeText(this, if (!isPinned) "Pinned to top" else "Unpinned", Toast.LENGTH_SHORT).show()
        }

        item2.setOnClickListener {
            popupWindow.dismiss()
            val title = noteObj.optString("summary", "")
            val body = noteObj.optString("body", "").replace("[ ] ", "☐ ").replace("[x] ", "☑ ")
            val fullText = if (body.isNotEmpty()) "$title\n\n$body" else title
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("MemoryVoid Note", fullText))
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, fullText)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Note"))
        }

        item3.setOnClickListener {
            popupWindow.dismiss()
            showDeleteNoteDialog(index, jsonArray)
        }

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupWidth = popupView.measuredWidth
        val popupHeight = popupView.measuredHeight

        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val spaceAbove = location[1]
        val spaceBelow = screenHeight - location[1] - anchorView.height

        val finalY = if (spaceBelow < popupHeight + 100 && spaceAbove > spaceBelow) {
            location[1] - popupHeight - 16
        } else {
            location[1] + anchorView.height + 16
        }

        val finalX = location[0] - ((popupWidth - anchorView.width) / 2)
        val safeX = finalX.coerceIn(32, screenWidth - popupWidth - 32)

        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, safeX, finalY)
    }

    private fun showCreateNoteDialog() {
        openFullNoteEditor(editIndex = -1)
    }

    private fun openFullNoteEditor(editIndex: Int = -1) {
        val themeKey = ThemeManager.getCurrentThemeKey(this)
        val themeRes = ThemeManager.getThemeDialogRes(this)
        val dialog = ComponentDialog(this, themeRes)
        dialog.setContentView(R.layout.dialog_note_editor)

        val existingNote = if (editIndex in 0 until masterNotesArray.length()) {
            masterNotesArray.optJSONObject(editIndex)
        } else null

        var currentTag = existingNote?.optString("tag", if (currentFilter != "All") currentFilter else "Personal")
            ?: (if (currentFilter != "All") currentFilter else "Personal")
        val imagePathsList = extractImagePaths(existingNote)
        val audioPathsList = extractAudioPaths(existingNote)
        var isChecklistMode = existingNote?.optBoolean("is_checklist", false) ?: false
        val isPinned = existingNote?.optBoolean("is_pinned", false) ?: false
        var currentCardColorHex = existingNote?.optString("card_color", "") ?: ""

        var editorRecorder: android.media.MediaRecorder? = null
        var activeRecordingTempPath: String? = null
        var isEditorRecording = false
        var isExplicitlyDeleted = false
        var hasSaved = false

        dialog.window?.let { window ->
            window.setWindowAnimations(R.style.IosModalAnimation)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (themeKey == ThemeManager.THEME_GLASS) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#B3000000")))
            }
        }

        val root = dialog.findViewById<View>(R.id.editorRoot)
        val topBar = dialog.findViewById<View>(R.id.editorTopBar)
        val bottomDock = dialog.findViewById<View>(R.id.editorBottomDock)
        val bottomCard = dialog.findViewById<com.google.android.material.card.MaterialCardView>(R.id.editorBottomCard)

        val btnBack = dialog.findViewById<ImageButton>(R.id.btnEditorBack)
        val tagBadge = dialog.findViewById<TextView>(R.id.editorTagBadge)
        val btnDelete = dialog.findViewById<ImageButton>(R.id.btnEditorDelete)

        val imagesScroll = dialog.findViewById<HorizontalScrollView>(R.id.editorImagesScroll)
        val imagesContainer = dialog.findViewById<LinearLayout>(R.id.editorImagesContainer)
        val audiosContainer = dialog.findViewById<LinearLayout>(R.id.editorAudiosContainer)

        val titleInput = dialog.findViewById<EditText>(R.id.editorTitleInput)
        val timestampText = dialog.findViewById<TextView>(R.id.editorTimestampText)
        val bodyInput = dialog.findViewById<EditText>(R.id.editorBodyInput)

        val checklistContainer = dialog.findViewById<LinearLayout>(R.id.editorChecklistContainer)
        val checklistItemsList = dialog.findViewById<LinearLayout>(R.id.checklistItemsList)
        val btnAddChecklistItem = dialog.findViewById<TextView>(R.id.btnAddChecklistItem)

        val colorPaletteScroll = dialog.findViewById<HorizontalScrollView>(R.id.editorColorPaletteScroll)
        val colorPaletteContainer = dialog.findViewById<LinearLayout>(R.id.editorColorPaletteContainer)

        val btnAddImage = dialog.findViewById<ImageButton>(R.id.btnEditorAddImage)
        val btnRecordAudio = dialog.findViewById<ImageButton>(R.id.btnEditorRecordAudio)
        val btnToggleChecklist = dialog.findViewById<ImageButton>(R.id.btnEditorToggleChecklist)
        val btnPalette = dialog.findViewById<ImageButton>(R.id.btnEditorPalette)
        val btnSave = dialog.findViewById<TextView>(R.id.btnEditorSave)

        val density = resources.displayMetrics.density
        val accentColor = getAccentColor()
        val primaryColor = ThemeManager.getPrimaryColor(this)
        val secondaryColor = if (themeKey == ThemeManager.THEME_LIGHT) Color.parseColor("#5C6270") else Color.parseColor("#98A2B3")
        val surfaceVariantColor = when (themeKey) {
            ThemeManager.THEME_LIGHT -> Color.parseColor("#E8ECF2")
            ThemeManager.THEME_GLASS -> Color.parseColor("#CC161920")
            else -> Color.parseColor("#161616")
        }

        fun applyEditorBackgroundColor() {
            val resolvedBg = if (currentCardColorHex.isNotEmpty()) {
                ThemeManager.resolveNoteCardBackground(this, currentCardColorHex)
            } else {
                when (themeKey) {
                    ThemeManager.THEME_LIGHT -> Color.parseColor("#F2F4F7")
                    ThemeManager.THEME_GLASS -> Color.TRANSPARENT
                    else -> Color.BLACK
                }
            }
            root?.setBackgroundColor(resolvedBg)
        }
        applyEditorBackgroundColor()

        bottomCard?.setCardBackgroundColor(surfaceVariantColor)
        bottomCard?.strokeColor = accentColor
        bottomCard?.strokeWidth = (1 * density).toInt()

        btnBack?.setColorFilter(primaryColor)
        btnDelete?.setColorFilter(primaryColor)
        btnDelete?.visibility = if (existingNote != null) View.VISIBLE else View.GONE

        titleInput?.setTextColor(primaryColor)
        titleInput?.setHintTextColor(secondaryColor)
        bodyInput?.setTextColor(primaryColor)
        bodyInput?.setHintTextColor(secondaryColor)
        timestampText?.setTextColor(secondaryColor)
        btnAddChecklistItem?.setTextColor(accentColor)

        btnAddImage?.setColorFilter(primaryColor)
        btnRecordAudio?.setColorFilter(primaryColor)
        btnToggleChecklist?.setColorFilter(if (isChecklistMode) accentColor else primaryColor)
        btnPalette?.setColorFilter(if (currentCardColorHex.isNotEmpty()) accentColor else primaryColor)

        btnSave?.backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
        btnSave?.setTextColor(ThemeManager.getContrastingTextColor(accentColor))

        // Build Google Keep-style Note Color Palette Strip
        if (colorPaletteContainer != null) {
            ThemeManager.applyPopupSurfaceStyle(this, colorPaletteContainer)
            val paletteHexes = listOf(
                "",          // Default Theme Surface
                "#692B2B",   // Bright Coral Red
                "#6E421C",   // Bright Tangerine Orange
                "#6B5B1B",   // Bright Golden Yellow
                "#235D3A",   // Bright Emerald Green
                "#1B575E",   // Bright Cyan Teal
                "#1E436E",   // Bright Cobalt Blue
                "#47286E",   // Bright Electric Purple
                "#69244C"    // Bright Magenta Pink
            )
            paletteHexes.forEach { hex ->
                val size = (34 * density).toInt()
                val swatch = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginEnd = (10 * density).toInt()
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        val fill = if (hex.isEmpty()) surfaceVariantColor else Color.parseColor(hex)
                        setColor(fill)
                        val isSelected = hex == currentCardColorHex
                        setStroke(
                            ((if (isSelected) 2.5f else 1f) * density).toInt(),
                            if (isSelected) accentColor else secondaryColor
                        )
                    }
                    addApplePressEffect()
                    setOnClickListener {
                        currentCardColorHex = hex
                        applyEditorBackgroundColor()
                        btnPalette?.setColorFilter(if (currentCardColorHex.isNotEmpty()) accentColor else primaryColor)
                        colorPaletteScroll?.visibility = View.GONE
                    }
                }
                colorPaletteContainer.addView(swatch)
            }
        }

        btnPalette?.addApplePressEffect()
        btnPalette?.setOnClickListener {
            colorPaletteScroll?.visibility = if (colorPaletteScroll?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // Handle System Bars & Keyboard Insets smoothly
        if (root != null) {
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                val bottomInset = Math.max(sysBars.bottom, ime.bottom)

                topBar?.let { bar ->
                    val lp = bar.layoutParams as ViewGroup.MarginLayoutParams
                    lp.topMargin = sysBars.top + (6 * density).toInt()
                    bar.layoutParams = lp
                }
                bottomDock?.setPadding(
                    (16 * density).toInt(),
                    (6 * density).toInt(),
                    (16 * density).toInt(),
                    bottomInset + (12 * density).toInt()
                )
                WindowInsetsCompat.CONSUMED
            }
        }

        // Category Badge Selector using custom themed flyout
        if (tagBadge != null) {
            tagBadge.text = currentTag
            ThemeManager.applyCategoryBadgeStyle(this, tagBadge)
            tagBadge.addApplePressEffect()
            tagBadge.setOnClickListener {
                val savedTags = prefs.getStringSet("custom_tags", setOf("All", "Work", "Personal")) ?: setOf("All", "Work", "Personal")
                val tagList = savedTags.filter { it != "All" }.toTypedArray()
                showCategoryFlyout(
                    anchorView = tagBadge,
                    noteObj = null,
                    index = editIndex,
                    jsonArray = null,
                    tagList = tagList
                ) { chosenTag ->
                    currentTag = chosenTag
                }
            }
        }

        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy • hh:mm a", java.util.Locale.getDefault())
        val noteTime = existingNote?.optLong("timestamp", System.currentTimeMillis()) ?: System.currentTimeMillis()
        timestampText?.text = "Edited ${sdf.format(java.util.Date(noteTime))}"

        titleInput?.setText(existingNote?.optString("summary", "") ?: "")
        val initialBody = existingNote?.optString("body", "") ?: ""

        // Checklist Row Builder with Enter-Key Auto-Add
        fun addChecklistRow(text: String = "", isChecked: Boolean = false, requestFocusNow: Boolean = false) {
            if (checklistItemsList == null) return
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            }

            val checkBox = CheckBox(this).apply {
                this.isChecked = isChecked
                buttonTintList = android.content.res.ColorStateList.valueOf(accentColor)
            }

            val itemInput = EditText(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                background = null
                setText(text)
                hint = "List item"
                textSize = 16f
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_NEXT
                setTextColor(if (isChecked) secondaryColor else primaryColor)
                setHintTextColor(secondaryColor)
                paintFlags = if (isChecked) paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            itemInput.setOnEditorActionListener { _, actionId, event ->
                val isEnter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                    addChecklistRow("", false, requestFocusNow = true)
                    true
                } else {
                    false
                }
            }

            checkBox.setOnCheckedChangeListener { _, checked ->
                itemInput.setTextColor(if (checked) secondaryColor else primaryColor)
                itemInput.paintFlags = if (checked) {
                    itemInput.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                } else {
                    itemInput.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                }
            }

            val btnDeleteRow = ImageButton(this).apply {
                layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt())
                setBackgroundResource(android.R.color.transparent)
                setImageResource(R.drawable.ic_clear_circle)
                setColorFilter(secondaryColor)
                setOnClickListener {
                    checklistItemsList.removeView(row)
                }
            }

            row.addView(checkBox)
            row.addView(itemInput)
            row.addView(btnDeleteRow)
            checklistItemsList.addView(row)

            if (requestFocusNow) {
                itemInput.post {
                    itemInput.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(itemInput, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }

        fun serializeChecklistToString(): String {
            if (checklistItemsList == null) return ""
            val lines = mutableListOf<String>()
            for (i in 0 until checklistItemsList.childCount) {
                val row = checklistItemsList.getChildAt(i) as? LinearLayout ?: continue
                val cb = row.getChildAt(0) as? CheckBox ?: continue
                val et = row.getChildAt(1) as? EditText ?: continue
                val text = et.text.toString().trim()
                if (text.isNotEmpty()) {
                    lines.add(if (cb.isChecked) "[x] $text" else "[ ] $text")
                }
            }
            return lines.joinToString("\n")
        }

        fun populateChecklistFromString(raw: String) {
            checklistItemsList?.removeAllViews()
            val lines = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) {
                addChecklistRow("", false, requestFocusNow = true)
            } else {
                lines.forEach { line ->
                    when {
                        line.startsWith("[x] ") -> addChecklistRow(line.removePrefix("[x] "), true)
                        line.startsWith("[ ] ") -> addChecklistRow(line.removePrefix("[ ] "), false)
                        else -> addChecklistRow(line, false)
                    }
                }
            }
        }

        if (isChecklistMode) {
            bodyInput?.visibility = View.GONE
            checklistContainer?.visibility = View.VISIBLE
            populateChecklistFromString(initialBody)
        } else {
            bodyInput?.visibility = View.VISIBLE
            checklistContainer?.visibility = View.GONE
            bodyInput?.setText(initialBody)
        }

        btnAddChecklistItem?.setOnClickListener {
            addChecklistRow("", false, requestFocusNow = true)
        }

        btnToggleChecklist?.addApplePressEffect()
        btnToggleChecklist?.setOnClickListener {
            isChecklistMode = !isChecklistMode
            btnToggleChecklist.setColorFilter(if (isChecklistMode) accentColor else primaryColor)
            if (isChecklistMode) {
                val currentText = bodyInput?.text?.toString() ?: ""
                bodyInput?.visibility = View.GONE
                checklistContainer?.visibility = View.VISIBLE
                populateChecklistFromString(currentText)
            } else {
                val serialized = serializeChecklistToString()
                    .replace("[ ] ", "")
                    .replace("[x] ", "")
                checklistContainer?.visibility = View.GONE
                bodyInput?.visibility = View.VISIBLE
                bodyInput?.setText(serialized)
            }
        }

        // MULTI-IMAGE GALLERY UI HELPER (Never overwrites existing screenshots!)
        fun refreshImagesGalleryUI() {
            if (imagesContainer == null || imagesScroll == null) return
            imagesContainer.removeAllViews()

            val validImages = imagePathsList.filter { it.isNotEmpty() && java.io.File(it).exists() }
            if (validImages.isEmpty()) {
                imagesScroll.visibility = View.GONE
                return
            }

            imagesScroll.visibility = View.VISIBLE
            val cardWidth = if (validImages.size == 1) {
                resources.displayMetrics.widthPixels - (40 * density).toInt()
            } else {
                (220 * density).toInt()
            }
            val cardHeight = (220 * density).toInt()

            validImages.forEach { imgPath ->
                val card = com.google.android.material.card.MaterialCardView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(cardWidth, cardHeight).apply {
                        marginEnd = (12 * density).toInt()
                    }
                    radius = 20f * density
                    cardElevation = 0f
                    setCardBackgroundColor(surfaceVariantColor)
                }

                val rel = RelativeLayout(this).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }

                val imgView = ImageView(this).apply {
                    layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    val bmp = BitmapFactory.decodeFile(imgPath)
                    setImageBitmap(bmp)
                    setOnClickListener { showZoomableImage(imgPath) }
                }

                val btnRemove = ImageButton(this).apply {
                    val btnSize = (32 * density).toInt()
                    layoutParams = RelativeLayout.LayoutParams(btnSize, btnSize).apply {
                        addRule(RelativeLayout.ALIGN_PARENT_TOP)
                        addRule(RelativeLayout.ALIGN_PARENT_END)
                        val m = (10 * density).toInt()
                        setMargins(m, m, m, m)
                    }
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_glass_circle)
                    setImageResource(R.drawable.ic_clear_circle)
                    setColorFilter(Color.WHITE)
                    setOnClickListener {
                        imagePathsList.remove(imgPath)
                        refreshImagesGalleryUI()
                    }
                }

                rel.addView(imgView)
                rel.addView(btnRemove)
                card.addView(rel)
                imagesContainer.addView(card)
            }
        }
        refreshImagesGalleryUI()

        btnAddImage?.addApplePressEffect()
        btnAddImage?.setOnClickListener {
            pendingImagePickedCallback = { savedPath ->
                // Appends new image to the list without removing existing screenshot!
                imagePathsList.add(savedPath)
                refreshImagesGalleryUI()
            }
            pickImageLauncher.launch("image/*")
        }

        // MULTI-AUDIO RECORDINGS STACK UI HELPER (Never overwrites existing voice memos!)
        fun refreshAudiosListUI() {
            if (audiosContainer == null) return
            audiosContainer.removeAllViews()

            val validAudios = audioPathsList.filter { it.isNotEmpty() && java.io.File(it).exists() }
            if (validAudios.isEmpty()) {
                audiosContainer.visibility = View.GONE
                return
            }

            audiosContainer.visibility = View.VISIBLE
            validAudios.forEachIndexed { idx, audioPath ->
                val pillCard = com.google.android.material.card.MaterialCardView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (50 * density).toInt()
                    ).apply {
                        bottomMargin = (8 * density).toInt()
                    }
                    radius = 16f * density
                    cardElevation = 0f
                    setCardBackgroundColor(surfaceVariantColor)
                }

                val row = LinearLayout(this).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val padH = (14 * density).toInt()
                    setPadding(padH, 0, padH, 0)
                }

                val btnPlay = ImageButton(this).apply {
                    val s = (34 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s)
                    setBackgroundResource(android.R.color.transparent)
                    setImageResource(R.drawable.ic_voice_note)
                    setColorFilter(accentColor)
                    setOnClickListener { playAudioNote(audioPath) }
                }

                val tvLabel = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (10 * density).toInt()
                    }
                    text = "Voice Memo ${idx + 1} • Tap to play"
                    textSize = 13f
                    typeface = getAppTypeface()
                    setTextColor(primaryColor)
                    setOnClickListener { playAudioNote(audioPath) }
                }

                val btnDelAudio = ImageButton(this).apply {
                    val s = (30 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s)
                    setBackgroundResource(android.R.color.transparent)
                    setImageResource(R.drawable.ic_clear_circle)
                    setColorFilter(secondaryColor)
                    setOnClickListener {
                        audioPathsList.remove(audioPath)
                        refreshAudiosListUI()
                    }
                }

                row.addView(btnPlay)
                row.addView(tvLabel)
                row.addView(btnDelAudio)
                pillCard.addView(row)
                audiosContainer.addView(pillCard)
            }
        }
        refreshAudiosListUI()

        fun stopEditorRecordingIfNeeded() {
            if (!isEditorRecording) return
            isEditorRecording = false
            try {
                editorRecorder?.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    editorRecorder?.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                editorRecorder = null
            }
            activeRecordingTempPath?.let { path ->
                if (java.io.File(path).exists() && java.io.File(path).length() > 0L) {
                    audioPathsList.add(path)
                }
            }
            activeRecordingTempPath = null
            btnRecordAudio?.setImageResource(R.drawable.ic_mic)
            btnRecordAudio?.setColorFilter(primaryColor)
            refreshAudiosListUI()
        }

        btnRecordAudio?.addApplePressEffect()
        btnRecordAudio?.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
                return@setOnClickListener
            }

            if (!isEditorRecording) {
                val outFile = java.io.File(filesDir, "audio_${System.currentTimeMillis()}.mp4")
                activeRecordingTempPath = outFile.absolutePath
                try {
                    editorRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        android.media.MediaRecorder(this)
                    } else {
                        @Suppress("DEPRECATION")
                        android.media.MediaRecorder()
                    }).apply {
                        setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                        setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                        setOutputFile(activeRecordingTempPath)
                        prepare()
                        start()
                    }
                    isEditorRecording = true
                    // Swap to ic_stop_record icon while recording!
                    btnRecordAudio.setImageResource(R.drawable.ic_stop_record)
                    btnRecordAudio.clearColorFilter()
                    Toast.makeText(this, "Recording voice memo...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    activeRecordingTempPath = null
                    Toast.makeText(this, "Could not start recording", Toast.LENGTH_SHORT).show()
                }
            } else {
                stopEditorRecordingIfNeeded()
                Toast.makeText(this, "Voice memo added", Toast.LENGTH_SHORT).show()
            }
        }

        // Save Helper Function (Auto-saves all images & voice memos on Back or Save)
        fun saveAndClose() {
            if (hasSaved || isExplicitlyDeleted) return
            hasSaved = true
            stopEditorRecordingIfNeeded()

            val finalTitle = titleInput?.text?.toString()?.trim() ?: ""
            val finalBody = if (isChecklistMode) {
                serializeChecklistToString()
            } else {
                bodyInput?.text?.toString()?.trim() ?: ""
            }

            val hasMedia = imagePathsList.isNotEmpty() || audioPathsList.isNotEmpty()
            if (finalTitle.isEmpty() && finalBody.isEmpty() && !hasMedia) {
                dialog.dismiss()
                return
            }

            val resolvedTitle = when {
                finalTitle.isNotEmpty() -> finalTitle
                finalBody.isNotEmpty() -> finalBody.lineSequence().first().replace("[ ] ", "").replace("[x] ", "").take(40)
                else -> "Memory - ${sdf.format(java.util.Date(System.currentTimeMillis()))}"
            }

            val imgJsonArray = JSONArray().apply { imagePathsList.forEach { put(it) } }
            val audJsonArray = JSONArray().apply { audioPathsList.forEach { put(it) } }

            loadDataToMemory()
            val noteObj = JSONObject().apply {
                put("summary", resolvedTitle)
                put("body", finalBody)
                put("is_checklist", isChecklistMode)
                put("is_pinned", isPinned)
                put("card_color", currentCardColorHex)
                put("tag", currentTag)
                // Save full arrays AND keep first item in legacy fields for 100% compatibility
                put("image_paths", imgJsonArray)
                put("audio_paths", audJsonArray)
                put("image_path", imagePathsList.firstOrNull() ?: "")
                put("audio_path", audioPathsList.firstOrNull() ?: "")
                put("timestamp", System.currentTimeMillis())
            }

            if (editIndex in 0 until masterNotesArray.length()) {
                masterNotesArray.put(editIndex, noteObj)
            } else {
                masterNotesArray.put(noteObj)
            }

            prefs.edit().putString("notes_array", masterNotesArray.toString()).apply()
            refreshUI(animateEntrance = editIndex == -1)
            dialog.dismiss()
        }

        btnBack?.addApplePressEffect()
        btnBack?.setOnClickListener { saveAndClose() }

        btnSave?.addApplePressEffect()
        btnSave?.setOnClickListener {
            saveAndClose()
            Toast.makeText(this, "Note Saved", Toast.LENGTH_SHORT).show()
        }

        btnDelete?.addApplePressEffect()
        btnDelete?.setOnClickListener {
            isExplicitlyDeleted = true
            stopEditorRecordingIfNeeded()
            dialog.dismiss()
            if (editIndex in 0 until masterNotesArray.length()) {
                showDeleteNoteDialog(editIndex, masterNotesArray)
            }
        }

        dialog.onBackPressedDispatcher.addCallback(dialog, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                saveAndClose()
            }
        })

        dialog.setOnDismissListener {
            if (!hasSaved && !isExplicitlyDeleted) {
                saveAndClose()
            }
        }

        dialog.show()

        if (editIndex == -1) {
            bodyInput?.post {
                bodyInput.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(bodyInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun showDeleteNoteDialog(index: Int, jsonArray: JSONArray) {
        val dialog = ComponentDialog(this, ThemeManager.getDialogThemeRes(this))
        dialog.setContentView(R.layout.dialog_glass)
        applyDialogSurface(dialog)

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.findViewById<TextView>(R.id.dialogTitle)?.text = "Delete Note"
        dialog.findViewById<TextView>(R.id.dialogMessage)?.text = "Are you sure you want to permanently delete this memory?"

        val btnNegative = dialog.findViewById<TextView>(R.id.btnNegative)
        val btnPositive = dialog.findViewById<TextView>(R.id.btnPositive)

        btnNegative?.addApplePressEffect()
        btnPositive?.addApplePressEffect()

        btnNegative?.text = "CANCEL"
        btnNegative?.setOnClickListener { dialog.dismiss() }

        btnPositive?.text = "DELETE"
        btnPositive?.setOnClickListener {
            jsonArray.remove(index)
            prefs.edit().putString("notes_array", jsonArray.toString()).apply()
            loadDataToMemory()
            refreshUI(animateEntrance = false)
            Toast.makeText(this, "Note Deleted", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }
}