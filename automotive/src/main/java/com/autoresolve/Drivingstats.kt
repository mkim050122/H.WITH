package com.autoresolve

import android.content.Context
import kotlin.math.abs

/**
 * 운전 습관 요약. 다음 차 추천에서 "얼마나 자주·많이, 빠르게 달리는지"를 묻지 않고 읽어 오기 위한 값입니다.
 *
 * 값은 속도 샘플로 [DrivingStatsStore]가 쌓습니다. 차량 속도를 받는 쪽(Vehicle SDK의 속도 콜백)에서
 * [DrivingStatsStore.onSpeedSample]만 불러 주면 됩니다.
 */
data class DrivingStats(
    val totalKm: Double,
    val sampleDays: Int,
    /** 일주일 기준 주행거리(km) */
    val weeklyKm: Double,
    /** 일주일 기준 운행 횟수(시동 후 출발 기준) */
    val tripsPerWeek: Double,
    /** 움직이는 동안의 평균 속도(km/h) */
    val avgMovingSpeedKmh: Double,
    /** 100km/h 이상으로 달린 시간 비율(0~1) */
    val highSpeedShare: Double,
    /** 100km당 급가속(초당 약 9km/h 이상 가속) 횟수 */
    val hardAccelPer100Km: Double,
    val isDemo: Boolean = false
) {
    /** 데이터가 충분히 쌓였는지. 시연 데이터는 항상 사용합니다. */
    val reliable: Boolean
        get() = isDemo || (sampleDays >= 3 && totalKm >= 20.0)

    fun usageLevel(): UsageLevel = when {
        tripsPerWeek >= 9 || weeklyKm >= 150 -> UsageLevel.DAILY
        tripsPerWeek >= 3 || weeklyKm >= 50 -> UsageLevel.OFTEN
        else -> UsageLevel.RARELY
    }

    /** 1 편안함 위주, 2 균형, 3 가속·고속 성능 중시 */
    fun performance(): Int {
        val score = hardAccelPer100Km / 4.0 + highSpeedShare * 4.0

        return when {
            score >= 2.5 -> 3
            score <= 0.8 -> 1
            else -> 2
        }
    }

    fun styleLabel(): String = when (performance()) {
        3 -> "빠르게 달리는 편"
        1 -> "차분하게 달리는 편"
        else -> "무난하게 달리는 편"
    }

    fun summaryLines(): List<String> = listOf(
        "일주일 약 ${weeklyKm.toInt()}km · 주 ${tripsPerWeek.toInt()}회 운행 (${usageLevel().label})",
        "평균 속도 ${avgMovingSpeedKmh.toInt()}km/h · 고속(100km/h 이상) ${(highSpeedShare * 100).toInt()}%",
        "급가속 100km당 ${"%.1f".format(hardAccelPer100Km)}회 → ${styleLabel()}"
    )
}

/** 시연·시험용 주행 습관. 실제 데이터가 아닙니다. */
enum class DemoDrivingProfile(val label: String, val stats: DrivingStats) {
    CITY_COMMUTER(
        "출퇴근 위주 (도심, 차분함)",
        DrivingStats(600.0, 30, 120.0, 10.0, 28.0, 0.02, 1.0, true)
    ),
    HIGHWAY_FAST(
        "고속도로 위주 (장거리, 빠름)",
        DrivingStats(1900.0, 30, 450.0, 8.0, 72.0, 0.38, 5.5, true)
    ),
    WEEKEND_SLOW(
        "주말에만 가끔 (짧게, 천천히)",
        DrivingStats(110.0, 30, 25.0, 2.0, 24.0, 0.0, 0.5, true)
    ),
    SPORTY(
        "자주 빠르게 (가속 많음)",
        DrivingStats(860.0, 30, 200.0, 7.0, 55.0, 0.15, 9.0, true)
    )
}

/**
 * 속도 샘플을 받아 운전 습관을 쌓아 둡니다. 이 기기 안(SharedPreferences)에만 저장하고
 * 위치나 경로는 저장하지 않습니다. 속도와 주행 거리 합계만 씁니다.
 */
class DrivingStatsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(
            "autoresolve_driving_stats", Context.MODE_PRIVATE
        )

    private var totalKm = prefs.getFloat("totalKm", 0f).toDouble()
    private var movingSec = prefs.getFloat("movingSec", 0f).toDouble()
    private var highSec = prefs.getFloat("highSec", 0f).toDouble()
    private var hardAccel = prefs.getInt("hardAccel", 0)
    private var trips = prefs.getInt("trips", 0)
    private var firstAt = prefs.getLong("firstAt", 0L)

    private var lastAt = 0L
    private var lastSpeed = 0f
    private var lastMovingAt = 0L
    private var lastHardAt = 0L
    private var lastFlush = 0L

    /** 속도 값(km/h)이 들어올 때마다 불러 주세요. 1초에 한 번 정도면 충분합니다. */
    @Synchronized
    fun onSpeedSample(speedKmh: Float, nowMs: Long = System.currentTimeMillis()) {
        if (firstAt == 0L) firstAt = nowMs

        // 새 운행 시작: 10분 넘게 멈춰 있다가 출발
        if (speedKmh > 5f && (lastMovingAt == 0L || nowMs - lastMovingAt > 10 * 60_000L)) {
            trips++
        }
        if (speedKmh > 5f) lastMovingAt = nowMs

        val dt = (nowMs - lastAt) / 1000.0

        if (lastAt != 0L && dt > 0 && dt <= 30) {
            val avg = (speedKmh + lastSpeed) / 2.0

            totalKm += avg * dt / 3600.0

            if (avg > 3.0) movingSec += dt
            if (speedKmh >= 100f) highSec += dt

            val accelKmhPerSec = (speedKmh - lastSpeed) / dt
            if (accelKmhPerSec >= 9.0 && nowMs - lastHardAt > 3000L) {
                hardAccel++
                lastHardAt = nowMs
            }
        }

        lastAt = nowMs
        lastSpeed = speedKmh

        if (nowMs - lastFlush > 15_000L) flush(nowMs)
    }

    @Synchronized
    fun flush(nowMs: Long = System.currentTimeMillis()) {
        lastFlush = nowMs

        prefs.edit()
            .putFloat("totalKm", totalKm.toFloat())
            .putFloat("movingSec", movingSec.toFloat())
            .putFloat("highSec", highSec.toFloat())
            .putInt("hardAccel", hardAccel)
            .putInt("trips", trips)
            .putLong("firstAt", firstAt)
            .apply()
    }

    fun setDemo(profile: DemoDrivingProfile?) {
        prefs.edit().apply {
            if (profile == null) remove("demo") else putString("demo", profile.name)
        }.apply()
    }

    fun demoProfile(): DemoDrivingProfile? =
        DemoDrivingProfile.values()
            .firstOrNull { it.name == prefs.getString("demo", null) }

    /** 시연 선택이 있으면 그 값을, 아니면 쌓인 데이터를 돌려줍니다. 아무것도 없으면 null. */
    @Synchronized
    fun snapshot(nowMs: Long = System.currentTimeMillis()): DrivingStats? {
        demoProfile()?.let { return it.stats }

        if (firstAt == 0L || totalKm <= 0.0) return null

        val days = ((nowMs - firstAt) / 86_400_000L).toInt() + 1
        val weeks = (days / 7.0).coerceAtLeast(1.0)

        val hours = movingSec / 3600.0

        return DrivingStats(
            totalKm = totalKm,
            sampleDays = days,
            weeklyKm = totalKm / weeks,
            tripsPerWeek = trips / weeks,
            avgMovingSpeedKmh = if (hours > 0) totalKm / hours else 0.0,
            highSpeedShare = if (movingSec > 0) highSec / movingSec else 0.0,
            hardAccelPer100Km = if (totalKm > 0) hardAccel / totalKm * 100.0 else 0.0
        )
    }
}