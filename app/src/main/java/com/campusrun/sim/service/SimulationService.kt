package com.campusrun.sim.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.campusrun.sim.location.MockLocationEngine
import com.campusrun.sim.model.PaceMath
import com.campusrun.sim.model.RoutePoint
import com.campusrun.sim.model.Telemetry
import com.campusrun.sim.model.TelemetryBus
import com.campusrun.sim.simulation.SimulationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service: the ONLY background delivery path in this project.
 *
 * Stability design (Android 14, screen-off, app-switching):
 * - foreground service with location type, so the system does not
 *   silently stop injection when the user switches apps;
 * - a partial wake lock held only while a run is active, released on
 *   stop, so screen-off does not pause the route;
 * - START_STICKY: if the system kills the service, Android restarts
 *   it; the UI must then be reopened to resume, it never silently
 *   teleports to a new position (position is only ever advanced by
 *   the engine, never set from the UI);
 * - pause keeps emitting the last position at zero speed instead of
 *   stopping fixes abruptly, which is what a real paused receiver
 *   looks like; resume continues from exactly that position.
 *
 * No floating window, no overlay, no seek/teleport action exists.
 */
class SimulationService : Service() {

    companion object {
        const val ACTION_START = "com.campusrun.sim.START"
        const val ACTION_PAUSE = "com.campusrun.sim.PAUSE"
        const val ACTION_RESUME = "com.campusrun.sim.RESUME"
        const val ACTION_STOP = "com.campusrun.sim.STOP"
        const val EXTRA_ROUTE = "route" // "lat,lon;lat,lon;..."
        const val EXTRA_PACE_MIN_PER_KM = "pace_min_per_km"
        const val EXTRA_JITTER_METRES = "jitter_metres"
        const val EXTRA_FIX_RATE_HZ = "fix_rate_hz"
        const val EXTRA_LOOP = "loop"
        const val EXTRA_STOP_DISTANCES = "stop_distances" // "m;m;..."
        const val EXTRA_DWELL_SECONDS = "dwell_seconds"

        private const val CHANNEL_ID = "campus_run_sim"
        private const val NOTIFICATION_ID = 1
    }

    @Volatile
    private var paused = false
    private var job: Job? = null
    private var mockEngine: MockLocationEngine? = null
    private var lastTelemetry: Telemetry? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Route simulation",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> paused = true
            ACTION_RESUME -> paused = false
            ACTION_STOP -> {
                stopSimulation()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val route = parseRoute(intent.getStringExtra(EXTRA_ROUTE).orEmpty())
                val pace = intent.getDoubleExtra(EXTRA_PACE_MIN_PER_KM, 7.5)
                val jitter = intent.getDoubleExtra(EXTRA_JITTER_METRES, 1.5)
                val fixRate = intent.getIntExtra(EXTRA_FIX_RATE_HZ, 1).coerceIn(1, 5)
                val loop = intent.getBooleanExtra(EXTRA_LOOP, true)
                val stops = parseDoubles(intent.getStringExtra(EXTRA_STOP_DISTANCES).orEmpty())
                val dwell = intent.getDoubleExtra(EXTRA_DWELL_SECONDS, 0.0)
                paused = false
                startSimulation(route, pace, jitter, fixRate, loop, stops, dwell)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun parseRoute(raw: String): List<RoutePoint> {
        return raw.split(";").mapNotNull { part ->
            val pieces = part.split(",")
            if (pieces.size != 2) null else {
                val lat = pieces[0].toDoubleOrNull()
                val lon = pieces[1].toDoubleOrNull()
                if (lat != null && lon != null) RoutePoint(lat, lon) else null
            }
        }
    }

    private fun parseDoubles(raw: String): List<Double> {
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { it.toDoubleOrNull() }
    }

    private fun notification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CampusRun Sim")
            .setContentText("Route simulation running")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CampusRunSim::route",
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L) // released on stop; 10h hard cap.
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
            // Best effort only.
        }
        wakeLock = null
    }

    private fun startSimulation(
        route: List<RoutePoint>,
        paceMinPerKm: Double,
        jitterMetres: Double,
        fixRateHz: Int,
        loop: Boolean,
        stopDistances: List<Double>,
        dwellSeconds: Double,
    ) {
        if (route.size < 2) return
        stopSimulation()
        startForeground(NOTIFICATION_ID, notification())
        acquireWakeLock()
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        mockEngine = MockLocationEngine(locationManager).also { it.start() }
        val targetSpeed = PaceMath.paceToSpeedMps(paceMinPerKm)
        val intervalMs = 1000L / fixRateHz
        job = scope.launch {
            while (isActive) {
                val engine = SimulationEngine(
                    route = route,
                    targetSpeedMps = targetSpeed,
                    jitterMetres = jitterMetres,
                    stopDistancesMetres = stopDistances,
                    dwellSeconds = dwellSeconds,
                )
                while (isActive && !engine.isFinished()) {
                    if (paused) {
                        // Paused: keep emitting the last fix at zero
                        // speed, do not advance, do not teleport.
                        lastTelemetry?.let { last ->
                            val pausedFix = last.copy(speedMps = 0.0)
                            TelemetryBus.latest = pausedFix
                            mockEngine?.inject(pausedFix)
                        }
                        delay(intervalMs)
                        continue
                    }
                    val telemetry = engine.tick(1.0 / fixRateHz)
                    lastTelemetry = telemetry
                    TelemetryBus.latest = telemetry
                    mockEngine?.inject(telemetry)
                    delay(intervalMs)
                }
                if (!loop || !isActive) break
                // Loop routes are closed (first == last), so restarting
                // from the start is continuous, not a teleport.
            }
            mockEngine?.stop()
        }
    }

    private fun stopSimulation() {
        job?.cancel()
        job = null
        mockEngine?.stop()
        mockEngine = null
        lastTelemetry = null
        TelemetryBus.latest = null
        paused = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopSimulation()
        scope.cancel()
        super.onDestroy()
    }
}
