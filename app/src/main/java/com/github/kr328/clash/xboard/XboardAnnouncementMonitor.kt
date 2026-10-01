package com.github.kr328.clash.xboard

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.text.HtmlCompat
import com.github.kr328.clash.R
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.store.AppStore
import com.github.kr328.clash.util.ApplicationObserver
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Fetches and presents XBoard announcements without repeatedly interrupting users. */
object XboardAnnouncementMonitor {
    private const val CHANNEL_ID = "xboard_announcement_channel"
    private const val SNOOZE_INTERVAL_MS = 6L * 60L * 60L * 1000L

    private val checkMutex = Mutex()
    private val dialogShowing = AtomicBoolean(false)

    fun onApplicationVisible(context: Context) {
        Global.launch {
            runCatching {
                check(context.applicationContext, foreground = true)
            }.onFailure {
                Log.w("XBoard announcement foreground check failed: ${it.message}", it)
            }
        }
    }

    suspend fun runBackgroundCheck(context: Context) {
        check(context.applicationContext, foreground = false)
    }

    private suspend fun check(context: Context, foreground: Boolean) {
        checkMutex.withLock {
            val appContext = context.applicationContext
            val sessionStore = XboardSessionStore(appContext)
            val session = sessionStore.getSession() ?: return@withLock
            val store = AppStore(appContext)
            val notices = XboardRepository(sessionStore)
                .fetchNotices()
                .filter(XboardNotice::isPopupNotice)

            val scope = noticeScope(session)
            val pending = notices.filter { notice ->
                "${scope}|${notice.acceptanceKey()}" !in store.xboardAcceptedNoticeIds
            }
            if (pending.isEmpty()) {
                NotificationManagerCompat.from(appContext)
                    .cancel(R.id.nf_xboard_announcement)
                return@withLock
            }

            val activity = ApplicationObserver.currentActivity
                ?.takeIf(::canShowActivity)
            if ((foreground || activity != null) && activity != null) {
                if (!isSnoozed(pending, store)) {
                    NotificationManagerCompat.from(appContext)
                        .cancel(R.id.nf_xboard_announcement)
                    showAnnouncementDialog(activity, pending, store, scope)
                }
            } else if (!foreground || activity == null) {
                showAnnouncementNotification(appContext, pending, store, scope)
            }
        }
    }

    private fun showAnnouncementDialog(
        activity: Activity,
        notices: List<XboardNotice>,
        store: AppStore,
        scope: String,
    ) {
        if (!canShowActivity(activity) || !dialogShowing.compareAndSet(false, true)) {
            return
        }

        activity.runOnUiThread {
            if (!canShowActivity(activity)) {
                dialogShowing.set(false)
                return@runOnUiThread
            }

            var accepted = false
            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.xboard_announcement_dialog_title)
                .setMessage(buildAnnouncementMessage(notices))
                .setNegativeButton(R.string.xboard_announcement_later, null)
                .setPositiveButton(R.string.xboard_announcement_confirm) { _, _ ->
                    accepted = true
                    val acceptedIds = store.xboardAcceptedNoticeIds.toMutableSet()
                    notices.forEach { notice ->
                        acceptedIds += "${scope}|${notice.acceptanceKey()}"
                    }
                    store.xboardAcceptedNoticeIds = acceptedIds
                    store.xboardNoticeSnoozedAt = 0L
                    NotificationManagerCompat.from(activity.applicationContext)
                        .cancel(R.id.nf_xboard_announcement)
                }
                .create()

            dialog.setOnShowListener {
                // HtmlCompat creates URLSpan instances for Markdown links.
                // Enable the movement method so links behave like they do in
                // the XBoard web panel instead of appearing as plain text.
                dialog.findViewById<TextView>(android.R.id.message)?.apply {
                    movementMethod = LinkMovementMethod.getInstance()
                    linksClickable = true
                }
            }
            dialog.setOnDismissListener {
                if (!accepted) {
                    store.xboardNoticeSnoozedAt = System.currentTimeMillis()
                }
                dialogShowing.set(false)
            }
            dialog.show()
        }
    }

    private fun showAnnouncementNotification(
        context: Context,
        notices: List<XboardNotice>,
        store: AppStore,
        scope: String,
    ) {
        val unseen = notices.filter { notice ->
            "${scope}|${notice.acceptanceKey()}" !in store.xboardNotifiedNoticeIds
        }
        if (unseen.isEmpty()) return

        val notifiedIds = store.xboardNotifiedNoticeIds.toMutableSet()
        unseen.forEach { notice ->
            notifiedIds += "${scope}|${notice.acceptanceKey()}"
        }
        store.xboardNotifiedNoticeIds = notifiedIds

        runCatching {
            val manager = NotificationManagerCompat.from(context)
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(
                    CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT,
                ).setName(context.getString(R.string.xboard_announcement_notification_channel)).build()
            )

            val intent = Intent(context, XboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val summary = if (unseen.size == 1) {
                unseen.first().title
            } else {
                context.getString(R.string.xboard_announcement_notification_message, unseen.size)
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.github.kr328.clash.service.R.drawable.ic_logo_service)
                .setColor(context.getColorCompat(DesignR.color.color_clash_light))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentTitle(context.getString(R.string.xboard_announcement_notification_title))
                .setContentText(summary)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        R.id.nf_xboard_announcement,
                        intent,
                        pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                    )
                )
                .build()

            manager.notify(R.id.nf_xboard_announcement, notification)
        }.onFailure {
            Log.w("Unable to show XBoard announcement notification: ${it.message}", it)
        }
    }

    private fun buildAnnouncementMessage(notices: List<XboardNotice>): CharSequence {
        val message = SpannableStringBuilder()
        notices.forEachIndexed { index, notice ->
            if (index > 0) message.append("\n\n")
            val title = TextUtils.htmlEncode(notice.title.ifBlank { "公告" })
            message.append(
                HtmlCompat.fromHtml(
                    "<b>$title</b><br>${XboardMarkdown.toHtml(notice.content)}",
                    HtmlCompat.FROM_HTML_MODE_COMPACT,
                )
            )
        }
        return message
    }

    private fun isSnoozed(notices: List<XboardNotice>, store: AppStore): Boolean {
        val snoozedAt = store.xboardNoticeSnoozedAt
        if (snoozedAt <= 0L || System.currentTimeMillis() - snoozedAt >= SNOOZE_INTERVAL_MS) {
            return false
        }

        return notices.none { it.revisionTimeMillis() > snoozedAt }
    }

    private fun noticeScope(session: XboardSession): String {
        val identity = session.email?.trim()?.takeIf { it.isNotEmpty() }
            ?: session.authData.takeLast(16)
        return "${session.baseUrl.trimEnd('/')}|$identity"
    }

    private fun canShowActivity(activity: Activity): Boolean {
        if (activity.isFinishing) return false
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 ||
            !activity.isDestroyed
    }
}
