package com.parcelgrade.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : IOException(message)

object Http {
    private const val UA = "ParcelGrade/1.0 (Android land-deal analyzer)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Longer timeouts for the AI research call, which runs several web searches. */
    val slowClient: OkHttpClient = client.newBuilder()
        .readTimeout(240, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()

    private fun execute(req: Request, c: OkHttpClient = client): String {
        c.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code}: ${body.take(300)}")
            return body
        }
    }

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        execute(b.build())
    }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject =
        JSONObject(getText(url, headers))

    suspend fun getJsonArray(url: String, headers: Map<String, String> = emptyMap()): JSONArray =
        JSONArray(getText(url, headers))

    suspend fun postJson(
        url: String, body: JSONObject, headers: Map<String, String> = emptyMap(), slow: Boolean = false,
    ): JSONObject = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        JSONObject(execute(b.build(), if (slow) slowClient else client))
    }

    suspend fun postForm(url: String, fields: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url(url).header("User-Agent", UA).post(form).build()
        JSONObject(execute(req))
    }
}

// Small org.json helpers that treat JSON null and missing keys the same way.
fun JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

fun JSONObject.num(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)
    return when (v) {
        is Number -> v.toDouble()
        is String -> v.replace(Regex("[$,\\s]"), "").toDoubleOrNull()
        else -> null
    }
}

fun JSONObject.bool(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Boolean -> v
        is String -> when (v.lowercase()) { "true", "yes" -> true; "false", "no" -> false; else -> null }
        else -> null
    }
}

fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
