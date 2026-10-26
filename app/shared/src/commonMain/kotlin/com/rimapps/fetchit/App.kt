package com.rimapps.fetchit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.rimapps.fetchit.ui.AccountScreen
import com.rimapps.fetchit.ui.ActivityScreen
import com.rimapps.fetchit.ui.ApproveScreen
import com.rimapps.fetchit.ui.BuyingScreen
import com.rimapps.fetchit.ui.DoneScreen
import com.rimapps.fetchit.ui.FetchScreen
import com.rimapps.fetchit.ui.FetchTheme
import com.rimapps.fetchit.ui.Ink
import com.rimapps.fetchit.ui.LinkPayPalScreen
import com.rimapps.fetchit.ui.OnbRulesScreen
import com.rimapps.fetchit.ui.OnbSizesScreen
import com.rimapps.fetchit.ui.ReadyScreen
import com.rimapps.fetchit.ui.ResultsScreen
import com.rimapps.fetchit.ui.RulesScreen
import com.rimapps.fetchit.ui.WelcomeScreen
import com.rimapps.fetchit.ui.WhichOneScreen

/** [register] gives the host (Android back button) a "go back" function; it returns false when there is nowhere to go. */
@Composable
fun App(register: ((() -> Boolean) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val app = remember { AppState(scope) }
    LaunchedEffect(Unit) {
        register?.invoke { app.back() }
        if (app.api.firebase.signedIn) app.refresh()
    }
    FetchTheme {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(app.screen, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "screen") { screen ->
                Box(Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.Welcome -> WelcomeScreen(app)
                        Screen.LinkPayPal -> LinkPayPalScreen(app)
                        Screen.OnbRules -> OnbRulesScreen(app)
                        Screen.OnbSizes -> OnbSizesScreen(app)
                        Screen.Ready -> ReadyScreen(app)
                        Screen.Fetch -> FetchScreen(app)
                        Screen.Activity -> ActivityScreen(app)
                        Screen.Rules -> RulesScreen(app)
                        Screen.Account -> AccountScreen(app)
                        Screen.WhichOne -> WhichOneScreen(app)
                        Screen.Results -> ResultsScreen(app)
                        is Screen.Buying -> BuyingScreen(app, screen)
                        is Screen.Approve -> ApproveScreen(app, screen)
                        is Screen.Done -> DoneScreen(app, screen)
                    }
                }
            }
            // Edge-to-edge: keep scrolled content from showing through the status bar.
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Ink.Background.copy(alpha = 0.94f)))
        }
    }
}
