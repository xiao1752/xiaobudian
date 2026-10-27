package com.campusrun.sim.ui

/** Shared flag for freehand drawing mode, read by the MapView
 *  touch listener. When true, dragging on the map draws a route
 *  instead of panning the map. */
object DrawModeBus {
    @Volatile
    var enabled: Boolean = false
}
