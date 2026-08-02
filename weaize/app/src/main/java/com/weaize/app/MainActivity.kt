package com.weaize.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.location.Location
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity() {

  private lateinit var map: MapView
  private lateinit var speedText: TextView
  private lateinit var uploadStatusText: TextView
  private var marker: Marker? = null
  private var tracking = false
  private var lastAutoDownload = 0L

  private lateinit var voice: VoiceAlerts
  private lateinit var navigator: Navigator
  private var destMarker: Marker? = null
  private var routeLine: Polyline? = null
  private var lastFix: Location? = null

  private val permissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        when (grants[Manifest.permission.ACCESS_FINE_LOCATION]) {
          true -> startTracking()
          false -> Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
          null -> Unit // background-location follow-up request; nothing to do here
        }
      }

  private val credsLauncher =
      registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
          val creds = CredsFile.importFromUri(this, uri)
          val msg =
              if (creds?.supabaseUrl != null) R.string.creds_imported else R.string.creds_invalid
          Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
      }

  private val locationReceiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          val lat = intent.getDoubleExtra(TrackerService.EXTRA_LAT, 0.0)
          val lon = intent.getDoubleExtra(TrackerService.EXTRA_LON, 0.0)
          val speedKmh = intent.getFloatExtra(TrackerService.EXTRA_SPEED_KMH, 0f)
          onLocation(lat, lon, speedKmh)
          updateUploadStatus()
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    OsmConfig.init(this)
    setContentView(R.layout.activity_main)

    map = findViewById(R.id.map)
    map.setTileSource(TileSourceFactory.MAPNIK)
    map.setMultiTouchControls(true)
    centerOnLastKnownLocation()

    speedText = findViewById(R.id.speed_text)
    speedText.text = getString(R.string.speed_format, 0)
    uploadStatusText = findViewById(R.id.upload_status_text)
    updateUploadStatus()

    tracking = Prefs.trackingEnabled(this)
    findViewById<Button>(R.id.btn_start).apply {
      setText(if (tracking) R.string.stop_tracking else R.string.start_tracking)
      setOnClickListener { toggleTracking() }
    }
    findViewById<Button>(R.id.btn_settings).setOnClickListener {
      startActivity(Intent(this, SettingsActivity::class.java))
    }
    findViewById<Button>(R.id.btn_download).setOnClickListener {
      startActivity(
          Intent(this, DownloadActivity::class.java)
              .putExtra("lat", map.mapCenter.latitude)
              .putExtra("lon", map.mapCenter.longitude)
              .putExtra("zoom", map.zoomLevelDouble))
    }
    findViewById<Button>(R.id.btn_creds).setOnClickListener {
      credsLauncher.launch(arrayOf("text/plain", "application/octet-stream"))
    }

    voice = VoiceAlerts(this)
    navigator = Navigator(this, voice)
    map.overlays.add(
        MapEventsOverlay(
            object : MapEventsReceiver {
              override fun singleTapConfirmedHelper(p: GeoPoint) = false

              override fun longPressHelper(p: GeoPoint): Boolean {
                onDestinationLongPress(p)
                return true
              }
            }))
  }

  private fun onDestinationLongPress(p: GeoPoint) {
    val current = navigator.destination
    if (current != null && current.distanceToAsDouble(p) < 100.0) {
      clearRoute()
      Toast.makeText(this, R.string.nav_cleared, Toast.LENGTH_SHORT).show()
      return
    }
    val from =
        lastFix?.let { GeoPoint(it.latitude, it.longitude) }
            ?: GeoPoint(map.mapCenter.latitude, map.mapCenter.longitude)
    Toast.makeText(this, R.string.nav_calculating, Toast.LENGTH_SHORT).show()
    lifecycleScope.launch {
      val route = withContext(Dispatchers.IO) { Routing.route(from, p) }
      if (route == null) {
        Toast.makeText(this@MainActivity, R.string.nav_route_failed, Toast.LENGTH_LONG).show()
      }
      navigator.start(p, route)
      drawRoute(p, route)
    }
  }

  private fun drawRoute(dest: GeoPoint, route: Routing.Route?) {
    clearRouteOverlays()
    destMarker =
        Marker(map).also {
          it.position = dest
          it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
          map.overlays.add(it)
        }
    if (route != null) {
      routeLine =
          Polyline(map).also {
            it.setPoints(route.points)
            it.outlinePaint.color = 0xFF1565C0.toInt()
            it.outlinePaint.strokeWidth = 10f
            map.overlays.add(it)
          }
    }
    map.invalidate()
  }

  private fun clearRouteOverlays() {
    destMarker?.let { map.overlays.remove(it) }
    routeLine?.let { map.overlays.remove(it) }
    destMarker = null
    routeLine = null
    map.invalidate()
  }

  private fun clearRoute() {
    navigator.stop()
    clearRouteOverlays()
  }

  private fun centerOnLastKnownLocation() {
    var centered = false
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED) {
      val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
      val last =
          lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
      if (last != null) {
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(last.latitude, last.longitude))
        centered = true
      }
    }
    if (!centered) {
      map.controller.setZoom(3.0)
    }
  }

  private fun toggleTracking() {
    if (tracking) {
      Prefs.setTrackingEnabled(this, false)
      stopService(Intent(this, TrackerService::class.java))
      tracking = false
      findViewById<Button>(R.id.btn_start).setText(R.string.start_tracking)
      return
    }
    val needed =
        mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION).apply {
          if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    if (needed.any {
      ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }) {
      permissionLauncher.launch(needed.toTypedArray())
    } else {
      startTracking()
    }
  }

  private fun startTracking() {
    ContextCompat.startForegroundService(this, Intent(this, TrackerService::class.java))
    tracking = true
    findViewById<Button>(R.id.btn_start).setText(R.string.stop_tracking)
    requestBackgroundPermissions()
  }

  /**
   * Ask for what long-running background tracking needs: background location ("Allow all the
   * time") and exemption from battery optimization so the system doesn't kill the service.
   */
  private fun requestBackgroundPermissions() {
    if (Build.VERSION.SDK_INT >= 29 &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
      permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }
    val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
      try {
        startActivity(
            Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(android.net.Uri.parse("package:$packageName")))
      } catch (e: android.content.ActivityNotFoundException) {
        // Some devices don't offer the dialog; tracking still works, just less reliably.
      }
    }
  }

  private fun onLocation(lat: Double, lon: Double, speedKmh: Float) {
    speedText.text = getString(R.string.speed_format, speedKmh.toInt())
    val point = GeoPoint(lat, lon)
    val fix =
        Location("weaize").also {
          it.latitude = lat
          it.longitude = lon
          it.speed = speedKmh / 3.6f
          it.bearing = lastFix?.let { prev -> prev.bearingTo(it) } ?: 0f
          it.time = System.currentTimeMillis()
        }
    lastFix = fix
    if (navigator.active) {
      navigator.onLocation(fix)
      if (!navigator.active) clearRouteOverlays()
    }
    if (marker == null) {
      marker =
          Marker(map).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            map.overlays.add(it)
          }
    }
    marker?.position = point
    map.controller.animateTo(point)
    map.invalidate()

    if (Prefs.autoDownload(this)) {
      val now = System.currentTimeMillis()
      if (now - lastAutoDownload > 60_000) {
        lastAutoDownload = now
        TileDownloader.autoDownloadAround(map, lat, lon)
      }
    }
  }

  override fun onResume() {
    super.onResume()
    map.onResume()
    ContextCompat.registerReceiver(
        this,
        locationReceiver,
        IntentFilter(TrackerService.ACTION_LOCATION_UPDATE),
        ContextCompat.RECEIVER_NOT_EXPORTED)
    updateUploadStatus()
  }

  private fun updateUploadStatus() {
    val status = Prefs.lastUploadStatus(this)
    uploadStatusText.text =
        when {
          status.isBlank() -> ""
          status == "OK" -> getString(R.string.upload_status_ok)
          else -> getString(R.string.upload_status_error, status)
        }
  }

  override fun onPause() {
    super.onPause()
    map.onPause()
    unregisterReceiver(locationReceiver)
  }

  override fun onDestroy() {
    voice.shutdown()
    super.onDestroy()
  }
}
