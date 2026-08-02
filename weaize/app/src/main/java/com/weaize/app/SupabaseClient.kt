package com.weaize.app

import android.content.Context
import java.io.File
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
 *
 * Rows that cannot be delivered (offline, server unreachable) are queued on disk and flushed,
 * oldest first, once an upload succeeds again.
 */
class SupabaseClient(private val context: Context) {

  companion object {
    private const val QUEUE_FILE = "upload_queue.jsonl"
    private const val QUEUE_MAX_ROWS = 20_000
  }

  private val queueFile: File
    get() = File(context.filesDir, QUEUE_FILE)
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
    if (privateKey.isBlank()) {
      Prefs.setLastUploadStatus(context, "no private key set in Settings")
      return false
    }

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
    if (services.isEmpty()) {
      Prefs.setLastUploadStatus(context, "no Supabase URL/API key configured in Settings")
      return false
    }
    val errors = mutableListOf<String>()
    for ((baseUrl, apiKey) in services) {
      val error = post(baseUrl, apiKey, row)
      if (error == null) {
        val flushed = flushQueue(services)
        Prefs.setLastUploadStatus(
            context, if (flushed > 0) "OK (recovered $flushed queued)" else "OK")
        return true
      }
      errors.add("$baseUrl: $error")
    }
    val queued = enqueue(row)
    Prefs.setLastUploadStatus(context, "${errors.joinToString("; ")} ($queued queued)")
    return false
  }

  /** Appends an undeliverable row to the on-disk queue; returns the queue size. */
  @Synchronized
  private fun enqueue(row: String): Int {
    val lines = if (queueFile.exists()) queueFile.readLines().toMutableList() else mutableListOf()
    lines.add(row)
    while (lines.size > QUEUE_MAX_ROWS) lines.removeAt(0)
    queueFile.writeText(lines.joinToString("\n"))
    return lines.size
  }

  /** Uploads queued rows oldest-first; stops at the first failure. Returns rows delivered. */
  @Synchronized
  private fun flushQueue(services: List<Pair<String, String>>): Int {
    if (!queueFile.exists()) return 0
    val lines = queueFile.readLines().filter { it.isNotBlank() }.toMutableList()
    var delivered = 0
    while (lines.isNotEmpty()) {
      val row = lines.first()
      val sent = services.any { (baseUrl, apiKey) -> post(baseUrl, apiKey, row) == null }
      if (!sent) break
      lines.removeAt(0)
      delivered++
    }
    if (lines.isEmpty()) queueFile.delete() else queueFile.writeText(lines.joinToString("\n"))
    return delivered
  }

  /** Returns null on success, otherwise a short error description. */
  private fun post(baseUrl: String, apiKey: String, row: String): String? {
    return try {
      val request =
          Request.Builder()
              .url("$baseUrl/rest/v1/locations")
              .header("apikey", apiKey)
              .header("Authorization", "Bearer $apiKey")
              .header("Prefer", "return=minimal")
              .post(row.toRequestBody(json))
              .build()
      http.newCall(request).execute().use { resp ->
        if (resp.isSuccessful) null
        else "HTTP ${resp.code} ${resp.body?.string()?.take(120) ?: ""}".trim()
      }
    } catch (e: IOException) {
      e.message ?: e.javaClass.simpleName
    } catch (e: IllegalArgumentException) {
      "invalid URL"
    }
  }
}
