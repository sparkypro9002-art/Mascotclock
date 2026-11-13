package com.hotaro.strictclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlin.math.roundToInt

class AlarmVolumeGuard(private val context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var targetVolume: Int = 0
    private var previousVolume: Int = -1
    private var isLocked: Boolean = false
    private var isActive: Boolean = false

    private var settingsObserver: ContentObserver? = null
    private var volumeReceiver: BroadcastReceiver? = null

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isActive || !isLocked) return
            enforceMinimumVolume()
            mainHandler.postDelayed(this, 250L)
        }
    }

    /**
     * Call right BEFORE MediaPlayer or Ringtone starts playing.
     */
    fun start(volumePercent: Int, lockVolumeWhileRinging: Boolean) {
        isLocked = lockVolumeWhileRinging
        isActive = true

        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (previousVolume == -1) {
            previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        }

        targetVolume = ((volumePercent.coerceIn(0, 100) / 100f) * maxVol)
            .roundToInt()
            .coerceIn(0, maxVol)

        // Force system alarm stream to the preset percentage immediately
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0)

        if (isLocked) {
            registerVolumeGuards()
            mainHandler.postDelayed(watchdogRunnable, 250L)
        }
    }

    private fun enforceMinimumVolume() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        if (current < targetVolume) {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0)
        }
    }

    private fun registerVolumeGuards() {
        // Layer 1: System settings observer
        settingsObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                enforceMinimumVolume()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            settingsObserver!!
        )

        // Layer 2: Instant volume-change broadcast interception
        volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                    val streamType = intent.getIntExtra(
                        "android.media.EXTRA_VOLUME_STREAM_TYPE",
                        -1
                    )
                    if (streamType == AudioManager.STREAM_ALARM) {
                        enforceMinimumVolume()
                    }
                }
            }
        }

        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(volumeReceiver, filter)
        }
    }

    /**
     * Call when the alarm is dismissed / challenge is solved.
     */
    fun stop() {
        isActive = false
        mainHandler.removeCallbacks(watchdogRunnable)

        settingsObserver?.let {
            runCatching { context.contentResolver.unregisterContentObserver(it) }
        }
        settingsObserver = null

        volumeReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
        }
        volumeReceiver = null

        // Restore original alarm volume after dismissal
        if (previousVolume != -1) {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
            previousVolume = -1
        }
    }
}
