package com.m15.deepgramagent

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize singletons BEFORE any Activity/ViewModel accesses them
        ServiceLocator.init(this)
    }
}

