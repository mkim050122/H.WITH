package com.autoresolve

/**
 * 추천 후보(NextCarRecommender.candidates)의 모델 이름 → 차량 사진 리소스.
 * 사진 파일은 res/drawable-nodpi/car_<브랜드>_<모델>.png 이고, 직접 R 참조라서 파일 이름이 틀리면 빌드 때 바로 오류가 난다.
 * 목록에 없는 모델이면 null(화면은 차량 아이콘으로 대신 표시).
 * 사진의 사용 허가 조건은 이 코드가 확인하지 않는다(사용자 제공 파일).
 */
object CarImages {
    fun drawableFor(modelName: String): Int? = when (modelName) {
        "캐스퍼" -> R.drawable.car_hyundai_casper
        "아반떼" -> R.drawable.car_hyundai_avante
        "쏘나타" -> R.drawable.car_hyundai_sonata
        "코나" -> R.drawable.car_hyundai_kona
        "투싼" -> R.drawable.car_hyundai_tucson
        "싼타페" -> R.drawable.car_hyundai_santafe
        "그랜저" -> R.drawable.car_hyundai_grandeur
        "아이오닉 5" -> R.drawable.car_hyundai_ioniq5
        "아이오닉 6" -> R.drawable.car_hyundai_ioniq6
        "팰리세이드" -> R.drawable.car_hyundai_palisade
        "스타리아" -> R.drawable.car_hyundai_staria
        "레이" -> R.drawable.car_kia_ray
        "K5" -> R.drawable.car_kia_k5
        "셀토스" -> R.drawable.car_kia_seltos
        "스포티지" -> R.drawable.car_kia_sportage
        "쏘렌토" -> R.drawable.car_kia_sorento
        "카니발" -> R.drawable.car_kia_carnival
        "EV3" -> R.drawable.car_kia_ev3
        "EV6" -> R.drawable.car_kia_ev6
        "G70" -> R.drawable.car_genesis_g70
        "G80" -> R.drawable.car_genesis_g80
        "GV70" -> R.drawable.car_genesis_gv70
        else -> null
    }
}
