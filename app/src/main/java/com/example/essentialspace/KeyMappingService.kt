package com.example.essentialspace

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class KeyMappingService : AccessibilityService() {

    private var isRecording = false
    private var screenshotAlreadyCapturedOnStart = false
    private var mediaRecorder: MediaRecorder? = null

    private var currentImageFile: File? = null
    private var currentAudioFile: File? = null

    private val handler = Handler(Looper.getMainLooper())
    private var holdRunnable: Runnable? = null
    private var pendingVolumeRunnable: Runnable? = null

    private var lastKeyDownCode = -1
    private var lastKeyUpTime = 0L
    private var activeRecordingKeyCode = -1

    private val assistReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MainActivity.ACTION_START_ASSIST_CAPTURE) {
                if (!isRecording) {
                    val prefs = getSharedPreferences("EssentialNotes", Context.MODE_PRIVATE)
                    val captureScreen = prefs.getBoolean(MainActivity.PREF_CAPTURE_SCREENSHOT, true)
                    handler.postDelayed({
                        startAudioRecording(captureImmediately = captureScreen)
                    }, 250)
                } else {
                    finishRecordingAndSave()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ContextCompat.registerReceiver(
            this,
            assistReceiver,
            IntentFilter(MainActivity.ACTION_START_ASSIST_CAPTURE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(assistReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        try {
            val keyCode = event.keyCode
            if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP) {
                return super.onKeyEvent(event)
            }

            val prefs = getSharedPreferences("EssentialNotes", Context.MODE_PRIVATE)
            val mode = prefs.getString(MainActivity.PREF_SHORTCUT_MODE, MainActivity.MODE_HOLD_VOL_DOWN)
                ?: MainActivity.MODE_HOLD_VOL_DOWN

            if (mode == MainActivity.MODE_ASSIST_POWER) {
                return false
            }

            when (mode) {
                MainActivity.MODE_HOLD_VOL_DOWN -> {
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        return handleSingleKeyHold(event, KeyEvent.KEYCODE_VOLUME_DOWN, captureOnStart = false)
                    }
                    return false
                }
                MainActivity.MODE_SCREENSHOT_COMBO -> {
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        return handleSingleKeyHold(event, KeyEvent.KEYCODE_VOLUME_DOWN, captureOnStart = true)
                    }
                    return false
                }
                MainActivity.MODE_DOUBLE_TAP_HOLD -> {
                    return handleDoubleTapHold(event)
                }
                MainActivity.MODE_SEQ_UP_DOWN -> {
                    return handleSequenceUpThenDown(event)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return super.onKeyEvent(event)
    }

    private fun handleSingleKeyHold(event: KeyEvent, targetKey: Int, captureOnStart: Boolean): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    holdRunnable?.let { handler.removeCallbacks(it) }
                    holdRunnable = Runnable {
                        if (!isRecording) {
                            activeRecordingKeyCode = targetKey
                            val prefs = getSharedPreferences("EssentialNotes", Context.MODE_PRIVATE)
                            val shouldScreenshot = prefs.getBoolean(MainActivity.PREF_CAPTURE_SCREENSHOT, true)
                            startAudioRecording(captureImmediately = captureOnStart && shouldScreenshot)
                        }
                    }
                    handler.postDelayed(holdRunnable!!, 350)
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                holdRunnable?.let { handler.removeCallbacks(it) }
                if (isRecording && activeRecordingKeyCode == targetKey) {
                    activeRecordingKeyCode = -1
                    finishRecordingAndSave()
                } else {
                    adjustVolumeNormally(targetKey)
                }
                return true
            }
        }
        return false
    }

    private fun handleDoubleTapHold(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val now = System.currentTimeMillis()

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    val isSecondTap = (keyCode == lastKeyDownCode) && (now - lastKeyUpTime <= 400L)
                    lastKeyDownCode = keyCode

                    // Cancel any pending single-tap volume adjustment so the volume UI NEVER appears!
                    pendingVolumeRunnable?.let { handler.removeCallbacks(it) }
                    holdRunnable?.let { handler.removeCallbacks(it) }

                    if (isSecondTap) {
                        holdRunnable = Runnable {
                            if (!isRecording) {
                                activeRecordingKeyCode = keyCode
                                val prefs = getSharedPreferences("EssentialNotes", Context.MODE_PRIVATE)
                                val shouldScreenshot = prefs.getBoolean(MainActivity.PREF_CAPTURE_SCREENSHOT, true)
                                startAudioRecording(captureImmediately = false && shouldScreenshot)
                            }
                        }
                        handler.postDelayed(holdRunnable!!, 200)
                    } else {
                        // If user holds on the FIRST tap (wants to hold-to-change volume), adjust volume without recording
                        holdRunnable = Runnable {
                            adjustVolumeNormally(keyCode)
                        }
                        handler.postDelayed(holdRunnable!!, 350)
                    }
                } else if (!isRecording) {
                    // Continued holding on the first tap -> keep stepping volume smoothly
                    adjustVolumeNormally(keyCode)
                }
                return true // Always consume so Android doesn't pop up the Volume UI on the first tap!
            }
            KeyEvent.ACTION_UP -> {
                holdRunnable?.let { handler.removeCallbacks(it) }

                if (isRecording && activeRecordingKeyCode == keyCode) {
                    activeRecordingKeyCode = -1
                    lastKeyUpTime = 0L
                    finishRecordingAndSave()
                    return true
                }

                // It was a single tap release -> wait 380ms to see if a 2nd tap comes.
                // If no 2nd tap comes, adjust volume normally!
                lastKeyUpTime = now
                pendingVolumeRunnable?.let { handler.removeCallbacks(it) }
                pendingVolumeRunnable = Runnable {
                    adjustVolumeNormally(keyCode)
                }
                handler.postDelayed(pendingVolumeRunnable!!, 380)
                return true
            }
        }
        return false
    }

    private fun handleSequenceUpThenDown(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val now = System.currentTimeMillis()

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    if (event.repeatCount == 0) {
                        lastKeyDownCode = KeyEvent.KEYCODE_VOLUME_UP
                        pendingVolumeRunnable?.let { handler.removeCallbacks(it) }
                        holdRunnable?.let { handler.removeCallbacks(it) }
                        holdRunnable = Runnable {
                            adjustVolumeNormally(KeyEvent.KEYCODE_VOLUME_UP)
                        }
                        handler.postDelayed(holdRunnable!!, 350)
                    } else {
                        adjustVolumeNormally(KeyEvent.KEYCODE_VOLUME_UP)
                    }
                    return true // Consume so Vol Up doesn't pop up the Volume UI during the combo!
                } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    if (event.repeatCount == 0) {
                        val isSequenceMatch = (lastKeyDownCode == KeyEvent.KEYCODE_VOLUME_UP) && (now - lastKeyUpTime <= 650L)
                        lastKeyDownCode = KeyEvent.KEYCODE_VOLUME_DOWN

                        holdRunnable?.let { handler.removeCallbacks(it) }

                        if (isSequenceMatch) {
                            // Sequence matched! Cancel the pending Volume Up UI popup!
                            pendingVolumeRunnable?.let { handler.removeCallbacks(it) }
                            holdRunnable = Runnable {
                                if (!isRecording) {
                                    activeRecordingKeyCode = KeyEvent.KEYCODE_VOLUME_DOWN
                                    startAudioRecording(captureImmediately = false)
                                }
                            }
                            handler.postDelayed(holdRunnable!!, 200)
                        } else {
                            holdRunnable = Runnable {
                                adjustVolumeNormally(KeyEvent.KEYCODE_VOLUME_DOWN)
                            }
                            handler.postDelayed(holdRunnable!!, 350)
                        }
                    } else if (!isRecording) {
                        adjustVolumeNormally(KeyEvent.KEYCODE_VOLUME_DOWN)
                    }
                    return true
                }
                return false
            }
            KeyEvent.ACTION_UP -> {
                holdRunnable?.let { handler.removeCallbacks(it) }

                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    lastKeyUpTime = now
                    // Wait 650ms to see if user presses Vol Down next. If not, raise volume normally!
                    pendingVolumeRunnable?.let { handler.removeCallbacks(it) }
                    pendingVolumeRunnable = Runnable {
                        adjustVolumeNormally(KeyEvent.KEYCODE_VOLUME_UP)
                    }
                    handler.postDelayed(pendingVolumeRunnable!!, 650)
                    return true
                } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    if (isRecording && activeRecordingKeyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        activeRecordingKeyCode = -1
                        lastKeyUpTime = 0L
                        finishRecordingAndSave()
                        return true
                    }
                    lastKeyUpTime = now
                    adjustVolumeNormally(KeyEvent.KEYCODE_VOLUME_DOWN)
                    return true
                }
                return false
            }
        }
        return false
    }


    private fun adjustVolumeNormally(keyCode: Int) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val dir = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startAudioRecording(captureImmediately: Boolean = false) {
        isRecording = true
        screenshotAlreadyCapturedOnStart = captureImmediately
        val timestamp = System.currentTimeMillis()

        currentImageFile = File(filesDir, "screen_$timestamp.png")
        currentAudioFile = File(filesDir, "audio_$timestamp.mp4")

        if (captureImmediately) {
            captureScreenToFile(currentImageFile) {}
        }

        Thread {
            try {
                mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this@KeyMappingService)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(currentAudioFile?.absolutePath)
                    prepare()
                    start()
                }
                handler.post {
                    NotificationHelper.showRecordingStarted(this@KeyMappingService)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mediaRecorder = null
                isRecording = false
            }
        }.start()
    }

    private fun captureScreenToFile(destFile: File?, onComplete: () -> Unit) {
        if (destFile == null) {
            onComplete()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    Thread {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            bitmap?.let {
                                FileOutputStream(destFile).use { out ->
                                    it.compress(Bitmap.CompressFormat.PNG, 100, out)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        handler.post { onComplete() }
                    }.start()
                }

                override fun onFailure(errorCode: Int) {
                    handler.post { onComplete() }
                }
            })
        } else {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            handler.postDelayed({ onComplete() }, 500)
        }
    }

    private fun finishRecordingAndSave() {
        isRecording = false

        Thread {
            try {
                mediaRecorder?.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    mediaRecorder?.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                mediaRecorder = null
            }

            handler.post {
                NotificationHelper.hideRecordingNotification(this@KeyMappingService)

                val prefs = getSharedPreferences("EssentialNotes", Context.MODE_PRIVATE)
                val captureEnabled = prefs.getBoolean(MainActivity.PREF_CAPTURE_SCREENSHOT, true)

                if (!captureEnabled) {
                    currentImageFile?.delete()
                    currentImageFile = null
                    saveNoteToStorage()
                } else if (screenshotAlreadyCapturedOnStart) {
                    saveNoteToStorage()
                } else {
                    captureScreenToFile(currentImageFile) {
                        saveNoteToStorage()
                    }
                }
            }
        }.start()
    }

    private fun saveNoteToStorage() {
        try {
            val prefs = getSharedPreferences("EssentialNotes", Context.MODE_PRIVATE)
            val jsonArray = try { JSONArray(prefs.getString("notes_array", "[]")) } catch (e: Exception) { JSONArray() }

            val timestamp = System.currentTimeMillis()
            val sdf = java.text.SimpleDateFormat("MM/dd/yy - hh:mm a", java.util.Locale.getDefault())
            val defaultName = "Memory - ${sdf.format(java.util.Date(timestamp))}"

            val hasImage = currentImageFile?.exists() == true && (currentImageFile?.length() ?: 0L) > 0L
            val hasAudio = currentAudioFile?.exists() == true && (currentAudioFile?.length() ?: 0L) > 0L

            val newNote = JSONObject().apply {
                put("summary", defaultName)
                put("tag", "Personal")
                put("image_path", if (hasImage) currentImageFile?.absolutePath ?: "" else "")
                put("audio_path", if (hasAudio) currentAudioFile?.absolutePath ?: "" else "")
                put("timestamp", timestamp)
            }
            jsonArray.put(newNote)
            prefs.edit().putString("notes_array", jsonArray.toString()).commit()

            val noteIndex = jsonArray.length() - 1
            NotificationHelper.showNoteSaved(this, defaultName, noteIndex)
            showInteractivePreview(defaultName, if (hasImage) currentImageFile?.absolutePath else null, noteIndex)


            val saveIntent = Intent("com.example.essentialspace.NOTE_SAVED").apply { setPackage(packageName) }
            sendBroadcast(saveIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showInteractivePreview(title: String, imgPath: String?, noteIndex: Int) {
        try {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = 120

            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val view = inflater.inflate(R.layout.floating_note_preview, null)

            view.findViewById<TextView>(R.id.previewTitle).text = title
            val previewImage = view.findViewById<ImageView>(R.id.previewImage)

            if (imgPath != null && File(imgPath).exists()) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(imgPath)
                previewImage.scaleType = ImageView.ScaleType.CENTER_CROP
                previewImage.clearColorFilter()
                previewImage.setImageBitmap(bitmap)
            } else {
                previewImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
                previewImage.setImageResource(R.drawable.ic_mic)
                previewImage.setColorFilter(Color.WHITE)
            }

            view.scaleX = 0.8f
            view.scaleY = 0.8f
            view.alpha = 0f

            windowManager.addView(view, params)

            var isRemoved = false

            fun safeRemoveView() {
                if (!isRemoved) {
                    isRemoved = true
                    try {
                        if (view.isAttachedToWindow) {
                            windowManager.removeView(view)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(400)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.8f))
                .start()

            view.setOnClickListener {
                try {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("open_note_index", noteIndex)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                view.animate()
                    .scaleX(0.8f)
                    .scaleY(0.8f)
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction { safeRemoveView() }
                    .start()
            }

            Handler(Looper.getMainLooper()).postDelayed({
                view.animate()
                    .scaleX(0.8f)
                    .scaleY(0.8f)
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction { safeRemoveView() }
                    .start()
            }, 4000)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}