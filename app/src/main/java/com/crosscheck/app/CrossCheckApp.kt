package com.crosscheck.app

import android.app.Application
import android.content.Context
import com.crosscheck.app.di.AppContainer

class CrossCheckApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    companion object {
        fun from(context: Context): CrossCheckApp = context.applicationContext as CrossCheckApp
    }
}
