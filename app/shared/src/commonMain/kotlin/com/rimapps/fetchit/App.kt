package com.rimapps.fetchit

import com.rimapps.fetchit.ui.SavedScreen
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.Alignment
import com.rimapps.fetchit.platform.rememberPhotoSource
import com.rimapps.fetchit.ui.BottomNav
import com.rimapps.fetchit.ui.TABS
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import com.rimapps.fetchit.ui.motion.pageTurn
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
import com.rimapps.fetchit.ui.OnbDeliverScreen
import com.rimapps.fetchit.ui.PlaceEditScreen
import com.rimapps.fetchit.ui.PlacesScreen
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
    val photos = rememberPhotoSource { app.startFetch(it) }
    val shake = remember { ScreenShake() }
    FetchTheme {
        CompositionLocalProvider(LocalReduceMotion provides reduceMotion, LocalScreenShake provides shake) { Box(Modifier.fillMaxSize()) {
            SharedTransitionLayout(Modifier.fillMaxSize().shakeable(shake)) {
                CompositionLocalProvider(LocalSharedScope provides this) {
                    AnimatedContent(app.screen, transitionSpec = { screenTransition(initialState, targetState, reduceMotion) }, label = "screen") { screen ->
                        CompositionLocalProvider(LocalScreenScope provides this) {
                            // Onboarding steps turn like pages: a slight swing around the bottom edge as they slide.
                            val turn = if (screen in ONBOARDING && !reduceMotion) transition.animateFloat({ tween(350) }, label = "turn") {
                                when (it) { EnterExitState.PreEnter -> 0f; EnterExitState.Visible -> 1f; EnterExitState.PostExit -> 2f }
                            }.value else 1f
                            Box(Modifier.fillMaxSize().then(if (turn != 1f) Modifier.pageTurn(turn) else Modifier)) {
                                when (screen) {
                                    Screen.Welcome -> WelcomeScreen(app)
                                    Screen.LinkPayPal -> LinkPayPalScreen(app)
                                    Screen.OnbDeliver -> OnbDeliverScreen(app)
                                    Screen.OnbRules -> OnbRulesScreen(app)
                                    Screen.OnbSizes -> OnbSizesScreen(app)
                                    Screen.Ready -> ReadyScreen(app)
                                    Screen.Fetch -> FetchScreen(app)
                                    Screen.Activity -> ActivityScreen(app)
                                    is Screen.Rules -> RulesScreen(app, screen)
                                    Screen.Account -> AccountScreen(app)
                                    Screen.Places -> PlacesScreen(app)
                                    Screen.Saved -> SavedScreen(app)
                                    is Screen.PlaceEdit -> PlaceEditScreen(app, screen)
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
            // The tab bar lives outside the screen switcher so its pill can slide between tabs.
            AnimatedVisibility(
                app.screen in TABS, modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(200)) + slideInVertically(tween(300)) { it / 2 }, exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it / 2 },
            ) { BottomNav(app, photos) }
            // Edge-to-edge: keep scrolled content from showing through the status bar.
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Ink.Background.copy(alpha = 0.94f)))
        } }
    }
}

private val ONBOARDING = listOf<Screen>(Screen.Welcome, Screen.LinkPayPal, Screen.OnbDeliver, Screen.OnbRules, Screen.OnbSizes, Screen.Ready)

/**
 * Onboarding steps slide like turning pages (back = the other way); "Start fetching" slides the camera screen up;
 * the photo flows from Fetch into Which one? / Results as a shared element, so those cross-fade slower so it can
 * travel. Everything else is a quick cross-fade. With reduce motion everything is a cross-fade.
 */
private fun screenTransition(from: Screen, to: Screen, reduce: Boolean): ContentTransform {
    val photoFlow = setOf<Screen>(Screen.Fetch, Screen.WhichOne, Screen.Results)
    return when {
        reduce -> fadeIn(tween(200)) togetherWith fadeOut(tween(150))
        from in ONBOARDING && to in ONBOARDING -> {
            val dir = if (ONBOARDING.indexOf(to) > ONBOARDING.indexOf(from)) 1 else -1
            (slideInHorizontally(tween(350)) { it * dir } + fadeIn(tween(250))) togetherWith
                (slideOutHorizontally(tween(350)) { -it * dir / 3 } + fadeOut(tween(250)))
        }
        // Big Scout shrinks into the small one peeking over the viewfinder (shared bounds): a plain cross-fade around it.
        from == Screen.Ready && to == Screen.Fetch -> fadeIn(tween(450)) togetherWith fadeOut(tween(450))
        from in photoFlow && to in photoFlow && from != to -> fadeIn(tween(420)) togetherWith fadeOut(tween(420))
        else -> fadeIn(tween(180)) togetherWith fadeOut(tween(120))
    }
}
