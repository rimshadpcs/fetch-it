package com.rimapps.fetchit.data

import com.rimapps.fetchit.platform.httpClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Everything the screens need from the backend, typed. One instance for the app. */
@OptIn(ExperimentalTime::class)
class FetchApi(val firebase: Firebase = Firebase(httpClient())) {
    private inline fun <reified T> JsonElement.to(): T = json.decodeFromJsonElement(this)

    // ---- Onboarding ----
    suspend fun startPayPalLink(): PayPalLinkStart = firebase.call("startPayPalLink").to()
    suspend fun completePayPalLink(setupTokenId: String): PayPalLinkResult =
        firebase.call("completePayPalLink", buildJsonObject { put("token", setupTokenId) }).to()
    suspend fun confirmPlace(placeId: String) { firebase.call("confirmPlace", buildJsonObject { put("placeId", placeId) }) }
    suspend fun setRules(rules: HouseRules): HouseRules = firebase.call("setRules", json.encodeToJsonElement(rules)).to()

    /** Only the fields given are changed (sizes: all four are sent, null clears one). */
    suspend fun setProfile(sizes: ProfileSizes? = null, askIfUnsure: Boolean? = null, dogName: String? = null): Profile =
        firebase.call(
            "setProfile",
            buildJsonObject {
                sizes?.let { put("sizes", json.encodeToJsonElement(ProfileSizes.serializer(), it)) }
                askIfUnsure?.let { put("askIfUnsure", it) }
                dogName?.let { put("dogName", it) }
            },
        ).to()

    // ---- Fetch ----
    /**
     * Photo → items → offers. [onItems] fires as soon as identify answers (before Channel3 search finishes),
     * so the screen can say "I think it's a…" early.
     */
    suspend fun fetchItem(
        jpeg: ByteArray,
        itemIndex: Int? = null,
        itemsToken: String? = null,
        onItems: (List<DetectedItem>) -> Unit = {},
    ): FetchResponse {
        val data = buildJsonObject {
            put("imageBase64", Base64.encode(jpeg))
            itemIndex?.let { put("itemIndex", it) }
            itemsToken?.let { put("itemsToken", it) }
        }
        val result = firebase.stream("fetchItem", data) { chunk ->
            val o = chunk.jsonObject
            if (o["type"]?.jsonPrimitive?.content == "items") onItems(o["items"]!!.to())
        }
        return result.to()
    }

    // ---- Buy ----
    /** Streams the rules decision first ([onDecision]), then for an auto-buy waits out the undo window and pays. */
    suspend fun purchaseOffer(offerId: String, placeId: String, size: String?, requestId: String, onDecision: (Purchase) -> Unit): Purchase {
        val data = buildJsonObject {
            put("offerId", offerId)
            put("placeId", placeId)
            put("requestId", requestId)
            size?.let { put("size", it) }
        }
        return firebase.stream("purchaseOffer", data) { onDecision(it.to()) }.to()
    }

    suspend fun approvePurchase(purchaseId: String): Purchase =
        firebase.call("approvePurchase", buildJsonObject { put("purchaseId", purchaseId) }).to()

    /** Undo inside the window, or decline an approval request. */
    suspend fun undoPurchase(purchaseId: String): Purchase =
        firebase.call("undoPurchase", buildJsonObject { put("purchaseId", purchaseId) }).to()

    suspend fun registerPushToken(token: String, platform: String) {
        firebase.call("registerPushToken", buildJsonObject { put("token", token); put("platform", platform) })
    }

    // ---- Reads (Firestore, own documents only) ----
    private fun users() = "users/${firebase.uid}"

    suspend fun user(): UserDoc = firebase.getDoc(users())?.to() ?: UserDoc()
    suspend fun rules(): HouseRules? = firebase.getDoc("${users()}/settings/rules")?.to()
    /** A purchase document (e.g. opened from a notification), in the shape the purchase callables return. */
    suspend fun purchase(purchaseId: String): Purchase? =
        firebase.getDoc("${users()}/purchases/$purchaseId")?.let { JsonObject(it + ("purchaseId" to JsonPrimitive(purchaseId))).to() }

    suspend fun profile(): Profile? = firebase.getDoc("${users()}/profile/main")?.to()
    suspend fun places(): List<Place> =
        firebase.listDocs("${users()}/places").map { it.withPlaceId().to() }

    /** Spent or reserved this calendar month (UTC month, like the backend's spend/{YYYY-MM}). */
    suspend fun monthToDate(): Double {
        val month = Clock.System.now().toString().take(7)
        val doc = firebase.getDoc("${users()}/spend/$month") ?: return 0.0
        return (doc["totalCents"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) / 100
    }

    suspend fun activity(): List<ActivityEntry> =
        firebase.listDocs("${users()}/activity", orderBy = "createdAt desc", pageSize = 100).map { it.to() }

    /** The simulated order's promised delivery date (YYYY-MM-DD), once the purchase is paid. */
    suspend fun promisedDelivery(purchaseId: String): String? =
        firebase.getDoc("${users()}/orders/$purchaseId")?.get("promisedDeliveryDate")?.jsonPrimitive?.content

    private fun JsonObject.withPlaceId() = JsonObject(this + ("placeId" to this["id"]!!))
}
