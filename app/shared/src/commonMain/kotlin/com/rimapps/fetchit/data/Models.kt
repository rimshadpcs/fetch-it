package com.rimapps.fetchit.data

import kotlinx.serialization.Serializable

// Shapes returned by our Cloud Functions (functions/src) and stored in Firestore under users/{uid}/….
// Unknown fields are ignored, so the backend can add fields without breaking the app.

@Serializable
data class Box(val x: Double, val y: Double, val width: Double, val height: Double)

@Serializable
data class DetectedItem(
    val search_query: String = "",
    val category: String = "",
    val item_type: String = "",
    val colors: List<String> = emptyList(),
    val material: String? = null,
    val confidence: Double = 0.0,
    val prominence: Double = 0.0,
    val box: Box? = null,
)

@Serializable
data class SizeOption(val label: String, val size: String? = null, val available: String? = null)

@Serializable
data class ColorOption(
    val label: String? = null,
    val hex: String? = null,
    val productId: String? = null,
    val available: String? = null,
    val imageUrl: String? = null,
    val selected: Boolean = false,
)

@Serializable
data class Offer(
    val offerId: String,
    val productId: String,
    val title: String,
    val brand: String? = null,
    val merchant: String,
    val url: String = "",
    val imageUrl: String? = null,
    val price: Double,
    val compareAtPrice: Double? = null,
    val currency: String = "USD",
    val availability: String = "",
    val color: String? = null,
    val sizes: List<SizeOption>? = null,
    val colors: List<ColorOption> = emptyList(),
    val category: String? = null,
)

/** `results` (offers for one item), `choose` (several items: "Which one?") or `none`. */
@Serializable
data class FetchResponse(
    val status: String,
    val items: List<DetectedItem> = emptyList(),
    val itemIndex: Int? = null,
    val itemsToken: String = "",
    val offers: List<Offer> = emptyList(),
    val timings: Map<String, Double> = emptyMap(),
)

@Serializable
data class Reason(val rule: String, val pass: Boolean, val onFail: String, val message: String)

/** purchaseOffer / approvePurchase / undoPurchase. */
@Serializable
data class Purchase(
    val purchaseId: String,
    val status: String,
    val decision: String? = null,
    val summary: String = "",
    val reasons: List<Reason> = emptyList(),
    val title: String = "",
    val merchant: String = "",
    val retailer: String? = null,
    val merchantOfRecord: String? = null,
    val imageUrl: String? = null,
    val price: Double? = null,
    val currency: String = "USD",
    val size: String? = null,
    val undoUntil: Long? = null,
    val paypalOrderId: String? = null,
    val error: String? = null,
)

@Serializable
data class PayPalLinkStart(val setupTokenId: String, val approvalUrl: String, val returnUrl: String, val cancelUrl: String)

@Serializable
data class Place(
    val placeId: String = "",
    val label: String = "",
    val fullName: String = "",
    val line1: String = "",
    val line2: String? = null,
    val city: String = "",
    val state: String = "",
    val postalCode: String = "",
    val countryCode: String = "",
    val suggested: Boolean = false,
) {
    val oneLine get() = listOf(line1, city, "$state $postalCode".trim()).filter { it.isNotBlank() }.joinToString(", ")
}

@Serializable
data class PayPalLinkResult(val paypalLinked: Boolean, val email: String? = null, val suggestedPlace: Place? = null)

@Serializable
data class HouseRules(
    val currency: String = "USD",
    val monthlyBudget: Double = 200.0,
    val perItemLimit: Double = 75.0,
    val allowedCategories: List<String> = listOf("home-garden", "clothing", "shoes", "books"),
    val askBeforeNewStore: Boolean = true,
    val onlyShipToSavedPlaces: Boolean = true,
    val askIfUnsureAboutSize: Boolean = true,
)

/** users/{uid}/orders/{purchaseId}: the simulated order behind a completed purchase. */
@Serializable
data class Order(
    val id: String = "",
    val purchaseId: String = "",
    val status: String = "",
    val retailer: String = "",
    val retailerOrderId: String = "",
    val items: List<OrderItem> = emptyList(),
    val totalCents: Long = 0,
    val currency: String = "USD",
    val promisedDeliveryDate: String? = null,
    val trackingNumber: String? = null,
    val refund: Refund? = null,
) {
    /** Before it ships, an order can be cancelled (and refunded). */
    val cancellable get() = status == "placed" || status == "confirmed"
}

@Serializable
data class OrderItem(val title: String = "", val imageUrl: String? = null, val size: String? = null)

@Serializable
data class Refund(val id: String = "", val status: String = "", val amountCents: Long = 0)

/** users/{uid}/profile/main (setProfile). Shoe sizes are "US 9" / "UK 8" / "EU 42". */
@Serializable
data class Profile(
    val sizes: ProfileSizes = ProfileSizes(),
    val askIfUnsure: Boolean? = null,
    val dogName: String? = null,
)

@Serializable
data class ProfileSizes(val top: String? = null, val waist: String? = null, val shoe: String? = null, val fit: String? = null)

@Serializable
data class UserDoc(val paypalLinked: Boolean = false, val paypalEmail: String? = null)

/** One users/{uid}/activity entry (purchase, order, paypal or watch). */
@Serializable
data class ActivityEntry(
    val id: String = "",
    val type: String = "",
    val purchaseId: String? = null,
    val status: String = "",
    val summary: String = "",
    val decision: String? = null,
    val reasons: List<Reason> = emptyList(),
    val title: String? = null,
    val merchant: String? = null,
    val retailer: String? = null,
    val price: Double? = null,
    val currency: String? = null,
    val imageUrl: String? = null,
    val size: String? = null,
    val placeLabel: String? = null,
    val promisedDeliveryDate: String? = null,
    val trackingNumber: String? = null,
    /** Epoch millis (Firestore timestampValue). */
    val createdAt: Long = 0,
)

/**
 * Shopping categories on the rules screen → Channel3 category slugs. An offer passes when any slug in its
 * category path is allowed (e.g. home-garden > lighting > lamps > table-lamps).
 */
enum class ShopCategory(val label: String, val slug: String) {
    HomeDecor("Home decor", "home-garden"),
    Clothing("Clothing", "clothing"),
    Shoes("Shoes", "shoes"),
    Books("Books", "books"),
    Electronics("Electronics", "electronics"),
    GiftCards("Gift cards", "gift-cards"),
}
