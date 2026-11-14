
package com.autoresolve

data class VehicleModel(
    val name: String,
    val powertrains: List<String>
)

object VehicleCatalog {
    const val EV = "Electric (EV)"
    const val GAS = "Gasoline"
    const val DIESEL = "Diesel"
    const val HEV = "Hybrid (HEV)"
    const val PHEV = "Plug-in Hybrid (PHEV)"
    const val FCEV = "Hydrogen (FCEV)"

    val powertrainLabels = linkedMapOf(
        EV to "전기차",
        GAS to "가솔린",
        DIESEL to "디젤",
        HEV to "하이브리드",
        PHEV to "플러그인 하이브리드",
        FCEV to "수소전기차"
    )

    private fun car(
        name: String,
        vararg types: String
    ) = VehicleModel(name, types.toList())

    val brands = linkedMapOf(
        "Hyundai" to "현대",
        "Kia" to "기아",
        "Genesis" to "제네시스"
    )

    val models = linkedMapOf(
        "Hyundai" to listOf(
            car("캐스퍼", GAS),
            car("캐스퍼 일렉트릭", EV),
            car("베뉴", GAS),
            car("코나", GAS, HEV),
            car("코나 일렉트릭", EV),
            car("투싼", GAS, DIESEL, HEV, PHEV),
            car("싼타페", GAS, DIESEL, HEV),
            car("팰리세이드", GAS, DIESEL, HEV),
            car("아이오닉 5", EV),
            car("아이오닉 5 N", EV),
            car("아이오닉 6", EV),
            car("아이오닉 6 N", EV),
            car("아이오닉 9", EV),
            car("넥쏘", FCEV),
            car("아반떼", GAS, HEV),
            car("아반떼 N", GAS),
            car("쏘나타", GAS, HEV),
            car("그랜저", GAS, HEV),
            car("스타리아", DIESEL, GAS, HEV),
            car("스타리아 일렉트릭", EV),
            car("포터 II", DIESEL, GAS),
            car("포터 II 일렉트릭", EV),
            car("ST1", EV),
            car("아이오닉 일렉트릭", EV),
            car("아이오닉 하이브리드", HEV),
            car("아이오닉 플러그인", PHEV),
            car("벨로스터", GAS),
            car("벨로스터 N", GAS),
            car("i30", GAS, DIESEL),
            car("액센트", GAS, DIESEL),
            car("에쿠스", GAS),
            car("제네시스 세단", GAS)
        ),
        "Kia" to listOf(
            car("모닝", GAS),
            car("레이", GAS),
            car("레이 EV", EV),
            car("K3", GAS),
            car("K5", GAS, HEV),
            car("K7", GAS, DIESEL, HEV),
            car("K8", GAS, HEV),
            car("K9", GAS),
            car("셀토스", GAS, DIESEL, HEV),
            car("니로", HEV, PHEV),
            car("니로 EV", EV),
            car("니로 플러스", EV),
            car("스포티지", GAS, DIESEL, HEV, PHEV),
            car("쏘렌토", GAS, DIESEL, HEV, PHEV),
            car("카니발", GAS, DIESEL, HEV),
            car("카니발 하이리무진", GAS, DIESEL, HEV),
            car("타스만", DIESEL, GAS),
            car("EV3", EV),
            car("EV3 GT", EV),
            car("EV4", EV),
            car("EV4 GT", EV),
            car("EV5", EV),
            car("EV5 GT", EV),
            car("EV6", EV),
            car("EV6 GT", EV),
            car("EV9", EV),
            car("EV9 GT", EV),
            car("PV5", EV),
            car("봉고 III", DIESEL, GAS),
            car("봉고 III EV", EV),
            car("모하비", DIESEL),
            car("스팅어", GAS, DIESEL),
            car("쏘울", GAS, DIESEL),
            car("쏘울 EV", EV),
            car("스토닉", GAS, DIESEL),
            car("프라이드", GAS, DIESEL),
            car("올 뉴 카렌스", GAS, DIESEL)
        ),
        "Genesis" to listOf(
            car("G70", GAS, DIESEL),
            car("G70 슈팅 브레이크", GAS, DIESEL),
            car("G80", GAS, DIESEL),
            car("G80 전동화 모델", EV),
            car("G90", GAS),
            car("G90 롱휠베이스", GAS),
            car("G90 블랙", GAS),
            car("GV60", EV),
            car("GV60 마그마", EV),
            car("GV70", GAS, DIESEL),
            car("GV70 전동화 모델", EV),
            car("GV80", GAS, DIESEL, HEV),
            car("GV80 쿠페", GAS),
            car("GV90", EV),
            car("EQ900", GAS)
        )
    )

    fun getModels(brand: String): List<VehicleModel> {
        return models[brand].orEmpty()
    }

    fun getModel(
        brand: String,
        name: String
    ): VehicleModel? {
        return getModels(brand).firstOrNull {
            it.name == name
        }
    }

    fun getPowertrainLabel(value: String): String {
        return powertrainLabels[value] ?: value
    }
}
