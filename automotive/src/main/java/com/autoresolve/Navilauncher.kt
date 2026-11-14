package com.autoresolve

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.data.RouteInfo

/**
 * 서비스센터까지 길안내를 시작합니다. Pleos NaviHelper로 목적지를 설정하고, 연결에 실패하면
 * 일반 지도 앱(geo:)으로 넘깁니다.
 *
 * 주의: 검증된 센터 좌표(verifiedPlaces)가 있는 센터에만 길안내를 합니다. 지금은 없습니다.
 * RouteInfo의 import 경로(ai.pleos.playground.navi.data)는 문서의 API Reference URL로 추정한 값이라,
 * 빨간 줄이 뜨면 Alt+Enter로 자동 import 하세요.
 */
object NaviLauncher {

    private const val TAG = "AutoResolveNavi"

    data class Place(val lat: Double, val lon: Double, val address: String)

    /**
     * 길안내에 쓸 수 있는 "검증된" 센터 좌표입니다. 출처·정확성·이용 조건이 확인된 좌표만 넣습니다.
     * 지금은 실제 서비스센터 좌표가 없어서 비어 있습니다. 시연 센터에는 길안내를 하지 않습니다.
     * 검증된 좌표가 생기면 여기에 넣으면 접수 완료 화면의 길안내 버튼이 다시 나타납니다.
     * (임의 좌표를 넣지 마세요. 이 목록에 없는 센터는 NaviHelper와 지도 앱(geo:) 모두로 보내지 않습니다.)
     */
    private val verifiedPlaces: Map<String, Place> = emptyMap()

    const val NO_NAVIGATION_NOTICE = "시연용 정비소는 현재 길 안내를 지원하지 않아요."

    fun hasPlace(centerId: String?): Boolean =
        centerId != null && verifiedPlaces.containsKey(centerId)

    fun hasAnyVerifiedPlace(): Boolean = verifiedPlaces.isNotEmpty()

    private var helper: NaviHelper? = null
    @Volatile private var connected = false
    private var connecting = false

    fun warmUp(context: Context) {
        if (helper != null || connecting) return

        connecting = true

        try {
            val navi = NaviHelper(context.applicationContext)
            helper = navi

            navi.initialize(
                onConnected = {
                    connected = true
                    connecting = false
                    Log.d(TAG, "NaviHelper connected")
                },
                onFailed = { error ->
                    connected = false
                    connecting = false
                    helper = null
                    Log.e(TAG, "NaviHelper connection failed", error)
                }
            )
        } catch (e: Exception) {
            connecting = false
            helper = null
            Log.e(TAG, "NaviHelper init failed", e)
        }
    }

    /** 길안내를 요청합니다. 결과 문구를 [onResult]로 돌려줍니다. */
    fun navigate(
        context: Context,
        centerId: String,
        name: String,
        onResult: (String) -> Unit
    ) {
        val place = verifiedPlaces[centerId]

        if (place == null) {
            // 검증되지 않은 센터는 NaviHelper로도, 지도 앱(geo:)으로도 보내지 않습니다.
            Log.i(TAG, "navigation blocked: no verified place for centerId=$centerId")
            onResult(NO_NAVIGATION_NOTICE)
            return
        }

        val navi = helper

        if (navi != null && connected) {
            try {
                navi.requestRoute(
                    RouteInfo(
                        latitude = place.lat,
                        longitude = place.lon,
                        displayLongitude = null,
                        displayLatitude = null,
                        poiName = name,
                        poiId = centerId,
                        poiSubId = "0",
                        address = place.address
                    )
                )

                onResult("내비게이션에 목적지를 보냈어요. 경로 탐색은 내비게이션 앱에서 진행돼요.")
                return
            } catch (e: Exception) {
                Log.e(TAG, "requestRoute failed", e)
            }
        } else {
            warmUp(context)
        }

        // 내비게이션 연결이 없으면 일반 지도 앱으로 엽니다.
        try {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("geo:${place.lat},${place.lon}?q=${Uri.encode(name)}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            onResult("내비게이션 연결이 없어 지도 앱으로 열었어요.")
        } catch (e: Exception) {
            Log.e(TAG, "geo intent failed", e)
            onResult("길안내를 시작하지 못했어요. 내비게이션 연결을 확인해 주세요.")
        }
    }
}
