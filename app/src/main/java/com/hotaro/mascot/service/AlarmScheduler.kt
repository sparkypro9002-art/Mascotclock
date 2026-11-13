package com.hotaro.mascot.service

import android.app.*
import android.content.*
import com.hotaro.mascot.data.AlarmEntity

class AlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(alarm: AlarmEntity) {
        if (!alarm.isActive) { cancel(alarm); return }
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("ALARM_ID", alarm.id)
            putExtra("CHALLENGE_TYPE", alarm.challengeType)
            putExtra("SOUND_URI", alarm.soundUri)
            putExtra("VIBRATION_ENABLED", alarm.vibrationEnabled)
            putExtra("QR_CODE_DATA", alarm.qrCodeData)
            putExtra("QR_CODE_NAME", alarm.qrCodeName)
            putExtra("CAMERA_OBJECT", alarm.cameraObject)
            putExtra("MATH_OPERATIONS", alarm.mathOperations)
            putExtra("MATH_DIFFICULTY", alarm.mathDifficulty)
            putExtra("VOLUME_PERCENT", alarm.volumePercent)
            putExtra("LOCK_VOLUME", alarm.lockVolumeWhileRinging)
        }
        val pi = PendingIntent.getBroadcast(context, alarm.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val trigger = com.hotaro.mascot.utils.AlarmUtils.getNextTriggerTime(alarm)
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, pi), pi)
    }

    fun cancel(alarm: AlarmEntity) {
        val pi = PendingIntent.getBroadcast(context, alarm.id, Intent(context, AlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(pi)
    }
}
