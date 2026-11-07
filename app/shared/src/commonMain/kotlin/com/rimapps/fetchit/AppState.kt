package com.rimapps.fetchit

import kotlin.uuid.Uuid
import kotlin.uuid.ExperimentalUuidApi
import kotlin.time.ExperimentalTime
import kotlin.time.Clock
import com.rimapps.fetchit.platform.PhotoFiles
import com.rimapps.fetchit.data.json
import kotlinx.serialization.Serializable
import com.rimapps.fetchit.data.SavedItem
import com.rimapps.fetchit.data.Watch
import com.rimapps.fetchit.data.WatchStarted
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
import kotlinx.coroutines.awaitAll
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
    /** House rules (opened from You, the budget chip, or a "Change rule" link). [focus]: the setting to show first. */
    data class Rules(val focus: RuleFocus? = null) : Screen
    /** The You tab: house rules summary, PayPal, places, sizes, Scout's name, notifications, privacy, help, sign out. */
    data object Account : Screen
    /** Every fetched photo as stamps (kept on the phone); [open] a snap's sheet straight away. */
    data class Snaps(val open: String? = null) : Screen
    /** You → Saved: hearted results, each buyable at its live price. */
    data object Saved : Screen
    /** You → Price watches: what's being watched, the target, the last checked price; stop one. */
    data object Watches : Screen
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

/** Which part of the Rules screen a "Change rule" link opens at. */
enum class RuleFocus { Budget, PerItem, Categories, Asks;
    companion object {
        /** The setting behind a rules-engine check id (functions/src/rules.ts), if it's on the Rules screen. */
        fun forRule(rule: String): RuleFocus? = when (rule) {
            "monthly_budget" -> Budget
            "per_item_limit" -> PerItem
            "category" -> Categories
            "new_store", "size" -> Asks
            else -> null
        }
    }
}

/** The user's saved sizes (local for now; a setProfile function would sync them). */
data class Sizes(val top: String? = null, val waist: String? = null, val shoe: String? = null, val fit: String? = null, val askIfUnsure: Boolean = true)

/** An offer as a Snap remembers it, to spot what's new or cheaper next time ("product|store" → price). */
@Serializable
data class SnapOffer(val key: String, val price: Double)

/**
 * A photo the user fetched, kept on this device (PhotoFiles) so they can search again later. [offers] are the
 * matches from the last search; [checkedAt] when that was.
 */
@Serializable
data class Snap(
    val id: String,
    val createdAt: Long,
    val label: String? = null,
    val checkedAt: Long = createdAt,
    val offers: List<SnapOffer> = emptyList(),
)

fun Offer.snapKey() = "$productId|$merchant"

/** One photo's journey: identify → (Which one?) → offers. */
/** [snapId]: the Snap this photo is saved as. [before]: the last search's offers when this is a search-again. */
/** A shared link being turned into a photo ([error] once it failed). */
data class SharedLink(val from: String, val error: String? = null)

class FetchSession(val photo: ByteArray, val snapId: String? = null, val before: List<SnapOffer>? = null, val beforeAt: Long? = null) {
    var identified by mutableStateOf<List<DetectedItem>>(emptyList())
    /** The first answer: "results" for one item, or "choose" (several items + the signed itemsToken). */
    var response by mutableStateOf<FetchResponse?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    /** After "Which one?": each picked item's results (by item index), any that failed, and the one on show. */
    var results by mutableStateOf<Map<Int, FetchResponse>>(emptyMap())
    var resultErrors by mutableStateOf<Map<Int, String>>(emptyMap())
    var current by mutableStateOf<Int?>(null)
    /** Items ticked on "Which one?" (none to start). */
    var picked by mutableStateOf<Set<Int>>(emptySet())

    /** What Results shows: the current picked item's offers, or the single-item answer. */
    val shown: FetchResponse? get() = current?.let { results[it] } ?: response?.takeIf { it.status == "results" }
}

const val MAX_SNAPS = 60

@OptIn(ExperimentalTime::class)
private fun now() = Clock.System.now().toEpochMilliseconds()

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
    // While a profile write is on its way, a refresh must not put the older server copy back (You refreshes the
    // moment Save brings you back to it, and a cold setProfile takes a few seconds).
    private var profileWrites = 0

    private fun writeProfile(call: suspend () -> Profile, onSuccess: (Profile) -> Unit = {}) {
        profileWrites++
        scope.launch {
            runCatching { call() }.onSuccess { if (profileWrites == 1) applyProfile(it); onSuccess(it) } // a newer write still on its way wins
            profileWrites--
        }
    }

    fun saveDogName(name: String) {
        cacheDogName(name.trim().ifBlank { "Scout" })
        val n = dogName
        writeProfile({ api.setProfile(dogName = n) })
    }

    fun saveSizes(s: Sizes) {
        cacheSizes(s)
        writeProfile({ api.setProfile(sizes = ProfileSizes(s.top, s.waist, s.shoe, s.fit), askIfUnsure = s.askIfUnsure) }) {
            rules = rules?.copy(askIfUnsureAboutSize = s.askIfUnsure)
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
        takeSharedPhoto()
        registerPush()
    }

    fun signOut() {
        api.firebase.signOut()
        deleteSnaps(snaps.map { it.id }.toSet()) // they're this user's photos
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
            api.profile()?.let { if (profileWrites == 0) applyProfile(it) }
            places = api.places()
            monthToDate = api.monthToDate()
            activity = api.activity()
            activityLoaded = true
            saved = api.saved()
            watches = api.watches()
        }
    }

    /** After sign-in: tell the backend where to push "Needs your OK". */
    fun registerPush() = scope.launch {
        runCatching { pushToken()?.let { api.registerPushToken(it, platformName) } }
    }

    /** A tapped notification: the approval sheet, the purchase's outcome, or (a drop above target) Price watches. */
    fun openFromPush(p: PushOpen) = scope.launch {
        if (!api.firebase.signedIn) return@launch
        if (p.open == "watch") {
            runCatching { api.watches() }.onSuccess { watches = it }
            reset(Screen.Fetch); go(Screen.Watches)
            return@launch
        }
        runCatching { api.purchase(p.purchaseId) }.getOrNull()?.let(::remember)
        refreshActivity()
        val target = if (p.open == "approve" && purchases.value[p.purchaseId]?.status == "needs_approval") Screen.Approve(p.purchaseId) else Screen.Done(p.purchaseId)
        if (purchases.value[p.purchaseId] != null) { reset(Screen.Activity); go(target) }
    }

    var askedPush: Boolean
        get() = store.get("askedPush") == "1"
        set(v) = store.set("askedPush", if (v) "1" else null)

    /** Hearted results (users/{uid}/saved), newest first. */
    var saved by mutableStateOf<List<SavedItem>>(emptyList())

    fun isSaved(o: Offer) = saved.any { it.productId == o.productId && it.merchant == o.merchant }

    /** Heart / un-heart: the list changes at once, then the server; a failure puts it back. */
    fun toggleSaved(o: Offer) {
        val wasSaved = isSaved(o)
        val before = saved
        saved = if (wasSaved) saved.filterNot { it.productId == o.productId && it.merchant == o.merchant }
        else listOf(SavedItem("", o.productId, o.merchant, o.title, o.imageUrl, o.url, o.price, o.currency)) + saved
        scope.launch {
            runCatching { api.saveItem(o.offerId, !wasSaved); saved = api.saved() }.onFailure { saved = before }
        }
    }

    /** Un-heart from the Saved list (the row is already gone), by its saved id. */
    fun toggleSavedById(item: SavedItem) = scope.launch {
        runCatching { api.unsave(item.id); saved = api.saved() }
            .onFailure { saved = api.saved() }
    }

    /** Price watches (users/{uid}/watches), newest change first. */
    var watches by mutableStateOf<List<Watch>>(emptyList())

    /** The live watch on this item, if any (one per item: watching again changes the target). */
    fun watchFor(o: Offer) = watches.firstOrNull { it.watching && it.productId == o.productId && it.merchant == o.merchant }

    suspend fun startWatch(o: Offer, target: Double, size: String?, placeId: String?): Result<WatchStarted> =
        runCatching { api.watchOffer(o.offerId, target, size, placeId) }
            .onSuccess { runCatching { watches = api.watches() }; refreshActivity() }

    suspend fun stopWatch(w: Watch): Result<Unit> {
        val before = watches
        watches = watches.map { if (it.id == w.id) it.copy(status = "stopped") else it }
        return runCatching { api.stopWatch(w.id) }
            .onSuccess { runCatching { watches = api.watches() }; refreshActivity() }
            .onFailure { watches = before }
    }

    /** False until Activity has loaded once (Activity shows blurred placeholder notes until then). */
    var activityLoaded by mutableStateOf(false)
        private set

    fun refreshActivity() = scope.launch { runCatching { activity = api.activity(); activityLoaded = true; monthToDate = api.monthToDate() } }

    /** A purchase is waiting for the user's OK (its latest Activity entry says so): the red dot on Activity. */
    val needsApproval: Boolean get() = activity.distinctBy { it.purchaseId ?: it.id }.any { it.status == "needs_approval" }

    /** Confirmed places (a PayPal suggestion only counts once the user says yes). */
    val savedPlaces: List<Place> get() = places.filter { !it.suggested }
    /** Where Buy ships unless the user picks another place: the default, else the first saved one. */
    val defaultPlace: Place? get() = savedPlaces.firstOrNull { it.isDefault } ?: savedPlaces.firstOrNull()

    fun refreshPlaces() = scope.launch { runCatching { places = api.places() } }

    fun budgetLeft(): Double? = rules?.let { it.monthlyBudget - monthToDate }

    // ---- Snaps: every fetched photo, kept on this phone so it can be searched again ----
    var snaps by mutableStateOf(loadSnaps())
        private set

    private fun loadSnaps(): List<Snap> = store.get("snaps")?.let { runCatching { json.decodeFromString<List<Snap>>(it) }.getOrNull() } ?: emptyList()

    private fun saveSnaps(list: List<Snap>) {
        snaps = list
        store.set("snaps", json.encodeToString(list))
    }

    /** Keep the newest [MAX_SNAPS]; older photos are deleted from the phone. */
    private fun addSnap(photo: ByteArray): String {
        @OptIn(ExperimentalUuidApi::class) val id = Uuid.random().toHexString()
        PhotoFiles.save(id, photo)
        val list = listOf(Snap(id, now())) + snaps
        list.drop(MAX_SNAPS).forEach { PhotoFiles.delete(it.id) }
        saveSnaps(list.take(MAX_SNAPS))
        return id
    }

    /** After a search: name the snap after what was found and remember its matches (merged over several items). */
    private fun recordSnap(s: FetchSession, label: String?, offers: List<Offer>, fresh: Boolean) {
        val id = s.snapId ?: return
        saveSnaps(snaps.map { snap ->
            if (snap.id != id) snap else {
                val got = offers.map { SnapOffer(it.snapKey(), it.price) }
                snap.copy(
                    label = snap.label ?: label,
                    checkedAt = now(),
                    offers = if (fresh) got else (snap.offers.filter { o -> got.none { it.key == o.key } } + got),
                )
            }
        })
    }

    fun deleteSnaps(ids: Set<String>) {
        ids.forEach { PhotoFiles.delete(it) }
        saveSnaps(snaps.filterNot { it.id in ids })
    }

    /** Search a saved photo again; Results then marks what's new or cheaper than [Snap.offers]. */
    fun searchAgain(snap: Snap) {
        val photo = PhotoFiles.load(snap.id) ?: run { deleteSnaps(setOf(snap.id)); return }
        startFetch(photo, snap)
    }

    // ---- Fetch ----
    /** A photo shared from another app: fetch it now if the user is set up, else it waits for onboarding to finish. */
    fun takeSharedPhoto() {
        if (!onboarded || !api.firebase.signedIn) return
        val shared = com.rimapps.fetchit.platform.SharedPhotos
        shared.pendingLink.value?.let { text ->
            shared.pendingLink.value = null
            fetchLink(text)
        }
        val photo = shared.pending.value ?: return
        shared.pending.value = null
        startFetch(photo)
    }

    /** A link shared from Pinterest / a store page: "Getting the photo from Pinterest…", then the normal fetch. */
    var sharedLink by mutableStateOf<SharedLink?>(null)

    private fun fetchLink(text: String) {
        val from = when {
            "pin.it" in text || "pinterest." in text -> "Pinterest"
            "instagram.com" in text || "instagr.am" in text -> "Instagram"
            else -> "that page"
        }
        val link = SharedLink(from)
        sharedLink = link
        session = null
        reset(Screen.Fetch)
        scope.launch {
            runCatching { ensureSignedIn(); api.linkPhoto(text) }
                .onSuccess { (photo, _) -> if (sharedLink === link) { sharedLink = null; startFetch(photo) } }
                .onFailure { if (sharedLink === link) sharedLink = link.copy(error = it.friendly()) }
        }
    }

    fun startFetch(photo: ByteArray, again: Snap? = null) {
        val snapId = again?.id ?: runCatching { addSnap(photo) }.getOrNull()
        val s = FetchSession(photo, snapId, again?.offers, again?.checkedAt)
        session = s
        reset(Screen.Fetch)
        scope.launch {
            try {
                ensureSignedIn()
                val r = api.fetchItem(photo, onItems = { s.identified = it })
                s.response = r
                s.identified = r.items
                if (r.status == "results") recordSnap(s, r.items.firstOrNull()?.item_type, r.offers, fresh = true)
                else if (r.status == "choose") recordSnap(s, r.items.firstOrNull()?.item_type, emptyList(), fresh = again != null)
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

    /**
     * After "Which one?": search every picked item at once (each reuses the signed items, so no second identify;
     * each is its own fetchItem call). Results opens when they're all back, on the first that found something;
     * the others are tabs there. An item that finds nothing gets a note in its tab.
     */
    fun chooseItems(indices: List<Int>) {
        val s = session ?: return
        val token = s.response?.itemsToken ?: return
        if (indices.isEmpty()) return
        s.loading = true
        s.error = null
        s.results = emptyMap(); s.resultErrors = emptyMap(); s.current = null
        scope.launch {
            indices.map { i ->
                async {
                    try {
                        val r = api.fetchItem(s.photo, itemIndex = i, itemsToken = token)
                        if (r.status == "results" && r.offers.isNotEmpty()) {
                            s.results = s.results + (i to r)
                            recordSnap(s, s.response?.items?.getOrNull(i)?.item_type, r.offers, fresh = false)
                        }
                        else s.resultErrors = s.resultErrors + (i to "Nothing to buy found for that one.")
                    } catch (e: Exception) {
                        s.resultErrors = s.resultErrors + (i to e.friendly())
                    }
                }
            }.awaitAll()
            s.loading = false
            val first = indices.firstOrNull { it in s.results }
            if (first == null) s.error = s.resultErrors.values.firstOrNull() ?: "Nothing to buy found."
            else { s.current = first; showWhenOn(Screen.WhichOne, Screen.Results) }
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
