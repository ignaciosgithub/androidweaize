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
    val url =
        map["supabase local address"]
            ?: map["supabase url"] ?: projId?.let { "https://$it.supabase.co" }
    return Creds(
        supabaseUrl = url?.removeSuffix("/"),
        supabaseApiKey = map["supabase apikey pub"] ?: map["supabase apikey"],
        projectId = projId,
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
    editor.apply()
    return creds
  }
}
