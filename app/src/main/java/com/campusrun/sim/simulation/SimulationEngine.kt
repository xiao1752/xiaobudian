package com.campusrun.sim.simulation

import com.campusrun.sim.model.RoutePoint
import com.campusrun.sim.model.Telemetry
import com.campusrun.sim.model.bearingDegrees
import com.campusrun.sim.model.haversineMetres
import com.campusrun.sim.model.interpolate
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * Arc-length simulation with realistic motion only:
 * - gradual acceleration (max 1.0 m/s^2) and braking, never an instant
 *   jump to full speed and never a teleport;
 * - corner slowing: the sharper the turn, the lower the allowed speed;
 * - small GPS jitter (1-2 m) instead of a perfect, jitter-free line,
 *   which is a classic unrealistic signal;
 * - dwell at stops: when a stop distance is reached, speed decays to
 *   zero and fixes keep being emitted from the same spot for the
 *   dwell time, exactly as a real receiver would while parked.
 * Fixes are continuous in time and space: the engine advances by
 * distance travelled, not by jumping between route vertices.
 */
class SimulationEngine(
    private val route: List<RoutePoint>,
    private val targetSpeedMps: Double,
    private val jitterMetres: Double,
    private val stopDistancesMetres: List<Double> = emptyList(),
    private val dwellSeconds: Double = 0.0,
    private val random: Random = Random.Default,
) {
    private val cumulative: DoubleArray
    private val totalDistance: Double
    private var distanceTravelled = 0.0
    private var speedMps = 0.0
    private var segmentIndex = 0
    private var nextStopIndex = 0
    private var dwellRemainingSeconds = 0.0
    private var dwelling = false

    init {
        require(route.size >= 2) { "route needs at least 2 points" }
        require(targetSpeedMps > 0) { "target speed must be positive" }
        cumulative = DoubleArray(route.size)
        var total = 0.0
        for (i in 1 until route.size) {
            total += haversineMetres(route[i - 1], route[i])
            cumulative[i] = total
        }
        totalDistance = total
    }

    fun totalDistanceMetres(): Double = totalDistance

    private fun cornerSpeedLimit(atIndex: Int): Double {
        if (atIndex <= 0 || atIndex >= route.size - 1) return targetSpeedMps
        val before = bearingDegrees(route[atIndex - 1], route[atIndex])
        val after = bearingDegrees(route[atIndex], route[atIndex + 1])
        var turn = abs(after - before) % 360.0
        if (turn > 180.0) turn = 360.0 - turn
        // Straight: full speed. 90-degree turn: about half speed.
        val factor = 1.0 - (turn / 180.0) * 0.5
        return (targetSpeedMps * factor).coerceAtLeast(1.0)
    }

    private fun positionAt(distance: Double): Pair<RoutePoint, Int> {
        var index = segmentIndex
        while (index < cumulative.size - 1 && cumulative[index + 1] < distance) index++
        while (index > 0 && cumulative[index] > distance) index--
        segmentIndex = index
        if (index >= route.size - 1) return route.last() to route.size - 1
        val segmentLength = cumulative[index + 1] - cumulative[index]
        val fraction = if (segmentLength <= 0.0) 0.0
            else (distance - cumulative[index]) / segmentLength
        return interpolate(route[index], route[index + 1], fraction) to index
    }

    private fun telemetryAt(distance: Double, speed: Double): Telemetry {
        val clamped = distance.coerceIn(0.0, totalDistance)
        val (point, _) = positionAt(clamped)
        val nextPoint = if (clamped >= totalDistance) point else {
            val (p2, _) = positionAt(min(totalDistance, clamped + 1.0))
            p2
        }
        val bearing = bearingDegrees(point, nextPoint)
        val jitterLat = if (jitterMetres > 0) {
            random.nextDouble(-jitterMetres, jitterMetres) / 111_000.0
        } else 0.0
        val jitterLon = if (jitterMetres > 0) {
            random.nextDouble(-jitterMetres, jitterMetres) / 111_000.0
        } else 0.0
        return Telemetry(
            latitude = point.latitude + jitterLat,
            longitude = point.longitude + jitterLon,
            speedMps = speed,
            bearingDeg = bearing,
            altitudeM = 50.0,
            distanceM = clamped,
            isRunning = clamped < totalDistance || dwelling,
        )
    }

    /** Advance by [dtSeconds] and return the next telemetry fix. */
    fun tick(dtSeconds: Double): Telemetry {
        // Dwell at a stop: keep emitting fixes, do not advance.
        if (dwelling) {
            dwellRemainingSeconds -= dtSeconds
            speedMps = maxOf(0.0, speedMps - 1.5 * dtSeconds)
            if (dwellRemainingSeconds <= 0.0) {
                dwelling = false
                nextStopIndex++
            }
            return telemetryAt(distanceTravelled, speedMps)
        }
        // Arrive at the next stop: start dwelling instead of passing through.
        if (dwellSeconds > 0.0 && nextStopIndex < stopDistancesMetres.size &&
            distanceTravelled >= stopDistancesMetres[nextStopIndex] - 0.5
        ) {
            dwelling = true
            dwellRemainingSeconds = dwellSeconds
            return telemetryAt(distanceTravelled, speedMps)
        }
        val (_, index) = positionAt(distanceTravelled)
        val limit = cornerSpeedLimit(index)
        val acceleration = 1.0 // m/s^2, a gentle student-run profile.
        speedMps = if (speedMps < limit) {
            min(limit, speedMps + acceleration * dtSeconds)
        } else {
            maxOf(limit, speedMps - acceleration * 1.5 * dtSeconds)
        }
        distanceTravelled += speedMps * dtSeconds
        return telemetryAt(distanceTravelled, speedMps)
    }

    fun isFinished(): Boolean =
        distanceTravelled >= totalDistance && !dwelling

    fun reset() {
        distanceTravelled = 0.0
        speedMps = 0.0
        segmentIndex = 0
        nextStopIndex = 0
        dwellRemainingSeconds = 0.0
        dwelling = false
    }
}
