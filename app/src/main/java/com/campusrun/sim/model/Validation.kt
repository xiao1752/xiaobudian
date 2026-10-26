package com.campusrun.sim.model

/**
 * 陕西工业职业技术学院 渭城校区跑步规则（小步点，2026-09-06 发布，
 * 用户 2026-10-26 截图确认，男生）：
 * - 单次最低 1.5km，每日计入成绩上限 3.0km
 * - 配速 3'00"-12'00" 分钟/公里
 * - 步频 50-300 步/分钟
 * - 有效时段 06:30:00-23:00:59
 * - 已开启进阶段点位跑：点位是随机生成的阶段点，不是固定点
 * - 最大暂停时间 5 分钟
 *
 * 官方范围很宽，不能只按官方范围放行：例如配速 6:00/km 配
 * 步频 50 步/分钟，算出来步长是 3.33 米，真人根本跑不出来，
 * 服务端一算步长就露馅。所以本文件分两层：
 * 1. 官方硬性范围：超出直接拒绝。
 * 2. 真实性警告/步长校验：步长超出 0.60-1.10 米直接拒绝；
 *    配速、步频在官方范围内但偏离学生正常范围时给出警告。
 */
object PaceValidation {
    data class Result(
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    ) {
        val isValid: Boolean get() = errors.isEmpty()
    }

    // 官方硬性范围（男生，截图规则）
    const val MIN_PACE = 3.0
    const val MAX_PACE = 12.0
    const val MIN_CADENCE = 50.0
    const val MAX_CADENCE = 300.0
    const val MIN_DISTANCE_KM = 1.5
    const val MAX_DAILY_KM = 3.0
    const val MAX_PAUSE_MINUTES = 5.0

    fun validate(paceMinPerKm: Double, cadenceSpm: Double): Result {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (paceMinPerKm < MIN_PACE) {
            errors.add("配速快于 3:00/km，超出小步点规则上限，请调慢。")
        }
        if (paceMinPerKm > MAX_PACE) {
            errors.add("配速慢于 12:00/km，超出小步点规则下限，请调快。")
        }
        if (cadenceSpm < MIN_CADENCE || cadenceSpm > MAX_CADENCE) {
            errors.add("步频应在 50-300 步/分钟之间（小步点规则），请调整。")
        }
        if (errors.isEmpty()) {
            val speed = PaceMath.paceToSpeedMps(paceMinPerKm)
            val step = PaceMath.stepLengthMetres(speed, cadenceSpm)
            if (step < 0.60 || step > 1.10) {
                errors.add(
                    "配速与步频对不上：按当前设置步长为 %.2f 米，超出 0.60-1.10 米的合理范围（例如 6:00/km 配 50 步/分钟会算出 3.33 米步长），请调整配速或步频。".format(step)
                )
            }
        }
        // 真实性警告：官方范围内，但不像普通学生校园跑
        if (paceMinPerKm in 3.0..5.5) {
            warnings.add("配速偏快，接近竞技水平，校园跑建议用 6:00-9:00/km。")
        }
        if (paceMinPerKm in 10.0..12.0) {
            warnings.add("配速偏慢，更接近快走，确认这就是你想要的跑步配速。")
        }
        if (cadenceSpm < 130.0 || cadenceSpm > 200.0) {
            warnings.add("步频在官方范围内，但超出学生正常 130-200 步/分钟，建议调回这个区间。")
        }
        return Result(errors, warnings)
    }

    /** 距离与暂停校验：目标里程取中间值，不要卡边界。 */
    fun validateRun(distanceKm: Double, pauseMinutes: Double): Result {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (distanceKm < MIN_DISTANCE_KM) {
            errors.add("单次里程 %.2fkm 低于最低 1.5km，不计成绩。".format(distanceKm))
        }
        if (distanceKm > MAX_DAILY_KM) {
            errors.add("单次里程 %.2fkm 超过每日计入上限 3.0km，超出部分不计成绩。".format(distanceKm))
        }
        if (distanceKm in 1.5..1.7 || distanceKm in 2.9..3.0) {
            warnings.add("里程卡在边界上，GPS 漂移很容易把它算成无效，建议目标 2.0-2.5km。")
        }
        if (pauseMinutes > MAX_PAUSE_MINUTES) {
            errors.add("暂停 %.1f 分钟超过最大暂停 5 分钟，成绩可能无效。".format(pauseMinutes))
        } else if (pauseMinutes > 3.0) {
            warnings.add("暂停已超过 3 分钟，离 5 分钟上限很近，尽快继续。")
        }
        return Result(errors, warnings)
    }

    /** 有效时段 06:30-23:00:59，时段外不要开始。 */
    fun isWithinValidWindow(hour: Int, minute: Int): Boolean {
        val t = hour * 60 + minute
        return t in (6 * 60 + 30)..(23 * 60 + 0)
    }
}
