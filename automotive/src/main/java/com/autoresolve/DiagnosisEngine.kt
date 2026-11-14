
package com.autoresolve

data class DiagnosisOption(
    val label: String,
    val nextId: String
)

data class DiagnosisQuestion(
    val id: String,
    val text: String,
    val options: List<DiagnosisOption>
)

data class DiagnosisResult(
    val id: String,
    val title: String,
    val explanation: String,
    val nextAction: String,
    val needsProfessionalHelp: Boolean
)

data class DiagnosisFlow(
    val id: String,
    val title: String,
    val startId: String,
    val questions: Map<String, DiagnosisQuestion>,
    val results: Map<String, DiagnosisResult>
)

object DiagnosisEngine {

    fun getFlow(issue: String): DiagnosisFlow? {
        return when (issue) {
            "charging" -> chargingFlow()
            "starting" -> startingFlow()
            "ac" -> airConditioningFlow()
            "tire" -> tireFlow()
            else -> null
        }
    }

    private fun chargingFlow(): DiagnosisFlow {
        val questions = listOf(
            DiagnosisQuestion(
                "charger_error",
                "충전기 화면에 오류 메시지가 표시되나요?",
                listOf(
                    DiagnosisOption("네, 오류가 있어요", "other_charger"),
                    DiagnosisOption("아니요", "connector"),
                    DiagnosisOption("잘 모르겠어요", "unknown")
                )
            ),
            DiagnosisQuestion(
                "other_charger",
                "다른 충전기에서는 충전이 되나요?",
                listOf(
                    DiagnosisOption("네, 다른 곳에서는 돼요", "external"),
                    DiagnosisOption("아니요, 다른 곳도 안 돼요", "vehicle"),
                    DiagnosisOption("아직 확인하지 않았어요", "unknown")
                )
            ),
            DiagnosisQuestion(
                "connector",
                "차량 화면에서 충전 커넥터가 " +
                        "연결된 것으로 표시되나요?",
                listOf(
                    DiagnosisOption("네, 연결됐어요", "schedule"),
                    DiagnosisOption("아니요", "connection"),
                    DiagnosisOption("잘 모르겠어요", "unknown")
                )
            ),
            DiagnosisQuestion(
                "schedule",
                "예약 충전 기능이 설정되어 있나요?",
                listOf(
                    DiagnosisOption("네", "scheduled"),
                    DiagnosisOption("아니요", "vehicle"),
                    DiagnosisOption("잘 모르겠어요", "unknown")
                )
            )
        ).associateBy { it.id }

        val results = listOf(
            DiagnosisResult(
                "external",
                "외부 충전기 문제 가능성",
                "다른 충전기에서는 정상적으로 충전되므로 " +
                        "현재 충전기나 인증·연결 환경을 " +
                        "우선 확인할 필요가 있어요.",
                "충전기의 오류 메시지를 확인하고 " +
                        "해당 충전기 운영사에 문의해 보세요.",
                false
            ),
            DiagnosisResult(
                "vehicle",
                "차량 충전 시스템 점검 권장",
                "현재 답변만으로 고장 원인을 " +
                        "확정할 수 없어요. 차량 충전 설정 " +
                        "또는 충전 시스템 문제일 수 있어요.",
                "차량의 충전 설정과 경고 메시지를 " +
                        "확인하고 문제가 지속되면 " +
                        "공식 서비스센터에 문의하세요.",
                true
            ),
            DiagnosisResult(
                "connection",
                "충전 연결 상태 확인 필요",
                "차량이 충전 커넥터를 인식하지 " +
                        "못하는 것으로 보이지만 " +
                        "정확한 원인은 확인되지 않았어요.",
                "충전이 중단되고 안전한 상태에서 " +
                        "사용설명서의 연결 점검 절차를 " +
                        "확인하세요. 손상된 장비는 만지지 마세요.",
                false
            ),
            DiagnosisResult(
                "scheduled",
                "예약 충전 설정 확인",
                "예약 충전 설정으로 인해 " +
                        "충전 시작이 지연되고 있을 수 있어요.",
                "차량 사용설명서에 따라 " +
                        "예약 충전 시간과 설정을 확인하세요.",
                false
            ),
            DiagnosisResult(
                "unknown",
                "추가 확인이 필요해요",
                "현재 답변만으로는 차량과 충전기 " +
                        "중 어디에 문제가 있는지 알 수 없어요.",
                "차량 및 충전기의 오류 메시지를 " +
                        "확인하고 필요에 따라 충전기 " +
                        "운영사 또는 차량 고객센터에 문의하세요.",
                false
            )
        ).associateBy { it.id }

        return DiagnosisFlow(
            "charging",
            "전기차 충전 문제 자가 확인",
            "charger_error",
            questions,
            results
        )
    }

    private fun startingFlow(): DiagnosisFlow {
        val questions = listOf(
            DiagnosisQuestion(
                "warning",
                "계기판에 경고 메시지가 표시되나요?",
                listOf(
                    DiagnosisOption("네", "warning_result"),
                    DiagnosisOption("아니요", "smartkey"),
                    DiagnosisOption("잘 모르겠어요", "unknown")
                )
            ),
            DiagnosisQuestion(
                "smartkey",
                "차량에서 스마트키를 인식하나요?",
                listOf(
                    DiagnosisOption("네", "inspection"),
                    DiagnosisOption("아니요", "key"),
                    DiagnosisOption("잘 모르겠어요", "unknown")
                )
            )
        ).associateBy { it.id }

        val results = listOf(
            DiagnosisResult(
                "warning_result",
                "계기판 경고 확인 필요",
                "경고 메시지가 표시되고 있지만 " +
                        "현재 정보만으로 원인을 확정할 수 없어요.",
                "경고 내용을 기록하고 사용설명서를 " +
                        "확인하세요. 심각한 경고가 있다면 " +
                        "안전한 상태를 유지하고 전문 지원을 받으세요.",
                true
            ),
            DiagnosisResult(
                "inspection",
                "시동 시스템 점검 권장",
                "스마트키가 인식되더라도 " +
                        "시동 문제의 원인은 다양할 수 있어요.",
                "차량 사용설명서의 시동 절차를 " +
                        "확인하고 시동이 걸리지 않으면 " +
                        "긴급출동 또는 정비 서비스를 이용하세요.",
                true
            ),
            DiagnosisResult(
                "key",
                "스마트키 인식 문제 가능성",
                "차량이 스마트키를 인식하지 " +
                        "못하고 있을 가능성이 있어요.",
                "스마트키 배터리와 차량별 비상 시동 " +
                        "절차를 사용설명서에서 확인하세요.",
                false
            ),
            DiagnosisResult(
                "unknown",
                "시동 문제 원인 확인 불가",
                "현재 답변만으로 원인을 " +
                        "판단하기 어려워요.",
                "계기판 메시지를 확인하고 " +
                        "필요하면 긴급출동을 요청하세요.",
                true
            )
        ).associateBy { it.id }

        return DiagnosisFlow(
            "starting",
            "시동 문제 자가 확인",
            "warning",
            questions,
            results
        )
    }


    private fun tireFlow(): DiagnosisFlow {
        val questions = listOf(
            DiagnosisQuestion(
                "visible_damage",
                "타이어가 찢어졌거나 심하게 주저앉아 있나요?",
                listOf(
                    DiagnosisOption("네, 손상이 보여요", "danger"),
                    DiagnosisOption("아니요", "pressure"),
                    DiagnosisOption("잘 모르겠어요", "uncertain")
                )
            ),
            DiagnosisQuestion(
                "pressure",
                "계기판에서 타이어 공기압이 낮다고 표시되나요?",
                listOf(
                    DiagnosisOption("네", "low_pressure"),
                    DiagnosisOption("아니요", "warning_check"),
                    DiagnosisOption("확인할 수 없어요", "uncertain")
                )
            ),
            DiagnosisQuestion(
                "warning_check",
                "경고등이 계속 켜져 있나요?",
                listOf(
                    DiagnosisOption("네", "inspection"),
                    DiagnosisOption("아니요", "monitor"),
                    DiagnosisOption("잘 모르겠어요", "uncertain")
                )
            )
        ).associateBy { it.id }

        val results = listOf(
            DiagnosisResult(
                "danger",
                "타이어 손상 가능성",
                "타이어에 눈에 띄는 손상이 있다면 " +
                        "계속 운행하는 것이 위험할 수 있어요.",
                "안전한 곳에 정차하고 운행을 피하세요. " +
                        "긴급출동 또는 전문 정비 지원을 요청하세요.",
                true
            ),
            DiagnosisResult(
                "low_pressure",
                "타이어 공기압 점검 필요",
                "공기압이 낮을 가능성이 있어요. " +
                        "펑크 여부는 확인되지 않았어요.",
                "안전한 곳에서 타이어 상태를 확인하고 " +
                        "차량 사용설명서에 표시된 권장 공기압을 " +
                        "기준으로 점검받으세요.",
                true
            ),
            DiagnosisResult(
                "inspection",
                "타이어 경고 시스템 점검 권장",
                "경고가 지속되고 있지만 현재 정보만으로 " +
                        "공기압 또는 센서 문제인지 구분할 수 없어요.",
                "타이어와 공기압 모니터링 시스템을 " +
                        "전문가에게 점검받으세요.",
                true
            ),
            DiagnosisResult(
                "monitor",
                "경고 상태 재확인",
                "현재 경고가 보이지 않더라도 " +
                        "문제가 완전히 해결됐다고 단정할 수 없어요.",
                "안전한 장소에서 타이어 상태를 확인하고 " +
                        "경고가 다시 표시되면 점검받으세요.",
                false
            ),
            DiagnosisResult(
                "uncertain",
                "안전 확인이 필요해요",
                "타이어 손상 여부가 확인되지 않았어요.",
                "안전하게 정차한 상태에서 타이어를 확인하고 " +
                        "판단이 어려우면 긴급출동이나 " +
                        "정비 전문가에게 문의하세요.",
                true
            )
        ).associateBy { it.id }

        return DiagnosisFlow(
            "tire",
            "타이어 경고 자가 확인",
            "visible_damage",
            questions,
            results
        )
    }

    private fun airConditioningFlow(): DiagnosisFlow {
        val questions = listOf(
            DiagnosisQuestion(
                "airflow",
                "송풍구에서 바람이 나오나요?",
                listOf(
                    DiagnosisOption("네", "temperature"),
                    DiagnosisOption("아니요", "no_airflow"),
                    DiagnosisOption("잘 모르겠어요", "unknown")
                )
            ),
            DiagnosisQuestion(
                "temperature",
                "냉방 기능이 켜져 있고 " +
                        "온도가 낮게 설정되어 있나요?",
                listOf(
                    DiagnosisOption("네", "inspection"),
                    DiagnosisOption("아니요", "settings"),
                    DiagnosisOption("잘 모르겠어요", "unknown")
                )
            )
        ).associateBy { it.id }

        val results = listOf(
            DiagnosisResult(
                "no_airflow",
                "송풍 상태 점검 필요",
                "송풍구에서 바람이 나오지 않지만 " +
                        "정확한 원인은 확인되지 않았어요.",
                "팬 속도와 공조 설정을 확인하세요. " +
                        "문제가 지속되면 전문 점검을 받으세요.",
                true
            ),
            DiagnosisResult(
                "inspection",
                "냉방 성능 점검 권장",
                "바람은 나오지만 냉방이 충분하지 " +
                        "않을 가능성이 있어요.",
                "공조 설정을 다시 확인하고 " +
                        "문제가 지속되면 서비스센터에 문의하세요.",
                true
            ),
            DiagnosisResult(
                "settings",
                "공조 설정 확인",
                "현재 공조 설정이 냉방이 " +
                        "되지 않는 이유일 수 있어요.",
                "사용설명서를 참고해 냉방 모드와 " +
                        "온도를 설정한 뒤 다시 확인하세요.",
                false
            ),
            DiagnosisResult(
                "unknown",
                "공조 상태 추가 확인 필요",
                "현재 답변만으로 원인을 " +
                        "판단하기 어려워요.",
                "차량의 공조 설정과 송풍 상태를 " +
                        "확인한 뒤 문제가 지속되면 " +
                        "전문가에게 문의하세요.",
                false
            )
        ).associateBy { it.id }

        return DiagnosisFlow(
            "ac",
            "에어컨 문제 자가 확인",
            "airflow",
            questions,
            results
        )
    }
}
