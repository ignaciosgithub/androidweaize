package com.weaize.app

import android.content.Context
import android.location.Location
import org.osmdroid.util.GeoPoint

/**
 * Tracks progress along the active route and emits voice guidance: an upcoming-maneuver
 * announcement, the maneuver itself, arrival, and a straight-line fallback when no route could be
 * fetched (offline).
 */
class Navigator(private val context: Context, private val voice: VoiceAlerts) {

  companion object {
    private const val ANNOUNCE_M = 250.0
    private const val MANEUVER_M = 40.0
    private const val ARRIVE_M = 35.0
    private const val OFFLINE_UPDATE_INTERVAL_MS = 60_000L
  }

  var destination: GeoPoint? = null
    private set

  var route: Routing.Route? = null
    private set

  private var stepIndex = 0
  private var announcedStep = -1
  private var lastOfflineUpdateMs = 0L

  val active: Boolean
    get() = destination != null

  fun start(dest: GeoPoint, fetchedRoute: Routing.Route?) {
    destination = dest
    route = fetchedRoute
    stepIndex = 0
    announcedStep = -1
    lastOfflineUpdateMs = 0L
    if (fetchedRoute != null) {
      voice.speak(
          context.getString(
              R.string.nav_route_started, (fetchedRoute.distanceM / 1000).toInt()))
    } else {
      voice.speak(context.getString(R.string.nav_offline_guidance))
    }
  }

  fun stop() {
    destination = null
    route = null
  }

  fun onLocation(location: Location) {
    val dest = destination ?: return
    val here = GeoPoint(location.latitude, location.longitude)

    if (here.distanceToAsDouble(dest) < ARRIVE_M) {
      voice.speak(context.getString(R.string.nav_arrive))
      stop()
      return
    }

    val r = route
    if (r == null) {
      offlineGuidance(here, location, dest)
      return
    }

    while (stepIndex < r.steps.size &&
        here.distanceToAsDouble(r.steps[stepIndex].location) < MANEUVER_M) {
      val step = r.steps[stepIndex]
      voice.speak(context.getString(step.instruction))
      stepIndex++
      announcedStep = stepIndex - 1
    }

    if (stepIndex < r.steps.size && announcedStep < stepIndex) {
      val step = r.steps[stepIndex]
      val d = here.distanceToAsDouble(step.location)
      if (d < ANNOUNCE_M) {
        announcedStep = stepIndex
        voice.speak(
            context.getString(R.string.nav_in_meters, d.toInt(), context.getString(step.instruction)))
      }
    }
  }

  private fun offlineGuidance(here: GeoPoint, location: Location, dest: GeoPoint) {
    val now = System.currentTimeMillis()
    if (now - lastOfflineUpdateMs < OFFLINE_UPDATE_INTERVAL_MS) return
    lastOfflineUpdateMs = now
    val distance = here.distanceToAsDouble(dest)
    val bearingTo = here.bearingTo(dest)
    var relative = bearingTo - location.bearing
    while (relative < -180) relative += 360
    while (relative > 180) relative -= 360
    val direction =
        when {
          relative > 30 -> context.getString(R.string.nav_turn_right)
          relative < -30 -> context.getString(R.string.nav_turn_left)
          else -> context.getString(R.string.nav_continue)
        }
    voice.speak(
        context.getString(R.string.nav_offline_update, (distance / 100).toInt() * 100, direction))
  }
}
