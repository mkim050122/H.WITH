package com.autoresolve

/**
 * "진행 중인 상담 접수"를 안전하게 고르는 순수 로직입니다. (Context 없음 → 단독 테스트 가능)
 *
 * 단순히 가장 최근 접수를 고르지 않습니다. 아래를 모두 통과한 접수만 후보가 됩니다.
 *  1. 등록된 지 [WINDOW_MS](30분) 이내
 *  2. 접수 저장소에 실제로 있고, 아직 센터에 전달되지 않음
 *  3. 기록해 둔 시각과 접수 생성 시각이 거의 같음(다른 접수와 id만 겹친 경우 방지)
 *  4. 등록 당시 차량과 지금 차량이 같음
 * 후보가 둘 이상이거나 차량이 바뀌었으면 자동으로 고르지 않고 사용자 확인으로 넘깁니다.
 */
data class ActiveEntry(
    val requestId: String,
    val vehicleKey: String,
    val createdAt: Long,
    /** "gleo" 또는 "voice" (어느 경로의 상담인지) */
    val source: String
)

/** 접수 저장소에서 읽은 최소 정보. */
data class RequestInfo(
    val createdAt: Long,
    val alreadySent: Boolean
)

sealed class ActiveResolution {
    /** 연결할 상담이 없음(없거나 30분이 지났거나 이미 전달됨). */
    object None : ActiveResolution()

    /** 안전하게 하나로 정해짐. */
    data class Single(val entry: ActiveEntry) : ActiveResolution()

    /** 같은 차량의 상담이 둘 이상. 사용자가 고르게 해야 함. */
    data class Ambiguous(val entries: List<ActiveEntry>) : ActiveResolution()

    /** 상담은 있지만 지금 등록된 차량과 다름. 사용자 확인 필요. */
    data class VehicleChanged(val entries: List<ActiveEntry>) : ActiveResolution()
}

object ActiveConsultLogic {

    const val WINDOW_MS = 30L * 60L * 1000L
    const val MAX_ENTRIES = 5
    private const val CREATED_AT_TOLERANCE_MS = 60_000L

    fun vehicleKeyOf(profile: VehicleProfile?): String =
        if (profile == null) {
            "unregistered"
        } else {
            listOf(profile.manufacturer, profile.model, profile.year, profile.powertrain)
                .joinToString("|")
        }

    /** 기한이 지났거나, 시계가 거꾸로 간 항목은 버립니다. */
    fun isFresh(entry: ActiveEntry, now: Long): Boolean {
        val age = now - entry.createdAt
        return age in 0..WINDOW_MS
    }

    fun prune(entries: List<ActiveEntry>, now: Long): List<ActiveEntry> =
        entries.filter { isFresh(it, now) }
            .sortedByDescending { it.createdAt }
            .take(MAX_ENTRIES)

    /**
     * @param requestedId Gleo/화면이 접수 번호를 명시한 경우. 이 id도 같은 검증을 통과해야 합니다.
     * @param lookup 접수 id로 저장소 정보를 읽는 함수. 없으면 null.
     */
    fun select(
        entries: List<ActiveEntry>,
        currentVehicleKey: String,
        now: Long,
        lookup: (String) -> RequestInfo?,
        requestedId: String? = null
    ): ActiveResolution {
        val valid = entries
            .filter { isFresh(it, now) }
            .filter { entry ->
                val info = lookup(entry.requestId) ?: return@filter false
                !info.alreadySent &&
                        kotlin.math.abs(info.createdAt - entry.createdAt) <= CREATED_AT_TOLERANCE_MS
            }
            .distinctBy { it.requestId }
            .sortedByDescending { it.createdAt }

        if (requestedId != null) {
            val picked = valid.firstOrNull { it.requestId == requestedId }
                ?: return ActiveResolution.None

            return if (picked.vehicleKey == currentVehicleKey) {
                ActiveResolution.Single(picked)
            } else {
                ActiveResolution.VehicleChanged(listOf(picked))
            }
        }

        val same = valid.filter { it.vehicleKey == currentVehicleKey }
        val other = valid.filter { it.vehicleKey != currentVehicleKey }

        return when {
            same.size == 1 -> ActiveResolution.Single(same[0])
            same.size > 1 -> ActiveResolution.Ambiguous(same)
            other.isNotEmpty() -> ActiveResolution.VehicleChanged(other)
            else -> ActiveResolution.None
        }
    }
}
