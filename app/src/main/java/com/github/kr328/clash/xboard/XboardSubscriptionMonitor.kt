package com.github.kr328.clash.xboard

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.github.kr328.clash.store.AppStore
import com.github.kr328.clash.util.ApplicationObserver
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.kr328.clash.design.R as DesignR

object XboardSubscriptionMonitor {
    private const val CHANNEL_ID = "xboard_subscription_channel"
    private const val UNIQUE_WORK_NAME = "xboard_subscription_check"

    private val checkMutex = Mutex()
    private val genericPromptShowing = AtomicBoolean(false)
    private val visibilityLock = Any()
    private var visibilityGeneration = 0L
    private var promptGeneration = -1L

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<XboardSubscriptionWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }

    fun onApplicationVisible(context: Context) {
        val appContext = context.applicationContext
        val store = AppStore(appContext)
        val sessionStore = XboardSessionStore(appContext)

        if (!store.xboardOnboardingCompleted || sessionStore.getSession() == null) {
            return
        }

        val generation = synchronized(visibilityLock) {
            visibilityGeneration += 1
            visibilityGeneration
        }

        // Re-use the last known state immediately when the app returns to
        // the foreground. The network refresh below can then correct a stale
        // value without leaving an already-known expiration unannounced.
        if (store.xboardSubscriptionExpired) {
            showPromptOrNotification(appContext, generation)
        }

        Global.launch {
            runCatching {
                refreshStatus(appContext)
            }.onSuccess { expired ->
                if (expired) {
                    showPromptOrNotification(appContext, generation)
                }
            }.onFailure {
                Log.w("XBoard subscription foreground check failed: ${it.message}", it)
            }
        }
    }

    suspend fun runBackgroundCheck(context: Context): Boolean {
        val appContext = context.applicationContext

        return checkMutex.withLock {
            val expired = refreshStatusUnlocked(appContext)

            if (expired) {
                showPromptOrNotification(appContext, currentVisibilityGeneration())
            }

            expired
        }
    }

    private suspend fun refreshStatus(context: Context): Boolean {
        return checkMutex.withLock {
            refreshStatusUnlocked(context)
        }
    }

    private suspend fun refreshStatusUnlocked(context: Context): Boolean {
        val appContext = context.applicationContext
        val store = AppStore(appContext)
        val sessionStore = XboardSessionStore(appContext)
        val session = sessionStore.getSession()

        if (!store.xboardOnboardingCompleted || session == null) {
            store.xboardSubscriptionExpired = false
            NotificationManagerCompat.from(appContext)
                .cancel(R.id.nf_xboard_subscription_expired)
            return false
        }

        val user = XboardRepository(sessionStore).getUserInfo()
        // A missing plan means the user has never subscribed; it should not
        // be treated as an "expired" subscription reminder.
        val expired = user.planId?.let { it > 0 && user.isSubscriptionExpired() } == true

        store.xboardSubscriptionExpired = expired
        if (!expired) {
            NotificationManagerCompat.from(appContext)
                .cancel(R.id.nf_xboard_subscription_expired)
        }
        return expired
    }

    /** Shows the cached reminder on a foreground Activity when one is known to be expired. */
    fun showIfExpired(activity: Activity) {
        val context = activity.applicationContext
        val store = AppStore(context)
        val session = XboardSessionStore(context).getSession()
        if (session != null && store.xboardSubscriptionExpired) {
            showPromptOnActivity(activity, currentVisibilityGeneration())
        }
    }

    private fun showPromptOrNotification(context: Context, generation: Long) {
        val activity = ApplicationObserver.currentActivity
            ?.takeIf(::canShowActivity)

        if (activity == null) {
            showRenewalNotification(context.applicationContext)
            return
        }

        // A foreground dialog supersedes a reminder that may have been
        // posted while the process was in the background.
        NotificationManagerCompat.from(context.applicationContext)
            .cancel(R.id.nf_xboard_subscription_expired)
        showPromptOnActivity(activity, generation)
    }

    private fun showPromptOnActivity(activity: Activity, generation: Long) {
        synchronized(visibilityLock) {
            if (promptGeneration == generation) {
                return
            }
            promptGeneration = generation
        }

        activity.runOnUiThread {
            showGlobalRenewalDialog(activity)
        }
    }

    private fun showGlobalRenewalDialog(activity: Activity) {
        if (!canShowActivity(activity) || !genericPromptShowing.compareAndSet(false, true)) {
            return
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.xboard_expired_dialog_title)
            .setMessage(R.string.xboard_expired_dialog_message)
            .setNeutralButton(R.string.xboard_change_subscription) { _, _ ->
                if (activity is XboardActivity) {
                    activity.showSubscription()
                } else {
                    activity.startActivity(Intent(activity, XboardActivity::class.java))
                }
            }
            .setNegativeButton(R.string.xboard_remind_later, null)
            .setPositiveButton(R.string.xboard_open_panel_renewal) { _, _ ->
                openPanelForRenewal(activity)
            }
            .create()

        dialog.setOnDismissListener {
            genericPromptShowing.set(false)
        }
        dialog.show()
    }

    private fun openPanelForRenewal(activity: Activity) {
        Global.launch {
            runCatching {
                val sessionStore = XboardSessionStore(activity.applicationContext)
                XboardRepository(sessionStore).getQuickLoginUrl("/dashboard")
            }.onSuccess { loginUrl ->
                activity.runOnUiThread {
                    if (canShowActivity(activity)) {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)))
                    }
                }
            }.onFailure { error ->
                activity.runOnUiThread {
                    if (canShowActivity(activity)) {
                        android.widget.Toast.makeText(
                            activity,
                            error.message ?: activity.getString(R.string.xboard_open_web_failed),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    private fun canShowActivity(activity: Activity): Boolean {
        if (activity.isFinishing) {
            return false
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed
    }

    private fun currentVisibilityGeneration(): Long {
        return synchronized(visibilityLock) { visibilityGeneration }
    }

    private fun showRenewalNotification(context: Context) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)

            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(
                    CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_HIGH
                ).setName(context.getString(R.string.xboard_expired_notification_channel)).build()
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.github.kr328.clash.service.R.drawable.ic_logo_service)
                .setColor(context.getColorCompat(DesignR.color.color_clash_light))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentTitle(context.getString(R.string.xboard_expired_notification_title))
                .setContentText(context.getString(R.string.xboard_expired_notification_message))
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        R.id.nf_xboard_subscription_expired,
                        createRenewalIntent(context),
                        pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
                    )
                )
                .build()

            manager.notify(R.id.nf_xboard_subscription_expired, notification)
        }.onFailure {
            Log.w("Unable to show XBoard subscription notification: ${it.message}", it)
        }
    }

    private fun createRenewalIntent(context: Context): Intent {
        return Intent(context, XboardActivity::class.java)
            .putExtra(XboardActivity.EXTRA_SHOW_RENEWAL_PROMPT, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

class XboardSubscriptionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            XboardSubscriptionMonitor.runBackgroundCheck(applicationContext)
            runCatching {
                XboardAppUpdateMonitor.runBackgroundCheck(applicationContext)
            }.onFailure {
                // An app-update endpoint outage must not make the subscription
                // worker retry or interfere with normal profile updates.
                Log.w("XBoard app update background check failed: ${it.message}", it)
            }
            runCatching {
                XboardAnnouncementMonitor.runBackgroundCheck(applicationContext)
            }.onFailure {
                // An announcement endpoint outage must not affect subscription checks.
                Log.w("XBoard announcement background check failed: ${it.message}", it)
            }
            Result.success()
        } catch (e: Exception) {
            Log.w("XBoard subscription background check failed: ${e.message}", e)
            Result.retry()
        }
    }
}
