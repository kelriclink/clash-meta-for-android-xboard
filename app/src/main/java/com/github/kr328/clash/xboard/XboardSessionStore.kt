package com.github.kr328.clash.xboard

import android.content.Context

class XboardSessionStore(context: Context) {
    val appContext: Context = context.applicationContext
    private val storage = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun getSession(): XboardSession? {
        val baseUrl = storage.getString(KEY_BASE_URL, null).orEmpty()
        val authData = storage.getString(KEY_AUTH_DATA, null).orEmpty()

        if (baseUrl.isBlank() || authData.isBlank()) {
            return null
        }

        return XboardSession(
            baseUrl = baseUrl,
            authData = authData,
            token = storage.getString(KEY_TOKEN, null),
            email = storage.getString(KEY_EMAIL, null),
            profileUuid = storage.getString(KEY_PROFILE_UUID, null),
        )
    }

    fun saveSession(session: XboardSession) {
        storage.edit()
            .putString(KEY_BASE_URL, session.baseUrl)
            .putString(KEY_AUTH_DATA, session.authData)
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_PROFILE_UUID, session.profileUuid)
            .apply()
    }

    fun updateProfileUuid(uuid: String) {
        storage.edit().putString(KEY_PROFILE_UUID, uuid).apply()
    }

    fun updateToken(token: String) {
        if (token.isNotBlank()) {
            storage.edit().putString(KEY_TOKEN, token).apply()
        }
    }

    fun getUpdateIntervalMinutes(): Long {
        return storage.getLong(KEY_UPDATE_INTERVAL_MINUTES, DEFAULT_UPDATE_INTERVAL_MINUTES)
    }

    fun setUpdateIntervalMinutes(minutes: Long) {
        storage.edit().putLong(KEY_UPDATE_INTERVAL_MINUTES, minutes).apply()
    }

    fun clear() {
        storage.edit().clear().apply()
    }

    companion object {
        const val DEFAULT_UPDATE_INTERVAL_MINUTES = 24L * 60L

        private const val FILE_NAME = "xboard"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_AUTH_DATA = "auth_data"
        private const val KEY_TOKEN = "token"
        private const val KEY_EMAIL = "email"
        private const val KEY_PROFILE_UUID = "profile_uuid"
        private const val KEY_UPDATE_INTERVAL_MINUTES = "update_interval_minutes"
    }
}
