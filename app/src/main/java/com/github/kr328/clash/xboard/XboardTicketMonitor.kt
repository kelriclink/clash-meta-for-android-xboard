package com.github.kr328.clash.xboard

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks ticket replies only after a user has opened or created an open ticket.
 * WorkManager is cancelled again as soon as the last ticket is closed.
 */
object XboardTicketMonitor {
    private const val UNIQUE_WORK_NAME = "xboard_ticket_check"
    private const val CHANNEL_ID = "xboard_ticket_channel_v2"
    private const val CHECK_INTERVAL_MINUTES = 15L
    private const val FOREGROUND_CHECK_INTERVAL_MS = 30_000L

    private val checkMutex = Mutex()
    private val promptShowing = AtomicBoolean(false)
    private var foregroundJob: Job? = null

    fun scheduleIfNeeded(context: Context) {
        val appContext = context.applicationContext
        if (AppStore(appContext).xboardTicketsHaveOpen) {
            schedule(appContext)
        } else {
            cancel(appContext)
        }
    }

    fun recordFetchedTickets(context: Context, session: XboardSession, tickets: List<XboardTicket>) {
        val store = AppStore(context.applicationContext)
        val scope = noticeScope(session)
        if (store.xboardTicketStateScope != scope) {
            store.xboardTicketStateScope = scope
            store.xboardTicketPollInitialized = false
            store.xboardTicketKnownRevisions = emptySet()
            store.xboardTicketNotifiedReplies = emptySet()
            store.xboardTicketPendingReplies = emptySet()
        }

        store.xboardTicketsHaveOpen = tickets.any(XboardTicket::isOpen)
        // Opening the ticket page is an explicit acknowledgement of the
        // current state; do not notify about old replies on the first sync.
        store.xboardTicketKnownRevisions = tickets.mapTo(mutableSetOf()) { ticketKey(it) }
        store.xboardTicketPollInitialized = true
        scheduleIfNeeded(context)
        if (store.xboardTicketsHaveOpen && ApplicationObserver.currentActivity != null) {
            startForegroundPolling(context)
        }
    }

    fun acknowledgeTicket(context: Context, session: XboardSession, ticket: XboardTicket) {
        val store = AppStore(context.applicationContext)
        if (store.xboardTicketStateScope != noticeScope(session)) return
        val key = ticketKey(ticket)
        store.xboardTicketPendingReplies = store.xboardTicketPendingReplies - key
    }

    fun onApplicationVisible(context: Context) {
        val appContext = context.applicationContext
        if (!AppStore(appContext).xboardTicketsHaveOpen) return
        startForegroundPolling(appContext)
    }

    fun onApplicationInvisible() {
        foregroundJob?.cancel()
        foregroundJob = null
    }

    private fun startForegroundPolling(context: Context) {
        val appContext = context.applicationContext
        foregroundJob?.cancel()
        Global.launch {
            // ActivityObserver can publish the visibility transition slightly
            // before currentActivity is assigned. Retry briefly so a pending
            // reply is not lost between the network check and dialog display.
            for (attempt in 0 until 4) {
                if (attempt > 0) delay(350L)
                runCatching { check(appContext, foreground = true) }
                    .onFailure { Log.w("XBoard ticket foreground check failed: ${it.message}", it) }
                if (promptShowing.get()) break
            }

            while (isActive) {
                delay(FOREGROUND_CHECK_INTERVAL_MS)
                if (!AppStore(appContext).xboardTicketsHaveOpen) break
                runCatching { check(appContext, foreground = true) }
                    .onFailure { Log.w("XBoard ticket foreground polling failed: ${it.message}", it) }
            }
        }.also { foregroundJob = it }
    }

    suspend fun runBackgroundCheck(context: Context) {
        // WorkManager may run while the app is still open. Treat an active
        // Activity as foreground so replies become an in-app dialog instead
        // of a notification hidden behind the current screen.
        val foreground = ApplicationObserver.currentActivity
            ?.let(::canShowActivity) == true
        check(context.applicationContext, foreground = foreground)
    }

    private suspend fun check(context: Context, foreground: Boolean) {
        checkMutex.withLock {
            val appContext = context.applicationContext
            val store = AppStore(appContext)
            if (!store.xboardTicketsHaveOpen) return@withLock

            val session = XboardSessionStore(appContext).getSession() ?: return@withLock
            val scope = noticeScope(session)
            if (store.xboardTicketStateScope != scope) {
                store.xboardTicketStateScope = scope
                store.xboardTicketPollInitialized = false
                store.xboardTicketKnownRevisions = emptySet()
                store.xboardTicketNotifiedReplies = emptySet()
                store.xboardTicketPendingReplies = emptySet()
            }

            val tickets = XboardRepository(XboardSessionStore(appContext)).fetchTickets()
            val known = store.xboardTicketKnownRevisions.toMutableSet()
            val notified = store.xboardTicketNotifiedReplies.toMutableSet()
            val pendingReplies = store.xboardTicketPendingReplies.toMutableSet()
            val currentKeys = tickets.mapTo(mutableSetOf()) { ticketKey(it) }
            val replies = mutableListOf<XboardTicket>()

            if (store.xboardTicketPollInitialized) {
                for (ticket in tickets) {
                    if (!ticket.isOpen()) continue
                    val key = ticketKey(ticket)
                    if (key in known || ticket.revision() <= 0L) continue

                    val detail = XboardRepository(XboardSessionStore(appContext)).fetchTicket(ticket.id)
                    val latest = detail.message.orEmpty().maxByOrNull { it.id }
                    if (latest?.isMe == false && key !in notified) {
                        replies += ticket
                        notified += key
                        pendingReplies += key
                    }
                }
            }

            store.xboardTicketKnownRevisions = currentKeys
            store.xboardTicketNotifiedReplies = notified.toList().takeLast(100).toSet()
            store.xboardTicketPendingReplies = pendingReplies
            store.xboardTicketsHaveOpen = tickets.any(XboardTicket::isOpen)
            scheduleIfNeeded(appContext)

            val pendingTickets = tickets.filter { ticketKey(it) in pendingReplies }
            if (replies.isEmpty() && (!foreground || pendingTickets.isEmpty())) return@withLock
            val activity = ApplicationObserver.currentActivity
                ?.takeIf(::canShowActivity)
            if (foreground && activity != null && pendingTickets.isNotEmpty()) {
                // Once the popup is shown, keep it from reappearing on every
                // foreground check. The next page refresh acknowledges it.
                store.xboardTicketPendingReplies = emptySet()
                showReplyDialog(activity, pendingTickets)
            } else {
                // If the visibility callback arrives before an Activity is
                // available, keep a system notification as a fallback. The
                // pending key remains stored and a later retry can replace it
                // with the foreground dialog.
                val notifyTickets = if (replies.isNotEmpty()) replies else pendingTickets
                if (notifyTickets.isNotEmpty()) showReplyNotification(appContext, notifyTickets)
            }
        }
    }

    private fun showReplyDialog(activity: Activity, tickets: List<XboardTicket>) {
        if (!promptShowing.compareAndSet(false, true)) return
        val message = tickets.joinToString("\n") { "• ${it.subject.ifBlank { "工单" }}" }
        activity.runOnUiThread {
            if (!canShowActivity(activity)) {
                promptShowing.set(false)
                return@runOnUiThread
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.xboard_ticket_reply_title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.xboard_ticket_open) { _, _ ->
                    activity.startActivity(
                        Intent(activity, XboardActivity::class.java)
                            .putExtra(XboardActivity.EXTRA_SHOW_TICKETS, true)
                            .putExtra(XboardActivity.EXTRA_TICKET_ID, tickets.firstOrNull()?.id ?: 0)
                    )
                }
                .setOnDismissListener { promptShowing.set(false) }
                .show()
        }
    }

    private fun showReplyNotification(context: Context, tickets: List<XboardTicket>) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(
                    CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_HIGH,
                ).setName(context.getString(R.string.xboard_ticket_notification_channel)).build()
            )
            val intent = Intent(context, XboardActivity::class.java)
                .putExtra(XboardActivity.EXTRA_SHOW_TICKETS, true)
                .putExtra(XboardActivity.EXTRA_TICKET_ID, tickets.firstOrNull()?.id ?: 0)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val summary = if (tickets.size == 1) {
                tickets.first().subject
            } else {
                context.getString(R.string.xboard_ticket_notification_message, tickets.size)
            }
            manager.notify(
                R.id.nf_xboard_ticket,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(com.github.kr328.clash.service.R.drawable.ic_logo_service)
                    .setColor(context.getColorCompat(DesignR.color.color_clash_light))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentTitle(context.getString(R.string.xboard_ticket_notification_title))
                    .setContentText(summary)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            R.id.nf_xboard_ticket,
                            intent,
                            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                        )
                    )
                    .build(),
            )
        }.onFailure { Log.w("Unable to show XBoard ticket notification: ${it.message}", it) }
    }

    private fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<XboardTicketWorker>(
            CHECK_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        ).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        ).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    private fun ticketKey(ticket: XboardTicket): String = "${ticket.id}:${ticket.revision()}"

    private fun noticeScope(session: XboardSession): String {
        val identity = session.email?.trim()?.takeIf { it.isNotEmpty() }
            ?: session.authData.takeLast(16)
        return "${session.baseUrl.trimEnd('/')}|$identity"
    }

    private fun canShowActivity(activity: Activity): Boolean {
        return !activity.isFinishing &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed)
    }
}

class XboardTicketWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            XboardTicketMonitor.runBackgroundCheck(applicationContext)
            Result.success()
        }.getOrElse {
            Log.w("XBoard ticket background check failed: ${it.message}", it)
            Result.retry()
        }
    }
}
