package com.dsh.ledger

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Calendar

/**
 * 每天固定在 21:00 提醒「今天的帳還沒抓」。
 * 點通知會直接開網銀視窗（登入後自動抓明細），不需要自己找路徑。
 */
object DailyReminder {
    const val CHANNEL_ID = "ledger_daily"
    private const val REQ = 4001
    private const val HOUR = 21

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "每日抓帳提醒", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
    }

    fun schedule(ctx: Context) {
        ensureChannel(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }
        val pi = PendingIntent.getBroadcast(
            ctx, REQ,
            Intent(ctx, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
        }
    }

    fun notifyNow(ctx: Context) {
        ensureChannel(ctx)
        val acc = Vault.accounts.firstOrNull()
        val intent = if (acc != null) {
            BankWebActivity.intent(
                ctx, acc.bankName, acc.loginUrl, acc.queryUrl,
                acc.idNo, acc.userCode, acc.accountTail, acc.bankAcctNo
            )
        } else {
            Intent(ctx, MainActivity::class.java)
        }
        val pi = PendingIntent.getActivity(
            ctx, REQ, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("今天的帳還沒抓")
            .setContentText("點一下開網銀，登入後自動抓明細並記帳")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(REQ, n) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        DailyReminder.notifyNow(context)
    }
}
