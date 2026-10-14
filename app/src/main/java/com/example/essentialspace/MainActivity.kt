package com.example.essentialspace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
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

    private lateinit var prefs: android.content.SharedPreferences
    private var searchQuery = ""
    private var currentFilter = "All"

    // High-performance LruCache for decoded note image thumbnails & audio player management
    private val thumbnailCache = object : androidx.collection.LruCache<String, android.graphics.Bitmap>(30) {}
    private var activeMediaPlayer: MediaPlayer? = null

    // Your lightning-fast in-memory database
    private var masterNotesArray = JSONArray()

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
            val openIndex = it.getIntExtra("open_note_index", -1)
            if (openIndex != -1) {
                it.removeExtra("open_note_index")
                window.decorView.postDelayed({
                    loadDataToMemory()
                    val noteObj = masterNotesArray.optJSONObject(openIndex)
                    if (noteObj != null) {
                        showFullScreenNote(
                            noteObj.getString("summary"),
                            noteObj.optString("tag", "Personal"),
                            noteObj.optString("image_path", ""),
                            noteObj.optString("audio_path", ""),
                            openIndex,
                            masterNotesArray
                        )
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
                    v.pivotX = event.x.coerceIn(0f, v.width.toFloat())
                    v.pivotY = event.y.coerceIn(0f, v.height.toFloat())
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

        val sidebarPanel = dialog.findViewById<View>(R.id.sidebarPanel)
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

        val primaryColor = ThemeManager.getPrimaryColor(this)
        dialog.findViewById<ImageView>(R.id.menuLogo)?.apply {
            setImageResource(getAppIconRes())
            setColorFilter(primaryColor)
        }
        btnCloseSideBar?.setColorFilter(primaryColor)
        btnSettingsIcon?.setColorFilter(primaryColor)
        itemHowToUse?.setTextColor(primaryColor)
        itemAppearance?.setTextColor(primaryColor)

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

        btnSettingsIcon?.setOnClickListener {
            showSettingsPopup()
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
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke((3 * density).toInt(), color)
                }
                visibility = View.INVISIBLE
            }

            val circleView = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(innerSize, innerSize).apply {
                    gravity = Gravity.CENTER
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
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

        val tutorialText = """
            Setup:
            1. Open Navigation Menu > Settings.
            2. Grant Accessibility to detect the Volume Down shortcut.
            3. Grant Microphone & Overlay permissions for voice notes and previews.
            
            Usage:
            • Press & Hold 'Volume Down': Records your voice note while holding.
            • Release 'Volume Down': Takes a screenshot and saves your voice note and screen capture together.
            • Tap '+' (top right) to create a text note directly inside the app.
            • Tap the 3-dots icon on any note card to rename or delete.
            • Tap the play button to hear your voice note.
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

        val font = getAppTypeface()
        val accentColor = getAccentColor()
        val themeKey = ThemeManager.getCurrentThemeKey(this)
        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy • hh:mm a", java.util.Locale.getDefault())

        val cardBgColor = when (themeKey) {
            ThemeManager.THEME_LIGHT -> Color.WHITE
            ThemeManager.THEME_GLASS -> Color.parseColor("#B3161920")
            else -> Color.parseColor("#121212")
        }
        val titleColor = if (themeKey == ThemeManager.THEME_LIGHT) Color.parseColor("#121316") else Color.WHITE
        val dateColor = if (themeKey == ThemeManager.THEME_LIGHT) Color.parseColor("#5C6270") else Color.parseColor("#98A2B3")

        lifecycleScope.launch {
            for (i in jsonArray.length() - 1 downTo 0) {
                val noteObj = jsonArray.getJSONObject(i)
                val summary = noteObj.getString("summary")
                val tags = noteObj.optString("tag", "Personal")

                if (currentFilter != "All" && currentFilter != tags) continue
                if (searchQuery.isNotEmpty() && !summary.lowercase().contains(searchQuery.lowercase())) continue

                val view = inflater.inflate(R.layout.note_item, container, false)
                (view as? com.google.android.material.card.MaterialCardView)?.setCardBackgroundColor(cardBgColor)

                val itemTitle = view.findViewById<TextView>(R.id.itemTitle)
                itemTitle.text = summary
                itemTitle.typeface = font
                itemTitle.setTextColor(titleColor)

                val timestamp = noteObj.optLong("timestamp", 0L)
                val dateView = view.findViewById<TextView>(R.id.itemDate)
                dateView.typeface = font
                dateView.setTextColor(dateColor)
                if (timestamp > 0L) {
                    dateView.text = sdf.format(java.util.Date(timestamp))
                } else {
                    dateView.text = "Just now"
                }

                val imgPath = noteObj.optString("image_path", "")
                val imageView = view.findViewById<ImageView>(R.id.itemImage)
                val imageContainer = view.findViewById<View>(R.id.imageContainer)

                if (imgPath.isNotEmpty() && java.io.File(imgPath).exists()) {
                    imageContainer?.visibility = View.VISIBLE
                    val cachedBitmap = thumbnailCache.get(imgPath)
                    if (cachedBitmap != null && !cachedBitmap.isRecycled) {
                        imageView.setImageBitmap(cachedBitmap)
                    } else {
                        launch(Dispatchers.IO) {
                            val options = BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                                BitmapFactory.decodeFile(imgPath, this)
                                inSampleSize = calculateInSampleSize(this, 400, 300)
                                inJustDecodeBounds = false
                            }
                            val bitmap = BitmapFactory.decodeFile(imgPath, options)
                            if (bitmap != null) {
                                thumbnailCache.put(imgPath, bitmap)
                                withContext(Dispatchers.Main) {
                                    imageView.setImageBitmap(bitmap)
                                }
                            }
                        }
                    }
                } else {
                    imageContainer?.visibility = View.GONE
                }

                val audioPath = noteObj.optString("audio_path", "")
                val playBtn = view.findViewById<ImageButton>(R.id.itemPlay)
                playBtn.setColorFilter(accentColor)

                if (audioPath.isNotEmpty() && java.io.File(audioPath).exists()) {
                    playBtn.visibility = View.VISIBLE
                    playBtn.setOnClickListener {
                        playAudioNote(audioPath)
                    }
                } else {
                    playBtn.visibility = View.GONE
                }

                val tagView = view.findViewById<TextView>(R.id.itemTag)
                tagView.text = tags
                tagView.typeface = font
                ThemeManager.applyCategoryBadgeStyle(this@MainActivity, tagView)
                tagView.setOnClickListener {
                    val savedTags = prefs.getStringSet("custom_tags", setOf("All", "Work", "Personal"))?.toMutableSet() ?: mutableSetOf()
                    val tagList = savedTags.filter { it != "All" }.toTypedArray()
                    showCategoryFlyout(tagView, noteObj, i, jsonArray, tagList)
                }

                val itemMenu = view.findViewById<ImageButton>(R.id.itemMenu)
                itemMenu?.setColorFilter(titleColor)
                itemMenu?.addApplePressEffect()
                itemMenu?.setOnClickListener {
                    showNoteOptionsFlyout(itemMenu, noteObj, i, jsonArray)
                }

                view.setOnClickListener {
                    showFullScreenNote(summary, tags, imgPath, audioPath, i, jsonArray)
                }

                view.addApplePressEffect()

                if (animateEntrance) {
                    view.translationY = 120f
                    view.scaleX = 0.95f
                    view.scaleY = 0.95f
                    view.alpha = 0f

                    val delayIndex = jsonArray.length() - 1 - i

                    view.animate()
                        .translationY(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setStartDelay((delayIndex * 45).toLong())
                        .setDuration(450)
                        .setInterpolator(appleOvershoot)
                        .start()
                }

                container.addView(view)
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

    private fun showCategoryFlyout(anchorView: View, noteObj: JSONObject, index: Int, jsonArray: JSONArray, tagList: Array<String>) {
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
            (anchorView as TextView).text = selectedTag

            try {
                noteObj.put("tag", selectedTag)
                prefs.edit().putString("notes_array", jsonArray.toString()).apply()
                loadDataToMemory()
                refreshUI(animateEntrance = false)
            } catch (e: Exception) {
                e.printStackTrace()
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

        item1.text = "Rename"
        item1.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_edit, 0, 0, 0)
        item1.compoundDrawablePadding = (12 * resources.displayMetrics.density).toInt()

        item2.text = "Delete"
        item2.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_delete, 0, 0, 0)
        item2.compoundDrawablePadding = (12 * resources.displayMetrics.density).toInt()

        item3.visibility = View.GONE

        // Apply surface and text/drawable colors AFTER setting drawables so icons get tinted properly!
        ThemeManager.applyPopupSurfaceStyle(this, popupView)

        item1.setOnClickListener {
            popupWindow.dismiss()
            showRenameNoteDialog(noteObj, index, jsonArray)
        }

        item2.setOnClickListener {
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

        dialog.findViewById<TextView>(R.id.dialogTitle)?.text = "Create Note"

        val input = dialog.findViewById<EditText>(R.id.dialogInput)
        input?.hint = "Write your note here..."
        input?.setText("")

        val btnNegative = dialog.findViewById<TextView>(R.id.btnNegative)
        val btnPositive = dialog.findViewById<TextView>(R.id.btnPositive)

        btnNegative?.addApplePressEffect()
        btnPositive?.addApplePressEffect()

        btnNegative?.text = "CANCEL"
        btnNegative?.setOnClickListener { dialog.dismiss() }

        btnPositive?.text = "SAVE"
        btnPositive?.setOnClickListener {
            val noteText = input?.text?.toString()?.trim() ?: ""
            if (noteText.isNotEmpty()) {
                saveNewNote(summary = noteText, tag = if (currentFilter != "All") currentFilter else "Personal")
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

    private fun saveNewNote(summary: String, tag: String = "Personal", imagePath: String = "", audioPath: String = "") {
        loadDataToMemory()
        val timestamp = System.currentTimeMillis()
        val newNote = JSONObject().apply {
            put("summary", summary)
            put("tag", tag)
            put("image_path", imagePath)
            put("audio_path", audioPath)
            put("timestamp", timestamp)
        }
        masterNotesArray.put(newNote)
        prefs.edit().putString("notes_array", masterNotesArray.toString()).apply()
        refreshUI()
        Toast.makeText(this, "Note Saved", Toast.LENGTH_SHORT).show()
    }

    private fun showRenameNoteDialog(noteObj: JSONObject, index: Int, jsonArray: JSONArray) {
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

        dialog.findViewById<TextView>(R.id.dialogTitle)?.text = "Rename Note"

        val input = dialog.findViewById<EditText>(R.id.dialogInput)
        val currentName = noteObj.optString("summary", "")
        input?.setText(currentName)
        input?.setSelection(currentName.length)

        val btnNegative = dialog.findViewById<TextView>(R.id.btnNegative)
        val btnPositive = dialog.findViewById<TextView>(R.id.btnPositive)

        btnNegative?.addApplePressEffect()
        btnPositive?.addApplePressEffect()

        btnNegative?.text = "CANCEL"
        btnNegative?.setOnClickListener { dialog.dismiss() }

        btnPositive?.text = "SAVE"
        btnPositive?.setOnClickListener {
            val newTitle = input?.text?.toString()?.trim() ?: ""
            if (newTitle.isNotEmpty()) {
                noteObj.put("summary", newTitle)
                jsonArray.put(index, noteObj)
                prefs.edit().putString("notes_array", jsonArray.toString()).apply()
                loadDataToMemory()
                refreshUI(animateEntrance = false)
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

    private fun showFullScreenNote(title: String, tag: String, imgPath: String, audioPath: String, index: Int, jsonArray: JSONArray) {
        val themeKey = ThemeManager.getCurrentThemeKey(this)
        val themeRes = ThemeManager.getThemeDialogRes(this)
        val dialog = ComponentDialog(this, themeRes)
        dialog.setContentView(R.layout.dialog_fullscreen)

        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)

        dialog.window?.let { window ->
            window.setWindowAnimations(R.style.IosModalAnimation)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            if (themeKey == ThemeManager.THEME_GLASS) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#80000000")))
            }

            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

            WindowCompat.setDecorFitsSystemWindows(window, false)
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Intercept Android 13–16 Predictive Back gestures cleanly inside ComponentDialog so MainActivity never closes!
        dialog.onBackPressedDispatcher.addCallback(dialog, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val fullTitle = dialog.findViewById<EditText>(R.id.fsTitle)
                if (fullTitle?.hasFocus() == true) {
                    fullTitle.clearFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(fullTitle.windowToken, 0)
                } else {
                    dialog.dismiss()
                }
            }
        })

        try {
            val fullImage = dialog.findViewById<ImageView>(R.id.fsImage)
            val fullTitle = dialog.findViewById<EditText>(R.id.fsTitle)
            val fullTag = dialog.findViewById<TextView>(R.id.fsTag)
            val fullPlayBtn = dialog.findViewById<ImageButton>(R.id.fsPlay)
            val btnDelete = dialog.findViewById<ImageButton>(R.id.fsDelete)
            val btnClose = dialog.findViewById<ImageButton>(R.id.fsClose)
            val bottomStack = dialog.findViewById<View>(R.id.fullscreenBottomStack)

            bottomStack?.translationY = 80f
            bottomStack?.alpha = 0f
            bottomStack?.animate()
                ?.translationY(0f)
                ?.alpha(1f)
                ?.setStartDelay(100)
                ?.setDuration(350)
                ?.setInterpolator(appleOvershoot)
                ?.start()

            bottomStack?.let { stack ->
                ViewCompat.setOnApplyWindowInsetsListener(stack) { v, insets ->
                    val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                    val bottomMargin = Math.max(imeHeight, sysBars)

                    val params = v.layoutParams as ViewGroup.MarginLayoutParams
                    params.bottomMargin = bottomMargin + (16 * resources.displayMetrics.density).toInt()
                    v.layoutParams = params

                    insets
                }
            }

            fullPlayBtn?.addApplePressEffect()
            btnDelete?.addApplePressEffect()
            btnClose?.addApplePressEffect()

            val font = getAppTypeface()
            val accentColor = getAccentColor()
            val primaryTextColor = ThemeManager.getPrimaryColor(this)

            fullTitle?.typeface = font
            fullTitle?.setTextColor(primaryTextColor)
            fullTag?.typeface = font
            if (fullTag != null) {
                ThemeManager.applyCategoryBadgeStyle(this, fullTag)
            }
            fullPlayBtn?.setColorFilter(accentColor)
            btnDelete?.setColorFilter(primaryTextColor)
            btnClose?.setColorFilter(primaryTextColor)

            fullTitle?.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(fullTitle, InputMethodManager.SHOW_IMPLICIT)
                }
            }

            btnClose?.setOnClickListener { dialog.dismiss() }

            dialog.setOnDismissListener {
                val newName = fullTitle?.text?.toString()?.trim() ?: ""
                if (newName.isNotEmpty() && newName != title) {
                    try {
                        val noteObj = jsonArray.getJSONObject(index)
                        noteObj.put("summary", newName)
                        prefs.edit().putString("notes_array", jsonArray.toString()).apply()
                        loadDataToMemory()
                        refreshUI(animateEntrance = false)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            fullTitle?.setText(title)
            fullTag?.text = tag

            val screenshotWindowCard = dialog.findViewById<View>(R.id.screenshotWindowCard)

            if (imgPath.isNotEmpty() && java.io.File(imgPath).exists()) {
                screenshotWindowCard?.visibility = View.VISIBLE
                val bitmap = BitmapFactory.decodeFile(imgPath)
                fullImage?.setImageBitmap(bitmap)

                fullImage?.setOnClickListener {
                    if (fullTitle?.hasFocus() == true) {
                        dialog.window?.let { win ->
                            androidx.core.view.WindowInsetsControllerCompat(win, fullTitle).hide(WindowInsetsCompat.Type.ime())
                        }
                        fullTitle.clearFocus()
                    } else {
                        showZoomableImage(imgPath)
                    }
                }
            }

            fullPlayBtn?.setOnClickListener {
                if (audioPath.isNotEmpty() && java.io.File(audioPath).exists()) {
                    playAudioNote(audioPath)
                } else {
                    Toast.makeText(this, "No Audio Attached", Toast.LENGTH_SHORT).show()
                }
            }

            fullTitle?.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    val newName = fullTitle.text.toString().trim()
                    if (newName.isNotEmpty()) {
                        try {
                            val noteObj = jsonArray.getJSONObject(index)
                            noteObj.put("summary", newName)

                            prefs.edit().putString("notes_array", jsonArray.toString()).apply()
                            loadDataToMemory()
                            refreshUI(animateEntrance = false)

                            Toast.makeText(this, "Name updated", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    fullTitle.clearFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(fullTitle.windowToken, 0)
                    true
                } else {
                    false
                }
            }

            val savedTags = prefs.getStringSet("custom_tags", setOf("All", "Work", "Personal")) ?: setOf("All", "Work", "Personal")
            val dynamicTagArray = savedTags.filter { it != "All" }.toTypedArray()

            fullTag?.setOnClickListener { view ->
                try {
                    val noteObj = jsonArray.getJSONObject(index)

                    showCategoryFlyout(
                        anchorView = view,
                        noteObj = noteObj,
                        index = index,
                        jsonArray = jsonArray,
                        tagList = dynamicTagArray
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            btnDelete?.setOnClickListener {
                dialog.dismiss()
                showDeleteNoteDialog(index, jsonArray)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        dialog.show()
    }
}