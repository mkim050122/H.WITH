package com.autoresolve

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 시승 신청서 데이터 모델(화면과 분리). 현재는 접수처가 없어 이 기기 메모리에만 임시 보관한다.
 * 실제 공식 예약 API/접수처가 생기면 [TestDriveSubmitter]를 구현해 연결한다(화면 코드는 바꾸지 않는다).
 * 개인정보(이름·연락처)는 로그에 남기지 않는다.
 */
data class TestDriveDraft(
    val brandLabel: String,
    val carName: String,
    val storeName: String,
    val storeAddress: String,
    val storePhone: String?,
    val date: String = "",
    val time: String = "",
    val applicantName: String = "",
    val applicantPhone: String = "",
    val consentGiven: Boolean = false,
    val status: Status = Status.EDITING
) {
    enum class Status { EDITING, SAVED_LOCALLY }

    fun missingFields(): List<String> = buildList {
        if (date.isBlank()) add("희망 날짜")
        if (time.isBlank()) add("희망 시간")
        if (applicantName.isBlank()) add("신청자 이름")
        if (applicantPhone.count { it.isDigit() } < 9) add("연락처")
        if (!consentGiven) add("개인정보 동의")
    }

    fun isFor(carName: String, storeAddress: String) =
        this.carName == carName && this.storeAddress == storeAddress

    companion object {
        fun of(brandLabel: String, carName: String, store: DealerStore) = TestDriveDraft(
            brandLabel = brandLabel,
            carName = carName,
            storeName = store.name,
            storeAddress = store.address,
            storePhone = store.phone
        )
    }
}

sealed class SubmitResult {
    /** 접수처가 연결되지 않아 전송하지 않았다(아무 곳으로도 보내지 않음). */
    object NotConnected : SubmitResult()
    data class Failed(val reason: String) : SubmitResult()
}

/** 실제 접수처 연결 지점. 구현체는 consentGiven이 true일 때만 전송해야 한다. */
interface TestDriveSubmitter {
    val connected: Boolean
    fun submit(draft: TestDriveDraft): SubmitResult
}

/** 현재 기본값: 접수처 없음. 전송 시도는 항상 NotConnected. */
object NoTestDriveSubmitter : TestDriveSubmitter {
    override val connected: Boolean = false
    override fun submit(draft: TestDriveDraft): SubmitResult = SubmitResult.NotConnected
}

/**
 * 시연용 예약 데이터. AutoResolve 내부(SharedPreferences)에만 저장되며 현대·기아·매장 등 앱 밖으로 전송되지 않는다.
 * 실제 예약이 아니다. 이름·연락처는 가상 시연 정보만 허용한다([DemoRules]).
 */
data class DemoReservation(
    val id: String,
    val brandLabel: String,
    val carName: String,
    val storeName: String,
    val storeAddress: String,
    val date: String,
    val time: String,
    val applicantName: String,
    val applicantPhone: String,
    val createdAt: String,
    val cancelled: Boolean = false,
    val cancelledAt: String = ""
)

/** 실제 개인정보가 저장되지 않도록 가상 시연 정보만 허용하는 규칙. */
object DemoRules {
    const val SAMPLE_NAME = "시연 사용자"
    const val SAMPLE_PHONE = "010-0000-0000"
    private val phoneRegex = Regex("^010-0000-[0-9]{4}$")

    fun isVirtualName(name: String) = name.trim().startsWith("시연")
    fun isVirtualPhone(phone: String) = phoneRegex.matches(phone.trim())

    fun problems(d: TestDriveDraft): List<String> = buildList {
        if (d.applicantName.isNotBlank() && !isVirtualName(d.applicantName)) add("이름은 '시연'으로 시작하는 가상 이름만 가능해요 (실제 이름 입력 금지)")
        if (d.applicantPhone.isNotBlank() && !isVirtualPhone(d.applicantPhone)) add("연락처는 010-0000-0000 형식의 가상 번호만 가능해요 (실제 번호 입력 금지)")
    }
}

/**
 * 시연 예약 저장소(앱 재시작 후에도 유지). 시연 모드(BuildConfig.DEMO_MODE)에서만 동작하고,
 * release 빌드에서는 항상 비활성이다. 접수번호는 단조 증가 번호라 로컬에서 유일하다(전체 삭제 후에도 재사용 안 함).
 * 개인정보(가상 정보 포함)는 로그에 남기지 않는다.
 */
class DemoReservationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("autoresolve_demo_reservations", Context.MODE_PRIVATE)

    val enabled: Boolean get() = BuildConfig.DEMO_MODE

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date())

    fun list(): List<DemoReservation> {
        if (!enabled) return emptyList()
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        val out = ArrayList<DemoReservation>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    DemoReservation(
                        id = o.getString("id"),
                        brandLabel = o.optString("brandLabel"),
                        carName = o.optString("carName"),
                        storeName = o.optString("storeName"),
                        storeAddress = o.optString("storeAddress"),
                        date = o.optString("date"),
                        time = o.optString("time"),
                        applicantName = o.optString("applicantName"),
                        applicantPhone = o.optString("applicantPhone"),
                        createdAt = o.optString("createdAt"),
                        cancelled = o.optBoolean("cancelled", false),
                        cancelledAt = o.optString("cancelledAt")
                    )
                )
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out.reversed() // 최신순
    }

    private fun save(items: List<DemoReservation>) {
        val arr = JSONArray()
        for (r in items) {
            arr.put(
                JSONObject()
                    .put("id", r.id).put("brandLabel", r.brandLabel).put("carName", r.carName)
                    .put("storeName", r.storeName).put("storeAddress", r.storeAddress)
                    .put("date", r.date).put("time", r.time)
                    .put("applicantName", r.applicantName).put("applicantPhone", r.applicantPhone)
                    .put("createdAt", r.createdAt).put("cancelled", r.cancelled).put("cancelledAt", r.cancelledAt)
            )
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).commit()
    }

    /** 시연 모드가 아니거나 가상 정보 규칙·필수 항목을 어기면 null. */
    fun create(d: TestDriveDraft): DemoReservation? {
        if (!enabled) return null
        if (d.missingFields().isNotEmpty() || DemoRules.problems(d).isNotEmpty()) return null
        val seq = prefs.getInt(KEY_SEQ, 0) + 1
        prefs.edit().putInt(KEY_SEQ, seq).commit()
        val day = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date())
        val r = DemoReservation(
            id = "DEMO-" + day + "-" + String.format(Locale.US, "%04d", seq),
            brandLabel = d.brandLabel, carName = d.carName,
            storeName = d.storeName, storeAddress = d.storeAddress,
            date = d.date, time = d.time,
            applicantName = d.applicantName, applicantPhone = d.applicantPhone,
            createdAt = now()
        )
        save(list().reversed() + r)
        return r
    }

    fun cancel(id: String): Boolean {
        if (!enabled) return false
        val items = list().reversed()
        if (items.none { it.id == id && !it.cancelled }) return false
        save(items.map { if (it.id == id) it.copy(cancelled = true, cancelledAt = now()) else it })
        return true
    }

    fun clear() {
        if (!enabled) return
        prefs.edit().remove(KEY_LIST).commit()
    }

    private companion object {
        const val KEY_LIST = "list"
        const val KEY_SEQ = "seq"
    }
}
