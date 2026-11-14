package com.autoresolve

import kotlin.math.abs

enum class UsageLevel(val label: String) {
    DAILY("거의 매일"),
    OFTEN("주 2~3회"),
    RARELY("가끔")
}

/**
 * 내 차량 기록에서 이미 알 수 있는 값입니다. 사용자에게 다시 묻지 않습니다.
 * 주행거리(Odometer) 연동이 되면 usage도 여기에서 채울 수 있습니다.
 */
data class VehicleRecord(
    val model: String,
    val year: Int?,
    val powertrain: String,
    val chargingTroubleCount: Int,
    val serviceRequestCount: Int
)

/**
 * 기록에 없는 것만 사용자에게 묻습니다.
 * seatDelta, budgetDelta는 지금 타는 차 기준의 변화입니다. (-1 낮게, 0 비슷하게, +1 높게)
 */
data class NextCarPrefs(
    val record: VehicleRecord,
    val seatDelta: Int,
    val budgetDelta: Int,
    val performance: Int,
    val usage: UsageLevel,
    /** 차량에서 읽은 운전 습관. 있으면 성향·빈도는 여기서 정해 묻지 않습니다. */
    val driving: DrivingStats? = null
)

data class CarCandidate(
    val brand: String,
    val name: String,
    val body: String,
    /** 1 낮음, 2 보통, 3 높음 */
    val seatHeight: Int,
    /** 1 경제적, 2 합리적, 3 중상, 4 프리미엄 */
    val price: Int,
    /** 1 편안함 위주, 2 균형, 3 가속 성능 강조 */
    val performance: Int,
    val powertrains: List<String>
)

data class CarRecommendation(
    val car: CarCandidate,
    val score: Int,
    val reasons: List<String>
)

/**
 * 다음 차 추천. 현대차그룹(현대·기아·제네시스) 안에서만 추천합니다.
 *
 * 주의: 후보의 분류(시야 높이, 가격대, 성능)는 시연용 대략 분류입니다.
 * 실제 제원과 가격은 공식 자료로 확인해서 바꿔야 합니다.
 * 나중에 MBTI·사주 같은 재미 요소를 붙일 때는 NextCarPrefs에 가중치를 더하면 됩니다.
 */
object NextCarRecommender {

    val performanceLabels = listOf("편안함 위주", "균형", "가속 성능 중시")

    private val seatLabels = listOf("낮은 편", "보통", "높은 편")
    private val budgetLabels = listOf("경제적", "합리적", "중상", "프리미엄")
    private val ELECTRIC_ONLY = listOf(VehicleCatalog.EV)

    val candidates = listOf(
        CarCandidate("Hyundai", "캐스퍼", "경형 SUV", 2, 1, 1,
            listOf(VehicleCatalog.GAS)),
        CarCandidate("Hyundai", "아반떼", "준중형 세단", 1, 2, 2,
            listOf(VehicleCatalog.GAS, VehicleCatalog.HEV)),
        CarCandidate("Hyundai", "쏘나타", "중형 세단", 1, 2, 2,
            listOf(VehicleCatalog.GAS, VehicleCatalog.HEV)),
        CarCandidate("Hyundai", "코나", "소형 SUV", 2, 2, 2,
            listOf(VehicleCatalog.GAS, VehicleCatalog.HEV)),
        CarCandidate("Hyundai", "투싼", "준중형 SUV", 3, 3, 2,
            listOf(VehicleCatalog.GAS, VehicleCatalog.DIESEL,
                VehicleCatalog.HEV, VehicleCatalog.PHEV)),
        CarCandidate("Hyundai", "싼타페", "중형 SUV", 3, 3, 2,
            listOf(VehicleCatalog.GAS, VehicleCatalog.DIESEL, VehicleCatalog.HEV)),
        CarCandidate("Hyundai", "그랜저", "준대형 세단", 1, 3, 2,
            listOf(VehicleCatalog.GAS, VehicleCatalog.HEV)),
        CarCandidate("Hyundai", "아이오닉 5", "전기 SUV", 2, 3, 3,
            ELECTRIC_ONLY),
        CarCandidate("Hyundai", "아이오닉 6", "전기 세단", 1, 3, 3,
            ELECTRIC_ONLY),
        CarCandidate("Hyundai", "팰리세이드", "대형 SUV", 3, 4, 2,
            listOf(VehicleCatalog.GAS, VehicleCatalog.DIESEL, VehicleCatalog.HEV)),
        CarCandidate("Hyundai", "스타리아", "MPV", 3, 3, 1,
            listOf(VehicleCatalog.DIESEL, VehicleCatalog.GAS, VehicleCatalog.HEV)),
        CarCandidate("Kia", "레이", "경형 MPV", 3, 1, 1,
            listOf(VehicleCatalog.GAS)),
        CarCandidate("Kia", "K5", "중형 세단", 1, 2, 2,
            listOf(VehicleCatalog.GAS, VehicleCatalog.HEV)),
        CarCandidate("Kia", "셀토스", "소형 SUV", 2, 2, 2,
            listOf(VehicleCatalog.GAS, VehicleCatalog.DIESEL, VehicleCatalog.HEV)),
        CarCandidate("Kia", "스포티지", "준중형 SUV", 3, 3, 2,
            listOf(VehicleCatalog.GAS, VehicleCatalog.DIESEL,
                VehicleCatalog.HEV, VehicleCatalog.PHEV)),
        CarCandidate("Kia", "쏘렌토", "중형 SUV", 3, 3, 2,
            listOf(VehicleCatalog.GAS, VehicleCatalog.DIESEL,
                VehicleCatalog.HEV, VehicleCatalog.PHEV)),
        CarCandidate("Kia", "카니발", "MPV", 3, 3, 1,
            listOf(VehicleCatalog.GAS, VehicleCatalog.DIESEL, VehicleCatalog.HEV)),
        CarCandidate("Kia", "EV3", "소형 전기 SUV", 2, 3, 2,
            ELECTRIC_ONLY),
        CarCandidate("Kia", "EV6", "전기 크로스오버", 2, 4, 3,
            ELECTRIC_ONLY),
        CarCandidate("Genesis", "G70", "스포츠 세단", 1, 4, 3,
            listOf(VehicleCatalog.GAS, VehicleCatalog.DIESEL)),
        CarCandidate("Genesis", "G80", "대형 세단", 1, 4, 3,
            listOf(VehicleCatalog.GAS, VehicleCatalog.DIESEL)),
        CarCandidate("Genesis", "GV70", "중형 SUV", 3, 4, 3,
            listOf(VehicleCatalog.GAS, VehicleCatalog.DIESEL))
    )

    /** 지금 타는 차와 이름이 가장 길게 겹치는 후보. 예: "캐스퍼 일렉트릭" → 캐스퍼 */
    fun baselineFor(model: String): CarCandidate? =
        candidates
            .filter { model.startsWith(it.name) }
            .maxByOrNull { it.name.length }

    fun recommend(
        prefs: NextCarPrefs,
        limit: Int = 3
    ): List<CarRecommendation> {
        val base = baselineFor(prefs.record.model)

        return candidates
            .filter { !prefs.record.model.startsWith(it.name) }
            .map { score(it, base, prefs) }
            .sortedWith(
                compareByDescending<CarRecommendation> { it.score }
                    .thenBy { it.car.name }
            )
            .take(limit)
    }

    private fun score(
        car: CarCandidate,
        base: CarCandidate?,
        prefs: NextCarPrefs
    ): CarRecommendation {
        val record = prefs.record
        var score = 100
        val reasons = mutableListOf<String>()

        // 시야 높이: 지금 차 기준으로 얼마나 바꾸고 싶은지
        val targetSeat = ((base?.seatHeight ?: 2) + prefs.seatDelta).coerceIn(1, 3)
        val seatGap = abs(car.seatHeight - targetSeat)
        score -= 18 * seatGap
        reasons += if (seatGap == 0) {
            when (prefs.seatDelta) {
                0 -> "지금 차와 비슷한 운전 포지션(${seatLabels[car.seatHeight - 1]})이에요."
                1 -> "원하신 대로 지금보다 운전 포지션이 높은 편이에요."
                else -> "원하신 대로 지금보다 운전 포지션이 낮은 편이에요."
            }
        } else {
            "운전 포지션은 ${seatLabels[car.seatHeight - 1]}이라 원하는 조건과 차이가 있어요."
        }

        // 가격대: 지금 차 기준. 예산을 넘으면 더 크게 감점합니다.
        val targetBudget = ((base?.price ?: 2) + prefs.budgetDelta).coerceIn(1, 4)
        val priceGap = car.price - targetBudget
        score -= if (priceGap > 0) 30 * priceGap else 8 * -priceGap
        reasons += when {
            priceGap == 0 -> "예산대(${budgetLabels[targetBudget - 1]})와 맞아요."
            priceGap > 0 -> "원하는 예산보다 ${priceGap}단계 높은 가격대예요."
            else -> "원하는 예산보다 여유 있는 가격대예요."
        }

        // 주행 성향
        val perfGap = abs(car.performance - prefs.performance)
        score -= 12 * perfGap
        if (perfGap == 0) {
            reasons += "주행 성향(${performanceLabels[prefs.performance - 1]})과 맞아요."
        }

        // 타는 빈도
        val efficient = car.powertrains.any {
            it == VehicleCatalog.HEV ||
                    it == VehicleCatalog.EV ||
                    it == VehicleCatalog.PHEV
        }
        when (prefs.usage) {
            UsageLevel.DAILY -> if (efficient) {
                score += 8
                reasons += "자주 타시면 연료·전기 효율이 중요해서 하이브리드나 전기 선택지가 있는 차예요."
            }
            UsageLevel.RARELY -> if (car.price <= 2) {
                score += 6
                reasons += "가끔 타시면 유지 부담이 적은 가격대가 잘 맞아요."
            }
            UsageLevel.OFTEN -> Unit
        }

        // 운전 습관(차량 데이터): 많이 달리는지, 빠르게 달리는지
        prefs.driving?.let { d ->
            val small = car.body.contains("경형") || car.body.contains("소형")

            if (d.weeklyKm >= 250 && efficient) {
                score += 10
                reasons += "일주일에 약 ${d.weeklyKm.toInt()}km를 타서 연료·전기 효율 차이가 크게 쌓여요."
            }

            if (d.highSpeedShare >= 0.25) {
                if (car.performance >= 2 || car.price >= 3) {
                    score += 6
                    reasons += "고속 주행 비중이 ${(d.highSpeedShare * 100).toInt()}%라 고속 안정감이 있는 차가 잘 맞아요."
                }
            }

            if (d.avgMovingSpeedKmh in 1.0..32.0 && d.weeklyKm < 200) {
                if (small) {
                    score += 5
                    reasons += "평균 ${d.avgMovingSpeedKmh.toInt()}km/h 도심 위주라 크기가 작은 차가 다루기 편해요."
                }
            }

            if (d.performance() == 1 && car.performance == 3) {
                score -= 4
            }
        }

        // 아래는 차량 기록에서 읽은 값입니다. (사용자에게 묻지 않음)
        val electricOnly = car.powertrains == ELECTRIC_ONLY

        if (record.chargingTroubleCount > 0) {
            if (electricOnly) {
                score -= 20
                reasons += "충전이 불편했던 정비 접수 기록이 있어 전기차 전용 모델은 점수를 낮췄어요."
            } else if (car.powertrains.contains(VehicleCatalog.HEV)) {
                score += 6
                reasons += "충전이 불편했다면 충전이 필요 없는 하이브리드 구성도 살펴볼 만해요."
            }
        } else if (record.powertrain == VehicleCatalog.EV && electricOnly) {
            score += 6
            reasons += "지금 전기차를 타고 계셔서 충전 환경이 익숙해요."
        }

        return CarRecommendation(car, score, reasons)
    }
}
