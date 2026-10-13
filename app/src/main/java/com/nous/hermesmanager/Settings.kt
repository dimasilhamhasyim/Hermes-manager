package com.nous.hermesmanager

data class Settings(
    val baseUrl: String = "",      // API server, e.g. http://192.168.1.50:8642
    val apiKey: String = "",       // API_SERVER_KEY bearer token
    val model: String = "",        // optional per-request model override
    val provider: String = "",     // optional provider slug
    val dashboardUrl: String = "", // web dashboard, e.g. http://192.168.1.50:9119
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()
}
