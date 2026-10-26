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
import com.campusrun.sim.model.haversineMetres
import com.campusrun.sim.route.CampusLoop
import com.campusrun.sim.route.GpxParser
import com.campusrun.sim.route.OsrmApi
import com.campusrun.sim.service.SimulationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream

data class UiState(
    val selectedPoints: List<RoutePoint> = emptyList(),
    val route: List<RoutePoint> = emptyList(),
    val routeDistanceMetres: Double = 0.0,
    val config: SimConfig = SimConfig(),
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
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
                _state.update {
                    it.copy(
                        isBuildingRoute = false,
                        error = "路线计算失败：请检查网络，或者换两个离道路更近的点再试。",
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

    fun start(context: Context) {
        val current = _state.value
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
        _state.update {
            it.copy(isRunning = true, isPaused = false, error = null)
        }
    }

    fun pause(context: Context) {
        val intent = Intent(context, SimulationService::class.java).apply {
            action = SimulationService.ACTION_PAUSE
        }
        context.startService(intent)
        _state.update { it.copy(isPaused = true) }
    }

    fun resume(context: Context) {
        val intent = Intent(context, SimulationService::class.java).apply {
            action = SimulationService.ACTION_RESUME
        }
        context.startService(intent)
        _state.update { it.copy(isPaused = false) }
    }

    fun stop(context: Context) {
        val intent = Intent(context, SimulationService::class.java).apply {
            action = SimulationService.ACTION_STOP
        }
        context.startService(intent)
        _state.update {
            it.copy(isRunning = false, isPaused = false, telemetry = Telemetry())
        }
    }
}
