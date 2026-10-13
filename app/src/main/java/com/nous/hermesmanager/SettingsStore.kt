package com.nous.hermesmanager

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

private object SettingsKeys {
    val BASE_URL = stringPreferencesKey("base_url")
    val API_KEY = stringPreferencesKey("api_key")
    val MODEL = stringPreferencesKey("model")
    val PROVIDER = stringPreferencesKey("provider")
    val DASHBOARD_URL = stringPreferencesKey("dashboard_url")
}

class SettingsStore(private val ctx: Context) {

    val flow: Flow<Settings> = ctx.dataStore.data.map { p ->
        Settings(
            baseUrl = p[SettingsKeys.BASE_URL] ?: "",
            apiKey = p[SettingsKeys.API_KEY] ?: "",
            model = p[SettingsKeys.MODEL] ?: "",
            provider = p[SettingsKeys.PROVIDER] ?: "",
            dashboardUrl = p[SettingsKeys.DASHBOARD_URL] ?: "",
        )
    }

    suspend fun current(): Settings = flow.first()

    suspend fun save(s: Settings) {
        ctx.dataStore.edit { p ->
            p[SettingsKeys.BASE_URL] = s.baseUrl
            p[SettingsKeys.API_KEY] = s.apiKey
            p[SettingsKeys.MODEL] = s.model
            p[SettingsKeys.PROVIDER] = s.provider
            p[SettingsKeys.DASHBOARD_URL] = s.dashboardUrl
        }
    }
}
