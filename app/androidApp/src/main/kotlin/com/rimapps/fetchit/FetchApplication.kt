package com.rimapps.fetchit

import android.app.Application
import com.rimapps.fetchit.platform.AndroidFirebase

class FetchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before any activity or the messaging service needs the context or FirebaseApp.
        AndroidFirebase.init(this)
    }
}
