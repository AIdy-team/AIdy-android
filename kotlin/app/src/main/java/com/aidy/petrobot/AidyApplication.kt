package com.aidy.petrobot

import android.app.Application
import android.content.Context

class AidyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        context = applicationContext
    }

    companion object {
        lateinit var context: Context
            private set
    }
}
