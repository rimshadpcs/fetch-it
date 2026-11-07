package com.rimapps.fetchit

import com.rimapps.fetchit.data.ActivityEntry
import com.rimapps.fetchit.data.Firebase
import com.rimapps.fetchit.data.Watch
import com.rimapps.fetchit.data.json
import com.rimapps.fetchit.ui.money
import com.rimapps.fetchit.ui.weekday
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedCommonTest {
    @Test
    fun decodesAWatchDocument() {
        // Shape written by watchOffer: integer target, timestamps, a colour map, nulls.
        val doc = json.parseToJsonElement(
            """
            {"name": "projects/p/databases/(default)/documents/users/u/watches/w1",
             "fields": {
               "status": {"stringValue": "watching"}, "productId": {"stringValue": "LtIyk1j"},
               "merchant": {"stringValue": "wayfair.com"}, "title": {"stringValue": "Lamp"},
               "color": {"mapValue": {"fields": {"option": {"stringValue": "Color"}, "label": {"stringValue": "Amber"}}}},
               "imageUrl": {"nullValue": null}, "targetPrice": {"integerValue": "30"}, "currency": {"stringValue": "USD"},
               "size": {"nullValue": null}, "placeId": {"stringValue": "home"}, "startPrice": {"doubleValue": 42.99},
               "lastPrice": {"doubleValue": 39.5}, "lastCheckedAt": {"timestampValue": "2026-11-07T18:30:00Z"},
               "channel3Tracking": {"stringValue": "active"}, "purchaseId": {"nullValue": null},
               "createdAt": {"timestampValue": "2026-11-07T18:30:00Z"}, "updatedAt": {"timestampValue": "2026-11-07T18:30:00Z"}
             }}
            """,
        ).jsonObject
        val w = json.decodeFromJsonElement<Watch>(Firebase.decodeDocument(doc))
        assertEquals("w1", w.id)
        assertEquals(30.0, w.targetPrice)
        assertEquals(true, w.watching)
        assertEquals(true, w.dropped)
    }

    @Test
    fun decodesFirestoreRestDocuments() {
        val doc = json.parseToJsonElement(
            """
            {"name": "projects/p/databases/(default)/documents/users/u/activity/abc123",
             "fields": {
               "type": {"stringValue": "purchase"}, "status": {"stringValue": "blocked"},
               "price": {"doubleValue": 41.99}, "purchaseId": {"stringValue": "p1"},
               "createdAt": {"timestampValue": "2026-10-21T18:30:00.123Z"},
               "retailer": {"nullValue": null},
               "reasons": {"arrayValue": {"values": [{"mapValue": {"fields": {
                 "rule": {"stringValue": "category"}, "pass": {"booleanValue": false},
                 "onFail": {"stringValue": "block"}, "message": {"stringValue": "Electronics isn't allowed"}}}}]}}
             }}
            """,
        ).jsonObject
        val e = json.decodeFromJsonElement<ActivityEntry>(Firebase.decodeDocument(doc))
        assertEquals("abc123", e.id)
        assertEquals(41.99, e.price)
        assertEquals(1792607400123L, e.createdAt)
        assertEquals(null, e.retailer)
        assertEquals("Electronics isn't allowed", e.reasons.single().message)
    }

    @Test
    fun formatsMoneyAndDays() {
        assertEquals("$42", money(42.0))
        assertEquals("$41.99", money(41.99))
        assertEquals("$0.50", money(0.5))
        assertEquals("Friday", weekday("2026-10-30"))
        assertEquals(null, weekday("not a date"))
    }
}
