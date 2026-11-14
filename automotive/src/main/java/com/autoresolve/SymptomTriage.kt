package com.autoresolve

/**
 * 증상 문장을 보고 "얼마나 급한지"와 "다음에 무엇을 하면 되는지"만 가르는 공용 분류기입니다.
 * 화면·Context에 의존하지 않아 Gleo 호출과 음성 상담이 같은 기준을 쓸 수 있습니다.
 *
 * 원칙
 * - 고장 원인을 확정하지 않습니다. 원인이 아니라 "점검 권장"과 "안전 안내"만 냅니다.
 * - 정보가 부족하면 원인을 짐작하지 않고 더 물어야 한다고만 알립니다.
 * - 위험해 보이는 증상은 항상 안전 안내가 먼저입니다.
 * - 질문 트리가 있는 4개 증상(충전·시동·에어컨·타이어)은 기존 MainActivity.analyzeSymptom과 같은 규칙으로 고릅니다.
 */
enum class Urgency(val wire: String) {
    /** 안전한 곳에 정차하고 운행을 멈추도록 먼저 안내. */
    STOP_NOW("stop_now"),

    /** 자가 확인 절차가 없어서, 가까운 시일 안에 전문 점검을 권장. */
    INSPECT_SOON("inspect_soon"),

    /** 앱에 질문형 자가 확인(DiagnosisEngine)이 있는 증상. */
    SELF_CHECK("self_check_available"),

    /** 분류하기에는 정보가 부족해서 더 물어봐야 함. */
    NEED_MORE_INFO("need_more_info")
}

/**
 * 위험도 3단계. Urgency(분류)와 별개로 "화면에 어떤 안내를 먼저 보여 줄지"를 정합니다.
 * 부위(브레이크 등)만으로는 정해지지 않고, 운전자가 말한 증상으로만 정해집니다.
 */
enum class Severity {
    /** 점검 권장. 일반 소음처럼 바로 위험하다고 볼 근거가 없음. */
    NOTICE,

    /** 운행 중단 권장. 제동력 저하·조향 이상 등. 안전한 곳에 정차하고 점검. */
    STOP,

    /** 긴급 안전 안내. 제동 불능·화재·연기·급발진·조향 불능 등. 차량 제어와 긴급 도움 요청이 먼저. */
    EMERGENCY
}

data class TriageResult(
    val urgency: Urgency,
    val part: PartCategory,
    /** DiagnosisEngine.getFlow에 넘길 값("charging","starting","ac","tire"). 없으면 null. */
    val selfCheckIssue: String?,
    val headline: String,
    val advice: String,
    /** NEED_MORE_INFO일 때 무엇이 부족한지. Gleo 콜백의 missingField로 쓴다. */
    val missingField: String?,
    /** 판단에 쓰인 단어(로그·검증용). 개인정보가 아닌 키워드만 담는다. */
    val matched: List<String>,
    /** 위험도 3단계. STOP_NOW가 아니면 항상 NOTICE. */
    val severity: Severity = Severity.NOTICE,
    /** 화면 배너용 한두 문장 요약. 자세한 안내는 [advice]. */
    val summary: String = "",
    /** 안전 안내를 순서대로 나눈 목록. 비어 있으면 [advice]만 씁니다. [advice]는 Gleo 콜백과 같은 값이라 바꾸지 않습니다. */
    val steps: List<String> = emptyList()
) {
    val needsMoreInfo: Boolean get() = urgency == Urgency.NEED_MORE_INFO
    val isSafetyFirst: Boolean get() = urgency == Urgency.STOP_NOW
    val isEmergency: Boolean get() = severity == Severity.EMERGENCY
}

object SymptomTriage {

    const val ISSUE_CHARGING = "charging"
    const val ISSUE_STARTING = "starting"
    const val ISSUE_AC = "ac"
    const val ISSUE_TIRE = "tire"

    fun compact(text: String): String =
        text.lowercase().replace("\\s+".toRegex(), "")

    // 기존 analyzeSymptom의 위험어 11개 + 제동·조향·화재·과열 쪽을 보강한 목록. 보수적으로(위험 쪽으로) 잡습니다.
    private val stopWords = listOf(
        "제동불량", "조향불량",
        "화재", "연기가나", "불꽃", "타는냄새", "급발진",
        "제동력이떨어", "제동력저하", "브레이크가약", "브레이크가푹",
        "제동이불량", "브레이크불량", "브레이크가불량", "제동이약", "제동이이상", "브레이크가이상",
        "페달이푹", "페달이바닥", "핸들이잠", "핸들이안돌",
        // 조향이 안 되거나 차량 제어가 어렵다고 말한 경우 (긴급)
        "스티어링이잠", "핸들이안움직", "핸들을못돌",
        "핸들을돌릴수없", "차량제어", "제어가어려",
        "연기", "불이붙", "엔진과열", "과열", "냉각수경고", "오일압력"
    )

    // 긴급(EMERGENCY) 판단용. 모두 stopWords가 STOP_NOW로 잡은 문장 안에서만 쓰입니다.
    // "브레이크 안쪽 소리"처럼 우연히 겹치는 말을 피하려고 "안 들어/안 먹어"처럼 구체적인 형태만 둡니다.
    private val fireWords = listOf("화재", "연기", "불꽃", "타는냄새", "불이붙")
    private val runawayWords = listOf("급발진")
    private val brakeLossWords = listOf(
        "브레이크안들", "브레이크안먹", "브레이크안잡", "브레이크가없",
        "제동안되", "제동불능", "페달이바닥"
    )
    private val steerLossWords = listOf(
        "핸들이잠", "핸들이안돌", "핸들이안움직", "핸들을못돌", "핸들을돌릴수없",
        "스티어링이잠", "차량제어", "제어가어려"
    )

    // 실제 제동 불능·조향 불능을 "주어 + 불능을 뜻하는 서술"의 짝으로 명시적으로 찾습니다. (공백을 지운 문장 기준)
    // 단순히 "안"이 들어 있다고 해서가 아니라, 브레이크/제동/핸들/조향 다음에 안 들어·안 먹어·안 돼·못 돌려처럼 불능을 뜻하는 말이 와야 합니다.
    private const val BRAKE_SUBJECT = "(?:브레이크|제동)(?:이|가|은|는|도|를|을)?(?:전혀|하나도|아예|거의)?"
    private const val STEER_SUBJECT = "(?:핸들|조향|스티어링)(?:이|가|은|는|도|을|를)?(?:전혀|하나도|아예|거의)?"
    private val brakeLossPattern = Regex(
        BRAKE_SUBJECT + "(?:안(?:들|먹|잡|돼|되|걸|듣|멈|서|써|작동|밟히|밟혀|눌리|눌려)|" +
                "(?:듣|먹|걸리|잡히|작동하|멈추|서)지(?:를)?않|말을안|불능|못(?:멈|잡|세우))"
    )
    private val steerLossPattern = Regex(
        STEER_SUBJECT + "(?:안(?:돌|움직|꺾|돼|되|잠기)|못(?:돌|움직|꺾)|잠기|잠겼|잠긴|" +
                "돌아가지않|움직이지않|돌릴수없|불능)|제어가(?:안(?:돼|되|됐)|불가)"
    )

    // 제동·조향이 "안정적이지 않다"는 말은 불능은 아니지만 제어 이상일 수 있어 운행 중단 쪽으로 봅니다.
    private val unstablePattern = Regex(
        "(?:브레이크|제동|조향|핸들|스티어링)(?:이|가|은|는|도)?" +
                "(?:불안정|안정적이지(?:않|못)|안정적이않|안정되지(?:않|못)|안정감이없|안정이안)"
    )

    // 위의 명시적인 표현에 걸리지 않았지만 "…안"으로 시작하는 위험어가 남은 경우: 보수적으로 운행 중단으로 봅니다.
    // 단, 뒷말이 안쪽·안전·안내처럼 위치·일반 단어이거나 "안 밟았는데"처럼 운전자의 행동을 부정한 말이면 고장 표현으로 보지 않습니다.
    // 이 규칙은 마지막 안전망일 뿐이고, 제동 불능·조향 불능 판단에는 쓰이지 않습니다.
    private val ambiguousAnWords = listOf(
        "브레이크가안", "브레이크안", "제동이안", "핸들이안", "조향이안", "스티어링이안", "제어가안",
        "브레이크가잘안", "제동이잘안", "조향이잘안"
    )
    private val notFailureAfterAn = listOf(
        "쪽", "전", "내", "정", "에서", "에", "팎", "착", "심", "면", "부",
        "밟았", "밟아", "밟는", "밟을", "밟고", "밟지", "누르", "눌렀", "쓰", "사용"
    )

    private fun ambiguousFailure(value: String): List<String> =
        ambiguousAnWords.filter { word ->
            var from = value.indexOf(word)
            var found = false
            while (from >= 0 && !found) {
                val rest = value.substring(from + word.length)
                if (notFailureAfterAn.none { rest.startsWith(it) }) found = true
                from = value.indexOf(word, from + 1)
            }
            found
        }

    private const val STOP_HEADLINE = "운행 중단을 권장해요"
    private const val STOP_ADVICE =
        "위험할 수 있는 증상이에요. 속도를 줄이고 안전한 곳에 정차해 운행을 중단해 주세요. " +
                "원인은 점검해 봐야 알 수 있어요. 필요하면 긴급출동이나 견인을 요청하세요."

    private val STOP_STEPS = listOf(
        "속도를 줄이고 안전한 곳에 정차하세요.",
        "운행을 멈추고 긴급출동 또는 견인을 요청하세요."
    )
    private val FIRE_STEPS = listOf(
        "안전한 곳에 차를 세우고 시동을 끄세요.",
        "모두 차에서 내려 멀리 떨어지세요.",
        "바로 119에 신고하세요."
    )
    private val RUNAWAY_STEPS = listOf(
        "비상등을 켜고 핸들을 단단히 잡으세요.",
        "브레이크를 강하게 계속 밟고, 변속을 중립(N)으로 바꿔 속도를 줄이세요.",
        "안전하게 멈췄다면 시동을 끄고 119나 긴급출동에 도움을 요청하세요."
    )
    private val BRAKE_LOSS_STEPS = listOf(
        "비상등을 켜고 핸들을 단단히 잡으세요.",
        "브레이크 페달을 여러 번 힘껏 밟고, 변속을 낮추거나 회생제동을 강하게 해 속도를 줄이세요.",
        "안전한 곳으로 서서히 이동해 멈추세요. 주차 브레이크는 급하게 당기지 말고 서서히 쓰세요.",
        "위험하면 119나 긴급출동을 요청하고, 멈춘 뒤에는 운행하지 말고 견인을 요청하세요."
    )
    private val STEER_LOSS_STEPS = listOf(
        "비상등을 켜고 급한 조작을 피하며 천천히 속도를 줄이세요.",
        "차를 제어하기 어렵거나 멈추지 않으면 119나 긴급출동을 요청하세요.",
        "멈춘 뒤에는 운행하지 말고 견인을 요청하세요."
    )

    private const val STOP_SUMMARY =
        "속도를 줄이고 안전한 곳에 정차한 뒤 긴급출동 또는 견인으로 점검을 받으세요. 원인은 점검해 봐야 알 수 있어요."
    private const val BRAKE_NOTICE_SUMMARY =
        "원인은 점검해 봐야 알 수 있어요. 제동이 약해지면 바로 안전한 곳에 정차하세요."
    private const val FIRE_SUMMARY = "안전한 곳에 세우고 시동을 끈 뒤 모두 내려 멀리 떨어지세요. 바로 119에 신고하세요."
    private const val RUNAWAY_SUMMARY = "비상등을 켜고 브레이크를 강하게 밟으며 속도를 줄이세요. 위험하면 119에 도움을 요청하세요."
    private const val BRAKE_LOSS_SUMMARY = "비상등을 켜고 차를 안정적으로 제어하며 속도를 줄이세요. 위험하면 119나 긴급출동을 요청하세요."
    private const val STEER_LOSS_SUMMARY = "비상등을 켜고 급한 조작 없이 천천히 속도를 줄이세요. 위험하면 119나 긴급출동을 요청하세요."

    private const val EMERGENCY_HEADLINE = "긴급 안전 안내"
    private const val FIRE_ADVICE =
        "화재나 연기가 의심되면 안전한 곳에 차를 세우고 시동을 끈 뒤 모두 차에서 내려 멀리 떨어지세요. " +
                "바로 119에 신고하세요. 원인은 점검해 봐야 알 수 있어요."
    private const val RUNAWAY_ADVICE =
        "비상등을 켜고 핸들을 단단히 잡으세요. 브레이크를 강하게 계속 밟고, 변속을 중립(N)으로 바꿔 속도를 줄이세요. " +
                "안전하게 멈췄다면 시동을 끄고 119나 긴급출동에 도움을 요청하세요. 원인은 점검해 봐야 알 수 있어요."
    private const val BRAKE_LOSS_ADVICE =
        "비상등을 켜고 핸들을 단단히 잡은 채 차를 안정적으로 제어하세요. 브레이크 페달을 여러 번 힘껏 밟아 보고, " +
                "변속을 낮추거나 회생제동을 강하게 해 속도를 줄이세요. 주차 브레이크는 급하게 당기지 말고 서서히 쓰세요. " +
                "주변 차량과 거리를 두고 안전한 곳으로 서서히 이동해 멈추세요. 멈추지 않거나 위험하면 119나 긴급출동에 도움을 요청하고, " +
                "멈춘 뒤에는 운행하지 말고 견인을 요청하세요. 원인은 점검해 봐야 알 수 있어요."
    private const val STEER_LOSS_ADVICE =
        "비상등을 켜고 급한 조작을 피하며 천천히 속도를 줄이세요. 핸들이 돌아가지 않거나 차를 제어하기 어렵다면 " +
                "119나 긴급출동에 도움을 요청하세요. 멈춘 뒤에는 운행하지 말고 견인을 요청하세요. 원인은 점검해 봐야 알 수 있어요."

    // 핸들(조향)이 무겁다는 설명은 정도에 따라 나눕니다. 이 문장만으로 무조건 정차로 보지 않습니다.
    private val steeringWords = listOf("핸들", "스티어링", "조향")
    private val steeringHeavyWords = listOf("무거", "무겁", "뻑뻑", "묵직", "힘들", "어렵")
    private val suddenWords = listOf(
        "갑자기", "너무무거", "엄청무거", "많이무거", "확무거",
        "돌리기힘", "돌리기가힘", "돌리기어렵", "돌리기가어렵"
    )
    private val gradualWords = listOf("서서히", "천천히", "점점", "조금씩", "며칠", "오래전", "몇주", "몇달")

    private val redLightWords = listOf("빨간", "빨강", "적색", "깜빡")
    private val lightWords = listOf("경고등", "경고", "불이들어", "불이켜")

    private val brakeWords = listOf("브레이크", "제동", "패드", "디스크")
    private val noiseWords = listOf(
        "소음", "소리", "진동", "떨림", "덜덜", "삐걱", "끼익", "끽", "삐", "딱딱", "쿵", "웅"
    )

    // 소음이 언제·어디서 나는지 알 수 있는 단어. 하나도 없으면 더 물어봅니다.
    private val noiseContextWords = listOf(
        "주행", "정차", "가속", "감속", "핸들", "브레이크", "엔진", "바퀴", "앞", "뒤", "시동",
        "속도", "코너", "턴", "요철", "출발", "에어컨"
    )

    private val lightDetailWords = listOf(
        "노란", "노랑", "주황", "초록", "파란", "배터리", "엔진", "타이어", "abs", "esp",
        "에어백", "오일", "모양", "표시", "마크", "느낌표", "그림"
    ) + redLightWords

    private val chargingWords = listOf("충전", "충전기", "충전커넥터")
    private val startingWords = listOf("시동", "스마트키", "키인식")
    private val acWords = listOf("에어컨", "냉방", "찬바람", "히터", "송풍", "공조")
    private val tireWords = listOf("타이어", "공기압", "펑크", "tpms")

    private fun has(value: String, words: List<String>): List<String> =
        words.filter { value.contains(it) }

    /**
     * @param symptom 운전자가 말한 증상(여러 번 말한 내용을 합친 문장이어도 됩니다)
     * @param partHint Gleo가 부위(part)를 넘겨준 경우. 없거나 OTHER면 문장으로 추정합니다.
     * @param evOrPhev 등록 차량이 전기차/플러그인 하이브리드인지. 충전 질문 트리는 이 경우에만 씁니다.
     */
    fun analyze(
        symptom: String,
        partHint: PartCategory? = null,
        evOrPhev: Boolean = false
    ): TriageResult {
        val value = compact(symptom)
        val hinted = partHint?.takeIf { it != PartCategory.OTHER }

        if (value.length < 2) {
            return needMore(
                hinted ?: PartCategory.OTHER,
                "symptom",
                "증상을 아직 알 수 없어요.",
                "어떤 증상인지 한 번만 더 알려 주세요. 위험하다고 느껴지면 먼저 안전한 곳에 정차해 주세요."
            )
        }

        // 1) 위험 증상은 무엇보다 먼저.
        //    실제 불능·화재·급발진은 명시적인 표현으로 먼저 가려내고(여러 표현이 섞이면 가장 위험한 것이 이깁니다),
        //    그다음에 제동력 저하·조향 불안정 등 운행 중단 대상을 봅니다.
        val fire = has(value, fireWords)
        val runaway = has(value, runawayWords)
        val brakeLoss = has(value, brakeLossWords) + brakeLossPattern.findAll(value).map { it.value }.toList()
        val steerLoss = has(value, steerLossWords) + steerLossPattern.findAll(value).map { it.value }.toList()

        val stop = (has(value, stopWords) + fire + runaway + brakeLoss + steerLoss).toMutableList()
        stop += unstablePattern.findAll(value).map { it.value }.toList()
        stop += ambiguousFailure(value)

        val lightMentioned = has(value, lightWords)
        if (lightMentioned.isNotEmpty() && has(value, redLightWords).isNotEmpty()) {
            stop += "빨간/깜빡이는 경고등"
        }
        if (stop.isNotEmpty()) {
            val emergency: Triple<String, String, List<String>>? = when {
                fire.isNotEmpty() -> Triple(FIRE_ADVICE, FIRE_SUMMARY, FIRE_STEPS)
                runaway.isNotEmpty() -> Triple(RUNAWAY_ADVICE, RUNAWAY_SUMMARY, RUNAWAY_STEPS)
                brakeLoss.isNotEmpty() -> Triple(BRAKE_LOSS_ADVICE, BRAKE_LOSS_SUMMARY, BRAKE_LOSS_STEPS)
                steerLoss.isNotEmpty() -> Triple(STEER_LOSS_ADVICE, STEER_LOSS_SUMMARY, STEER_LOSS_STEPS)
                else -> null
            }
            val part = hinted ?: guessPart(value)

            return if (emergency != null) {
                TriageResult(
                    Urgency.STOP_NOW, part, null,
                    EMERGENCY_HEADLINE, emergency.first, null,
                    stop.distinct(), Severity.EMERGENCY, emergency.second, emergency.third
                )
            } else {
                TriageResult(
                    Urgency.STOP_NOW, part, null,
                    STOP_HEADLINE, STOP_ADVICE, null,
                    stop.distinct(), Severity.STOP, STOP_SUMMARY, STOP_STEPS
                )
            }
        }

        // 1-2) 핸들이 무거움: 갑자기·돌리기 힘듦 → 정차 안내 / 서서히 → 점검 권장 / 그 밖(조금 무겁다 등) → 더 묻기
        val steer = has(value, steeringWords)
        val heavy = has(value, steeringHeavyWords)
        if (steer.isNotEmpty() && heavy.isNotEmpty()) {
            val sudden = has(value, suddenWords)
            val gradual = has(value, gradualWords)
            val part = hinted ?: PartCategory.OTHER

            return when {
                sudden.isNotEmpty() -> TriageResult(
                    Urgency.STOP_NOW, part, null,
                    STOP_HEADLINE,
                    "핸들이 갑자기 무거워졌거나 돌리기 힘들면 위험할 수 있어요. " +
                            "안전한 곳에 정차하고 운행을 중단해 주세요. 원인은 점검해 봐야 알 수 있어요.",
                    null,
                    (steer + heavy + sudden).distinct(),
                    Severity.STOP,
                    STOP_SUMMARY,
                    STOP_STEPS
                )

                gradual.isNotEmpty() -> TriageResult(
                    Urgency.INSPECT_SOON, part, null,
                    "조향 점검을 권장해요",
                    "핸들 느낌이 평소와 달라진 것은 안전과 관련돼요. 원인은 점검해 봐야 알 수 있어요. " +
                            "가까운 시일 안에 점검을 받고, 갑자기 심해지면 바로 안전한 곳에 정차해 주세요.",
                    null,
                    (steer + heavy + gradual).distinct()
                )

                else -> needMore(
                    part,
                    "steering_detail",
                    "핸들 상태를 조금 더 알아야 해요.",
                    "갑자기 무거워졌는지, 돌리기 힘든 정도인지, 경고등이 켜졌는지 알려 주세요. " +
                            "갑자기 심해졌거나 돌리기 힘들면 먼저 안전한 곳에 정차해 주세요."
                )
            }
        }

        // 2) 질문 트리가 있는 증상 (기존 analyzeSymptom과 같은 우선순위)
        val charging = has(value, chargingWords)
        val starting = has(value, startingWords)
        val ac = has(value, acWords)
        val tire = has(value, tireWords)

        val selfCheck: Pair<String, PartCategory>? = when {
            charging.isNotEmpty() && evOrPhev -> ISSUE_CHARGING to PartCategory.BATTERY_CHARGING
            starting.isNotEmpty() -> ISSUE_STARTING to PartCategory.ELECTRICAL
            ac.isNotEmpty() -> ISSUE_AC to PartCategory.AIR_CONDITIONING
            tire.isNotEmpty() -> ISSUE_TIRE to PartCategory.TIRE
            else -> null
        }

        if (selfCheck != null) {
            return TriageResult(
                Urgency.SELF_CHECK,
                selfCheck.second,
                selfCheck.first,
                "앱에서 바로 확인해 볼 수 있어요",
                "몇 가지 질문으로 먼저 확인해 볼 수 있어요. 결과는 참고용이고 고장을 확정하지 않아요.",
                null,
                charging + starting + ac + tire
            )
        }

        // 3) 브레이크 관련: 소리·느낌이 달라졌다면 원인은 단정하지 않고 점검을 권장
        val brake = has(value, brakeWords)
        if (brake.isNotEmpty() || hinted == PartCategory.BRAKE) {
            return TriageResult(
                Urgency.INSPECT_SOON,
                PartCategory.BRAKE,
                null,
                "브레이크 점검을 권장해요",
                "브레이크 관련 증상은 안전과 직결돼요. 원인은 점검해 봐야 알 수 있어요. " +
                        "가까운 시일 안에 정비 점검을 받아 보세요. " +
                        "제동이 약해지거나 페달 느낌이 크게 달라지면 바로 안전한 곳에 정차해 주세요.",
                null,
                brake,
                Severity.NOTICE,
                BRAKE_NOTICE_SUMMARY
            )
        }

        // 4) 경고등
        if (lightMentioned.isNotEmpty()) {
            val detail = has(value, lightDetailWords)
            return if (detail.isEmpty()) {
                needMore(
                    hinted ?: PartCategory.ELECTRICAL,
                    "warning_light_description",
                    "경고등 종류를 아직 알 수 없어요.",
                    "경고등의 색이나 모양을 알려 주세요. 빨간색이거나 깜빡이면 안전한 곳에 정차해 주세요."
                )
            } else {
                TriageResult(
                    Urgency.INSPECT_SOON,
                    hinted ?: PartCategory.ELECTRICAL,
                    null,
                    "경고등 점검을 권장해요",
                    "경고등은 종류마다 의미가 달라요. 원인은 점검해 봐야 알 수 있어요. " +
                            "가까운 시일 안에 점검을 받아 보세요.",
                    null,
                    lightMentioned + detail
                )
            }
        }

        // 5) 소리·진동
        val noise = has(value, noiseWords)
        if (noise.isNotEmpty()) {
            val context = has(value, noiseContextWords)
            return if (context.isEmpty() && hinted == null) {
                needMore(
                    PartCategory.OTHER,
                    "noise_condition",
                    "소리가 나는 상황을 아직 알 수 없어요.",
                    "언제 어디서 나는지 알려 주세요. 예를 들면 주행 중인지, 정차 중인지, 어느 쪽에서 나는지예요."
                )
            } else {
                TriageResult(
                    Urgency.INSPECT_SOON,
                    hinted ?: guessPart(value),
                    null,
                    "점검을 권장해요",
                    "소리나 진동은 원인이 다양해서 지금 말씀만으로는 원인을 알 수 없어요. " +
                            "계속되면 정비 점검을 받아 보세요.",
                    null,
                    noise + context
                )
            }
        }

        // 6) 그 밖의 증상: 원인을 짐작하지 않고, 짧으면 더 묻고, 충분하면 점검 권장
        return if (value.length < 6 && hinted == null) {
            needMore(
                PartCategory.OTHER,
                "symptom_detail",
                "증상을 조금 더 알아야 해요.",
                "어떤 상황에서 어떤 점이 이상한지 한 번만 더 알려 주세요."
            )
        } else {
            TriageResult(
                Urgency.INSPECT_SOON,
                hinted ?: guessPart(value),
                null,
                "전문 점검을 권장해요",
                "앱에 이 증상을 확인하는 절차가 없어요. 원인을 짐작하지 않고 전문 점검을 안내할게요.",
                null,
                emptyList()
            )
        }
    }

    private fun needMore(
        part: PartCategory,
        missing: String,
        headline: String,
        advice: String
    ) = TriageResult(Urgency.NEED_MORE_INFO, part, null, headline, advice, missing, emptyList())

    /** 기존 MainActivity.guessPart와 같은 규칙. (공용 로직으로 옮기기 전까지 두 곳이 같아야 합니다) */
    fun guessPart(compactText: String): PartCategory {
        val value = compact(compactText)

        fun any(vararg words: String) = words.any { value.contains(it) }

        return when {
            any("브레이크", "제동") -> PartCategory.BRAKE
            any("타이어", "공기압", "펑크", "tpms") -> PartCategory.TIRE
            any("충전", "배터리") -> PartCategory.BATTERY_CHARGING
            any("에어컨", "냉방", "히터", "송풍", "공조") -> PartCategory.AIR_CONDITIONING
            any("시동", "스마트키", "센서", "경고등") -> PartCategory.ELECTRICAL
            any("엔진", "변속", "미션") -> PartCategory.ENGINE
            any("범퍼", "도색", "찌그러", "판금") -> PartCategory.BODY
            else -> PartCategory.OTHER
        }
    }
}
