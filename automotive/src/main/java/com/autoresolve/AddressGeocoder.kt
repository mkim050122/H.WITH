package com.autoresolve

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 매장 주소 → WGS84 좌표(실시간 조회 전용). 결과는 저장하지 않는다(메모리의 선택 매장에만 잠시 보관).
 *
 * 카카오 로컬 API: 개발자 포럼 답변에 "실시간 호출만 가능, 저장 목적 호출 불가"가 있어 영구 저장·파일 생성은 하지 않는다.
 * 약관상 NaviHelper와의 병용 허용 여부는 공식 약관 조항으로 확인하지 못했다(포럼 답변은 타사 지도 SDK 병용 가능).
 * API 키는 코드에 넣지 않고 local.properties(kakao.rest.api.key) → BuildConfig로만 주입한다. 키가 비면 기능 꺼짐.
 */
object AddressMatcher {

    data class Parsed(val districts: List<String>, val road: String, val number: String)
    data class Doc(val roadAddressName: String?, val lon: Double, val lat: Double)

    sealed class Result {
        data class Ok(val lat: Double, val lon: Double, val matchedAddress: String) : Result()
        data class Fail(val reason: String) : Result()
    }

    // 도로명 + 건물번호: 예) 강남대로 292, 성수이로26길 61, 뒷골1로 2
    private val ROAD = Regex("([가-힣]+\\d*(?:대로|로)(?:\\d+번?길)?|[가-힣]+\\d*길)\\s+(\\d+(?:-\\d+)?)")

    fun parse(address: String): Parsed? {
        val m = ROAD.find(address) ?: return null
        val prefix = address.substring(0, m.range.first)
        val districts = prefix.split(Regex("\\s+")).filter { it.length >= 2 && (it.endsWith("구") || it.endsWith("시") || it.endsWith("군")) &&
                    !it.endsWith("특별시") && !it.endsWith("광역시") && !it.endsWith("자치시") }
        if (districts.isEmpty()) return null
        return Parsed(districts, m.groupValues[1], m.groupValues[2])
    }

    fun query(address: String): String? {
        val p = parse(address) ?: return null
        val m = ROAD.find(address)!!
        val region = address.substring(0, m.range.first).trim()
        return "$region ${p.road} ${p.number}"
    }

    /** 원본 주소와 일치하는 단 하나의 결과만 허용. 여러 건·불일치·없음이면 실패(첫 결과 자동 선택 안 함). */
    fun select(original: String, docs: List<Doc>): Result {
        val p = parse(original) ?: return Result.Fail("주소에서 도로명과 번호를 찾지 못했어요")
        if (docs.isEmpty()) return Result.Fail("검색 결과가 없어요")
        val distinct = docs.distinctBy { Triple(it.roadAddressName, it.lat, it.lon) }
        if (distinct.size > 1) return Result.Fail("검색 결과가 여러 건이라 선택하지 않았어요")
        val d = distinct[0]
        val name = d.roadAddressName ?: return Result.Fail("도로명 주소가 없는 결과예요")
        val roadOk = Regex("(^|\\s)" + Regex.escape(p.road) + "\\s+" + Regex.escape(p.number) + "(\\s|\\(|$)").containsMatchIn(name)
        val districtOk = p.districts.all { name.contains(it) }
        if (!roadOk || !districtOk) return Result.Fail("검색된 주소가 원본과 달라요: $name")
        if (d.lat !in 33.0..39.0 || d.lon !in 124.0..132.0) return Result.Fail("좌표가 한국 영역 밖이에요")
        return Result.Ok(d.lat, d.lon, name)
    }
}

object KakaoGeocoder {
    private const val TAG = "AutoResolveGeocode"

    private val key: String get() = BuildConfig.KAKAO_REST_API_KEY

    /** 키가 설정된 빌드에서만 사용 가능. */
    val enabled: Boolean get() = key.isNotBlank()

    /** 진행 중 요청 핸들. cancel() 하면 연결을 끊고, 이후 도착한 응답은 onDone으로 전달되지 않는다. */
    class Call {
        @Volatile var cancelled = false
            private set
        @Volatile internal var conn: HttpURLConnection? = null

        fun cancel() {
            cancelled = true
            try { conn?.disconnect() } catch (e: Exception) { Log.w(TAG, "[요청 취소 중 예외] ${e.javaClass.simpleName}") }
        }
    }

    /**
     * 백그라운드 스레드에서 1회 실시간 조회. 취소되면 onDone을 호출하지 않는다.
     * onDone은 백그라운드 스레드에서 실행되므로 UI는 runOnUiThread 필요.
     */
    fun lookup(address: String, onDone: (AddressMatcher.Result) -> Unit): Call {
        val call = Call()
        Thread {
            val r = request(address, call)
            if (!call.cancelled) onDone(r)
        }.start()
        return call
    }

    private fun request(address: String, call: Call): AddressMatcher.Result {
        if (!enabled) return AddressMatcher.Result.Fail("지오코딩 키가 설정되지 않았어요")
        val q = AddressMatcher.query(address) ?: return AddressMatcher.Result.Fail("주소에서 도로명과 번호를 찾지 못했어요")
        Log.i(TAG, "[지오코딩 요청] query=$q")
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(
                "https://dapi.kakao.com/v2/local/search/address.json?query=" +
                        URLEncoder.encode(q, "UTF-8") + "&analyze_type=exact&size=5"
            )
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Authorization", "KakaoAK $key")
            }
            call.conn = conn
            if (call.cancelled) return AddressMatcher.Result.Fail("취소됨")
            if (conn.responseCode != 200) {
                Log.w(TAG, "[지오코딩 결과] HTTP 오류 ${conn.responseCode}")
                val code = conn.responseCode
                return AddressMatcher.Result.Fail(
                    when (code) {
                        401, 403 -> "지오코딩 인증·권한 오류($code)"
                        429 -> "지오코딩 호출 한도 초과(429)"
                        else -> "지오코딩 서버 응답 오류($code)"
                    }
                )
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = org.json.JSONObject(body).optJSONArray("documents")
            val docs = ArrayList<AddressMatcher.Doc>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val x = o.optString("x").toDoubleOrNull() ?: continue
                    val y = o.optString("y").toDoubleOrNull() ?: continue
                    docs.add(AddressMatcher.Doc(o.optJSONObject("road_address")?.optString("address_name"), x, y))
                }
            }
            val r = AddressMatcher.select(address, docs)
            Log.i(
                TAG,
                "[지오코딩 결과] 후보=${docs.size} " +
                        if (r is AddressMatcher.Result.Fail) "실패: ${r.reason}" else "일치(좌표는 로그에 남기지 않음)"
            )
            r
        } catch (e: Exception) {
            Log.w(TAG, "[지오코딩 결과] 실패 ${e.javaClass.simpleName}")
            AddressMatcher.Result.Fail("네트워크 오류로 주소를 확인하지 못했어요")
        } finally {
            conn?.disconnect()
        }
    }
}
