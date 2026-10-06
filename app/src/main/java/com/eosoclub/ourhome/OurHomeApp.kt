package com.eosoclub.ourhome

import android.app.Application
import android.content.Context
import com.eosoclub.ourhome.data.AppSettings
import com.eosoclub.ourhome.data.SessionManager

class OurHomeApp : Application() {
    lateinit var session: SessionManager
        private set
    lateinit var settings: AppSettings
        private set

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        session = SessionManager(
            prefs = prefs,
            cookiePrefs = getSharedPreferences("cookies", Context.MODE_PRIVATE),
        )
        settings = AppSettings(prefs)
    }
}
