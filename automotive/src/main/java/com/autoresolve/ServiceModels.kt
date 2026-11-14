package com.autoresolve

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** 고장 부위. 서비스센터가 처리할 수 있는 범위를 나눌 때 씁니다. */
enum class PartCategory(val label: String, val urgent: Boolean = false) {
    TIRE("타이어·휠"),
    BRAKE("브레이크·제동", urgent = true),
    ENGINE("엔진·변속기"),
    BATTERY_CHARGING("배터리·충전"),
    AIR_CONDITIONING("에어컨·공조"),
    ELECTRICAL("전장·센서·시동"),
    BODY("외관·판금"),
    OTHER("기타 · 잘 모르겠어요");

    companion object {
        fun fromName(name: String?): PartCategory =
            values().firstOrNull { it.name == name } ?: OTHER
    }
}

enum class RequestStatus(val label: String) {
    SAVED("저장됨"),
    SENT_DEMO("센터 전달됨 (시연)");

    companion object {
        fun fromName(name: String?): RequestStatus =
            values().firstOrNull { it.name == name } ?: SAVED
    }
}

data class ServiceCenter(
    val id: String,
    val name: String,
    val address: String,
    val distanceKm: Double,
    /** 지금 접수하면 예상되는 대기 시간(분). */
    val waitMinutes: Int,
    /** 처리 가능한 부위. */
    val parts: Set<PartCategory>,
    /** 전기차 고전압 계통 점검이 가능한 센터인지. */
    val evCertified: Boolean
)

/**
 * 시연용 센터 목록입니다. 실제 센터가 아닙니다.
 * 실제 서비스에서는 현대자동차 서비스센터 데이터와 예약 API로 바꿔야 합니다.
 */
object DemoServiceCenters {
    val all = listOf(
        ServiceCenter(
            "demo-a", "시연 서비스센터 A", "시연용 주소 · 도심",
            1.8, 45,
            setOf(
                PartCategory.TIRE, PartCategory.ENGINE,
                PartCategory.ELECTRICAL, PartCategory.AIR_CONDITIONING,
                PartCategory.OTHER
            ),
            false
        ),
        ServiceCenter(
            "demo-b", "시연 서비스센터 B", "시연용 주소 · 외곽",
            3.2, 15,
            setOf(
                PartCategory.TIRE, PartCategory.AIR_CONDITIONING,
                PartCategory.BODY, PartCategory.OTHER
            ),
            false
        ),
        ServiceCenter(
            "demo-c", "시연 서비스센터 C", "시연용 주소 · 산업단지",
            5.5, 30,
            setOf(
                PartCategory.ENGINE, PartCategory.BRAKE,
                PartCategory.ELECTRICAL, PartCategory.BATTERY_CHARGING,
                PartCategory.OTHER
            ),
            true
        ),
        ServiceCenter(
            "demo-d", "시연 서비스센터 D", "시연용 주소 · 신도시",
            7.9, 10,
            setOf(
                PartCategory.TIRE, PartCategory.BRAKE, PartCategory.ENGINE,
                PartCategory.AIR_CONDITIONING, PartCategory.ELECTRICAL,
                PartCategory.BODY, PartCategory.OTHER
            ),
            false
        ),
        ServiceCenter(
            "demo-e", "시연 서비스센터 E", "시연용 주소 · 외곽",
            12.4, 5,
            setOf(
                PartCategory.BATTERY_CHARGING, PartCategory.ELECTRICAL,
                PartCategory.OTHER
            ),
            true
        )
    )
}

enum class CenterSort(val label: String) {
    NEAREST("가까운 순"),
    FASTEST("예상 소요시간순")
}

data class RankedCenter(
    val center: ServiceCenter,
    val travelMinutes: Int,
    val totalMinutes: Int
)

object ServiceCenterRanker {

    /** 이동 시간을 계산하는 가정 속도입니다. 실제 경로 시간으로 바꿔야 합니다. */
    private const val AVERAGE_SPEED_KMH = 30.0

    fun rank(
        centers: List<ServiceCenter>,
        part: PartCategory?,
        needsEvCertified: Boolean,
        sort: CenterSort
    ): List<RankedCenter> {
        val ranked = centers
            .filter {
                part == null ||
                        part == PartCategory.OTHER ||
                        part in it.parts
            }
            .filter { !needsEvCertified || it.evCertified }
            .map {
                val travel =
                    (it.distanceKm / AVERAGE_SPEED_KMH * 60).roundToInt()
                RankedCenter(it, travel, travel + it.waitMinutes)
            }

        return when (sort) {
            CenterSort.NEAREST -> ranked.sortedBy { it.center.distanceKm }
            CenterSort.FASTEST -> ranked.sortedBy { it.totalMinutes }
        }
    }
}

/** 센터에 전달할 정비 접수 내용. 이름과 연락처는 담지 않습니다. */
data class ServiceRequest(
    val id: String,
    val createdAt: Long,
    val vehicle: String,
    val part: PartCategory,
    val symptom: String,
    val diagnosisSummary: String,
    val transcript: String,
    val centerId: String?,
    val centerName: String?,
    val status: RequestStatus
)

fun newRequestId(now: Long = System.currentTimeMillis()): String {
    val day = SimpleDateFormat("yyMMdd", Locale.KOREA).format(Date(now))
    return "AR-$day-${(1000..9999).random()}"
}

fun formatDateTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(millis))

/** 센터와 정비사가 한 번에 읽을 수 있는 접수 내용. */
fun ServiceRequest.toReport(): String = buildString {
    appendLine("H.WITH 정비 접수 $id")
    appendLine("접수 시각: ${formatDateTime(createdAt)}")
    appendLine()
    appendLine("차량: $vehicle")
    appendLine("고장 부위: ${part.label}")
    appendLine("증상: $symptom")

    if (diagnosisSummary.isNotBlank()) {
        appendLine()
        appendLine("자가 확인 내용")
        appendLine(diagnosisSummary)
    }

    if (transcript.isNotBlank()) {
        appendLine()
        appendLine("AI 상담 기록")
        appendLine(transcript)
    }

    appendLine()
    appendLine("정확한 고장 원인은 전문 정비사의 확인이 필요합니다.")
}.trim()

/** 센터로 보내는 부분. 지금은 시연용이고, 서버가 생기면 여기만 교체합니다. */
interface ServiceDispatcher {
    fun dispatch(
        request: ServiceRequest,
        center: ServiceCenter,
        onDone: (Boolean) -> Unit
    )
}

object DemoServiceDispatcher : ServiceDispatcher {
    override fun dispatch(
        request: ServiceRequest,
        center: ServiceCenter,
        onDone: (Boolean) -> Unit
    ) {
        // 실제 전송은 하지 않고 성공으로 처리합니다.
        onDone(true)
    }
}

/** 접수 내역을 이 기기 안에만 저장합니다. 최대 개수를 넘으면 오래된 것부터 지웁니다. */
class ServiceRequestStore(context: Context) {

    private val prefs = context.getSharedPreferences(
        "autoresolve_service_requests",
        Context.MODE_PRIVATE
    )

    fun loadAll(): List<ServiceRequest> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()

        return try {
            val array = JSONArray(raw)
            (0 until array.length())
                .map { toRequest(array.getJSONObject(it)) }
                .sortedByDescending { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun find(id: String): ServiceRequest? =
        loadAll().firstOrNull { it.id == id }

    fun save(request: ServiceRequest) {
        val merged = loadAll().filter { it.id != request.id } + request
        write(
            merged
                .sortedByDescending { it.createdAt }
                .take(MAX_ITEMS)
        )
    }

    fun delete(id: String) {
        write(loadAll().filter { it.id != id })
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun write(list: List<ServiceRequest>) {
        val array = JSONArray()
        list.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun toJson(r: ServiceRequest): JSONObject =
        JSONObject()
            .put("id", r.id)
            .put("createdAt", r.createdAt)
            .put("vehicle", r.vehicle)
            .put("part", r.part.name)
            .put("symptom", r.symptom)
            .put("diagnosisSummary", r.diagnosisSummary)
            .put("transcript", r.transcript)
            .put("centerId", r.centerId ?: JSONObject.NULL)
            .put("centerName", r.centerName ?: JSONObject.NULL)
            .put("status", r.status.name)

    private fun toRequest(o: JSONObject): ServiceRequest =
        ServiceRequest(
            id = o.getString("id"),
            createdAt = o.getLong("createdAt"),
            vehicle = o.optString("vehicle"),
            part = PartCategory.fromName(o.optString("part")),
            symptom = o.optString("symptom"),
            diagnosisSummary = o.optString("diagnosisSummary"),
            transcript = o.optString("transcript"),
            centerId = if (o.isNull("centerId")) null else o.optString("centerId"),
            centerName = if (o.isNull("centerName")) null else o.optString("centerName"),
            status = RequestStatus.fromName(o.optString("status"))
        )

    companion object {
        private const val KEY = "items"
        private const val MAX_ITEMS = 20
    }
}