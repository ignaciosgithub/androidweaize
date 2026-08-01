package com.weaize.app

import android.content.Context
import android.net.Uri

/**
 * Parses a creds.txt file of "label : value" lines, matching the user's credential file layout,
 * e.g.:
 *
 * postgress supabase password: ...
 * postgress supabase user: ...
 * supabase local address : ...
 * supabase proj id: ...
 * supabase apikey pub: ...
 */
object CredsFile {
  data class Creds(
      val supabaseUrl: String?,
      val supabaseApiKey: String?,
      val projectId: String?,
      val supabaseUrl2: String? = null,
      val supabaseApiKey2: String? = null,
  )

  fun parse(text: String): Creds {
    val map = mutableMapOf<String, String>()
    for (line in text.lines()) {
      val idx = line.indexOf(':')
      if (idx <= 0) continue
      val key = line.substring(0, idx).trim().lowercase().replace(Regex("\\s+"), " ")
      val value = line.substring(idx + 1).trim()
      if (value.isNotEmpty()) map[key] = value
    }
    val projId = map["supabase proj id"]
    // Prefer the hosted https project URL: local http addresses only work on the
    // same machine as the Supabase instance, never from the phone.
    val url =
        projId?.let { "https://$it.supabase.co" }
            ?: map["supabase local address"] ?: map["supabase url"]
    val projId2 = map["supabase proj id 2"] ?: map["backup supabase proj id"]
    val url2 =
        projId2?.let { "https://$it.supabase.co" }
            ?: map["supabase local address 2"]
            ?: map["supabase url 2"]
            ?: map["backup supabase local address"] ?: map["backup supabase url"]
    return Creds(
        supabaseUrl = url?.removeSuffix("/"),
        supabaseApiKey = map["supabase apikey pub"] ?: map["supabase apikey"],
        projectId = projId,
        supabaseUrl2 = url2?.removeSuffix("/"),
        supabaseApiKey2 =
            map["supabase apikey pub 2"]
                ?: map["supabase apikey 2"] ?: map["backup supabase apikey pub"]
                    ?: map["backup supabase apikey"],
    )
  }

  fun importFromUri(context: Context, uri: Uri): Creds? {
    val text =
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return null
    val creds = parse(text)
    val editor = Prefs.get(context).edit()
    creds.supabaseUrl?.let { editor.putString(Prefs.KEY_SUPABASE_URL, it) }
    creds.supabaseApiKey?.let { editor.putString(Prefs.KEY_SUPABASE_APIKEY, it) }
    creds.supabaseUrl2?.let { editor.putString(Prefs.KEY_SUPABASE_URL_2, it) }
    creds.supabaseApiKey2?.let { editor.putString(Prefs.KEY_SUPABASE_APIKEY_2, it) }
    editor.apply()
    return creds
  }
}
