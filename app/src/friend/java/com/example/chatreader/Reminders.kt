package com.example.chatreader

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 第一期 ②：在對的時間提醒（事件當天早上 8:30）
 *
 * - 存事件時排一個鬧鐘（AlarmManager）；時間到 ReminderReceiver 跳通知
 * - 通知用小精靈的口吻；點通知 → 小精靈幫你想一句 → 去 Messenger 填入 → 你按送出
 * - 手機重開機後 BootReceiver 重新排鬧鐘
 * - 測試：MainActivity 的「🧪 1 分鐘後提醒」
 */
object ReminderScheduler {
    private const val TAG = "Reminder"
    const val CHANNEL = "companion"
    const val EXTRA_PERSON = "personId"
    const val EXTRA_EVENT = "eventId"
    private const val HOUR = 8
    private const val MINUTE = 30

    /** 建立通知頻道（重複呼叫沒關係） */
    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "交友小精靈的提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "朋友考試、生日等重要日子的提醒"
                }
            )
        }
    }

    /** 重新排所有還沒提醒過、而且還沒過期的事件 */
    fun scheduleAll(ctx: Context) {
        MemoryStore.init(ctx)
        MemoryStore.upcomingEvents().forEach { (p, e) -> if (!e.reminded) schedule(ctx, p, e) }
    }

    /** 排一個事件的提醒：當天 8:30；如果已經過了 8:30（例如今天才記的今天的事）就 1 分鐘後提醒 */
    fun schedule(ctx: Context, person: Person, event: FriendEvent) {
        val day = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN).parse(event.date)
        }.getOrNull() ?: return
        var at = day.time + (HOUR * 60 + MINUTE) * 60_000L
        val now = System.currentTimeMillis()
        val endOfDay = day.time + 24 * 3600_000L
        if (now >= endOfDay) return              // 已經過了那一天
        if (at <= now) at = now + 60_000L        // 今天的事、但已過 8:30 → 1 分鐘後
        setAlarm(ctx, person.id, event.id, at)
    }

    /** 測試用：指定幾秒後提醒 */
    fun testSoon(ctx: Context, person: Person, event: FriendEvent, seconds: Int = 60) {
        setAlarm(ctx, person.id, event.id, System.currentTimeMillis() + seconds * 1000L)
    }

    fun cancel(ctx: Context, eventId: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx, "", eventId))
    }

    private fun pending(ctx: Context, personId: String, eventId: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
            .setAction("com.example.chatreader.REMIND.$eventId")   // 每個事件一個不同的 action，才不會互相覆蓋
            .putExtra(EXTRA_PERSON, personId)
            .putExtra(EXTRA_EVENT, eventId)
        return PendingIntent.getBroadcast(
            ctx, eventId.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun setAlarm(ctx: Context, personId: String, eventId: String, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, personId, eventId)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)   // 不精準，可能晚幾分鐘
        }
        Log.i(TAG, "已排提醒 event=$eventId at=${SimpleDateFormat("MM-dd HH:mm:ss", Locale.TAIWAN).format(at)} exact=$exact")
    }

    /** 小精靈口吻的提醒文字 */
    fun message(person: Person, event: FriendEvent): String = when (event.kind) {
        "考試" -> "今天是${person.name}的「${event.title}」！要不要傳句加油給對方？📚"
        "生日" -> "今天是${person.name}的生日 🎂 要不要第一個祝對方生日快樂？"
        else -> "今天${person.name}有「${event.title}」，可以關心一下順不順利～"
    }

    /** 跳通知（ReminderReceiver 和測試按鈕共用） */
    fun notify(ctx: Context, person: Person, event: FriendEvent) {
        ensureChannel(ctx)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "沒有通知權限，無法提醒：${message(person, event)}")
            return
        }
        val open = Intent(ctx, CompanionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(CompanionActivity.EXTRA_MODE, CompanionActivity.MODE_REMIND)
            .putExtra(EXTRA_PERSON, person.id)
            .putExtra(EXTRA_EVENT, event.id)
        val pi = PendingIntent.getActivity(
            ctx, event.id.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("🧚 交友小精靈")
            .setContentText(message(person, event))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message(person, event) + "\n點我，小精靈幫你想一句"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(event.id.hashCode(), n) }
            .onFailure { Log.w(TAG, "通知失敗", it) }
    }
}

/** 鬧鐘時間到 → 跳通知 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        MemoryStore.init(ctx)
        val pid = intent.getStringExtra(ReminderScheduler.EXTRA_PERSON) ?: return
        val eid = intent.getStringExtra(ReminderScheduler.EXTRA_EVENT) ?: return
        val person = MemoryStore.allPeople().firstOrNull { it.id == pid } ?: return
        val event = person.events.firstOrNull { it.id == eid } ?: return   // 已經被刪掉就不提醒
        ReminderScheduler.notify(ctx, person, event)
        MemoryStore.markReminded(pid, eid)
    }
}

/** 開機後重新排鬧鐘（鬧鐘在重開機後會消失） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ReminderScheduler.scheduleAll(ctx)
    }
}
