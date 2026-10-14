package com.example.essentialspace

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
        return getPrefs(context).getString(KEY_THEME, THEME_DARK) ?: THEME_DARK
    }

    fun initActivityTheme(activity: AppCompatActivity) {
        val themeKey = getCurrentThemeKey(activity)
        val mode = if (themeKey == THEME_LIGHT) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        AppCompatDelegate.setDefaultNightMode(mode)

        when (themeKey) {
            THEME_BLACK -> {
                activity.setTheme(R.style.Theme_EssentialSpace_PitchBlack)
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    activity.window.setBackgroundBlurRadius(0)
                }
            }
            THEME_GLASS -> {
                activity.setTheme(R.style.Theme_EssentialSpace_Glass)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    activity.window.setBackgroundBlurRadius(60)
                }
            }
            else -> {
                activity.setTheme(R.style.Theme_EssentialSpace)
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

        // Instant clean activity restart with zero animation delay
        val intent = activity.intent
        activity.finish()
        activity.startActivity(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        }
    }

    fun getPrimaryColor(context: Context): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        return typedValue.data
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
            THEME_BLACK -> R.style.Theme_EssentialSpace_PitchBlack_FullscreenMenu
            THEME_GLASS -> R.style.Theme_EssentialSpace_Glass_FullscreenMenu
            else -> R.style.Theme_EssentialSpace_FullscreenMenu
        }
    }

    fun getContrastingTextColor(backgroundColor: Int): Int {
        val luminance = ColorUtils.calculateLuminance(backgroundColor)
        return if (luminance > 0.55) Color.BLACK else Color.WHITE
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
                bgDrawable.setColor(Color.BLACK) // Solid pitch black #000000
                textColor = Color.WHITE // Solid pure white #FFFFFF
            }
            THEME_GLASS -> {
                bgDrawable.setColor(Color.parseColor("#26FFFFFF")) // Translucent frosted white
                bgDrawable.setStroke((1 * density).toInt(), Color.parseColor("#4DFFFFFF")) // 1dp 30% stroke
                textColor = Color.WHITE // Solid pure white #FFFFFF
            }
            else -> { // THEME_DARK and THEME_BLACK
                bgDrawable.setColor(Color.WHITE) // Solid pure white #FFFFFF
                textColor = Color.BLACK // Solid pitch black #000000
            }
        }

        tagView.background = bgDrawable
        tagView.setTextColor(textColor)
    }

    @android.annotation.SuppressLint("ResourceType")
    fun applyThemeColorsToMain(activity: Activity) {
        val themeKey = getCurrentThemeKey(activity)
        val accent = getAccentColor(activity)

        val ta = activity.obtainStyledAttributes(intArrayOf(
            android.R.attr.colorBackground,
            android.R.attr.textColorPrimary,
            android.R.attr.textColorSecondary,
            com.google.android.material.R.attr.colorSurfaceVariant
        ))
        val colorBackground = ta.getColor(0, Color.BLACK)
        val textColorPrimary = ta.getColor(1, Color.WHITE)
        val textColorSecondary = ta.getColor(2, Color.GRAY)
        val colorSurfaceVariant = ta.getColor(3, Color.DKGRAY)
        ta.recycle()

        // Configure Light/Dark Status Bar Icons
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = (themeKey == THEME_LIGHT)

        // Root View Background
        activity.findViewById<View>(android.R.id.content)?.rootView?.setBackgroundColor(colorBackground)

        // Title and Icons Tint
        activity.findViewById<TextView>(R.id.tvHome)?.setTextColor(textColorPrimary)
        activity.findViewById<ImageButton>(R.id.btnMenu)?.setColorFilter(textColorPrimary)
        activity.findViewById<ImageButton>(R.id.btnCreateNote)?.setColorFilter(textColorPrimary)

        // Dynamic Gradient Overlays matching colorBackground
        val topFade = activity.findViewById<View>(R.id.topFadeOverlay)
        val bottomFade = activity.findViewById<View>(R.id.bottomFadeOverlay)

        val startAlpha = if (themeKey == THEME_GLASS) 0x66 else 0xCC
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