package dev.silver.burble

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        S3.watchNetwork(this)
        // A dictation this process's predecessor didn't deliver waits on the bubble.
        Dictation.recover(this)
    }
}
