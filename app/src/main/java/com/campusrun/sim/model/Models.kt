package com.campusrun.sim.model

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One point on a route. Latitude/longitude are in degrees.
 */
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
)

/** User-facing simulation settings. Only one primary delivery method is
 *  supported by this project: KernelSU system-app installation + foreground
 *  service (see README.md). There is intentionally no mock-location-app
 *  fallback, no floating overlay over the target app, and no teleport/seek in the UI.
 *  Joystick control is in-app only (see JoystickController). */
data class SimConfig(
    /** Pace in minutes per kilometre, e.g. 7.5 means 7:30/km. */
    val paceMinPerKm: Double = 7.5,
    /** Cadence in steps per minute. */
    val cadenceSpm: Double = 160.0,
    /** GPS fixes per second, 1..5. Higher is not more realistic. */
    val fixRateHz: Int = 1,
    /** Small GPS jitter in metres, 0..3. Keep it small and non-zero. */
    val jitterMetres: Double = 1.5,
    /** Repeat the route when it ends (campus loop). */
    val loopRoute: Boolean = true,
)

/** Latest telemetry from the foreground service, for the UI to display.
 *  The service runs in the same process, so a simple shared holder is
  *  enough: the ViewModel polls it and updates UiState.telemetry, which
   *  is what MapScreen shows as "实时". Without this, the UI always
    *  showed 0.00 m/s / 0 m even while the service was injecting. */
    object TelemetryBus {
        @Volatile
        var latest: Telemetry? = null
    }
data class Telemetry(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val speedMps: Double = 0.0,
    val bearingDeg: Double = 0.0,
    val altitudeM: Double = 0.0,
    val distanceM: Double = 0.0,
    val isRunning: Boolean = false,
)

/**
 * Pace / cadence maths. These three numbers must always agree:
 *   speed (m/s) = 1000 / (paceSeconds)
 *   stepLength (m) = speed (m/min) / cadence (steps/min)
 *   stepsPerKm = cadence * paceMinutes
 */
object PaceMath {
    fun paceToSpeedMps(paceMinPerKm: Double): Double {
        require(paceMinPerKm > 0) { "pace must be positive" }
        return 1000.0 / (paceMinPerKm * 60.0)
    }

    fun speedToKmh(speedMps: Double): Double = speedMps * 3.6

    fun speedToPaceMinPerKm(speedMps: Double): Double {
        require(speedMps > 0) { "speed must be positive" }
        return 1000.0 / speedMps / 60.0
    }

    fun paceToKmh(paceMinPerKm: Double): Double =
        speedToKmh(paceToSpeedMps(paceMinPerKm))

    fun stepLengthMetres(speedMps: Double, cadenceSpm: Double): Double {
        require(cadenceSpm > 0) { "cadence must be positive" }
        return speedMps * 60.0 / cadenceSpm
    }

    fun stepsPerKm(paceMinPerKm: Double, cadenceSpm: Double): Double =
        paceMinPerKm * cadenceSpm

    fun formatPace(paceMinPerKm: Double): String {
        val minutes = paceMinPerKm.toInt()
        val seconds = ((paceMinPerKm - minutes) * 60.0).toInt()
        return "%d:%02d/km".format(minutes, seconds)
    }
}

fun haversineMetres(a: RoutePoint, b: RoutePoint): Double {
    val earthRadius = 6_371_000.0
    val lat1 = a.latitude * PI / 180.0
    val lat2 = b.latitude * PI / 180.0
    val dLat = (b.latitude - a.latitude) * PI / 180.0
    val dLon = (b.longitude - a.longitude) * PI / 180.0
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * earthRadius * sqrt(h.coerceIn(0.0, 1.0))
}

fun bearingDegrees(a: RoutePoint, b: RoutePoint): Double {
    val lat1 = a.latitude * PI / 180.0
    val lat2 = b.latitude * PI / 180.0
    val dLon = (b.longitude - a.longitude) * PI / 180.0
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (atan2(y, x) * 180.0 / PI + 360.0) % 360.0
}

fun interpolate(a: RoutePoint, b: RoutePoint, fraction: Double): RoutePoint {
    val f = fraction.coerceIn(0.0, 1.0)
    return RoutePoint(
        latitude = a.latitude + (b.latitude - a.latitude) * f,
        longitude = a.longitude + (b.longitude - a.longitude) * f,
    )
}
