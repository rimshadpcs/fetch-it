package com.rimapps.fetchit

import com.rimapps.fetchit.data.ActivityEntry
import com.rimapps.fetchit.data.Firebase
import com.rimapps.fetchit.data.json
import com.rimapps.fetchit.ui.money
import com.rimapps.fetchit.ui.weekday
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedCommonTest {
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
