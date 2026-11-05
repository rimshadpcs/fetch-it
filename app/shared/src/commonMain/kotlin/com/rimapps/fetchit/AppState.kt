package com.rimapps.fetchit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rimapps.fetchit.data.ActivityEntry
import com.rimapps.fetchit.data.DetectedItem
import com.rimapps.fetchit.data.FetchApi
import com.rimapps.fetchit.data.FetchResponse
import com.rimapps.fetchit.data.FunctionsException
import com.rimapps.fetchit.data.HouseRules
import com.rimapps.fetchit.data.Offer
import com.rimapps.fetchit.data.Place
import com.rimapps.fetchit.data.Profile
import com.rimapps.fetchit.data.ProfileSizes
import com.rimapps.fetchit.data.Purchase
import com.rimapps.fetchit.data.UserDoc
import com.rimapps.fetchit.platform.KeyValueStore
import com.rimapps.fetchit.platform.PushOpen
import com.rimapps.fetchit.platform.platformName
import com.rimapps.fetchit.platform.pushToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

sealed interface Screen {
    // Onboarding
    data object Welcome : Screen
    data object LinkPayPal : Screen
    /** "Where should I deliver?": PayPal's address (confirm or replace) or the address form. */
    data object OnbDeliver : Screen
    data object OnbRules : Screen
    data object OnbSizes : Screen
    data object Ready : Screen

    // Main tabs
    data object Fetch : Screen
    data object Activity : Screen
    data object Rules : Screen
    data object Account : Screen
    /** You & settings → Saved places: list, default, delete. */
    data object Places : Screen
    /** Add (placeId null) or edit a saved place. */
    data class PlaceEdit(val placeId: String?) : Screen
    /** Hidden: long-press the dog on Account. Every motion piece on one page, for checking and recording. */
    data object MotionLab : Screen

    // Fetch flow
    data object WhichOne : Screen
    data object Results : Screen
    /** [requestId] is made once per Buy tap; see AppState.buy. [placeId]: where it ships (picked on Results). */
    data class Buying(val offer: Offer, val size: String?, val requestId: String, val placeId: String) : Screen
    data class Approve(val purchaseId: String) : Screen
    data class Done(val purchaseId: String) : Screen
    data class Order(val purchaseId: String) : Screen
}

/** The user's saved sizes (local for now; a setProfile function would sync them). */
data class Sizes(val top: String? = null, val waist: String? = null, val shoe: String? = null, val fit: String? = null, val askIfUnsure: Boolean = true)

/** One photo's journey: identify → (Which one?) → offers. */
class FetchSession(val photo: ByteArray) {
    var identified by mutableStateOf<List<DetectedItem>>(emptyList())
    var response by mutableStateOf<FetchResponse?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
}

class AppState(val scope: CoroutineScope, val api: FetchApi = FetchApi()) {
    private val store = KeyValueStore()

    private val stack = mutableStateListOf<Screen>(initialScreen())
    val screen: Screen get() = stack.last()

    var dogName by mutableStateOf(store.get("dogName") ?: "Scout")
        private set
    var sizes by mutableStateOf(loadSizes())
        private set

    var user by mutableStateOf(UserDoc())
    var rules by mutableStateOf<HouseRules?>(null)
    var places by mutableStateOf<List<Place>>(emptyList())
    var monthToDate by mutableStateOf(0.0)
    var activity by mutableStateOf<List<ActivityEntry>>(emptyList())

    var session by mutableStateOf<FetchSession?>(null)
    /** Latest known state of purchases started in this app run (by id). */
    val purchases = mutableStateOf<Map<String, Purchase>>(emptyMap())

    val onboarded: Boolean get() = store.get("onboarded") == "1"

    private fun initialScreen(): Screen = if (store.get("onboarded") == "1" && api.firebase.signedIn) Screen.Fetch else Screen.Welcome

    // ---- Navigation ----
    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    /** Switch tab / restart a flow: replace the whole stack. */
    fun reset(s: Screen) { stack.clear(); stack.add(s) }
    fun replace(s: Screen) { stack[stack.lastIndex] = s }

    // ---- Profile ----
    // Profile: saved server-side with setProfile (the rules engine reads the sizes); the phone copy is a cache
    // so screens show it instantly and offline.
    fun saveDogName(name: String) {
        cacheDogName(name.trim().ifBlank { "Scout" })
        scope.launch { runCatching { api.setProfile(dogName = dogName) } }
    }

    fun saveSizes(s: Sizes) {
        cacheSizes(s)
        scope.launch {
            runCatching { api.setProfile(sizes = ProfileSizes(s.top, s.waist, s.shoe, s.fit), askIfUnsure = s.askIfUnsure) }
                .onSuccess { rules = rules?.copy(askIfUnsureAboutSize = s.askIfUnsure) }
        }
    }

    private fun cacheDogName(name: String) {
        dogName = name
        store.set("dogName", name)
    }

    private fun cacheSizes(s: Sizes) {
        sizes = s
        store.set("sizes", listOf(s.top, s.waist, s.shoe, s.fit, s.askIfUnsure.toString()).joinToString("|") { it ?: "" })
    }

    /** Server copy wins: another device (or a reinstall) may have changed it. */
    private fun applyProfile(p: Profile) {
        p.dogName?.let(::cacheDogName)
        cacheSizes(Sizes(p.sizes.top, p.sizes.waist, p.sizes.shoe, p.sizes.fit, p.askIfUnsure ?: sizes.askIfUnsure))
    }

    private fun loadSizes(): Sizes {
        val p = store.get("sizes")?.split("|") ?: return Sizes()
        fun at(i: Int) = p.getOrNull(i)?.ifBlank { null }
        return Sizes(at(0), at(1), at(2), at(3), at(4) != "false")
    }

    fun finishOnboarding() {
        store.set("onboarded", "1")
        reset(Screen.Fetch)
        refresh()
        registerPush()
    }

    fun signOut() {
        api.firebase.signOut()
        listOf("onboarded", "dogName", "sizes").forEach { store.set(it, null) }
        dogName = "Scout"; sizes = Sizes(); user = UserDoc(); rules = null; places = emptyList(); activity = emptyList()
        reset(Screen.Welcome)
    }

    suspend fun ensureSignedIn() {
        if (!api.firebase.signedIn) api.firebase.signInAnonymously()
    }

    // ---- Data ----
    fun refresh() = scope.launch {
        runCatching {
            user = api.user()
            rules = api.rules()
            api.profile()?.let(::applyProfile)
            places = api.places()
            monthToDate = api.monthToDate()
        }
    }

    /** After sign-in: tell the backend where to push "Needs your OK". */
    fun registerPush() = scope.launch {
        runCatching { pushToken()?.let { api.registerPushToken(it, platformName) } }
    }

    /** A tapped notification: the approval sheet, or the purchase's outcome. */
    fun openFromPush(p: PushOpen) = scope.launch {
        if (!api.firebase.signedIn) return@launch
        runCatching { api.purchase(p.purchaseId) }.getOrNull()?.let(::remember)
        refreshActivity()
        val target = if (p.open == "approve" && purchases.value[p.purchaseId]?.status == "needs_approval") Screen.Approve(p.purchaseId) else Screen.Done(p.purchaseId)
        if (purchases.value[p.purchaseId] != null) { reset(Screen.Activity); go(target) }
    }

    var askedPush: Boolean
        get() = store.get("askedPush") == "1"
        set(v) = store.set("askedPush", if (v) "1" else null)

    fun refreshActivity() = scope.launch { runCatching { activity = api.activity(); monthToDate = api.monthToDate() } }

    /** Confirmed places (a PayPal suggestion only counts once the user says yes). */
    val savedPlaces: List<Place> get() = places.filter { !it.suggested }
    /** Where Buy ships unless the user picks another place: the default, else the first saved one. */
    val defaultPlace: Place? get() = savedPlaces.firstOrNull { it.isDefault } ?: savedPlaces.firstOrNull()

    fun refreshPlaces() = scope.launch { runCatching { places = api.places() } }

    fun budgetLeft(): Double? = rules?.let { it.monthlyBudget - monthToDate }

    // ---- Fetch ----
    fun startFetch(photo: ByteArray) {
        val s = FetchSession(photo)
        session = s
        reset(Screen.Fetch)
        scope.launch {
            try {
                ensureSignedIn()
                val r = api.fetchItem(photo, onItems = { s.identified = it })
                s.response = r
                s.identified = r.items
                when (r.status) {
                    "choose" -> showWhenOn(Screen.Fetch, Screen.WhichOne)
                    "results" -> showWhenOn(Screen.Fetch, Screen.Results)
                    else -> s.error = "I couldn't spot anything I can buy in that photo. Try a closer shot?"
                }
            } catch (e: Exception) {
                s.error = e.friendly()
            } finally {
                s.loading = false
            }
        }
    }

    /**
     * Results arrive asynchronously. Only open them if the user is still on the screen that asked for them;
     * otherwise a new screen (with Buy where the nav bar was) would appear under a tap meant for something else.
     * The Fetch screen shows a "ready" card instead.
     */
    private fun showWhenOn(expected: Screen, next: Screen) {
        if (screen == expected) go(next)
    }

    /** After "Which one?": search the chosen item (reuses the signed items, so no second identify). */
    fun chooseItem(index: Int) {
        val s = session ?: return
        val token = s.response?.itemsToken ?: return
        s.loading = true
        s.error = null
        scope.launch {
            try {
                val r = api.fetchItem(s.photo, itemIndex = index, itemsToken = token)
                s.response = r
                if (r.status == "results") showWhenOn(Screen.WhichOne, Screen.Results) else s.error = "Nothing to buy found for that one."
            } catch (e: Exception) {
                s.error = e.friendly()
            } finally {
                s.loading = false
            }
        }
    }

    /**
     * One Buy tap = one purchaseOffer call. The call runs in the app scope (leaving or re-entering the Buying
     * screen can't start another), keyed by the tap's requestId, which the server also uses as the purchase id.
     */
    private val buys = mutableMapOf<String, Deferred<Purchase>>()
    fun buy(offer: Offer, placeId: String, size: String?, requestId: String, onDecision: (Purchase) -> Unit): Deferred<Purchase> =
        buys.getOrPut(requestId) {
            scope.async {
                api.purchaseOffer(offer.offerId, placeId, size, requestId) { onDecision(it); remember(it) }.also { remember(it); refresh() }
            }
        }

    fun remember(p: Purchase) { purchases.value = purchases.value + (p.purchaseId to p) }

    /** A purchase we saw in this run, or rebuilt from its latest Activity entry (e.g. "Needs your OK" from Activity). */
    fun purchase(id: String): Purchase? = purchases.value[id] ?: activity.firstOrNull { it.purchaseId == id && it.type == "purchase" }?.let {
        Purchase(
            purchaseId = id, status = it.status, decision = it.decision, summary = it.summary, reasons = it.reasons,
            title = it.title ?: "", merchant = it.merchant ?: "", retailer = it.retailer, imageUrl = it.imageUrl,
            price = it.price, currency = it.currency ?: "USD", size = it.size,
        )
    }
}

fun Throwable.friendly(): String = when (this) {
    is FunctionsException -> message
    else -> "Couldn't reach Fetch It. Check your connection and try again."
}
