package com.rimapps.fetchit

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds: the debug provider. On first run it logs "Enter this debug secret into the allow list…" (tag
 * DebugAppCheckProvider); add that token in the Firebase console → App Check → Apps → Manage debug tokens.
 */
fun appCheckProviderFactory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
