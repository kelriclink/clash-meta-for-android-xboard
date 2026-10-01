package com.github.kr328.clash.xboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class XboardApiService(
    baseUrl: String,
    private val authData: String? = null,
    context: Context? = null,
) {
    private val endpoint = normalizeBaseUrl(baseUrl)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .apply {
            directNetwork(context)?.let { network ->
                // Keep XBoard traffic outside Clash's VPN tunnel. This is
                // important when the imported subscription is expired or
                // otherwise prevents the panel from loading.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    proxy(Proxy.NO_PROXY)
                    socketFactory(network.socketFactory)
                    dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> {
                            return network.getAllByName(hostname).toList()
                        }
                    })
                }
            }
        }
        .build()

    suspend fun login(email: String, password: String): XboardAuthData {
        return parseData(
            postForm(
                path = "/api/v1/passport/auth/login",
                fields = mapOf("email" to email, "password" to password),
            )
        )
    }

    suspend fun register(
        email: String,
        password: String,
        inviteCode: String? = null,
        emailCode: String? = null,
    ): XboardAuthData {
        val fields = mutableMapOf("email" to email, "password" to password)
        inviteCode?.takeIf { it.isNotBlank() }?.let { fields["invite_code"] = it }
        emailCode?.takeIf { it.isNotBlank() }?.let { fields["email_code"] = it }

        return parseData(
            postForm(
                path = "/api/v1/passport/auth/register",
                fields = fields,
            )
        )
    }

    suspend fun resetPassword(
        email: String,
        emailCode: String,
        password: String,
    ): Boolean {
        postForm(
            path = "/api/v1/passport/auth/forget",
            fields = mapOf(
                "email" to email,
                "email_code" to emailCode,
                "password" to password,
            ),
        )
        return true
    }

    suspend fun sendEmailVerify(email: String): Boolean {
        postForm(
            path = "/api/v1/passport/comm/sendEmailVerify",
            fields = mapOf("email" to email),
        )
        return true
    }

    suspend fun getGuestConfig(): XboardGuestConfig {
        return parseData(get("/api/v1/guest/comm/config"))
    }

    suspend fun getUserInfo(): XboardUserInfo {
        return parseData(get("/api/v1/user/info"))
    }

    suspend fun getSubscribe(): XboardSubscribeInfo {
        return parseData(get("/api/v1/user/getSubscribe"))
    }

    suspend fun getAppVersion(token: String): XboardAppVersion {
        require(token.isNotBlank()) { "XBoard subscription token is empty" }
        return parseData(
            get(
                path = "/api/v1/client/app/getVersion",
                query = mapOf("token" to token),
            )
        )
    }

    suspend fun fetchNoticesPage(current: Int): List<XboardNotice> {
        return parseData(
            get(
                path = "/api/v1/user/notice/fetch",
                query = mapOf("current" to current.toString()),
            )
        )
    }

    suspend fun fetchTickets(): List<XboardTicket> {
        return parseData(get("/api/v1/user/ticket/fetch"))
    }

    suspend fun fetchTicket(id: Int): XboardTicket {
        return parseData(
            get(
                path = "/api/v1/user/ticket/fetch",
                query = mapOf("id" to id.toString()),
            )
        )
    }

    suspend fun createTicket(subject: String, level: Int, message: String) {
        postForm(
            path = "/api/v1/user/ticket/save",
            fields = mapOf(
                "subject" to subject,
                "level" to level.toString(),
                "message" to message,
            ),
        )
    }

    suspend fun replyTicket(id: Int, message: String) {
        postForm(
            path = "/api/v1/user/ticket/reply",
            fields = mapOf("id" to id.toString(), "message" to message),
        )
    }

    suspend fun closeTicket(id: Int) {
        postForm(
            path = "/api/v1/user/ticket/close",
            fields = mapOf("id" to id.toString()),
        )
    }

    suspend fun fetchPlans(): List<XboardPlan> {
        return parseData(get("/api/v1/user/plan/fetch"))
    }

    suspend fun fetchOrders(status: Int? = null): List<XboardOrder> {
        val query = status?.let { mapOf("status" to it.toString()) } ?: emptyMap()
        return parseData(get("/api/v1/user/order/fetch", query))
    }

    suspend fun createOrder(planId: Int, period: String): String {
        val data = postForm(
            path = "/api/v1/user/order/save",
            fields = mapOf("plan_id" to planId.toString(), "period" to period),
        )

        return parseScalar(data, "Order response is empty")
    }

    suspend fun checkoutOrder(tradeNo: String) {
        postForm(
            path = "/api/v1/user/order/checkout",
            fields = mapOf("trade_no" to tradeNo),
        )
    }

    suspend fun getQuickLoginUrl(redirect: String): String {
        val data = postForm(
            path = "/api/v1/user/getQuickLoginUrl",
            fields = mapOf("redirect" to redirect),
        )

        return parseScalar(data, "Quick login url is empty")
    }

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonElement? {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(buildUrl(path, query))
                .get()
                .applyCommonHeaders()
                .build()

            executeWrapped(request)
        }
    }

    private suspend fun postForm(path: String, fields: Map<String, String>): JsonElement? {
        return withContext(Dispatchers.IO) {
            val formBody = FormBody.Builder().apply {
                fields.forEach { (key, value) ->
                    if (value.isNotBlank()) {
                        add(key, value)
                    }
                }
            }.build()

            val request = Request.Builder()
                .url(buildUrl(path))
                .post(formBody)
                .applyCommonHeaders()
                .build()

            executeWrapped(request)
        }
    }

    private fun Request.Builder.applyCommonHeaders(): Request.Builder {
        header("Accept", "application/json")
        header("User-Agent", "ClashMetaForAndroid-XBoard")
        authData?.takeIf { it.isNotBlank() }?.let {
            header("Authorization", it)
        }
        return this
    }

    private fun executeWrapped(request: Request): JsonElement? {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val parsed = parseJson(body)
            val parsedMessage = extractMessage(parsed)

            if (!response.isSuccessful) {
                throw IOException(parsedMessage ?: "HTTP ${response.code}")
            }

            val apiResponse = parsed?.let {
                runCatching { json.decodeFromJsonElement<XboardApiResponse>(it) }
                    .getOrElse { throw IOException("Invalid XBoard response: ${it.message}", it) }
            } ?: throw IOException("Invalid XBoard response: ${previewBody(body)}")

            if (!apiResponse.status.isNullOrBlank() &&
                !apiResponse.status.equals("success", ignoreCase = true)
            ) {
                throw IOException(parsedMessage ?: "XBoard request failed")
            }

            return unwrapResourceData(apiResponse.data)
        }
    }

    private fun parseJson(body: String): JsonElement? {
        if (body.isBlank()) {
            return null
        }

        return runCatching { json.parseToJsonElement(body) }.getOrNull()
    }

    private fun previewBody(body: String): String {
        return body
            .replace(Regex("\\s+"), " ")
            .take(120)
            .ifBlank { "<empty>" }
    }

    private fun extractMessage(element: JsonElement?): String? {
        val obj = element as? JsonObject ?: return null

        obj["message"]?.let { message ->
            val value = (message as? JsonPrimitive)?.contentOrNull
            if (!value.isNullOrBlank()) {
                return value
            }
        }

        obj["error"]?.let { error ->
            extractNestedMessage(error)?.let { return it }
        }

        obj["errors"]?.let { errors ->
            extractNestedMessage(errors)?.let { return it }
        }

        return null
    }

    private fun extractNestedMessage(element: JsonElement?): String? {
        return when (element) {
            null -> null
            is JsonPrimitive -> element.contentOrNull
            is JsonArray -> element.firstNotNullOfOrNull { extractNestedMessage(it) }
            is JsonObject -> element.values.firstNotNullOfOrNull { extractNestedMessage(it) }
            else -> null
        }
    }

    private fun unwrapResourceData(data: JsonElement?): JsonElement? {
        val obj = data as? JsonObject ?: return data
        return obj["data"] ?: data
    }

    private inline fun <reified T> parseData(data: JsonElement?): T {
        val actual = data ?: throw IOException("XBoard response data is empty")
        return runCatching { json.decodeFromJsonElement<T>(actual) }
            .getOrElse { throw IOException("XBoard response data parse failed: ${it.message}", it) }
    }

    private fun parseScalar(data: JsonElement?, message: String): String {
        val primitive = data as? JsonPrimitive ?: throw IOException(message)
        return primitive.contentOrNull ?: throw IOException(message)
    }

    private fun buildUrl(path: String, query: Map<String, String> = emptyMap()): String {
        if (query.isEmpty()) {
            return "$endpoint$path"
        }

        val params = query.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }
        return "$endpoint$path?$params"
    }

    private fun String.urlEncode(): String {
        return URLEncoder.encode(this, "UTF-8")
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
        }

        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            if (trimmed.isBlank()) {
                return trimmed
            }

            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }

        private fun directNetwork(context: Context?): Network? {
            if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return null
            }

            val connectivity = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null

            return connectivity.allNetworks
                .asSequence()
                .mapNotNull { network ->
                    val capabilities = connectivity.getNetworkCapabilities(network)
                    network.takeIf {
                        capabilities?.let {
                            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        } == true
                    }
                }
                .firstOrNull()
        }
    }
}
