package com.autoresolve

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/**
 * 「다음 차 추천」 → 시승 매장 찾기 연결. 추천 알고리즘은 건드리지 않고 brand/name만 받는다.
 *
 * 시승 예약은 앱에서 완료되지 않는다(공식 사이트로 이동만 한다).
 * 매장 데이터 API가 없어 DealerDirectory는 비어 있다. 검증된 주소/좌표만 넣을 것(임의 좌표 금지).
 */
object TestDriveLinks {
    private const val TAG = "AutoResolveTestDrive"

    // 한국 공식 사이트. 앱은 페이지로 이동만 하며 예약 시스템과 연결돼 있지 않다.
    // 현대 신청 폼(드라이빙라운지 시승 신청, 2026-11-12 브라우저로 열림 확인). 모델·장소 사전 선택 파라미터는 미검증.
    const val HYUNDAI_TEST_DRIVE_URL = "https://www.hyundai.com/kr/ko/e/vehicles/test-driving/reservation"
    const val HYUNDAI_BRANCH_URL = "https://www.hyundai.com/kr/ko/e/customer/branch"
    const val KIA_BRANCH_URL = "https://www.kia.com/kr/experience/branch"
    const val KIA_TEST_DRIVE_URL = "https://www.kia.com/kr/experience/book-a-test-drive/form"

    enum class Kind { FIND_STORE, TEST_DRIVE }

    /** 지원하지 않는 브랜드(예: Genesis)는 null. */
    fun urlFor(brandCode: String, kind: Kind): String? = when (brandCode) {
        "Hyundai" -> if (kind == Kind.FIND_STORE) HYUNDAI_BRANCH_URL else HYUNDAI_TEST_DRIVE_URL
        "Kia" -> if (kind == Kind.FIND_STORE) KIA_BRANCH_URL else KIA_TEST_DRIVE_URL
        else -> null
    }

    /** 성공하면 null, 실패하면 사용자에게 보여줄 오류 문구(실패 시 URL을 클립보드에 복사). */
    fun open(context: Context, brandCode: String, kind: Kind): String? {
        val url = urlFor(brandCode, kind)
        if (url == null) {
            Log.w(TAG, "[공식 페이지 열기] 지원하지 않는 브랜드 $brandCode")
            return "이 브랜드는 공식 판매점 링크가 준비되지 않았어요."
        }
        Log.i(TAG, "[공식 페이지 열기 시도] brand=$brandCode kind=$kind url=$url")
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Log.i(TAG, "[ACTION_VIEW 호출 완료] 예외 없음(실제 페이지가 떴는지는 화면으로 확인)")
            null
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "[공식 페이지 열기 실패] ActivityNotFoundException: 처리할 브라우저 없음")
            failMessage("이 차량에는 웹 브라우저가 없어 페이지를 열 수 없어요.", context, url)
        } catch (e: SecurityException) {
            Log.w(TAG, "[공식 페이지 열기 실패] SecurityException: ${e.message?.take(120)}")
            failMessage("이 앱은 웹 페이지를 열 수 있는 권한이 없어요.", context, url)
        } catch (e: Exception) {
            Log.w(TAG, "[공식 페이지 열기 실패] ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            failMessage("웹 페이지를 열지 못했어요.", context, url)
        }
    }

    private fun failMessage(reason: String, context: Context, url: String): String {
        val copied = copyText(context, "시승 신청 주소", url)
        val web = MapIntents.handlersOf(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.hyundai.com")))
        val why = if (web.isEmpty()) " (https를 처리하는 앱이 없음)" else ""
        return reason + why + (if (copied) " 주소가 복사되었어요. 휴대폰이나 PC에서 열어 주세요." else " 아래 주소를 휴대폰이나 PC에서 열어 주세요.") + "\n" + url
    }

    /** 클립보드 복사(권한 불필요). 성공 여부 반환. */
    fun copyText(context: Context, label: String, text: String): Boolean = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        // 복사가 실제로 반영됐는지 되읽어 확인(되읽기가 막힌 환경이면 null → 실패로 처리하지 않고 true)
        val back = cm.primaryClip?.getItemAt(0)?.text?.toString()
        val ok = back == null || back == text
        Log.i(TAG, "[클립보드 복사] label=$label 되읽기=${if (back == null) "불가" else if (ok) "일치" else "불일치"}")
        ok
    } catch (e: Exception) {
        Log.w(TAG, "[클립보드 복사 실패] ${e.javaClass.simpleName}")
        false
    }

    /** 지도 검색(geo:0,0?q=). 앱이 없으면 원인을 담은 Unavailable. 검색 요청 전달 ≠ 길안내 시작. */
    fun openMapSearch(context: Context, store: DealerStore): MapIntents.Outcome = MapIntents.searchOnMap(context, store)

    fun copyAddress(context: Context, store: DealerStore): Boolean = copyText(context, "매장 주소", store.address)

    /** 전화 앱 연결(ACTION_DIAL, 권한 불필요). 번호만 입력해 주며 자동 발신하지 않는다. */
    fun dial(context: Context, phone: String?): String? {
        val digits = phone?.filter { it.isDigit() || it == '+' }.orEmpty()
        if (digits.length < 7) return "확인된 전화번호가 없어요."
        return try {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            null
        } catch (e: Exception) {
            Log.w(TAG, "[전화 연결 실패] ${e.javaClass.simpleName}")
            "이 차량에서는 전화 연결이 지원되지 않아요. 번호를 확인해 직접 연락하세요."
        }
    }
}

/**
 * 매장 정보. demo=true 이면 시연용. 좌표(lat/lon)는 지오코딩 등으로 확인된 경우에만 채우고,
 * 없으면 null(목록·전화·주소 확인만 제공, 길안내 불가). source/verifiedAt은 공식 출처 URL과 확인 날짜.
 */
data class DealerStore(
    val brandCode: String,
    val name: String,
    val address: String,
    val phone: String?,
    val lat: Double?,
    val lon: Double?,
    val source: String,
    val verifiedAt: String,
    val coordSource: String?,
    val demo: Boolean,
    val coordVerifiedAt: String? = null,
    val coordStatus: String? = null
) {
    /** 실시간 지오코딩으로 원본 주소와 일치가 확인된 좌표를 메모리에서만 붙인 사본(저장하지 않음). */
    fun withVerifiedCoord(lat: Double, lon: Double, coordSource: String): DealerStore =
        copy(lat = lat, lon = lon, coordSource = coordSource)

    /** 한국 영역 안의 좌표가 확인된 실제(비시연) 매장만 길안내 가능. */
    val canNavigate: Boolean
        get() = !demo && lat != null && lon != null && lat in 33.0..39.0 && lon in 124.0..132.0
}

/**
 * assets/dealers.json 에서 읽는다. 형식:
 * [{"brand":"Hyundai","name":"..","address":"..","phone":"..","source":"공식 URL","verifiedAt":"2026-11-12",
 *   "lat":37.5,"lon":127.0,"coordSource":"좌표 출처(지오코더명·조회일)","demo":false}]
 * 필수: brand, name, address, source, verifiedAt. 좌표는 선택(없으면 길안내만 비활성).
 * lat/lon은 coordSource가 있을 때만 사용한다. 파일이 없으면 빈 목록(임의 데이터를 만들지 않는다).
 */
object DealerDirectory {
    private const val TAG = "AutoResolveDealers"
    private var cache: List<DealerStore>? = null

    fun load(context: Context): List<DealerStore> {
        cache?.let { return it }
        val out = ArrayList<DealerStore>()
        try {
            val text = context.assets.open("dealers.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = org.json.JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val address = o.optString("address").trim()
                val source = o.optString("source").trim()
                val verifiedAt = o.optString("verifiedAt").trim()
                val brand = o.optString("brand").trim()
                if (name.isEmpty() || address.isEmpty() || brand.isEmpty() ||
                    source.isEmpty() || verifiedAt.isEmpty()
                ) {
                    Log.w(TAG, "[매장 제외] 이름·주소·출처·확인일 누락 index=$i")
                    continue
                }
                val coordSource = o.optString("coordSource").trim().ifEmpty { null }
                val hasCoord = coordSource != null && o.has("lat") && o.has("lon") &&
                        !o.isNull("lat") && !o.isNull("lon")
                out.add(
                    DealerStore(
                        brandCode = brand,
                        name = name,
                        address = address,
                        phone = o.optString("phone").trim().ifEmpty { null },
                        lat = if (hasCoord) o.getDouble("lat") else null,
                        lon = if (hasCoord) o.getDouble("lon") else null,
                        source = source,
                        verifiedAt = verifiedAt,
                        coordSource = coordSource,
                        demo = o.optBoolean("demo", false)
                    )
                )
            }
        } catch (e: java.io.FileNotFoundException) {
            Log.i(TAG, "[매장 데이터 없음] assets/dealers.json")
        } catch (e: Exception) {
            Log.w(TAG, "[매장 데이터 읽기 실패] ${e.javaClass.simpleName}")
        }
        applyTempCoords(context, out)
        cache = out
        Log.i(TAG, "[매장 데이터] ${out.size}건 (좌표 있음 ${out.count { it.canNavigate }}건)")
        return out
    }

    /**
     * 시연 모드(BuildConfig.DEMO_MODE)에서만 debug 소스셋의 assets/dealer_coords_temp.json 을 읽어 좌표를 붙인다.
     * 이 좌표는 출처 서비스와 저장·재사용 조건이 미확인인 임시 검증 자료라 release 빌드/배포용 dealers.json 에는 넣지 않는다.
     * brand+name+address 가 dealers.json 항목과 정확히 같고 한국 영역(위도 33~39, 경도 124~132)일 때만 적용하며,
     * dealers.json 에 이미 좌표가 있으면 덮어쓰지 않는다.
     */
    private fun applyTempCoords(context: Context, stores: MutableList<DealerStore>) {
        if (!BuildConfig.DEMO_MODE) return
        try {
            val text = context.assets.open("dealer_coords_temp.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = org.json.JSONArray(text)
            var applied = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val brand = o.optString("brand").trim()
                val name = o.optString("name").trim()
                val address = o.optString("address").trim()
                val src = o.optString("coordSource").trim()
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                val idx = stores.indexOfFirst { it.brandCode == brand && it.name == name && it.address == address }
                if (idx < 0 || src.isEmpty() || lat.isNaN() || lon.isNaN() ||
                    lat !in 33.0..39.0 || lon !in 124.0..132.0
                ) {
                    Log.w(TAG, "[임시 좌표 제외] index=$i (이름·주소 불일치 또는 값 이상)")
                    continue
                }
                if (stores[idx].lat != null) continue
                stores[idx] = stores[idx].copy(
                    lat = lat, lon = lon, coordSource = src,
                    coordVerifiedAt = o.optString("coordinateVerifiedAt").trim().ifEmpty { null },
                    coordStatus = o.optString("verificationStatus").trim().ifEmpty { null }
                )
                applied++
            }
            Log.i(TAG, "[임시 좌표 적용] ${applied}건 (시연 모드 전용, 이용조건 미확인 자료)")
        } catch (e: java.io.FileNotFoundException) {
            Log.i(TAG, "[임시 좌표 파일 없음] 좌표 없는 매장은 길안내 불가")
        } catch (e: Exception) {
            Log.w(TAG, "[임시 좌표 읽기 실패] ${e.javaClass.simpleName}")
        }
    }

    fun search(context: Context, brandCode: String, region: String): List<DealerStore> {
        val q = region.trim()
        return load(context).filter {
            it.brandCode == brandCode && (q.isEmpty() || it.address.contains(q) || it.name.contains(q))
        }
    }
}

/** 사용자가 선택한 매장 하나에 대한 NaviHelper 길안내. 화면을 떠날 때 release() 호출. */
class DealerNavi(context: Context, onMessage: (String) -> Unit) {
    // release() 이후에는 어떤 콜백도 UI로 전달하지 않는다.
    private val sink = onMessage
    private fun say(m: String) { if (!released) sink(m) }

    private val tag = "AutoResolveDealerNavi"
    private val appContext = context.applicationContext
    private var navi: NaviHelper? = null
    private var connected = false
    private var released = false
    private var failed = false
    private var routeEventSeen = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pending: DealerStore? = null // 연결 중에 누른 길안내(사용자가 선택한 매장 1곳만)

    private val listener = object : NaviHelperEventListener {
        override fun onError(errorCode: NaviErrorCode) {
            routeEventSeen = true
            Log.w(tag, "[NaviHelper 오류 이벤트] $errorCode")
            val t = errorCode.toString()
            val friendly = when {
                t.contains("TOO_SHORT_DISTANCE") -> "현재 위치와 매장이 너무 가까워요."
                t.contains("OVER_DISTANCE") -> "탐색 가능한 거리를 넘었어요."
                t.contains("LOW_BATTERY") -> "배터리가 부족해요."
                t.contains("SEARCH_ROUTE") -> "경로 탐색 서버에 문제가 있어요."
                else -> "내비게이션에서 오류가 났어요."
            }
            say("길안내를 시작하지 못했어요. $friendly 「지도에서 보기」로 주소를 검색할 수 있어요. ($t)")
        }

        override fun onRouteStarted(info: RouteStartInfo) {
            routeEventSeen = true
            Log.i(tag, "[경로 시작 이벤트]")
            say("경로 안내가 시작됐어요.")
        }

        override fun onRoutingCompleted() {
            routeEventSeen = true
            Log.i(tag, "[경로 탐색 완료 이벤트]")
        }

        override fun onDestinationChanged() {
            Log.i(tag, "[목적지 변경]")
        }
    }

    init {
        try {
            Log.i(tag, "[NaviHelper 초기화 요청]")
            val helper = NaviHelper(context.applicationContext)
            navi = helper
            helper.addListener(listener)
            helper.initialize(
                {
                    if (!released) {
                        connected = true
                        Log.i(tag, "[NaviHelper 연결 성공]")
                        pending?.let { p -> pending = null; start(p) }
                    }
                },
                { e ->
                    if (!released) {
                        connected = false
                        failed = true
                        val why = "NaviHelper 연결 실패(${e?.javaClass?.simpleName}: ${e?.message?.take(100)})"
                        Log.w(tag, "[NaviHelper 연결 실패] $why")
                        val p = pending
                        pending = null
                        if (p != null) fallbackMapSearch(p, why)
                        else say("내비게이션 연결에 실패했어요. $why")
                    }
                }
            )
        } catch (e: Throwable) {
            Log.w(tag, "[NaviHelper 초기화 실패] ${e.javaClass.simpleName}: ${e.message?.take(100)}")
            navi = null
            failed = true
            say("내비게이션(NaviHelper)을 초기화하지 못했어요: ${e.javaClass.simpleName}")
        }
    }

    /** 사용자가 직접 고른 매장만 전달할 것. 좌표는 매장 데이터의 값만 사용. */
    fun start(store: DealerStore) {
        if (!store.canNavigate) {
            say("확인된 좌표가 없는 매장이라 길안내를 시작할 수 없어요.")
            return
        }
        val lat = store.lat
        val lon = store.lon
        if (lat == null || lon == null) return
        val helper = navi
        if (helper == null || failed) {
            fallbackMapSearch(store, "NaviHelper를 쓸 수 없어요(초기화 또는 연결 실패)")
            return
        }
        if (!connected) {
            pending = store
            say("내비게이션에 연결하는 중이에요… 연결되면 이 매장으로 목적지를 보내요.")
            return
        }
        try {
            Log.i(tag, "[requestRoute 호출] 매장=${store.name} 좌표출처=${store.coordSource ?: "없음"} 상태=${store.coordStatus ?: "없음"}")
            routeEventSeen = false
            helper.requestRoute(
                RouteInfo(
                    latitude = lat,
                    longitude = lon,
                    displayLongitude = null,
                    displayLatitude = null,
                    poiName = store.name,
                    poiId = "dealer-${store.brandCode}-${store.name.hashCode()}",
                    poiSubId = "0",
                    address = store.address
                )
            )
            Log.i(tag, "[경로 요청 결과] 호출 완료(예외 없음). 경로 시작 여부는 이벤트로 확인")
            say("내비게이션에 목적지를 요청했어요. 경로 안내가 시작되면 알려 드려요.")
            // Pleos 내비게이션 화면을 앞으로 가져오기(문서상 NaviHelper.requestNavigationScreen, 권한 NAVI_CUSTOM_MAP).
            // 화면이 실제로 앞으로 왔는지는 콜백이 없어 확인할 수 없다 → 호출 결과만 로그로 남긴다.
            try {
                Log.i(tag, "[requestNavigationScreen 호출] Foreground")
                helper.requestNavigationScreen(NavigationScreenRequest.Foreground)
                Log.i(tag, "[requestNavigationScreen 결과] 호출 완료(예외 없음). 화면 표시 여부는 확인 불가")
            } catch (e: Exception) {
                Log.w(tag, "[requestNavigationScreen 결과] 예외 ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            }
            // 일정 시간 안에 시작·완료·오류 이벤트가 없으면 사실대로 안내(성공으로 단정하지 않음)
            handler.postDelayed({
                if (!released && !routeEventSeen) {
                    Log.w(tag, "[경로 이벤트 없음] 요청 후 8초 동안 시작/완료/오류 이벤트가 오지 않음")
                    say("내비게이션에 요청은 보냈지만 시작 신호가 오지 않았어요. 내비게이션 화면을 확인해 보시고, 안 되면 「지도에서 보기」를 눌러 주세요.")
                }
            }, 8000)
        } catch (e: Exception) {
            val perm = if (e is SecurityException) " — 권한 거부(매니페스트의 NAVI_ROUTE_SEARCH 선언·허용 확인 필요)" else ""
            val why = "requestRoute 예외 ${e.javaClass.simpleName}: ${e.message?.take(100)}$perm"
            Log.w(tag, "[경로 요청 결과] $why")
            fallbackMapSearch(store, why)
        }
    }

    /** NaviHelper를 쓸 수 없거나 실패하면 주소 기반 지도 검색으로 복귀. 앱이 없으면 주소 복사·안내. */
    private fun fallbackMapSearch(store: DealerStore, reason: String) {
        val out = MapIntents.routeByAddress(appContext, store)
        say("내비게이션(NaviHelper) 길안내 실패: $reason\n대체 시도: " + out.message)
    }

    /** 이 인스턴스가 직접 만든 NaviHelper만 정리한다(NaviLauncher 등 다른 인스턴스와 공유하지 않음). 여러 번 호출해도 안전. */
    /** 매장 선택이 바뀌면 대기 중인 길안내 요청을 버린다(선택하지 않은 매장으로 가지 않도록). */
    fun cancelPending() { pending = null }

    fun release() {
        if (released) return
        handler.removeCallbacksAndMessages(null)
        pending = null
        released = true
        try {
            navi?.removeListener(listener)
            navi?.release()
        } catch (e: Exception) {
            Log.w(tag, "[release 실패] ${e.javaClass.simpleName}")
        }
        navi = null
        connected = false
    }
}

/**
 * 설치된 지도·내비게이션 앱 점검과 주소 기반 Intent. (매니페스트 <queries>에 geo/google.navigation/https/APP_MAPS 선언 필요)
 * Intent 전달 성공 = 앱이 요청을 받았다는 뜻일 뿐, 실제 화면 표시·길안내 시작을 보장하지 않는다.
 */
object MapIntents {
    private const val TAG = "AutoResolveMapIntents"

    sealed class Outcome(val message: String) {
        /** 요청을 받은 앱으로 전달했다. via는 "google.navigation" 또는 "geo". */
        class Sent(val via: String, message: String) : Outcome(message)
        class Unavailable(message: String) : Outcome(message)
    }

    fun handlersOf(context: Context, intent: Intent): List<String> = try {
        context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.distinct()
    } catch (e: Exception) {
        Log.w(TAG, "[핸들러 조회 실패] ${e.javaClass.simpleName}")
        emptyList()
    }

    /** 설치된 처리 앱을 로그로 남기고 요약 문자열을 돌려준다. */
    fun probeSummary(context: Context): String {
        val nav = handlersOf(context, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=test")))
        val geo = handlersOf(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=test")))
        val maps = handlersOf(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS))
        val web = handlersOf(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.hyundai.com")))
        val line = "google.navigation=$nav geo=$geo APP_MAPS=$maps https=$web"
        Log.i(TAG, "[지도 앱 점검] $line")
        return line
    }

    private fun start(context: Context, uri: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: android.content.ActivityNotFoundException) {
        Log.w(TAG, "[Intent 실패] ActivityNotFoundException scheme=${uri.substringBefore(':')}")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "[Intent 실패] SecurityException scheme=${uri.substringBefore(':')}: ${e.message?.take(100)}")
        false
    } catch (e: Exception) {
        Log.w(TAG, "[Intent 실패] ${e.javaClass.simpleName} scheme=${uri.substringBefore(':')}")
        false
    }

    private fun noApp(context: Context, store: DealerStore, what: String): Outcome.Unavailable {
        val copied = TestDriveLinks.copyAddress(context, store)
        return Outcome.Unavailable(
            "이 차량에는 $what 열어 줄 지도·내비게이션 앱이 없거나 실행할 수 없어요. " +
                    (if (copied) "주소가 복사되었어요. " else "") +
                    "휴대폰 지도 앱에 주소를 붙여 넣거나 공식 판매점 정보를 확인해 주세요."
        )
    }

    /** 좌표 없이 매장 주소로 길안내를 요청(google.navigation:q=주소) → 안 되면 지도 검색(geo)으로. */
    fun routeByAddress(context: Context, store: DealerStore): Outcome {
        probeSummary(context)
        val q = Uri.encode(store.address)
        if (start(context, "google.navigation:q=$q")) {
            Log.i(TAG, "[주소 길안내 요청 전달] google.navigation (시작 여부는 화면에서 확인 필요)")
            return Outcome.Sent(
                "google.navigation",
                "지도 앱에 매장 주소로 길안내를 요청했어요. 길안내가 실제로 시작됐는지와 도착지가 맞는지는 지도 화면에서 확인해 주세요."
            )
        }
        return searchOnMap(context, store, "주소 길안내(google.navigation)는 처리할 앱이 없어요. ")
    }

    /** 지도 검색(geo:0,0?q=매장명 주소). */
    fun searchOnMap(context: Context, store: DealerStore, prefix: String = ""): Outcome {
        probeSummary(context)
        val q = Uri.encode("${store.name} ${store.address}")
        if (start(context, "geo:0,0?q=$q")) {
            Log.i(TAG, "[지도 검색 요청 전달] geo (화면 표시 여부는 확인 필요)")
            return Outcome.Sent(
                "geo",
                prefix + "지도 앱에 주소 검색을 요청했어요. 길안내가 시작된 것은 아니에요. 지도에 위치가 표시됐는지 확인해 주세요."
            )
        }
        return noApp(context, store, "주소를")
    }
}
