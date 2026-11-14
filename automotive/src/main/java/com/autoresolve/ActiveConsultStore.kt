package com.autoresolve

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 진행 중인 상담 접수를 기기에 저장합니다. 판단 규칙은 [ActiveConsultLogic]에 있습니다.
 * 저장하는 것은 접수 번호·차량 식별값·시각·경로뿐이고, 증상 내용은 접수 저장소(ServiceRequestStore)에만 있습니다.
 */
class ActiveConsultStore(private val context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        "autoresolve_active_consult",
        Context.MODE_PRIVATE
    )

    private fun load(): List<ActiveEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()

        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                ActiveEntry(
                    requestId = o.getString("id"),
                    vehicleKey = o.optString("vehicle"),
                    createdAt = o.getLong("at"),
                    source = o.optString("src")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "active consult unreadable, ignoring", e)
            emptyList()
        }
    }

    private fun write(entries: List<ActiveEntry>) {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put("id", it.requestId)
                    .put("vehicle", it.vehicleKey)
                    .put("at", it.createdAt)
                    .put("src", it.source)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    /**
     * 상담으로 만든 접수를 "진행 중"으로 기록합니다. 오래된 항목은 이때 정리합니다.
     * 같은 경로(source)·같은 차량의 이전 항목은 새 상담으로 대체됩니다. (접수 자체는 접수 내역에 남습니다.)
     * 그래서 Gleo가 같은 대화에서 증상을 보충해 다시 호출해도 후보가 둘로 늘지 않습니다.
     */
    fun register(
        requestId: String,
        profile: VehicleProfile?,
        source: String,
        now: Long = System.currentTimeMillis()
    ) {
        val key = ActiveConsultLogic.vehicleKeyOf(profile)

        val entry = ActiveEntry(
            requestId = requestId,
            vehicleKey = key,
            createdAt = now,
            source = source
        )

        val kept = load().filter {
            it.requestId != requestId && !(it.source == source && it.vehicleKey == key)
        }

        write(ActiveConsultLogic.prune(kept + entry, now))
        Log.d(TAG, "registered $requestId source=$source")
    }

    fun resolve(
        profile: VehicleProfile?,
        requestedId: String? = null,
        now: Long = System.currentTimeMillis()
    ): ActiveResolution {
        val requests = ServiceRequestStore(context)

        return ActiveConsultLogic.select(
            entries = load(),
            currentVehicleKey = ActiveConsultLogic.vehicleKeyOf(profile),
            now = now,
            lookup = { id ->
                requests.find(id)?.let {
                    RequestInfo(
                        createdAt = it.createdAt,
                        alreadySent = it.status != RequestStatus.SAVED
                    )
                }
            },
            requestedId = requestedId
        )
    }

    /** 센터 연결까지 끝났거나 사용자가 취소한 상담을 목록에서 뺍니다. */
    fun complete(requestId: String) {
        write(load().filter { it.requestId != requestId })
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val TAG = "AutoResolveActive"
        private const val KEY = "entries"
    }
}
