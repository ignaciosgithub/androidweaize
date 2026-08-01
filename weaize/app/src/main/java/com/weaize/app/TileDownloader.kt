package com.weaize.app

import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView

/**
 * Downloads OSM tiles into osmdroid's offline cache so the road network keeps rendering without
 * connectivity. Supports explicit manual downloads of a chosen region and automatic pre-fetching
 * of a small area around the current position.
 */
object TileDownloader {

  const val MIN_ZOOM = 10
  const val MAX_ZOOM = 16

  /** ~0.05 deg is roughly a 5 km box around the position. */
  private const val AUTO_RADIUS_DEG = 0.05

  fun downloadRegion(
      map: MapView,
      box: BoundingBox,
      zoomMin: Int,
      zoomMax: Int,
      callback: CacheManager.CacheManagerCallback,
  ) {
    CacheManager(map).downloadAreaAsync(map.context, box, zoomMin, zoomMax, callback)
  }

  fun autoDownloadAround(map: MapView, lat: Double, lon: Double) {
    val box =
        BoundingBox(
            lat + AUTO_RADIUS_DEG, lon + AUTO_RADIUS_DEG, lat - AUTO_RADIUS_DEG, lon - AUTO_RADIUS_DEG)
    CacheManager(map)
        .downloadAreaAsyncNoUI(
            map.context,
            box,
            12,
            15,
            object : CacheManager.CacheManagerCallback {
              override fun onTaskComplete() {}

              override fun onTaskFailed(errors: Int) {}

              override fun updateProgress(
                  progress: Int,
                  currentZoomLevel: Int,
                  zoomMin: Int,
                  zoomMax: Int
              ) {}

              override fun downloadStarted() {}

              override fun setPossibleTilesInArea(total: Int) {}
            })
  }
}
