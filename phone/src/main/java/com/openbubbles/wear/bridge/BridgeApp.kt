package com.openbubbles.wear.bridge

import android.app.Application

class BridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationCenter.init(this)
        ActionDedupe.init(this)
    }
}
