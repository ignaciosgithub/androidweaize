package com.weaize.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView

/** Manual download of a rectangular map region (road system tiles) for offline use. */
class DownloadActivity : AppCompatActivity() {

  private lateinit var status: TextView
  private lateinit var hiddenMap: MapView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_download)

    status = findViewById(R.id.download_status)
    hiddenMap = findViewById(R.id.hidden_map)

    val lat = intent.getDoubleExtra("lat", 0.0)
    val lon = intent.getDoubleExtra("lon", 0.0)
    findViewById<EditText>(R.id.input_north).setText((lat + 0.1).toString())
    findViewById<EditText>(R.id.input_south).setText((lat - 0.1).toString())
    findViewById<EditText>(R.id.input_east).setText((lon + 0.1).toString())
    findViewById<EditText>(R.id.input_west).setText((lon - 0.1).toString())

    findViewById<Button>(R.id.btn_do_download).setOnClickListener { startDownload() }
  }

  private fun startDownload() {
    val north = num(R.id.input_north) ?: return
    val south = num(R.id.input_south) ?: return
    val east = num(R.id.input_east) ?: return
    val west = num(R.id.input_west) ?: return
    val box = BoundingBox(north, east, south, west)
    status.setText(R.string.download_started)
    TileDownloader.downloadRegion(
        hiddenMap,
        box,
        TileDownloader.MIN_ZOOM,
        TileDownloader.MAX_ZOOM,
        object : CacheManager.CacheManagerCallback {
          override fun onTaskComplete() {
            runOnUiThread { status.setText(R.string.download_complete) }
          }

          override fun onTaskFailed(errors: Int) {
            runOnUiThread { status.text = getString(R.string.download_failed, errors) }
          }

          override fun updateProgress(
              progress: Int,
              currentZoomLevel: Int,
              zoomMin: Int,
              zoomMax: Int
          ) {
            runOnUiThread {
              status.text = getString(R.string.download_progress, progress, currentZoomLevel)
            }
          }

          override fun downloadStarted() {}

          override fun setPossibleTilesInArea(total: Int) {}
        })
  }

  private fun num(id: Int): Double? =
      findViewById<EditText>(id).text.toString().toDoubleOrNull()
}
