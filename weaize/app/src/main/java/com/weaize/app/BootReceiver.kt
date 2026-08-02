package com.weaize.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Restarts tracking after a reboot if it was enabled when the device shut down. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    if (!Prefs.trackingEnabled(context)) return
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED)
        return
    try {
      ContextCompat.startForegroundService(context, Intent(context, TrackerService::class.java))
    } catch (e: SecurityException) {
      // Some Android versions forbid starting a location foreground service from boot;
      // tracking then resumes the next time the app is opened.
    } catch (e: IllegalStateException) {
      // Same: never crash on boot.
    }
  }
}
