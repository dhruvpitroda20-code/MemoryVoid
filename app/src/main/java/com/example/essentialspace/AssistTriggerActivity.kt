package com.example.essentialspace

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class AssistTriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Immediately trigger background recording in KeyMappingService without showing any UI
        val assistBroadcast = Intent(MainActivity.ACTION_START_ASSIST_CAPTURE).apply {
            setPackage(packageName)
        }
        sendBroadcast(assistBroadcast)

        // Close immediately with zero animation so the underlying screen stays 100% untouched
        finish()
        overridePendingTransition(0, 0)
    }
}