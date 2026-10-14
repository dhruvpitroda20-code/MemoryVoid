package com.example.essentialspace

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

object ThemeManager {

    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val THEME_BLACK = "black"
    const val THEME_GLASS = "glass"

    private const val PREFS_NAME = "EssentialNotes"
    private const val KEY_THEME = "theme_pref"
    private const val KEY_ACCENT = "accent_color"

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getCurrentThemeKey(context: Context): String {
        val saved = getPrefs(context).getString(KEY_THEME, THEME_BLACK) ?: THEME_BLACK
        return if (saved == THEME_DARK) THEME_BLACK else saved
    }

    fun initActivityTheme(activity: AppCompatActivity) {
        val themeKey = getCurrentThemeKey(activity)
        val mode = if (themeKey == THEME_LIGHT) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        AppCompatDelegate.setDefaultNightMode(mode)

        when (themeKey) {
            THEME_LIGHT -> {
                activity.setTheme(R.style.Theme_EssentialSpace)
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    activity.window.setBackgroundBlurRadius(0)
                }
            }
            THEME_GLASS -> {
                activity.setTheme(R.style.Theme_EssentialSpace_Glass)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                activity.window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#66000000")))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    activity.window.setBackgroundBlurRadius(60)
                }
            }
            else -> { // THEME_BLACK & THEME_DARK -> Pure Pitch Black (#000000)
                activity.setTheme(R.style.Theme_EssentialSpace_PitchBlack)
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    activity.window.setBackgroundBlurRadius(0)
                }
            }
        }
    }

    fun applyTheme(activity: Activity, themeKey: String) {
        getPrefs(activity).edit().putString(KEY_THEME, themeKey).commit()
        val mode = if (themeKey == THEME_LIGHT) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        AppCompatDelegate.setDefaultNightMode(mode)

        // Instant clean activity restart with zero animation delay and clean intent
        val cleanIntent = android.content.Intent(activity, activity::class.java)
        activity.finish()
        activity.startActivity(cleanIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        }
    }

    fun getPrimaryColor(context: Context): Int {
        return when (getCurrentThemeKey(context)) {
            THEME_LIGHT -> Color.parseColor("#121316")
            else -> Color.WHITE
        }
    }

    fun getAccentColor(context: Context): Int {
        val primary = getPrimaryColor(context)
        return getPrefs(context).getInt(KEY_ACCENT, primary)
    }

    fun setAccentColor(context: Context, color: Int?) {
        val prefs = getPrefs(context)
        if (color == null || color == getPrimaryColor(context)) {
            prefs.edit().remove(KEY_ACCENT).commit()
        } else {
            prefs.edit().putInt(KEY_ACCENT, color).commit()
        }
    }

    fun getThemeDialogRes(context: Context): Int {
        return when (getCurrentThemeKey(context)) {
            THEME_LIGHT -> R.style.Theme_EssentialSpace_FullscreenMenu
            THEME_GLASS -> R.style.Theme_EssentialSpace_Glass_FullscreenMenu
            else -> R.style.Theme_EssentialSpace_PitchBlack_FullscreenMenu
        }
    }

    fun getDialogThemeRes(context: Context): Int {
        return when (getCurrentThemeKey(context)) {
            THEME_LIGHT -> R.style.ThemedDialog_Light
            THEME_GLASS -> R.style.ThemedDialog_Glass
            else -> R.style.ThemedDialog_PitchBlack
        }
    }

    fun getContrastingTextColor(backgroundColor: Int): Int {
        val luminance = ColorUtils.calculateLuminance(backgroundColor)
        return if (luminance > 0.55) Color.BLACK else Color.WHITE
    }

    fun applyPopupSurfaceStyle(context: Context, view: View) {
        val themeKey = getCurrentThemeKey(context)
        val density = context.resources.displayMetrics.density
        val cornerRadius = 24f * density

        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
        }

        val primaryText: Int
        val secondaryText: Int

        when (themeKey) {
            THEME_LIGHT -> {
                bgDrawable.setColor(Color.parseColor("#FFFFFF"))
                bgDrawable.setStroke((1 * density).toInt(), Color.parseColor("#D0D5DD"))
                primaryText = Color.parseColor("#121316")
                secondaryText = Color.parseColor("#5C6270")
            }
            THEME_GLASS -> {
                bgDrawable.setColor(Color.parseColor("#CC161920"))
                bgDrawable.setStroke((1 * density).toInt(), Color.parseColor("#4DFFFFFF"))
                primaryText = Color.WHITE
                secondaryText = Color.parseColor("#D0D5DD")
            }
            else -> { // THEME_BLACK
                bgDrawable.setColor(Color.parseColor("#141414"))
                bgDrawable.setStroke((1 * density).toInt(), Color.parseColor("#2A2A2A"))
                primaryText = Color.WHITE
                secondaryText = Color.parseColor("#98A2B3")
            }
        }

        view.background = bgDrawable
        tintDialogChildren(view, primaryText, secondaryText)
    }

    private fun tintDialogChildren(view: View, primaryText: Int, secondaryText: Int) {
        when (view) {
            is SwitchCompat -> {
                // Keep custom switch styling intact
            }
            is EditText -> {
                view.setTextColor(primaryText)
                view.setHintTextColor(secondaryText)
            }
            is TextView -> {
                val id = view.id
                if (id == R.id.btnNegative || id == R.id.dialogMessage || id == R.id.btnWalkthroughSettings) {
                    view.setTextColor(secondaryText)
                } else if (id == R.id.btnWalkthroughGetStarted) {
                    // Keep contrast on pill button
                } else {
                    view.setTextColor(primaryText)
                }
                view.compoundDrawablesRelative.forEach { it?.setTint(primaryText) }
                view.compoundDrawables.forEach { it?.setTint(primaryText) }
            }
            is ImageView -> {
                if (view.id == R.id.btnCloseAppearance) {
                    view.setColorFilter(secondaryText)
                } else if (view.tag == null) {
                    // Only tint icons that aren't dynamic accent color checkmarks
                    view.setColorFilter(primaryText)
                }
            }
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    tintDialogChildren(view.getChildAt(i), primaryText, secondaryText)
                }
            }
        }
    }

    fun applyCategoryBadgeStyle(context: Context, tagView: TextView) {
        val themeKey = getCurrentThemeKey(context)
        val density = context.resources.displayMetrics.density
        val cornerRadius = 100f * density

        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
        }

        val textColor: Int

        when (themeKey) {
            THEME_LIGHT -> {
                bgDrawable.setColor(Color.BLACK)
                textColor = Color.WHITE
            }
            THEME_GLASS -> {
                bgDrawable.setColor(Color.parseColor("#26FFFFFF"))
                bgDrawable.setStroke((1 * density).toInt(), Color.parseColor("#4DFFFFFF"))
                textColor = Color.WHITE
            }
            else -> {
                bgDrawable.setColor(Color.WHITE)
                textColor = Color.BLACK
            }
        }

        tagView.background = bgDrawable
        tagView.setTextColor(textColor)
    }

    @android.annotation.SuppressLint("ResourceType")
    fun applyThemeColorsToMain(activity: Activity) {
        val themeKey = getCurrentThemeKey(activity)
        val accent = getAccentColor(activity)

        val colorBackground: Int
        val textColorPrimary: Int
        val textColorSecondary: Int
        val colorSurfaceVariant: Int

        when (themeKey) {
            THEME_LIGHT -> {
                colorBackground = Color.parseColor("#F2F4F7")
                textColorPrimary = Color.parseColor("#121316")
                textColorSecondary = Color.parseColor("#5C6270")
                colorSurfaceVariant = Color.parseColor("#E8ECF2")
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                activity.window.setBackgroundDrawable(ColorDrawable(colorBackground))
            }
            THEME_GLASS -> {
                colorBackground = Color.parseColor("#66000000")
                textColorPrimary = Color.WHITE
                textColorSecondary = Color.parseColor("#E6E6E6")
                colorSurfaceVariant = Color.parseColor("#CC12141A")
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                activity.window.setBackgroundDrawable(ColorDrawable(colorBackground))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    activity.window.setBackgroundBlurRadius(60)
                }
            }
            else -> { // THEME_BLACK
                colorBackground = Color.BLACK
                textColorPrimary = Color.parseColor("#F0F2F5")
                textColorSecondary = Color.parseColor("#98A2B3")
                colorSurfaceVariant = Color.parseColor("#121212")
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                activity.window.setBackgroundDrawable(ColorDrawable(colorBackground))
            }
        }

        // Configure Light/Dark Status Bar Icons
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = (themeKey == THEME_LIGHT)

        // Ensure content container & activity_main root don't block live wallpaper in Glass mode
        val contentView = activity.findViewById<ViewGroup>(android.R.id.content)
        contentView?.setBackgroundColor(Color.TRANSPARENT)
        contentView?.getChildAt(0)?.setBackgroundColor(if (themeKey == THEME_GLASS) Color.TRANSPARENT else colorBackground)

        // Title and Icons Tint
        activity.findViewById<TextView>(R.id.tvHome)?.setTextColor(textColorPrimary)
        activity.findViewById<ImageButton>(R.id.btnMenu)?.setColorFilter(textColorPrimary)
        activity.findViewById<ImageButton>(R.id.btnCreateNote)?.setColorFilter(textColorPrimary)

        // Dynamic Gradient Overlays matching colorBackground
        val topFade = activity.findViewById<View>(R.id.topFadeOverlay)
        val bottomFade = activity.findViewById<View>(R.id.bottomFadeOverlay)

        val startAlpha = if (themeKey == THEME_GLASS) 0x55 else 0xCC
        val fadeColor = Color.argb(startAlpha, Color.red(colorBackground), Color.green(colorBackground), Color.blue(colorBackground))
        val endColor = Color.argb(0, Color.red(colorBackground), Color.green(colorBackground), Color.blue(colorBackground))

        topFade?.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(fadeColor, endColor))
        bottomFade?.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(fadeColor, endColor))

        // Floating Dock Card & Search Button
        val dockCard = activity.findViewById<com.google.android.material.card.MaterialCardView>(R.id.dockCard)
        dockCard?.setCardBackgroundColor(colorSurfaceVariant)
        dockCard?.strokeColor = accent
        dockCard?.strokeWidth = (1 * activity.resources.displayMetrics.density).toInt()

        val btnSearchBubble = activity.findViewById<ImageButton>(R.id.btnSearchBubble)
        btnSearchBubble?.setColorFilter(accent)

        val searchBar = activity.findViewById<EditText>(R.id.searchBar)
        searchBar?.setTextColor(textColorPrimary)
        searchBar?.setHintTextColor(textColorSecondary)

        activity.findViewById<ImageView>(R.id.searchIconExpanded)?.setColorFilter(accent)

        // SwipeRefresh Layout Colors
        val swipe = activity.findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)
        swipe?.setColorSchemeColors(accent)
        swipe?.setProgressBackgroundColorSchemeColor(colorSurfaceVariant)
    }
}