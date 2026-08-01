package com.weaize.app

import android.content.Context
import java.io.File
import org.osmdroid.config.Configuration

/**
 * Shared osmdroid setup: persistent tile storage in the app's external files dir with a large
 * cache budget and a long expiration override so downloaded regions survive restarts and are not
 * trimmed or re-fetched.
 */
object OsmConfig {

  private const val CACHE_MAX_BYTES = 2L * 1024 * 1024 * 1024
  private const val CACHE_TRIM_BYTES = CACHE_MAX_BYTES - 100L * 1024 * 1024
  private const val TILE_EXPIRATION_MS = 365L * 24 * 60 * 60 * 1000

  fun init(context: Context) {
    val config = Configuration.getInstance()
    config.load(context, Prefs.get(context))
    config.userAgentValue = context.packageName
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    config.osmdroidBasePath = base
    config.osmdroidTileCache = File(base, "tiles")
    config.tileFileSystemCacheMaxBytes = CACHE_MAX_BYTES
    config.tileFileSystemCacheTrimBytes = CACHE_TRIM_BYTES
    config.expirationOverrideDuration = TILE_EXPIRATION_MS
    config.save(context, Prefs.get(context))
  }
}
