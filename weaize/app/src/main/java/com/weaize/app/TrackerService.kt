package com.weaize.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that tracks location (GPS), estimates speed, listens to the gyroscope for
 * sharp-turn detection, issues voice warnings, and periodically uploads encrypted positions to the
 * Supabase server.
 */
class TrackerService : Service(), LocationListener, SensorEventListener {

  companion object {
    const val CHANNEL_ID = "weaize_tracker"
    private const val RESTART_REQUEST_CODE = 1

    /** Cancels any pending service-restart alarm scheduled by [onTaskRemoved]. */
    fun cancelRestartAlarm(context: Context) {
      val restart =
          PendingIntent.getForegroundService(
              context,
              RESTART_REQUEST_CODE,
              Intent(context, TrackerService::class.java),
              PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      val alarm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
      alarm.cancel(restart)
    }
    const val NOTIFICATION_ID = 1
    const val ACTION_LOCATION_UPDATE = "com.weaize.app.LOCATION_UPDATE"
    const val EXTRA_LAT = "lat"
    const val EXTRA_LON = "lon"
    const val EXTRA_SPEED_KMH = "speed_kmh"
    const val EXTRA_BEARING = "bearing"

    /** rad/s around the vertical axis considered a sharp turn while driving. */
    private const val SHARP_TURN_RAD_S = 1.2f
    private const val MIN_SPEED_FOR_TURN_KMH = 25f
  }

  private lateinit var locationManager: LocationManager
  private lateinit var sensorManager: SensorManager
  private var gyro: Sensor? = null
  private lateinit var voice: VoiceAlerts
  private lateinit var supabase: SupabaseClient
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private var lastLocation: Location? = null
  private var lastUploadMs = 0L
  private var lastTurnWarningMs = 0L
  private var currentSpeedKmh = 0f

  override fun onCreate() {
    super.onCreate()
    locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    voice = VoiceAlerts(this)
    supabase = SupabaseClient(this)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    try {
      startForeground(NOTIFICATION_ID, buildNotification())
    } catch (e: SecurityException) {
      stopSelf()
      return START_NOT_STICKY
    } catch (e: IllegalStateException) {
      // ForegroundServiceStartNotAllowedException and friends: the system refused a
      // foreground start (e.g. a stale restart from the background); bail out quietly.
      stopSelf()
      return START_NOT_STICKY
    }
    // A sticky/alarm restart arriving after the user pressed Stop must not revive tracking.
    if (intent == null && !Prefs.trackingEnabled(this)) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }
    Prefs.setTrackingEnabled(this, true)
    startLocationUpdates()
    gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    return START_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    // Keep tracking when the app is swiped away: schedule the service to restart.
    if (Prefs.trackingEnabled(this)) {
      val restart =
          PendingIntent.getForegroundService(
              this,
              RESTART_REQUEST_CODE,
              Intent(this, TrackerService::class.java),
              PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      val alarm = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
      alarm.set(
          android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
          android.os.SystemClock.elapsedRealtime() + 2000,
          restart)
    }
    super.onTaskRemoved(rootIntent)
  }

  private fun startLocationUpdates() {
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED)
        return
    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2f, this)
    if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
      locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, this)
    }
  }

  override fun onLocationChanged(location: Location) {
    val prev = lastLocation
    // Speed estimate: prefer GPS doppler speed; fall back to distance/time between fixes.
    val speedMps =
        if (location.hasSpeed() && location.speed > 0f) location.speed
        else if (prev != null) {
          val dt = (location.time - prev.time) / 1000.0
          if (dt > 0) (prev.distanceTo(location) / dt).toFloat() else 0f
        } else 0f
    currentSpeedKmh = speedMps * 3.6f
    lastLocation = location

    if (Prefs.voiceWarnings(this) && currentSpeedKmh > Prefs.speedLimitKmh(this)) {
      voice.speedWarning(this, currentSpeedKmh, Prefs.speedLimitKmh(this))
    }

    sendBroadcast(
        Intent(ACTION_LOCATION_UPDATE)
            .setPackage(packageName)
            .putExtra(EXTRA_LAT, location.latitude)
            .putExtra(EXTRA_LON, location.longitude)
            .putExtra(EXTRA_SPEED_KMH, currentSpeedKmh)
            .putExtra(EXTRA_BEARING, location.bearing))

    val now = System.currentTimeMillis()
    if (now - lastUploadMs >= Prefs.uploadIntervalS(this) * 1000) {
      lastUploadMs = now
      scope.launch {
        supabase.uploadLocation(
            location.latitude,
            location.longitude,
            speedMps,
            location.bearing,
            location.accuracy,
            location.time)
      }
    }
  }

  override fun onProviderDisabled(provider: String) {
    if (provider == LocationManager.GPS_PROVIDER && Prefs.voiceWarnings(this)) {
      voice.gpsLostWarning(this)
    }
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
    val yawRate = abs(event.values[2])
    val now = System.currentTimeMillis()
    if (yawRate > SHARP_TURN_RAD_S &&
        currentSpeedKmh > MIN_SPEED_FOR_TURN_KMH &&
        now - lastTurnWarningMs > 10_000 &&
        Prefs.voiceWarnings(this)) {
      lastTurnWarningMs = now
      voice.sharpTurnWarning(this)
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

  private fun buildNotification(): Notification {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
    val pi =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.notification_tracking))
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentIntent(pi)
        .setOngoing(true)
        .build()
  }

  override fun onDestroy() {
    locationManager.removeUpdates(this)
    sensorManager.unregisterListener(this)
    voice.shutdown()
    scope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
