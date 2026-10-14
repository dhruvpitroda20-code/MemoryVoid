package com.example.essentialspace

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class KeyMappingService : AccessibilityService() {

    private var isRecording = false
    private var pressStartTime = 0L
    private var mediaRecorder: MediaRecorder? = null

    private var currentImageFile: File? = null
    private var currentAudioFile: File? = null

    private val handler = Handler(Looper.getMainLooper())
    private var holdRunnable: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        try {
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            pressStartTime = System.currentTimeMillis()
                            holdRunnable?.let { handler.removeCallbacks(it) }

                            holdRunnable = Runnable {
                                if (!isRecording) {
                                    startAudioRecording()
                                }
                            }
                            handler.postDelayed(holdRunnable!!, 350)
                        }
                        return true
                    }
                    KeyEvent.ACTION_UP -> {
                        holdRunnable?.let { handler.removeCallbacks(it) }

                        if (isRecording) {
                            captureScreenshotAndSaveNote()
                        } else {
                            try {
                                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        return true
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return super.onKeyEvent(event)
    }

    private fun startAudioRecording() {
        isRecording = true
        val timestamp = System.currentTimeMillis()

        currentImageFile = File(filesDir, "screen_$timestamp.png")
        currentAudioFile = File(filesDir, "audio_$timestamp.mp4")

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
            }
        }.start()
    }

    private fun captureScreenshotAndSaveNote() {
        isRecording = false

        Thread {
            // Stop audio recording in background
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

                // Capture screenshot on release, then save note
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            Thread {
                                try {
                                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                    bitmap?.let {
                                        FileOutputStream(currentImageFile).use { out ->
                                            it.compress(Bitmap.CompressFormat.PNG, 100, out)
                                        }
                                    }
                                } catch (e: Exception) { e.printStackTrace() }
                                handler.post { saveNoteToStorage() }
                            }.start()
                        }

                        override fun onFailure(errorCode: Int) {
                            handler.post { saveNoteToStorage() }
                        }
                    })
                } else {
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                    handler.postDelayed({ saveNoteToStorage() }, 500)
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

            if (Settings.canDrawOverlays(this)) {
                showInteractivePreview(defaultName, currentImageFile?.absolutePath, noteIndex)
            }

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = 120 // some top margin

            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val view = inflater.inflate(R.layout.floating_note_preview, null)

            view.findViewById<TextView>(R.id.previewTitle).text = title
            if (imgPath != null && File(imgPath).exists()) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(imgPath)
                view.findViewById<ImageView>(R.id.previewImage).setImageBitmap(bitmap)
            }

            // Use an animator for smooth apple bounce
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
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
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