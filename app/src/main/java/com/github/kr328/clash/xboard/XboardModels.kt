package com.github.kr328.clash.xboard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

@Serializable
data class XboardApiResponse(
    val status: String? = null,
    val message: String? = null,
    val data: JsonElement? = null,
    val error: JsonElement? = null,
)

@Serializable
data class XboardAuthData(
    val token: String? = null,
    @SerialName("auth_data")
    val authData: String? = null,
    @SerialName("is_admin")
    val isAdmin: Boolean = false,
)

@Serializable
data class XboardSession(
    val baseUrl: String,
    val authData: String,
    val token: String? = null,
    val email: String? = null,
    val profileUuid: String? = null,
)

@Serializable
data class XboardGuestConfig(
    @SerialName("is_email_verify")
    val isEmailVerify: JsonElement? = null,
    @SerialName("is_invite_force")
    val isInviteForce: JsonElement? = null,
) {
    fun requiresEmailVerify(): Boolean = isEmailVerify.asBooleanFlag()

    fun requiresInviteCode(): Boolean = isInviteForce.asBooleanFlag()
}

@Serializable
data class XboardUserInfo(
    val email: String? = null,
    @SerialName("plan_id")
    val planId: Int? = null,
    @SerialName("expired_at")
    val expiredAt: Long? = null,
    @SerialName("transfer_enable")
    val transferEnable: Long? = null,
    val u: Long? = null,
    val d: Long? = null,
    val banned: Boolean? = null,
)

@Serializable
data class XboardSubscribeInfo(
    val email: String? = null,
    @SerialName("plan_id")
    val planId: Int? = null,
    @SerialName("subscribe_url")
    val subscribeUrl: String? = null,
    @SerialName("expired_at")
    val expiredAt: Long? = null,
    @SerialName("transfer_enable")
    val transferEnable: Long? = null,
    val u: Long? = null,
    val d: Long? = null,
    val plan: XboardPlan? = null,
    val token: String? = null,
)

@Serializable
data class XboardAppVersion(
    @SerialName("android_version")
    val androidVersion: JsonElement? = null,
    @SerialName("android_download_url")
    val androidDownloadUrl: String? = null,
)

@Serializable
data class XboardNotice(
    val id: Int = 0,
    val title: String = "",
    val content: String = "",
    @SerialName("img_url")
    val imageUrl: String? = null,
    val tags: JsonElement? = null,
    @SerialName("created_at")
    val createdAt: Long? = null,
    @SerialName("updated_at")
    val updatedAt: Long? = null,
) {
    fun isPopupNotice(): Boolean {
        return when (val value = tags) {
            is JsonArray -> value.any {
                (it as? JsonPrimitive)?.contentOrNull?.trim() == "弹窗"
            }
            is JsonPrimitive -> value.contentOrNull
                ?.split(',', '|', ';')
                ?.any { it.trim() == "弹窗" } == true
            else -> false
        }
    }

    fun acceptanceKey(): String {
        val revision = updatedAt ?: createdAt ?: 0L
        return if (id > 0) {
            "$id:$revision"
        } else {
            "${title.hashCode()}:${content.hashCode()}:$revision"
        }
    }

    fun revisionTimeMillis(): Long {
        return (updatedAt ?: createdAt ?: 0L) * 1000L
    }

}

@Serializable
data class XboardTicketMessage(
    val id: Int = 0,
    @SerialName("ticket_id")
    val ticketId: Int = 0,
    @SerialName("is_me")
    val isMe: Boolean? = null,
    val message: String = "",
    @SerialName("created_at")
    val createdAt: Long? = null,
    @SerialName("updated_at")
    val updatedAt: Long? = null,
)

@Serializable
data class XboardTicket(
    val id: Int = 0,
    val level: Int = 1,
    val status: Int = 0,
    @SerialName("reply_status")
    val replyStatus: Int? = null,
    val subject: String = "",
    val message: List<XboardTicketMessage>? = null,
    @SerialName("user_id")
    val userId: Int? = null,
    @SerialName("created_at")
    val createdAt: Long? = null,
    @SerialName("updated_at")
    val updatedAt: Long? = null,
) {
    fun isOpen(): Boolean = status == 0

    fun revision(): Long = updatedAt ?: createdAt ?: 0L
}

fun XboardAppVersion.androidVersionString(): String? {
    return (androidVersion as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
}

@Serializable
data class XboardPlan(
    val id: Int = 0,
    val name: String? = null,
    val content: String? = null,
    @SerialName("transfer_enable")
    val transferEnable: Long? = null,
    @SerialName("speed_limit")
    val speedLimit: Long? = null,
    @SerialName("device_limit")
    val deviceLimit: Long? = null,
    val sell: Boolean = true,
    @SerialName("month_price")
    val monthPrice: Double? = null,
    @SerialName("quarter_price")
    val quarterPrice: Double? = null,
    @SerialName("half_year_price")
    val halfYearPrice: Double? = null,
    @SerialName("year_price")
    val yearPrice: Double? = null,
    @SerialName("two_year_price")
    val twoYearPrice: Double? = null,
    @SerialName("three_year_price")
    val threeYearPrice: Double? = null,
    @SerialName("onetime_price")
    val onetimePrice: Double? = null,
)

data class XboardPlanPeriod(
    val key: String,
    val label: String,
    val price: Double?,
)

@Serializable
data class XboardOrder(
    val id: Int = 0,
    @SerialName("trade_no")
    val tradeNo: String? = null,
    val status: Int? = null,
    val period: String? = null,
    @SerialName("total_amount")
    val totalAmount: Long? = null,
    @SerialName("created_at")
    val createdAt: Long? = null,
    val plan: XboardPlan? = null,
) {
    fun isUnfinished(): Boolean = status == STATUS_PENDING || status == STATUS_PROCESSING

    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_PROCESSING = 1
    }
}

data class XboardImportResult(
    val success: Boolean,
    val profileUuid: String,
)

fun XboardPlan.availablePeriods(): List<XboardPlanPeriod> {
    return listOf(
        XboardPlanPeriod("month_price", "月付", monthPrice),
        XboardPlanPeriod("quarter_price", "季付", quarterPrice),
        XboardPlanPeriod("half_year_price", "半年付", halfYearPrice),
        XboardPlanPeriod("year_price", "年付", yearPrice),
        XboardPlanPeriod("two_year_price", "两年付", twoYearPrice),
        XboardPlanPeriod("three_year_price", "三年付", threeYearPrice),
        XboardPlanPeriod("onetime_price", "一次性", onetimePrice),
    ).filter { (it.price ?: 0.0) > 0.0 }
}

private fun JsonElement?.asBooleanFlag(): Boolean {
    val primitive = this as? JsonPrimitive ?: return false

    primitive.booleanOrNull?.let { return it }

    val content = primitive.contentOrNull?.trim().orEmpty()
    if (content.isEmpty()) {
        return false
    }

    return content == "1" || content.equals("true", ignoreCase = true)
}
