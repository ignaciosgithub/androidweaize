package com.weaize.app

import android.content.Context
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Uploads encrypted location rows to a Supabase Postgres table via the PostgREST endpoint.
 *
 * Rows are append-only (no deletes), so history is preserved. The payload column contains only
 * ciphertext; see [Crypto]. If a backup credential set is configured, uploads that fail against
 * the primary service are retried against the backup.
 */
class SupabaseClient(private val context: Context) {
  private val http =
      OkHttpClient.Builder()
          .connectTimeout(java.time.Duration.ofSeconds(10))
          .callTimeout(java.time.Duration.ofSeconds(20))
          .build()

  private val json = "application/json".toMediaType()

  fun uploadLocation(
      lat: Double,
      lon: Double,
      speedMps: Float,
      bearing: Float,
      accuracy: Float,
      timestampMs: Long,
  ): Boolean {
    val privateKey = Prefs.privateKey(context)
    if (privateKey.isBlank()) return false

    val payload =
        JSONObject()
            .put("lat", lat)
            .put("lon", lon)
            .put("speed_mps", speedMps)
            .put("bearing", bearing)
            .put("accuracy_m", accuracy)
            .put("timestamp_ms", timestampMs)
            .toString()

    val row =
        JSONObject()
            .put("device_id", Prefs.deviceId(context))
            .put("recorded_at", java.time.Instant.ofEpochMilli(timestampMs).toString())
            .put("payload", Crypto.encrypt(privateKey, payload))
            .toString()

    val services =
        listOf(
                Prefs.supabaseUrl(context) to Prefs.supabaseApiKey(context),
                Prefs.supabaseUrl2(context) to Prefs.supabaseApiKey2(context),
            )
            .filter { (url, key) -> url.isNotBlank() && key.isNotBlank() }
    for ((baseUrl, apiKey) in services) {
      if (post(baseUrl, apiKey, row)) return true
    }
    return false
  }

  private fun post(baseUrl: String, apiKey: String, row: String): Boolean {
    val request =
        Request.Builder()
            .url("$baseUrl/rest/v1/locations")
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $apiKey")
            .header("Prefer", "return=minimal")
            .post(row.toRequestBody(json))
            .build()
    return try {
      http.newCall(request).execute().use { it.isSuccessful }
    } catch (e: IOException) {
      false
    }
  }
}
