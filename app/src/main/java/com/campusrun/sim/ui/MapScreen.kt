package com.campusrun.sim.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import android.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color as UiColor
import androidx.core.content.ContextCompat
import com.campusrun.sim.model.PaceMath
import com.campusrun.sim.model.RoutePoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.myLocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.myLocation.MyLocationNewOverlay
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
 *    it never teleports. There is no seek and no overlay.
 * 6. Joystick mode: an in-app drag pad that steers the free
 *    simulation from the device's real position, for random
  *    stage points. It never floats over another app.
 */
@Composable
fun MapScreen(viewModel: SimulationViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val mapViewRef = remember { mutableStateOf<org.osmdroid.views.MapView?>(null) }
    val density = LocalDensity.current
    var padOffsetX by remember { mutableStateOf(0f) }
    var padOffsetY by remember { mutableStateOf(0f) }
    val gpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    viewModel.importGpx(input)
                }
            } catch (_: Exception) {
                viewModel.showError("GPX 文件打开失败，请换一个标准 GPX 文件再试。")
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.55f),
            factory = { ctx ->
                MapView(ctx).apply {
                mapViewRef.value = this
                    setTileSource(TileSourceFactory.MAPNIK)
                    setUseDataConnection(true)
                    setMultiTouchControls(true)
                    controller.setZoom(16.0)
                    controller.setCenter(GeoPoint(39.996, 116.394))
                    // Real-position overlay: show the blue dot and,
                    // the first GPS fix, move the map to the device's
                    // real position instead of staying on Beijing.
                    if (ContextCompat.checkSelfPermission(
                        ctx,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(
                            ctx,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        val myLocationOverlay = MyLocationNewOverlay(
                            GpsMyLocationProvider(ctx),
                            this,
                        )
                        myLocationOverlay.enableMyLocation()
                        myLocationOverlay.runOnFirstFix {
                            val loc = myLocationOverlay.myLocation
                            if (loc != null) {
                                post {
                                    controller.animateTo(loc)
                                    controller.setZoom(17.0)
                                }
                            }
                        }
                        overlays.add(myLocationOverlay)
                    }
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
                                // Keep overlays in sync with state. Preserve the events overlay
                // and the MyLocation overlay; markers and the route follow them.
                val preserved = mapView.overlays.filter {
                    it is MapEventsOverlay || it is MyLocationNewOverlay
                }
                mapView.overlays.clear()
                mapView.overlays.addAll(preserved)
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
            Row {
                Button(
                    onClick = {
                     val mapView = mapViewRef.value
                        var overlay = mapView?.overlays?.filterIsInstance<MyLocationNewOverlay>()?.firstOrNull()
                        if (overlay == null && mapView != null && (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
                         overlay = MyLocationNewOverlay(GpsMyLocationProvider(context), mapView)
                         overlay.enableMyLocation()
                         mapView.overlays.add(overlay)
                        }
                        val loc = overlay?.myLocation
                        if (loc != null) {
                            mapViewRef.value?.controller?.animateTo(loc)
                            mapViewRef.value?.controller?.setZoom(17.0)
                        } else if (overlay != null) {
                         overlay.runOnFirstFix {
                          val firstLoc = overlay?.myLocation
                          if (firstLoc != null) {
                           mapView?.post {
                            mapView?.controller?.animateTo(firstLoc)
                            mapView?.controller?.setZoom(17.0)
                           }
                          }
                         }
                         viewModel.showError("正在等第一次定位：请稍等几秒再点回到我位置。")
                        } else {
                            viewModel.showError("还没拿到定位：请等地图出现定位标记，或确认已给定位权限并打开 GPS。")
                        }
                    },
                ) { Text("回到我位置") }
                Spacer(modifier = Modifier.padding(4.dp))
                Button(
                 onClick = {
                  mapViewRef.value?.apply {
                   setTileSource(TileSourceFactory.MAPNIK)
                   invalidate()
                  }
                 },
                 ) { Text("刷新底图") }
            }
            Row {
                Button(
                    onClick = { viewModel.loadMyRoute(context) },
                    enabled = !state.isRunning,
                ) { Text("我的校园路线") }
                Spacer(modifier = Modifier.padding(4.dp))
                Button(
                    onClick = { viewModel.saveMyRoute(context) },
                    enabled = !state.isRunning,
                ) { Text("保存我的校园路线") }
            }
            Row {
                Button(
                    onClick = { gpxLauncher.launch(arrayOf("*/*")) },
                    enabled = !state.isRunning,
                ) { Text("导入 GPX") }
            }
            Row {
                Spacer(modifier = Modifier.padding(4.dp))
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    },
                ) { Text("去开发者选项选模拟位置应用") }
            }
            Text("开始前确认：开发者选项 > 选择模拟位置应用 已选 CampusRun Sim，否则别的 App 定位不会动。")
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
                valueRange = 3.0f..12.0f,
                steps = 17,
                enabled = !state.isRunning,
            )

            Text("第 3 步：步频：${state.config.cadenceSpm.toInt()} 步/分钟")
            Slider(
                value = state.config.cadenceSpm.toFloat(),
                onValueChange = { viewModel.updateCadence(it.toDouble()) },
                valueRange = 50.0f..300.0f,
                steps = 24,
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
            if (state.isRunning) {
                Text(
                    "已跑 %d 分 %02d 秒，目标 2.0-2.5 公里，暂停别超 5分钟。".format(
                        state.elapsedSeconds / 60,
                        state.elapsedSeconds % 60,
                    )
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text("第 5 步：摇杆模式（随机点位用，不用路线）")
            Row {
                Text("摇杆模式")
                Spacer(modifier = Modifier.padding(4.dp))
                Switch(
                    checked = state.isJoystickMode,
                    onCheckedChange = { viewModel.setJoystickMode(it) },
                    enabled = !state.isRunning,
                )
            }
            if (state.isJoystickMode) {
                Text("先在小步点里开始跑，看到当前随机点位后切回这里推摇杆；松手 = 平滑减速到 0，仍在发位置。")
                Row {
                    if (!state.isRunning) {
                        Button(onClick = {
                            val manager = context.getSystemService(
                                Context.LOCATION_SERVICE
                            ) as LocationManager
                            val loc = try {
                                manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                            } catch (_: SecurityException) {
                                null
                            }
                            if (loc == null) {
                                viewModel.showError("还没拿到定位：请等地图出现定位标记再开始摇杆。")
                            } else {
                                viewModel.startJoystick(context, loc.latitude, loc.longitude)
                            }
                        }) { Text("开始摇杆") }
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
                Box(
                    modifier = Modifier
                    .size(180.dp)
                    .clip(CircleShape)
                    .background(UiColor(0xFFE8E0F5))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = {
                                padOffsetX = 0f
                                padOffsetY = 0f
                                viewModel.updateJoystick(0.0, 0.0)
                            },
                            onDragCancel = {
                                padOffsetX = 0f
                                padOffsetY = 0f
                                viewModel.updateJoystick(0.0, 0.0)
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            val radius = 70f * density.density
                            var nx = padOffsetX + dragAmount.x
                            var ny = padOffsetY + dragAmount.y
                            val mag = kotlin.math.hypot(nx, ny)
                            if (mag > radius) {
                                nx *= radius / mag
                                ny *= radius / mag
                            }
                            padOffsetX = nx
                            padOffsetY = ny
                            viewModel.updateJoystick(
                                (nx / radius).toDouble(),
                                (-ny / radius).toDouble(),
                            )
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                        .offset { IntOffset(padOffsetX.toInt(), padOffsetY.toInt()) }
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(UiColor(0xFF6750A4)),
                    )
                }
                Text("摇杆：上 = 北，下 = 南，左 = 西，右 = 东，推满 = 当前配速，松手减速。")
            }
            Text("注意：本工具只做通用 GPS 路线模拟测试，不保证绕过任何检测。")
        }
    }
}
