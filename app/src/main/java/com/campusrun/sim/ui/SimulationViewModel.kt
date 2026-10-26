package com.campusrun.sim.ui

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusrun.sim.model.PaceMath
import com.campusrun.sim.model.PaceValidation
import com.campusrun.sim.model.RoutePoint
import com.campusrun.sim.model.SimConfig
import com.campusrun.sim.model.Telemetry
import com.campusrun.sim.model.TelemetryBus
import com.campusrun.sim.model.haversineMetres
import com.campusrun.sim.route.CampusLoop
import com.campusrun.sim.route.GpxParser
import com.campusrun.sim.route.OsrmApi
import com.campusrun.sim.service.SimulationService
import com.campusrun.sim.simulation.JoystickController
import com.campusrun.sim.simulation.JoystickInputBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.InputStream

data class UiState(
    val selectedPoints: List<RoutePoint> = emptyList(),
    val route: List<RoutePoint> = emptyList(),
    val routeDistanceMetres: Double = 0.0,
    val config: SimConfig = SimConfig(),
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val isJoystickMode: Boolean = false,
    val joystickX: Double = 0.0,
    val joystickY: Double = 0.0,
    val elapsedSeconds: Long = 0,
    val isBuildingRoute: Boolean = false,
    val telemetry: Telemetry = Telemetry(),
    val error: String? = null,
    val warnings: List<String> = emptyList(),
) {
    val speedMps: Double get() = PaceMath.paceToSpeedMps(config.paceMinPerKm)
    val speedKmh: Double get() = PaceMath.speedToKmh(speedMps)
    val stepLengthMetres: Double
        get() = PaceMath.stepLengthMetres(speedMps, config.cadenceSpm)
    val stepsPerKm: Double
        get() = PaceMath.stepsPerKm(config.paceMinPerKm, config.cadenceSpm)
}

/**
 * First-run flow, in order: map -> route -> pace -> cadence -> start.
 * The ViewModel never starts a run with a missing route, missing GPS,
 * or a rejected pace/cadence combination: it sets a clear error
 * message instead, and the UI shows that message.
 */
class SimulationViewModel : ViewModel() {


    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    
    private var runStartMs: Long = 0
    private var pauseStartMs: Long? = null
    
    init {
        viewModelScope.launch {
            while (true) {
                delay(500)
                val latest = TelemetryBus.latest
                val elapsed = if (_state.value.isRunning && runStartMs > 0) {
                    (System.currentTimeMillis() - runStartMs) / 1000
                } else 0L
                if ((latest!= null && latest!= _state.value.telemetry) ||
                            elapsed!= _state.value.elapsedSeconds
                ) {
                    _state.update {
                        it.copy(
                            telemetry = latest?: it.telemetry,
                            elapsedSeconds = elapsed,
                        )
                    }
                }
            }
        }
    }

    fun showError(message: String) {
        _state.update { it.copy(error = message)}
    }
    fun setJoystickMode(enabled: Boolean) {
        JoystickInputBus.input = JoystickController.Input(0.0, 0.0)
        _state.update {
            it.copy(isJoystickMode = enabled, joystickX = 0.0, joystickY = 0.0)
        }
    }

    fun updateJoystick(x: Double, y: Double) {
        JoystickInputBus.input = JoystickController.Input(x, y)
        _state.update { it.copy(joystickX = x, joystickY = y)}
    }

    fun saveMyRoute(context: Context) {
        val route = _state.value.route.ifEmpty { _state.value.selectedPoints }
        if (route.size < 2) {
            _state.update { it.copy(error = "先选点并生成路线，再保存为我的校园路线。") }
            return
        }
        val raw = route.joinToString(";") { "${it.latitude},${it.longitude}" }
        context.getSharedPreferences("campus_run", Context.MODE_PRIVATE).edit().putString("my_campus_route", raw).apply()
        _state.update { it.copy(error = null) }
    }

    fun loadMyRoute(context: Context) {
        val raw = context.getSharedPreferences("campus_run", Context.MODE_PRIVATE).getString("my_campus_route", null)
        if (raw.isNullOrEmpty()) {
            _state.update { it.copy(error = "还没有保存过的校园路线：先选点生成路线，再点保存。") }
            return
        }
        val points = raw.split(";").mapNotNull { item ->
                val parts = item.split(",")
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lon = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat == null || lon == null) null else RoutePoint(lat, lon)
        }
        if (points.size < 2) {
            _state.update { it.copy(error = "保存的路线已损坏，请重新选点生成。") }
            return
        }
        _state.update {
            it.copy(
                selectedPoints = points,
                route = points,
                routeDistanceMetres = routeDistance(points),
                error = null,
            )
        }
    }

    fun addPoint(point: RoutePoint) {
        _state.update {
            it.copy(
                selectedPoints = it.selectedPoints + point,
                error = null,
            )
        }
    }

    fun clearRoute() {
        _state.update {
            it.copy(
                selectedPoints = emptyList(),
                route = emptyList(),
                routeDistanceMetres = 0.0,
                error = null,
            )
        }
    }

    fun loadCampusLoop() {
        val loop = CampusLoop.exampleLoop
        _state.update {
            it.copy(
                selectedPoints = loop,
                route = CampusLoop.closed(loop),
                routeDistanceMetres = routeDistance(CampusLoop.closed(loop)),
                error = null,
            )
        }
    }

    fun updatePace(paceMinPerKm: Double) {
        _state.update { it.copy(config = it.config.copy(paceMinPerKm = paceMinPerKm)) }
        refreshValidation()
    }

    fun updateCadence(cadenceSpm: Double) {
        _state.update { it.copy(config = it.config.copy(cadenceSpm = cadenceSpm)) }
        refreshValidation()
    }

    fun updateLoop(loop: Boolean) {
        _state.update { it.copy(config = it.config.copy(loopRoute = loop)) }
    }

    private fun refreshValidation() {
        val current = _state.value
        val result = PaceValidation.validate(
            current.config.paceMinPerKm,
            current.config.cadenceSpm,
        )
        _state.update {
            it.copy(
                warnings = result.warnings,
                error = result.errors.firstOrNull(),
            )
        }
    }

    fun buildRoute() {
        val points = _state.value.selectedPoints
        if (points.size < 2) {
            _state.update {
                it.copy(error = "请先在地图上长按选至少 2 个点：起点和终点。")
            }
            return
        }
        _state.update { it.copy(isBuildingRoute = true, error = null) }
        viewModelScope.launch {
            val routed = OsrmApi.routeThrough(points)
            if (routed == null || routed.size < 2) {
            val fallback = if (_state.value.config.loopRoute) {
                CampusLoop.closed(points)
            } else points
                _state.update {
                    it.copy(
                        route = fallback,
                        routeDistanceMetres = routeDistance(fallback),
                        isBuildingRoute = false,
                        warnings = listOf("在线算路失败，已用直线连点兜底：尽量选贴近道路的点。"),
                    )
                }
            } else {
                val closed = if (_state.value.config.loopRoute) {
                    CampusLoop.closed(routed)
                } else routed
                _state.update {
                    it.copy(
                        route = closed,
                        routeDistanceMetres = routeDistance(closed),
                        isBuildingRoute = false,
                    )
                }
            }
        }
    }

    fun importGpx(input: InputStream) {
        val points = try {
            GpxParser.parse(input)
        } catch (_: Exception) {
            _state.update {
                it.copy(error = "GPX 文件解析失败：请确认是标准的 GPX 轨迹文件。")
            }
            return
        }
        if (points.size < 2) {
            _state.update {
                it.copy(error = "GPX 里少于 2 个轨迹点，无法生成路线。")
            }
            return
        }
        _state.update {
            it.copy(
                selectedPoints = points,
                route = points,
                routeDistanceMetres = routeDistance(points),
                error = null,
            )
        }
    }

    private fun routeDistance(route: List<RoutePoint>): Double {
        var total = 0.0
        for (i in 1 until route.size) total += haversineMetres(route[i - 1], route[i])
        return total
    }

    private fun routeString(route: List<RoutePoint>): String {
        return route.joinToString(";") { "${it.latitude},${it.longitude}" }
    }

    private fun checkValidWindow(): String? {
        val cal = java.util.Calendar.getInstance()
        val ok = PaceValidation.isWithinValidWindow(
                        cal.get(java.util.Calendar.HOUR_OF_DAY),
                               cal.get(java.util.Calendar.MINUTE),
        )
        return if (ok) null else "现在不在有效时段 06:30-23:00 内，开始可能不计成绩。"
    }
    
    fun startJoystick(context: Context, startLat: Double, startLon: Double) {
        val current = _state.value
        checkValidWindow()?.let {
            _state.update { s -> s.copy(error = it)}
            return
        }
        val validation = PaceValidation.validate(
            current.config.paceMinPerKm,
            current.config.cadenceSpm,
        )
        if (!validation.isValid) {
            _state.update {
                it.copy(error = validation.errors.first(), warnings = validation.warnings)
            }
            return
        }
        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        _state.update {
            it.copy(error = "GPS 未开启：请先在系统设置里打开定位，再开始模拟。")
        }
        return
    }
    val intent = Intent(context, SimulationService::class.java).apply {
        action = SimulationService.ACTION_START_JOYSTICK
        putExtra(SimulationService.EXTRA_START_LAT, startLat)
        putExtra(SimulationService.EXTRA_START_LON, startLon)
        putExtra(
            SimulationService.EXTRA_MAX_SPEED_MPS,
            PaceMath.paceToSpeedMps(current.config.paceMinPerKm),
        )
        putExtra(
            SimulationService.EXTRA_STEP_LENGTH,
            current.stepLengthMetres,
        )
        putExtra(SimulationService.EXTRA_JITTER_METRES, current.config.jitterMetres)
        putExtra(SimulationService.EXTRA_FIX_RATE_HZ, current.config.fixRateHz)
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
    runStartMs = System.currentTimeMillis()
    pauseStartMs = null
        _state.update {
            it.copy(isRunning = true, isPaused = false, error = null)
        }
    }
    
            fun start(context: Context) {
        val current = _state.value
        checkValidWindow()?.let {
            _state.update { s -> s.copy(error = it)}
            return
        }
        if (!current.config.loopRoute && current.routeDistanceMetres < 1500.0) {
            _state.update {
                it.copy(error = "路线只有 %.2f 公里，低于单次最低 1.5 公里，不计成绩：请加长路线或打开循环路线。".format(current.routeDistanceMetres / 1000.0))
            }
            return
        }
        if (current.route.size < 2) {
            _state.update {
                it.copy(error = "请先生成路线：地图选点后点“生成路线”，或用示例校园环线。")
            }
            return
        }
        val validation = PaceValidation.validate(
            current.config.paceMinPerKm,
            current.config.cadenceSpm,
        )
        if (!validation.isValid) {
            _state.update {
                it.copy(error = validation.errors.first(), warnings = validation.warnings)
            }
            return
        }
        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            _state.update {
                it.copy(error = "GPS 未开启：请先在系统设置里打开定位，再开始模拟。")
            }
            return
        }
        val intent = Intent(context, SimulationService::class.java).apply {
            action = SimulationService.ACTION_START
            putExtra(SimulationService.EXTRA_ROUTE, routeString(current.route))
            putExtra(
                SimulationService.EXTRA_PACE_MIN_PER_KM,
                current.config.paceMinPerKm,
            )
            putExtra(SimulationService.EXTRA_JITTER_METRES, current.config.jitterMetres)
            putExtra(SimulationService.EXTRA_FIX_RATE_HZ, current.config.fixRateHz)
            putExtra(SimulationService.EXTRA_LOOP, current.config.loopRoute)
            putExtra(SimulationService.EXTRA_DWELL_SECONDS, 0.0)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        runStartMs = System.currentTimeMillis()
        pauseStartMs = null
        _state.update {
            it.copy(isRunning = true, isPaused = false, error = null)
        }
    }

    fun pause(context: Context) {
        val intent = Intent(context, SimulationService::class.java).apply {
            action = SimulationService.ACTION_PAUSE
        }
        context.startService(intent)
        pauseStartMs = System.currentTimeMillis()
        _state.update { it.copy(isPaused = true) }
    }

    fun resume(context: Context) {
        val pausedMs = pauseStartMs?.let { System.currentTimeMillis() - it } ?: 0L
        if (pausedMs > 5 * 60 * 1000L) {
            // Over the 5-minute pause cap: stop instead of resuming
            // a run that may already be invalid.
            stop(context)
            _state.update {
                it.copy(error = "暂停已超过 5 分钟上限，本次已停止，不要继续用这次成绩。")
            }
            return
        }
        val intent = Intent(context, SimulationService::class.java).apply {
            action = SimulationService.ACTION_RESUME
        }
        context.startService(intent)
        pauseStartMs = null
        _state.update { it.copy(isPaused = false) }
    }

    fun stop(context: Context) {
        val intent = Intent(context, SimulationService::class.java).apply {
            action = SimulationService.ACTION_STOP
        }
        context.startService(intent)
JoystickInputBus.input = JoystickController.Input(0.0, 0.0)
runStartMs = 0
        pauseStartMs = null
        _state.update {
            it.copy(
                isRunning = false,
                isPaused = false,
                joystickX = 0.0,
                joystickY = 0.0,
                elapsedSeconds = 0,
                telemetry = Telemetry(),
            )
        }
    }
}
