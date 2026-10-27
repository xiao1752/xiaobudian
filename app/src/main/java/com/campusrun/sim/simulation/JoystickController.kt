package com.campusrun.sim.simulation

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 手动摇杆控制模式。
 *
 * 用户在 2026-10-26 明确要求：尽量以摇杆形式自己控制着跑，
 * 因为小步点的阶段点位是随机的，自动路线没法提前写死点位，
 * 手动摇杆可以一边看当前阶段点位、一边往那个方向跑。
 *
 * 安全折中（不是悬浮摇杆）：
 * - 摇杆只在 CampusRun Sim 自己的界面里，不做系统悬浮窗，
 *   不盖在小步点上面，目标 App 那边看不到一个显眼的摇杆。
 * - 摇杆只给“方向 + 目标速度”，真正的速度、转向仍然走
 *   SimulationEngine 的平滑逻辑：加速度上限约 1.0 m/s²，
 *   转向逐渐转过去，不会瞬间满速、瞬间掉头、瞬间传送。
 * - 摇杆推满对应的最高速度由用户设定的配速上限决定，
 *   默认不超过 6:00/km（2.78 m/s），不要推到 3:00/km 那种
 *   精英速度。摇杆回中 = 平滑减速到 0，仍持续发位置，
 *   这段时间算暂停，累计暂停不要超过 5 分钟（建议 3 分钟内）。
 * - 步频不由摇杆直接定：步长固定为用户设定值（默认 0.83 米），
 *   步频 = 速度（米/分钟）÷ 步长，随速度自动对齐，
 *   这样配速、步频、步长三者始终算得通。
 *
 * 使用流程：小步点里先开始跑 → 看到当前随机阶段点位 →
 * 切到本 App，用摇杆往点位方向推 → 到点后切回小步点确认
 * 该阶段已通过 → 出现下一阶段点位 → 重复。
 * 也可以用分屏同时看两个 App，但不要让摇杆盖住小步点。
 */

/** Shared joystick input, read by the foreground service each tick.
 *  The pad UI writes here on drag; drag end writes (0, 0), so the
  *  service smoothly decelerates instead of stopping dead. */
object JoystickInputBus {
    @Volatile
    var input: JoystickController.Input = JoystickController.Input(0.0, 0.0)
    var locked: Boolean = false
}

object JoystickController {

    data class State(
        val speedMps: Double = 0.0,
        val bearingDeg: Double = 0.0,
        val cadenceSpm: Double = 0.0,
        val paceMinPerKm: Double = 0.0,
    )

    /** 摇杆输入：x, y 均为 -1..1， magnitude = 推的幅度。 */
    data class Input(val x: Double, val y: Double) {
        val magnitude: Double get() = min(1.0, hypot(x, y))
        // 屏幕坐标：上为北。atan2(x, -y) 给出以北为 0、顺时针的方向角。
        val bearingDeg: Double get() = Math.toDegrees(atan2(x, -y))
    }

    private const val MAX_ACCEL = 1.0 // m/s^2，和 SimulationEngine 一致
    private const val MAX_TURN_DEG_PER_SEC = 60.0

    /**
     * 每 tick（建议 1 秒）调用一次。
     * @param maxSpeedMps 摇杆推满时的最高速度，由设定配速决定
     * @param stepLengthMetres 用户设定的步长，默认 0.83 米
     */
    fun update(
        current: State,
        input: Input,
        dtSeconds: Double,
        maxSpeedMps: Double,
        stepLengthMetres: Double,
    ): State {
        val targetSpeed = input.magnitude * maxSpeedMps
        // 速度平滑：每秒最多变化 MAX_ACCEL
        val speedDelta = (targetSpeed - current.speedMps)
            .coerceIn(-MAX_ACCEL * dtSeconds, MAX_ACCEL * dtSeconds)
        val speed = max(0.0, current.speedMps + speedDelta)
        // 方向平滑：每秒最多转 MAX_TURN_DEG_PER_SEC，取最短转角
        val bearing = if (speed < 0.1 || input.magnitude < 0.05) {
            current.bearingDeg
        } else {
            var diff = (input.bearingDeg - current.bearingDeg) % 360.0
            if (diff > 180.0) diff -= 360.0
            if (diff < -180.0) diff += 360.0
            val turn = diff.coerceIn(
                -MAX_TURN_DEG_PER_SEC * dtSeconds,
                MAX_TURN_DEG_PER_SEC * dtSeconds
            )
            (current.bearingDeg + turn + 360.0) % 360.0
        }
        val cadence = if (speed > 0.0) (speed * 60.0) / stepLengthMetres else 0.0
        val pace = if (speed > 0.0) (1000.0 / speed) / 60.0 else 0.0
        return State(speed, bearing, cadence, pace)
    }
}
