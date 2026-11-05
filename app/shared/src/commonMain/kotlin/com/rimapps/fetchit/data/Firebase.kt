package com.rimapps.fetchit.data

import com.rimapps.fetchit.platform.KeyValueStore
import com.rimapps.fetchit.platform.appCheckToken
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.IOException
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** A callable function failed: `status` is the Firebase code (FAILED_PRECONDITION, …), `message` is user-facing. */
class FunctionsException(val status: String, override val message: String, val details: JsonObject? = null) : Exception(message) {
    /** Per-field problems a callable sent back (addPlace: `details.fields`), e.g. postalCode → "ZIP is 5 digits". */
    val fields: Map<String, String> get() = (details?.get("fields") as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content ?: "" } ?: emptyMap()
}

/** App Check token header, verified by callables (ENFORCE_APP_CHECK) and Firestore. */
private const val APP_CHECK = "X-Firebase-AppCheck"

val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/**
 * Firebase over plain REST (Auth, callable Functions, Firestore reads), so the same code runs on Android and iOS
 * without the native SDKs. The app only ever talks to our Functions and reads its own users/{uid} documents.
 */
@OptIn(ExperimentalTime::class)
class Firebase(private val http: HttpClient, private val store: KeyValueStore = KeyValueStore()) {
    private val key = FirebaseConfig.API_KEY
    private val functionsBase = "https://${FirebaseConfig.REGION}-${FirebaseConfig.PROJECT_ID}.cloudfunctions.net"
    private val firestoreBase =
        "https://firestore.googleapis.com/v1/projects/${FirebaseConfig.PROJECT_ID}/databases/(default)/documents"

    // ---- Auth (anonymous: the PayPal link is the real identity; no passwords in the app) ----

    private val tokenLock = Mutex()
    private var idToken: String? = null
    private var expiresAt = 0L

    val uid: String? get() = store.get("uid")
    val signedIn get() = store.get("refreshToken") != null

    suspend fun signInAnonymously() {
        val res = http.post("https://identitytoolkit.googleapis.com/v1/accounts:signUp") {
            parameter("key", key)
            contentType(ContentType.Application.Json)
            setBody("""{"returnSecureToken":true}""")
        }
        val body = json.parseToJsonElement(res.bodyAsText()).jsonObject
        if (!res.status.isSuccess()) throw FunctionsException("AUTH", authError(body))
        saveTokens(body["idToken"]!!.str, body["refreshToken"]!!.str, body["localId"]!!.str, body["expiresIn"]!!.str)
    }

    fun signOut() {
        listOf("uid", "refreshToken").forEach { store.set(it, null) }
        idToken = null
    }

    private suspend fun token(): String = tokenLock.withLock {
        val now = Clock.System.now().toEpochMilliseconds()
        idToken?.takeIf { now < expiresAt - 60_000 }?.let { return it }
        val refresh = store.get("refreshToken") ?: throw FunctionsException("UNAUTHENTICATED", "Sign in first.")
        val res = http.submitForm(
            "https://securetoken.googleapis.com/v1/token?key=$key",
            parameters { append("grant_type", "refresh_token"); append("refresh_token", refresh) },
        )
        val body = json.parseToJsonElement(res.bodyAsText()).jsonObject
        if (!res.status.isSuccess()) throw FunctionsException("UNAUTHENTICATED", authError(body))
        saveTokens(body["id_token"]!!.str, body["refresh_token"]!!.str, body["user_id"]!!.str, body["expires_in"]!!.str)
        idToken!!
    }

    private fun saveTokens(id: String, refresh: String, uid: String, expiresIn: String) {
        idToken = id
        expiresAt = Clock.System.now().toEpochMilliseconds() + expiresIn.toLong() * 1000
        store.set("refreshToken", refresh)
        store.set("uid", uid)
    }

    private fun authError(body: JsonObject): String {
        val msg = body["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Sign-in failed"
        return if (msg.startsWith("ADMIN_ONLY_OPERATION") || msg.startsWith("OPERATION_NOT_ALLOWED")) {
            "Anonymous sign-in is turned off for this Firebase project."
        } else msg
    }

    // ---- Callable functions (https://firebase.google.com/docs/functions/callable-reference) ----

    suspend fun call(name: String, data: JsonElement = JsonObject(emptyMap())): JsonElement {
        val res = retryUnsent { http.post("$functionsBase/$name") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
            appCheckToken()?.let { header(APP_CHECK, it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("data", data) }.toString())
        } }
        val body = runCatching { json.parseToJsonElement(res.bodyAsText()).jsonObject }.getOrNull()
        body?.get("error")?.let { throw it.toFunctionsException() }
        if (!res.status.isSuccess() || body == null) throw FunctionsException("INTERNAL", "Something went wrong (${res.status.value}).")
        return body["result"] ?: JsonNull
    }

    /** Streaming callable: each `message` chunk goes to [onChunk] as it arrives; returns the final `result`. */
    suspend fun stream(name: String, data: JsonElement, onChunk: suspend (JsonElement) -> Unit): JsonElement {
        val bearer = token()
        val appCheck = appCheckToken()
        return retryUnsent { http.preparePost("$functionsBase/$name") {
            header(HttpHeaders.Authorization, "Bearer $bearer")
            appCheck?.let { header(APP_CHECK, it) }
            header(HttpHeaders.Accept, "text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("data", data) }.toString())
        }.execute { res ->
            it() // headers are in: from here on a failure is not "unsent"
            if (!res.status.isSuccess()) {
                val body = runCatching { json.parseToJsonElement(res.bodyAsText()).jsonObject }.getOrNull()
                throw body?.get("error")?.toFunctionsException() ?: FunctionsException("INTERNAL", "Something went wrong (${res.status.value}).")
            }
            val channel = res.bodyAsChannel()
            var result: JsonElement? = null
            while (result == null) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val event = json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject
                event["message"]?.let { onChunk(it) }
                event["error"]?.let { throw it.toFunctionsException() }
                event["result"]?.let { result = it }
            }
            result ?: throw FunctionsException("UNAVAILABLE", "The connection dropped. Check Activity for what happened.")
        } }
    }

    /**
     * Our one retry policy (OkHttp's silent one is off): re-send only when the connection failed before any
     * response arrived, at most twice. Safe for every callable we have: purchaseOffer is idempotent on its
     * requestId, approve/undo re-check the purchase state, the rest don't charge anything.
     */
    private suspend fun <T> retryUnsent(send: suspend (markReceived: () -> Unit) -> T): T {
        repeat(2) { attempt ->
            var received = false
            try {
                return send { received = true }
            } catch (e: IOException) {
                if (received) throw e
                delay(400L * (attempt + 1))
            }
        }
        return send {}
    }

    private fun JsonElement.toFunctionsException(): FunctionsException {
        val o = jsonObject
        return FunctionsException(o["status"]?.jsonPrimitive?.content ?: "INTERNAL", o["message"]?.jsonPrimitive?.content ?: "Something went wrong.", o["details"] as? JsonObject)
    }

    // ---- Firestore reads (users/{uid}/… only; rules deny everything else) ----

    /** One document as plain JSON (with `id`), or null if it doesn't exist. */
    suspend fun getDoc(path: String): JsonObject? {
        val res = http.get("$firestoreBase/$path") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
            appCheckToken()?.let { header(APP_CHECK, it) }
        }
        if (res.status.value == 404) return null
        val body = json.parseToJsonElement(res.bodyAsText()).jsonObject
        if (!res.status.isSuccess()) throw FunctionsException("FIRESTORE", body["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Read failed")
        return decodeDocument(body)
    }

    suspend fun listDocs(path: String, orderBy: String? = null, pageSize: Int = 100): List<JsonObject> {
        val res = http.get("$firestoreBase/$path") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
            appCheckToken()?.let { header(APP_CHECK, it) }
            parameter("pageSize", pageSize)
            orderBy?.let { parameter("orderBy", it) }
        }
        val body = json.parseToJsonElement(res.bodyAsText()).jsonObject
        if (!res.status.isSuccess()) throw FunctionsException("FIRESTORE", body["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Read failed")
        return body["documents"]?.jsonArray?.map { decodeDocument(it.jsonObject) } ?: emptyList()
    }

    companion object {
        /** Firestore REST document → plain JSON object with an `id`. Timestamps become epoch millis. */
        fun decodeDocument(doc: JsonObject): JsonObject {
            val fields = doc["fields"]?.jsonObject ?: JsonObject(emptyMap())
            val id = doc["name"]?.jsonPrimitive?.content?.substringAfterLast('/') ?: ""
            return JsonObject(mapOf("id" to JsonPrimitive(id)) + fields.mapValues { decodeValue(it.value.jsonObject) })
        }

        fun decodeValue(v: JsonObject): JsonElement {
            val (type, value) = v.entries.first()
            return when (type) {
                "nullValue" -> JsonNull
                "booleanValue", "stringValue", "referenceValue" -> value
                "integerValue" -> JsonPrimitive(value.jsonPrimitive.content.toLong())
                "doubleValue" -> JsonPrimitive(value.jsonPrimitive.content.toDouble())
                "timestampValue" -> JsonPrimitive(Instant.parse(value.jsonPrimitive.content).toEpochMilliseconds())
                "mapValue" -> JsonObject(
                    (value.jsonObject["fields"]?.jsonObject ?: JsonObject(emptyMap())).mapValues { decodeValue(it.value.jsonObject) },
                )
                "arrayValue" -> JsonArray(value.jsonObject["values"]?.jsonArray?.map { decodeValue(it.jsonObject) } ?: emptyList())
                else -> JsonNull
            }
        }
    }
}

private val JsonElement.str get() = jsonPrimitive.content
