package ac.mdiq.podcini.automation

import ac.mdiq.podcini.PodciniApp.Companion.getAppContext
import ac.mdiq.podcini.receiver.TimerReceiver
import ac.mdiq.podcini.storage.database.appAttribsFlow
import ac.mdiq.podcini.storage.database.runOnIOScope
import ac.mdiq.podcini.storage.database.upsert
import ac.mdiq.podcini.storage.model.Timer
import ac.mdiq.podcini.utils.Logd
import ac.mdiq.podcini.utils.Loge
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import java.time.Instant
import java.time.ZoneId

private const val TAG = "AlarmHandler"
const val ALARM_TYPE = "AlarmType"

private fun idFromTriggerTime(millis: Long): Int {
    return Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).let {
        "%d%02d%02d%02d%02d".format(it.year % 10, it.monthValue, it.dayOfMonth, it.hour, it.minute).toInt()
    }
}

fun reset(timer: Timer) {
    val context = getAppContext()
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (!alarmManager.canScheduleExactAlarms()) {
            requestExactAlarmPermission()
            return
        }
    }

    val intent = Intent(context, TimerReceiver::class.java)
    var pendingIntent = PendingIntent.getBroadcast(context, timer.alarmId, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    if (pendingIntent != null) {
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel() // Good practice to also cancel the PendingIntent
        Logd(TAG, "Timer cancelled.")
    }

    intent.putExtra(ALARM_TYPE, AlarmTypes.PLAY_EPISODE.name + ":" + timer.episodeId.toString())
    pendingIntent = PendingIntent.getBroadcast(context, timer.alarmId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.triggerTime, pendingIntent)
}

fun cancel(timer: Timer) {
    val context = getAppContext()
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, TimerReceiver::class.java)

    val pendingIntent = PendingIntent.getBroadcast(context, timer.alarmId, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    if (pendingIntent != null) {
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel() // Good practice to also cancel the PendingIntent
        Logd(TAG, "Timer cancelled.")
    }
}

fun cancelTimer(triggerTime: Long) {
    val context = getAppContext()
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, TimerReceiver::class.java)

    val alarmId = idFromTriggerTime(triggerTime)
    val pendingIntent = PendingIntent.getBroadcast(context, alarmId, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    if (pendingIntent != null) {
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel() // Good practice to also cancel the PendingIntent
        Logd(TAG, "Timer cancelled.")
    }
}

fun playEpisodeAtTime(triggerTime: Long, episodeId: Long, repeat: Boolean = false) {
    val context = getAppContext()
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (!alarmManager.canScheduleExactAlarms()) {
            requestExactAlarmPermission()
            return
        }
    }

    val intent = Intent(context, TimerReceiver::class.java).apply {
        putExtra(ALARM_TYPE, AlarmTypes.PLAY_EPISODE.name + ":" + episodeId.toString() + ":" + repeat)
    }

    val timer = Timer()
    timer.episodeId = episodeId
    timer.triggerTime = triggerTime
    timer.alarmId = idFromTriggerTime(triggerTime)

    runOnIOScope { upsert(appAttribsFlow!!.value) { it.timetable.add(timer)} }

    val pendingIntent = PendingIntent.getBroadcast(context, timer.alarmId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
}

fun requestExactAlarmPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = getAppContext()
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

        if (context is ComponentActivity) context.startActivity(intent)
        else Loge(TAG, "Cannot request permission without an Activity context.")
    }
}

enum class AlarmTypes {
    PLAY_EPISODE
}

