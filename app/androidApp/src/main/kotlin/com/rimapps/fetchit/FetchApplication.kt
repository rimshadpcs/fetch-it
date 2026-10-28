package com.rimapps.fetchit

import android.app.Application
import com.google.firebase.appcheck.FirebaseAppCheck
import com.rimapps.fetchit.platform.AndroidFirebase

class FetchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before any activity or the messaging service needs the context or FirebaseApp.
        AndroidFirebase.init(this)
        // Debug or Play Integrity, by build type (src/debug, src/release). Tokens go on every request (Firebase.kt).
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(appCheckProviderFactory())
    }
}
