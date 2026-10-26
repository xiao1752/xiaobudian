package com.campusrun.sim.ui

import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.campusrun.sim.model.PaceMath
import com.campusrun.sim.model.RoutePoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.events.MapEventsReceiver

/**
 * First-run flow, top to bottom:
 * 1. Map: long-press to add start / end / stops.
 * 2. Route: "生成路线" or "示例校园环线".
 * 3. Pace: slider, shown as min/km and km/h together.
 * 4. Cadence: slider, shown with step length and steps/km together,
 *    so the three numbers are always visibly consistent.
 * 5. Start / Pause / Resume / Stop. Pause keeps the last position,
 *    it never teleports. There is no seek, no joystick, no overlay.
 */
@Composable
fun MapScreen(viewModel: SimulationViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.55f),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(16.0)
                    controller.setCenter(GeoPoint(39.996, 116.394))
                    overlays.add(
                        MapEventsOverlay(
                            object : MapEventsReceiver {
                                override fun singleTapConfirmedHelper(
                                    p: GeoPoint?,
                                ): Boolean = false

                                override fun longPressHelper(p: GeoPoint?): Boolean {
                                    if (p != null && !viewModel.state.value.isRunning) {
                                        viewModel.addPoint(
                                            RoutePoint(p.latitude, p.longitude)
                                        )
                                    }
                                    return true
                                }
                            }
                        )
                    )
                }
            },
            update = { mapView ->
                // Keep overlays in sync with state. The events overlay
                // is always index 0; markers and the route follow it.
                val events = mapView.overlays.firstOrNull()
                mapView.overlays.clear()
                if (events != null) mapView.overlays.add(events)
                if (state.route.isNotEmpty()) {
                    val line = Polyline().apply {
                        setPoints(
                            state.route.map { GeoPoint(it.latitude, it.longitude) }
                        )
                        color = Color.BLUE
                        width = 6f
                    }
                    mapView.overlays.add(line)
                }
                state.selectedPoints.forEachIndexed { index, point ->
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(point.latitude, point.longitude)
                        title = "点 ${index + 1}"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    mapView.overlays.add(marker)
                }
                mapView.invalidate()
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (state.error != null) {
                Text(
                    text = "提示：${state.error}",
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            state.warnings.forEach { warning ->
                Text(text = "注意：$warning")
                Spacer(modifier = Modifier.height(4.dp))
            }

            Text("第 1 步：地图长按选点（已选 ${state.selectedPoints.size} 个）")
            Row {
                Button(
                    onClick = { viewModel.buildRoute() },
                    enabled = !state.isBuildingRoute && !state.isRunning,
                ) {
                    Text(if (state.isBuildingRoute) "生成路线中…" else "生成路线")
                }
                Spacer(modifier = Modifier.padding(4.dp))
                OutlinedButton(
                    onClick = { viewModel.loadCampusLoop() },
                    enabled = !state.isRunning,
                ) { Text("示例校园环线") }
                Spacer(modifier = Modifier.padding(4.dp))
                OutlinedButton(
                    onClick = { viewModel.clearRoute() },
                    enabled = !state.isRunning,
                ) { Text("清空") }
            }
            Text(
                "路线长度：%.2f 公里".format(state.routeDistanceMetres / 1000.0)
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "第 2 步：配速：${PaceMath.formatPace(state.config.paceMinPerKm)}" +
                    "（%.2f km/h，%.2f m/s）".format(state.speedKmh, state.speedMps)
            )
            Slider(
                value = state.config.paceMinPerKm.toFloat(),
                onValueChange = { viewModel.updatePace(it.toDouble()) },
                valueRange = 5.0f..10.0f,
                steps = 9,
                enabled = !state.isRunning,
            )

            Text("第 3 步：步频：${state.config.cadenceSpm.toInt()} 步/分钟")
            Slider(
                value = state.config.cadenceSpm.toFloat(),
                onValueChange = { viewModel.updateCadence(it.toDouble()) },
                valueRange = 130.0f..200.0f,
                steps = 13,
                enabled = !state.isRunning,
            )
            Text(
                "步长：%.2f 米，步数：%.0f 步/公里".format(
                    state.stepLengthMetres,
                    state.stepsPerKm,
                )
            )
            Text("配速、步频、步长三者已按公式保持一致，改一个，另两个自动对齐。")

            Row {
                Text("循环路线")
                Spacer(modifier = Modifier.padding(4.dp))
                Switch(
                    checked = state.config.loopRoute,
                    onCheckedChange = { viewModel.updateLoop(it) },
                    enabled = !state.isRunning,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text("第 4 步：开始 / 暂停 / 停止")
            Row {
                if (!state.isRunning) {
                    Button(onClick = { viewModel.start(context) }) { Text("开始") }
                } else if (!state.isPaused) {
                    Button(onClick = { viewModel.pause(context) }) { Text("暂停") }
                } else {
                    Button(onClick = { viewModel.resume(context) }) { Text("继续") }
                }
                Spacer(modifier = Modifier.padding(4.dp))
                OutlinedButton(
                    onClick = { viewModel.stop(context) },
                    enabled = state.isRunning,
                ) { Text("停止") }
            }
            Text(
                "实时：速度 %.2f m/s，距离 %.0f 米".format(
                    state.telemetry.speedMps,
                    state.telemetry.distanceM,
                )
            )
            Text("注意：本工具只做通用 GPS 路线模拟测试，不保证绕过任何检测。")
        }
    }
}
