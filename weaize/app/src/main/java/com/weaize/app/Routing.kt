package com.weaize.app

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.util.GeoPoint

/** Fetches driving routes from the public OSRM API and turns steps into voice instructions. */
object Routing {

  data class Step(val location: GeoPoint, val instruction: Int, val roadName: String)

  data class Route(val points: List<GeoPoint>, val steps: List<Step>, val distanceM: Double)

  private val http = OkHttpClient()

  fun instructionRes(maneuverType: String, modifier: String): Int =
      when (maneuverType) {
        "arrive" -> R.string.nav_arrive
        "depart" -> R.string.nav_depart
        "roundabout",
        "rotary" -> R.string.nav_roundabout
        else ->
            when {
              modifier.contains("left") -> R.string.nav_turn_left
              modifier.contains("right") -> R.string.nav_turn_right
              modifier == "uturn" -> R.string.nav_uturn
              else -> R.string.nav_continue
            }
      }

  /** Blocking; call from a background dispatcher. Returns null on failure. */
  fun route(from: GeoPoint, to: GeoPoint): Route? {
    val url =
        "https://router.project-osrm.org/route/v1/driving/" +
            "${from.longitude},${from.latitude};${to.longitude},${to.latitude}" +
            "?overview=full&geometries=geojson&steps=true"
    val request = Request.Builder().url(url).header("User-Agent", "weaize").build()
    return try {
      http.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) return null
        val body = JSONObject(resp.body?.string() ?: return null)
        val routes = body.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null
        val route = routes.getJSONObject(0)

        val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
        val points = ArrayList<GeoPoint>(coords.length())
        for (i in 0 until coords.length()) {
          val c = coords.getJSONArray(i)
          points.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
        }

        val steps = ArrayList<Step>()
        val legs = route.getJSONArray("legs")
        for (l in 0 until legs.length()) {
          val legSteps = legs.getJSONObject(l).getJSONArray("steps")
          for (s in 0 until legSteps.length()) {
            val step = legSteps.getJSONObject(s)
            val maneuver = step.getJSONObject("maneuver")
            val loc = maneuver.getJSONArray("location")
            steps.add(
                Step(
                    GeoPoint(loc.getDouble(1), loc.getDouble(0)),
                    instructionRes(
                        maneuver.optString("type"), maneuver.optString("modifier")),
                    step.optString("name")))
          }
        }
        Route(points, steps, route.optDouble("distance", 0.0))
      }
    } catch (e: IOException) {
      null
    } catch (e: org.json.JSONException) {
      null
    }
  }
}
