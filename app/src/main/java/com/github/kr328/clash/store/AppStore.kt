package com.github.kr328.clash.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider

class AppStore(context: Context) {
    private val store = Store(
        context
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .asStoreProvider()
    )

    var updatedAt: Long by store.long(
        key = "updated_at",
        defaultValue = -1,
    )

    var xboardOnboardingCompleted: Boolean by store.boolean(
        key = "xboard_onboarding_completed",
        defaultValue = false,
    )

    var xboardSubscriptionExpired: Boolean by store.boolean(
        key = "xboard_subscription_expired",
        defaultValue = false,
    )

    var xboardAppUpdatePromptedVersion: String? by store.typedString(
        key = "xboard_app_update_prompted_version",
        from = { value -> value.takeIf { it.isNotBlank() } },
        to = { it.orEmpty() },
    )

    var xboardAppUpdateNotifiedVersion: String? by store.typedString(
        key = "xboard_app_update_notified_version",
        from = { value -> value.takeIf { it.isNotBlank() } },
        to = { it.orEmpty() },
    )

    var xboardAppUpdateDismissedAt: Long by store.long(
        key = "xboard_app_update_dismissed_at",
        defaultValue = 0L,
    )

    /** Update discovered while the app was in the background. */
    var xboardAppUpdatePendingVersion: String? by store.typedString(
        key = "xboard_app_update_pending_version",
        from = { value -> value.takeIf { it.isNotBlank() } },
        to = { it.orEmpty() },
    )

    var xboardAppUpdatePendingUrl: String? by store.typedString(
        key = "xboard_app_update_pending_url",
        from = { value -> value.takeIf { it.isNotBlank() } },
        to = { it.orEmpty() },
    )

    var xboardAcceptedNoticeIds: Set<String> by store.stringSet(
        key = "xboard_accepted_notice_ids",
        defaultValue = emptySet(),
    )

    var xboardNotifiedNoticeIds: Set<String> by store.stringSet(
        key = "xboard_notified_notice_ids",
        defaultValue = emptySet(),
    )

    var xboardNoticeSnoozedAt: Long by store.long(
        key = "xboard_notice_snoozed_at",
        defaultValue = 0L,
    )

    var xboardTicketsHaveOpen: Boolean by store.boolean(
        key = "xboard_tickets_have_open",
        defaultValue = false,
    )

    var xboardTicketStateScope: String by store.string(
        key = "xboard_ticket_state_scope",
        defaultValue = "",
    )

    var xboardTicketPollInitialized: Boolean by store.boolean(
        key = "xboard_ticket_poll_initialized",
        defaultValue = false,
    )

    var xboardTicketKnownRevisions: Set<String> by store.stringSet(
        key = "xboard_ticket_known_revisions",
        defaultValue = emptySet(),
    )

    var xboardTicketNotifiedReplies: Set<String> by store.stringSet(
        key = "xboard_ticket_notified_replies",
        defaultValue = emptySet(),
    )

    var xboardTicketPendingReplies: Set<String> by store.stringSet(
        key = "xboard_ticket_pending_replies",
        defaultValue = emptySet(),
    )

    companion object {
        private const val FILE_NAME = "app"
    }
}
