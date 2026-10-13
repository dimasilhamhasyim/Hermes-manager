package com.nous.hermesmanager

import android.app.Application

class HermesApp : Application() {
    lateinit var settingsStore: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
    }
}
