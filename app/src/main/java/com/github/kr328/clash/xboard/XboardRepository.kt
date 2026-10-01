package com.github.kr328.clash.xboard

class XboardRepository(
    private val sessionStore: XboardSessionStore,
) {
    fun currentSession(): XboardSession? = sessionStore.getSession()

    suspend fun login(email: String, password: String): XboardSession {
        val auth = XboardApiService(DEFAULT_BASE_URL, context = sessionStore.appContext).login(email, password)
        return saveAuthenticatedSession(email, auth)
    }

    suspend fun register(
        email: String,
        password: String,
        inviteCode: String? = null,
        emailCode: String? = null,
    ): XboardSession {
        val auth = XboardApiService(DEFAULT_BASE_URL, context = sessionStore.appContext)
            .register(email, password, inviteCode, emailCode)
        return saveAuthenticatedSession(email, auth)
    }

    suspend fun resetPassword(email: String, emailCode: String, password: String): Boolean {
        return XboardApiService(DEFAULT_BASE_URL, context = sessionStore.appContext)
            .resetPassword(email, emailCode, password)
    }

    suspend fun sendEmailVerify(email: String): Boolean {
        return XboardApiService(DEFAULT_BASE_URL, context = sessionStore.appContext)
            .sendEmailVerify(email)
    }

    suspend fun getGuestConfig(): XboardGuestConfig {
        return XboardApiService(DEFAULT_BASE_URL, context = sessionStore.appContext)
            .getGuestConfig()
    }

    suspend fun getUserInfo(): XboardUserInfo {
        return api().getUserInfo()
    }

    suspend fun getSubscribe(): XboardSubscribeInfo {
        return api().getSubscribe().also { info ->
            info.token?.takeIf { it.isNotBlank() }?.let(sessionStore::updateToken)
        }
    }

    suspend fun getAppVersion(): XboardAppVersion {
        val session = requireNotNull(sessionStore.getSession()) { "请先登录 XBoard" }
        val token = session.token?.takeIf { it.isNotBlank() }
            ?: getSubscribe().token?.takeIf { it.isNotBlank() }
            ?: sessionStore.getSession()?.token?.takeIf { it.isNotBlank() }
            ?: throw java.io.IOException("XBoard subscription token is empty")

        return XboardApiService(session.baseUrl, session.authData, sessionStore.appContext)
            .getAppVersion(token)
    }

    suspend fun fetchNotices(): List<XboardNotice> {
        val notices = buildList {
            for (page in 1..100) {
                val current = api().fetchNoticesPage(page)
                if (current.isEmpty()) break
                addAll(current)
                // The XBoard endpoint currently uses a fixed page size of 5.
                if (current.size < 5) break
            }
        }

        return notices.distinctBy { it.acceptanceKey() }
    }

    suspend fun fetchTickets(): List<XboardTicket> = api().fetchTickets()

    suspend fun fetchTicket(id: Int): XboardTicket = api().fetchTicket(id)

    suspend fun createTicket(subject: String, level: Int, message: String) {
        api().createTicket(subject, level, message)
    }

    suspend fun replyTicket(id: Int, message: String) {
        api().replyTicket(id, message)
    }

    suspend fun closeTicket(id: Int) {
        api().closeTicket(id)
    }

    suspend fun fetchPlans(): List<XboardPlan> {
        return api().fetchPlans()
    }

    suspend fun fetchUnfinishedOrders(): List<XboardOrder> {
        return listOf(
            api().fetchOrders(XboardOrder.STATUS_PENDING),
            api().fetchOrders(XboardOrder.STATUS_PROCESSING),
        ).flatten()
            .filter { it.isUnfinished() && !it.tradeNo.isNullOrBlank() }
            .distinctBy { it.tradeNo }
            .sortedByDescending { it.createdAt ?: 0L }
    }

    suspend fun createOrder(planId: Int, period: String): String {
        return api().createOrder(planId, period)
    }

    suspend fun checkoutOrder(tradeNo: String) {
        api().checkoutOrder(tradeNo)
    }

    suspend fun getQuickLoginUrl(redirect: String): String {
        return api().getQuickLoginUrl(redirect)
    }

    fun logout() {
        sessionStore.clear()
    }

    private fun saveAuthenticatedSession(email: String, auth: XboardAuthData): XboardSession {
        val current = sessionStore.getSession()
        val session = XboardSession(
            baseUrl = DEFAULT_BASE_URL,
            authData = requireNotNull(auth.authData) { "Auth data is empty" },
            token = auth.token,
            email = email,
            profileUuid = current?.profileUuid,
        )

        sessionStore.saveSession(session)
        return session
    }

    private fun api(): XboardApiService {
        val session = requireNotNull(sessionStore.getSession()) { "请先登录 XBoard" }
        return XboardApiService(session.baseUrl, session.authData, sessionStore.appContext)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://xb.linvk.com"
    }
}
