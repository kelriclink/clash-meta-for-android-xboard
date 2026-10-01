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
import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.R
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.store.AppStore
import com.github.kr328.clash.util.ApplicationObserver
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Checks the Android version advertised by the XBoard panel. */
object XboardAppUpdateMonitor {
    private const val CHANNEL_ID = "xboard_app_update_channel"
    private const val DISMISS_REMINDER_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val FOREGROUND_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

    private val checkMutex = Mutex()
    private val dialogShowing = AtomicBoolean(false)
    private var foregroundJob: Job? = null

    enum class CheckResult {
        UPDATE_AVAILABLE,
        UP_TO_DATE,
        UNAVAILABLE,
    }

    fun onApplicationVisible(context: Context) {
        val appContext = context.applicationContext
        val sessionStore = XboardSessionStore(appContext)
        if (sessionStore.getSession() == null) {
            return
        }

        startForegroundPolling(appContext)
    }

    /** Stop the short-interval foreground checks when the app is hidden. */
    fun onApplicationInvisible() {
        foregroundJob?.cancel()
        foregroundJob = null
    }

    suspend fun runBackgroundCheck(context: Context) {
        val foreground = ApplicationObserver.currentActivity
            ?.let(::canShowDialog) == true
        checkForUpdate(context.applicationContext, showDialog = foreground)
    }

    suspend fun checkNow(context: Context): CheckResult {
        return checkForUpdate(
            context = context.applicationContext,
            showDialog = true,
            ignoreDismissed = true,
        )
    }

    private fun startForegroundPolling(context: Context) {
        val appContext = context.applicationContext
        foregroundJob?.cancel()
        foregroundJob = Global.launch {
            // Activity lifecycle callbacks publish visibility just before the
            // Activity is resumed. Retry briefly so startup/resume checks can
            // always attach the dialog to the current foreground Activity.
            for (attempt in 0 until 4) {
                if (attempt > 0) delay(350L)
                runCatching {
                    checkForUpdate(appContext, showDialog = true)
                }.onFailure {
                    Log.w("XBoard app update foreground check failed: ${it.message}", it)
                }
                if (ApplicationObserver.currentActivity?.let(::canShowDialog) == true) {
                    break
                }
            }

            while (isActive) {
                delay(FOREGROUND_CHECK_INTERVAL_MS)
                if (XboardSessionStore(appContext).getSession() == null) break
                runCatching {
                    checkForUpdate(appContext, showDialog = true)
                }.onFailure {
                    Log.w("XBoard app update foreground polling failed: ${it.message}", it)
                }
            }
        }
    }

    private suspend fun checkForUpdate(
        context: Context,
        showDialog: Boolean,
        ignoreDismissed: Boolean = false,
    ): CheckResult {
        return checkMutex.withLock {
            val appContext = context.applicationContext
            val sessionStore = XboardSessionStore(appContext)
            if (sessionStore.getSession() == null) {
                return@withLock CheckResult.UNAVAILABLE
            }

            val store = AppStore(appContext)
            val activity = ApplicationObserver.currentActivity
                ?.takeIf(::canShowDialog)

            // Restore an update discovered by a background worker before
            // waiting for the network. This makes returning to the app
            // reliable even when the update endpoint is temporarily slow.
            if (showDialog && activity != null) {
                val pendingVersion = store.xboardAppUpdatePendingVersion
                val pendingUrl = store.xboardAppUpdatePendingUrl
                val pendingVersionValue = pendingVersion?.takeIf { it.isNotBlank() }
                val pendingUrlValue = pendingUrl?.takeIf { it.isNotBlank() }
                if (pendingVersionValue != null && pendingUrlValue != null &&
                    isNewerVersion(pendingVersionValue, BuildConfig.VERSION_NAME)
                ) {
                    val dismissedRecently = store.xboardAppUpdatePromptedVersion == pendingVersionValue &&
                        System.currentTimeMillis() - store.xboardAppUpdateDismissedAt <
                        DISMISS_REMINDER_INTERVAL_MS
                    if (!dismissedRecently || ignoreDismissed) {
                        store.xboardAppUpdatePromptedVersion = pendingVersionValue
                        showUpdateDialog(activity, pendingVersionValue, pendingUrlValue, store)
                    }
                } else if (!pendingVersion.isNullOrBlank() || !pendingUrl.isNullOrBlank()) {
                    store.xboardAppUpdatePendingVersion = null
                    store.xboardAppUpdatePendingUrl = null
                }
            }

            val remote = XboardRepository(sessionStore).getAppVersion()
            val remoteVersion = remote.androidVersionString()
                ?: return@withLock CheckResult.UNAVAILABLE
            val downloadUrl = remote.androidDownloadUrl
                ?.trim()
                ?.takeIf(::isHttpUrl)
                ?: return@withLock CheckResult.UNAVAILABLE

            if (!isNewerVersion(remoteVersion, BuildConfig.VERSION_NAME)) {
                store.xboardAppUpdatePendingVersion = null
                store.xboardAppUpdatePendingUrl = null
                return@withLock CheckResult.UP_TO_DATE
            }

            store.xboardAppUpdatePendingVersion = remoteVersion
            store.xboardAppUpdatePendingUrl = downloadUrl

            if (showDialog && activity != null) {
                val dismissedRecently = store.xboardAppUpdatePromptedVersion == remoteVersion &&
                    System.currentTimeMillis() - store.xboardAppUpdateDismissedAt <
                    DISMISS_REMINDER_INTERVAL_MS
                if (dismissedRecently && !ignoreDismissed) {
                    return@withLock CheckResult.UPDATE_AVAILABLE
                }

                store.xboardAppUpdatePromptedVersion = remoteVersion
                activity.runOnUiThread { showUpdateDialog(activity, remoteVersion, downloadUrl, store) }
            } else if (!showDialog && store.xboardAppUpdateNotifiedVersion != remoteVersion) {
                store.xboardAppUpdateNotifiedVersion = remoteVersion
                showUpdateNotification(appContext, remoteVersion, downloadUrl)
            }

            CheckResult.UPDATE_AVAILABLE
        }
    }

    private fun showUpdateDialog(
        activity: Activity,
        version: String,
        downloadUrl: String,
        store: AppStore,
    ) {
        if (!canShowDialog(activity) || !dialogShowing.compareAndSet(false, true)) {
            return
        }

        activity.runOnUiThread {
            if (!canShowDialog(activity)) {
                dialogShowing.set(false)
                return@runOnUiThread
            }

            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.xboard_app_update_dialog_title)
                .setMessage(activity.getString(R.string.xboard_app_update_dialog_message, version))
                .setNegativeButton(R.string.xboard_app_update_later, null)
                .setPositiveButton(R.string.xboard_app_update) { _, _ ->
                    openDownloadUrl(activity, downloadUrl)
                }
                .create()

            dialog.setOnDismissListener {
                store.xboardAppUpdateDismissedAt = System.currentTimeMillis()
                dialogShowing.set(false)
            }
            dialog.show()
        }
    }

    private fun showUpdateNotification(context: Context, version: String, downloadUrl: String) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(
                    CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT,
                ).setName(context.getString(R.string.xboard_app_update_notification_channel)).build()
            )

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.github.kr328.clash.service.R.drawable.ic_logo_service)
                .setColor(context.getColorCompat(com.github.kr328.clash.design.R.color.color_clash_light))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentTitle(context.getString(R.string.xboard_app_update_notification_title))
                .setContentText(
                    context.getString(R.string.xboard_app_update_notification_message, version)
                )
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        R.id.nf_xboard_app_update,
                        intent,
                        pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                    )
                )
                .build()

            manager.notify(R.id.nf_xboard_app_update, notification)
        }.onFailure {
            Log.w("Unable to show XBoard app update notification: ${it.message}", it)
        }
    }

    private fun openDownloadUrl(context: Context, downloadUrl: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            )
        }.onFailure {
            Log.w("Unable to open XBoard app update URL: ${it.message}", it)
        }
    }

    private fun canShowDialog(activity: Activity): Boolean {
        if (activity.isFinishing) {
            return false
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed
    }

    private fun isHttpUrl(value: String): Boolean {
        val scheme = Uri.parse(value).scheme?.lowercase(Locale.ROOT)
        return scheme == "http" || scheme == "https"
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        val remoteParts = parseVersion(remote) ?: return false
        val localParts = parseVersion(local) ?: return false
        val count = maxOf(remoteParts.size, localParts.size)

        for (index in 0 until count) {
            val remotePart = remoteParts.getOrElse(index) { 0 }
            val localPart = localParts.getOrElse(index) { 0 }
            if (remotePart != localPart) {
                return remotePart > localPart
            }
        }

        return false
    }

    private fun parseVersion(value: String): List<Int>? {
        val match = Regex("(?i)^\\s*v?(\\d+(?:\\.\\d+){0,3})").find(value)
            ?: return null
        return match.groupValues[1].split('.').mapNotNull { it.toIntOrNull() }
            .takeIf { it.isNotEmpty() }
    }
}
