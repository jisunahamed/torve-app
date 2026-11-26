package com.example

import android.app.Application
import com.example.core.TorveContainer

class TorveApplication : Application() {
    lateinit var container: TorveContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = TorveContainer(this)
    }
}
