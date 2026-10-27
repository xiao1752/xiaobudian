package com.campusrun.sim.route

import com.campusrun.sim.model.RoutePoint

/**
 * EXAMPLE campus loop preset only. These coordinates are a small,
 * walkable rectangular loop used so the app works out of the box for
 * testing. Replace them with the user's own campus loop before any
 * real use: a loop must start and end at a plausible place, stay on
 * real campus paths/roads, and be repeatable without teleporting.
 *
 * The loop is closed by repeating the first point at the end.
 */
object CampusLoop {
    val exampleLoop: List<RoutePoint> = listOf(
        RoutePoint(39.9990, 116.3900),
        RoutePoint(39.9990, 116.3980),
        RoutePoint(39.9930, 116.3980),
        RoutePoint(39.9930, 116.3900),
        RoutePoint(39.9990, 116.3900),
    )

    /** Loop centred on the user's real position, about 2 km around
     *  (444 m tall x about 550 m wide at mid-latitudes), so the
     *  example button is visible on screen instead of a far-away
     *  Beijing preset. The old Beijing preset stays as exampleLoop
     *  for reference only and is no longer used by the UI. */
    fun loopAround(lat: Double, lon: Double): List<RoutePoint> {
        return listOf(
            RoutePoint(lat + 0.002, lon - 0.003),
            RoutePoint(lat + 0.002, lon + 0.003),
            RoutePoint(lat - 0.002, lon + 0.003),
            RoutePoint(lat - 0.002, lon - 0.003),
            RoutePoint(lat + 0.002, lon - 0.003),
        )
    }

    /** Close an open route so it can loop without a jump. */
    fun closed(points: List<RoutePoint>): List<RoutePoint> {
        if (points.isEmpty()) return points
        return if (points.first() == points.last()) points else points + points.first()
    }
}
