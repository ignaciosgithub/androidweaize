package com.weaize.app

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

object Prefs {
  const val KEY_LANGUAGE = "language"
  const val KEY_SPEED_LIMIT = "speed_limit_kmh"
  const val KEY_VOICE_WARNINGS = "voice_warnings"
  const val KEY_AUTO_DOWNLOAD = "auto_download_tiles"
  const val KEY_PRIVATE_KEY = "private_key"
  const val KEY_SUPABASE_URL = "supabase_url"
  const val KEY_SUPABASE_APIKEY = "supabase_apikey"
  const val KEY_SUPABASE_URL_2 = "supabase_url_2"
  const val KEY_SUPABASE_APIKEY_2 = "supabase_apikey_2"
  const val KEY_DEVICE_ID = "device_id"
  const val KEY_UPLOAD_INTERVAL = "upload_interval_s"

  fun get(context: Context): SharedPreferences =
      PreferenceManager.getDefaultSharedPreferences(context)

  fun language(context: Context): String = get(context).getString(KEY_LANGUAGE, "en") ?: "en"

  fun speedLimitKmh(context: Context): Float =
      (get(context).getString(KEY_SPEED_LIMIT, "100") ?: "100").toFloatOrNull() ?: 100f

  fun voiceWarnings(context: Context): Boolean = get(context).getBoolean(KEY_VOICE_WARNINGS, true)

  fun autoDownload(context: Context): Boolean = get(context).getBoolean(KEY_AUTO_DOWNLOAD, false)

  fun privateKey(context: Context): String = get(context).getString(KEY_PRIVATE_KEY, "") ?: ""

  fun supabaseUrl(context: Context): String = get(context).getString(KEY_SUPABASE_URL, "") ?: ""

  fun supabaseApiKey(context: Context): String =
      get(context).getString(KEY_SUPABASE_APIKEY, "") ?: ""

  fun supabaseUrl2(context: Context): String = get(context).getString(KEY_SUPABASE_URL_2, "") ?: ""

  fun supabaseApiKey2(context: Context): String =
      get(context).getString(KEY_SUPABASE_APIKEY_2, "") ?: ""

  fun deviceId(context: Context): String {
    val p = get(context)
    var id = p.getString(KEY_DEVICE_ID, null)
    if (id.isNullOrBlank()) {
      id = java.util.UUID.randomUUID().toString()
      p.edit().putString(KEY_DEVICE_ID, id).apply()
    }
    return id
  }

  fun uploadIntervalS(context: Context): Long =
      (get(context).getString(KEY_UPLOAD_INTERVAL, "30") ?: "30").toLongOrNull() ?: 30L
}
