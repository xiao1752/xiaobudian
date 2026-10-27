package com.campusrun.sim.ui

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.campusrun.sim.service.SimulationService
import com.campusrun.sim.simulation.JoystickController
import com.campusrun.sim.simulation.JoystickInputBus
import kotlin.math.hypot

/**
 * Floating joystick overlay, shown over other apps while a joystick
 * run is active. The user asked for this on 2026-10-27: the in-app
 * pad disappears when they switch to Xiaobudian, and releasing the
 * pad stops the run, so a floating pad plus a lock switch is needed.
 * Lock on = releasing keeps the last direction/speed; lock off =
 * releasing decelerates to 0, same as the in-app pad.
 *
 * This service is intentionally not a foreground service: SimulationService
 * in the same process already runs in the foreground and keeps the
 * process alive, so this service needs no foreground notification.
 */
class FloatingJoystickService : Service() {

    private var windowManager: WindowManager? = null
    private var rootView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 40, 40, 60))
            setPadding(16, 16, 16, 16)
        }
        val title = TextView(this).apply {
            text = "CampusRun 悬浮摇杆"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val pad = JoystickPadView(this)
        val lockButton = Button(this).apply {
            text = "锁定方向：关"
            setOnClickListener {
                JoystickInputBus.locked = !JoystickInputBus.locked
                text = if (JoystickInputBus.locked) "锁定方向：开" else "锁定方向：关"
            }
        }
        val stopButton = Button(this).apply {
            text = "停止"
            setOnClickListener {
                val stop = Intent(this@FloatingJoystickService, SimulationService::class.java).apply {
                    action = SimulationService.ACTION_STOP
                }
                startService(stop)
                JoystickInputBus.input = JoystickController.Input(0.0, 0.0)
                JoystickInputBus.locked = false
                stopSelf()
            }
        }
        root.addView(title)
        root.addView(pad)
        root.addView(lockButton)
        root.addView(stopButton)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }
        rootView = root
        try {
            wm.addView(root, params)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        try {
            rootView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        rootView = null
        super.onDestroy()
    }

    private class JoystickPadView(context: Context) : View(context) {
        private val bgPaint = Paint().apply {
            color = Color.argb(255, 232, 224, 245)
            style = Paint.Style.FILL
        }
        private val knobPaint = Paint().apply {
            color = Color.argb(255, 103, 58, 183)
            style = Paint.Style.FILL
        }
        private var knobX = 0f
        private var knobY = 0f
        private var centreX = 0f
        private var centreY = 0f
        private var radius = 0f

        init {
            layoutParams = LinearLayout.LayoutParams(360, 360)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            centreX = w / 2f
            centreY = h / 2f
            radius = minOf(w, h) / 2f * 0.8f
            knobX = centreX
            knobY = centreY
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawCircle(centreX, centreY, radius, bgPaint)
            canvas.drawCircle(knobX, knobY, radius * 0.35f, knobPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    var dx = event.x - centreX
                    var dy = event.y - centreY
                    val mag = hypot(dx, dy)
                    if (mag > radius && mag > 0f) {
                        dx *= radius / mag
                        dy *= radius / mag
                    }
                    knobX = centreX + dx
                    knobY = centreY + dy
                    JoystickInputBus.input = JoystickController.Input(
                        (dx / radius).toDouble(),
                        (-dy / radius).toDouble(),
                    )
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!JoystickInputBus.locked) {
                        JoystickInputBus.input = JoystickController.Input(0.0, 0.0)
                        knobX = centreX
                        knobY = centreY
                        invalidate()
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
