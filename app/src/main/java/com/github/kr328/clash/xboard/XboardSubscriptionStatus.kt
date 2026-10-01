package com.github.kr328.clash.xboard

fun XboardUserInfo.hasActiveSubscription(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
    val planId = planId ?: return false
    if (planId <= 0) {
        return false
    }

    val expiredAt = expiredAt ?: return true
    return expiredAt <= 0L || expiredAt > nowSeconds
}

fun XboardUserInfo.isSubscriptionExpired(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
    return !hasActiveSubscription(nowSeconds)
}
