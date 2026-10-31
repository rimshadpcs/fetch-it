package com.rimapps.fetchit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.rimapps.fetchit.platform.PushOpens
import com.rimapps.fetchit.platform.systemReduceMotion
import com.rimapps.fetchit.ui.MotionLabScreen
import com.rimapps.fetchit.ui.motion.LocalReduceMotion
import com.rimapps.fetchit.ui.motion.LocalScreenScope
import com.rimapps.fetchit.ui.motion.LocalScreenShake
import com.rimapps.fetchit.ui.motion.LocalSharedScope
import com.rimapps.fetchit.ui.motion.ScreenShake
import com.rimapps.fetchit.ui.motion.shakeable
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
import com.rimapps.fetchit.ui.OrderScreen
import com.rimapps.fetchit.ui.OnbSizesScreen
import com.rimapps.fetchit.ui.ReadyScreen
import com.rimapps.fetchit.ui.ResultsScreen
import com.rimapps.fetchit.ui.RulesScreen
import com.rimapps.fetchit.ui.WelcomeScreen
import com.rimapps.fetchit.ui.WhichOneScreen

/** [register] gives the host (Android back button) a "go back" function; it returns false when there is nowhere to go. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun App(register: ((() -> Boolean) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val app = remember { AppState(scope) }
    LaunchedEffect(Unit) {
        register?.invoke { app.back() }
        if (app.api.firebase.signedIn) {
            app.refresh()
            if (app.onboarded) app.registerPush()
        }
    }
    // A tapped "Needs your OK" / price-drop notification.
    LaunchedEffect(Unit) {
        PushOpens.pending.collect { open -> if (open != null) { PushOpens.pending.value = null; app.openFromPush(open) } }
    }
    val reduceMotion = systemReduceMotion()
    val shake = remember { ScreenShake() }
    FetchTheme {
        CompositionLocalProvider(LocalReduceMotion provides reduceMotion, LocalScreenShake provides shake) {
            SharedTransitionLayout(Modifier.fillMaxSize().shakeable(shake)) {
                CompositionLocalProvider(LocalSharedScope provides this) {
                    AnimatedContent(app.screen, transitionSpec = { screenTransition(initialState, targetState) }, label = "screen") { screen ->
                        CompositionLocalProvider(LocalScreenScope provides this) {
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
                                    Screen.MotionLab -> MotionLabScreen(app)
                                    Screen.WhichOne -> WhichOneScreen(app)
                                    Screen.Results -> ResultsScreen(app)
                                    is Screen.Buying -> BuyingScreen(app, screen)
                                    is Screen.Approve -> ApproveScreen(app, screen)
                                    is Screen.Done -> DoneScreen(app, screen)
                                    is Screen.Order -> OrderScreen(app, screen)
                                }
                            }
                        }
                    }
                }
            }
            // Edge-to-edge: keep scrolled content from showing through the status bar.
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Ink.Background.copy(alpha = 0.94f)))
        }
    }
}

/** The photo flows from Fetch into Which one? / Results as a shared element: those switches cross-fade slower so it can travel. */
private fun screenTransition(from: Screen, to: Screen): ContentTransform {
    val photoFlow = setOf<Screen>(Screen.Fetch, Screen.WhichOne, Screen.Results)
    return if (from in photoFlow && to in photoFlow && from != to) fadeIn(tween(420)) togetherWith fadeOut(tween(420))
    else fadeIn(tween(180)) togetherWith fadeOut(tween(120))
}
