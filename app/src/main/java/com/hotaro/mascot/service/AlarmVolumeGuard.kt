package com.hotaro.mascot.service

import android.content.*
import android.database.ContentObserver
import android.media.AudioManager
import android.os.*
import android.provider.Settings
import kotlin.math.roundToInt

class AlarmVolumeGuard(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var targetVolume = 0
    private var previousVolume = -1
    private var isLocked = false
    private var isActive = false
    private var settingsObserver: ContentObserver? = null
    private var volumeReceiver: BroadcastReceiver? = null

    private val watchdog = object : Runnable {
        override fun run() {
            if (!isActive || !isLocked) return
            enforceMin()
            mainHandler.postDelayed(this, 200L)
        }
    }

    fun start(volumePercent: Int, lockVolume: Boolean) {
        isLocked = lockVolume
        isActive = true
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (previousVolume == -1) previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        targetVolume = ((volumePercent.coerceIn(0, 100) / 100f) * maxVol).roundToInt().coerceIn(0, maxVol)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0)
        if (isLocked) {
            registerGuards()
            mainHandler.postDelayed(watchdog, 200L)
        }
    }

    private fun enforceMin() {
        if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) < targetVolume) {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0)
        }
    }

    private fun registerGuards() {
        settingsObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) { enforceMin() }
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver!!)
        volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") enforceMin()
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(volumeReceiver, filter)
        }
    }

    fun stop() {
        isActive = false
        mainHandler.removeCallbacks(watchdog)
        settingsObserver?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        volumeReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        if (previousVolume != -1) {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
            previousVolume = -1
        }
    }
}
