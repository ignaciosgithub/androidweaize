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
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MainActivity : AppCompatActivity() {

  private lateinit var map: MapView
  private lateinit var speedText: TextView
  private var marker: Marker? = null
  private var tracking = false
  private var lastAutoDownload = 0L

  private val permissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) startTracking()
        else Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
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
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val config = Configuration.getInstance()
    config.load(this, Prefs.get(this))
    config.userAgentValue = packageName
    val base = getExternalFilesDir(null) ?: filesDir
    config.osmdroidBasePath = base
    config.osmdroidTileCache = java.io.File(base, "tiles")
    setContentView(R.layout.activity_main)

    map = findViewById(R.id.map)
    map.setTileSource(TileSourceFactory.MAPNIK)
    map.setMultiTouchControls(true)
    centerOnLastKnownLocation()

    speedText = findViewById(R.id.speed_text)
    speedText.text = getString(R.string.speed_format, 0)

    findViewById<Button>(R.id.btn_start).setOnClickListener { toggleTracking() }
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
  }

  private fun onLocation(lat: Double, lon: Double, speedKmh: Float) {
    speedText.text = getString(R.string.speed_format, speedKmh.toInt())
    val point = GeoPoint(lat, lon)
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
  }

  override fun onPause() {
    super.onPause()
    map.onPause()
    unregisterReceiver(locationReceiver)
  }
}
