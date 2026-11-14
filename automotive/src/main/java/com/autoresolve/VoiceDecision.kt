package com.autoresolve

/**
 * 음성 상담에서 쓰는 순수 판단 로직. 화면·SDK와 섞이지 않게 분리해 두었고, 기기 없이 테스트할 수 있습니다.
 *
 * 1) 이어받기: 발화가 정말 중간에 끊겼을 때만 추가 발화를 기다립니다.
 * 2) 안전 안내: SymptomTriage(공용 분류)의 위험도에 맞춰 말할 문장을 만듭니다. 원인은 단정하지 않습니다.
 */
object VoiceDecision {

    /** 문장이 이어질 것 같은 끝맺음. */
    val UNFINISHED_ENDINGS = listOf(
        "그리고", "그런데", "근데", "그래서", "그러니까", "그러면",
        "는데", "은데", "인데", "니까", "다가", "지만", "면서", "해서", "어서", "아서", "하고", "으면"
    )

    // 서술어 없이 조사로 끝난 조각("브레이크 페달이", "에어컨 바람이"). 이 뒤에 말이 더 이어질 가능성이 큽니다.
    private val BARE_PARTICLE_CHARS = setOf('이', '가', '은', '는', '을', '를', '에', '도', '의', '로', '과', '와', '랑')
    private val PREDICATE_END_CHARS = setOf('요', '다')

    private fun stripEnd(text: String): String =
        text.trim().trimEnd('.', '!', '?', ',', ' ')

    /** 이어질 것 같은 어미(예: "~는데")로 끝나면 그 어미, 아니면 null. */
    fun unfinishedEnding(text: String): String? {
        val t = stripEnd(text).removeSuffix("요")
        return UNFINISHED_ENDINGS.firstOrNull { t.endsWith(it) }
    }

    /** 조사로만 끝난 조각이면 그 조사, 아니면 null. */
    fun bareParticleEnding(text: String): String? {
        val t = stripEnd(text)
        if (t.isEmpty()) return null
        val last = t.last()
        if (last in PREDICATE_END_CHARS) return null
        if (t.endsWith("에서")) return "에서"
        if (t.endsWith("으로")) return "으로"
        return if (last in BARE_PARTICLE_CHARS) last.toString() else null
    }

    // 서술어 없이 부사로 끊긴 조각("핸들이 갑자기", "시동이 안"). 두 단어 이상일 때만 봅니다.
    // ("계속", "자주" 같은 한 단어 답변은 정상 답이라 해당하지 않습니다.)
    private val DANGLING_ENDINGS = setOf(
        "갑자기", "너무", "자꾸", "점점", "조금", "아주", "가끔", "계속", "좀", "잘", "못", "안",
        "막", "그냥", "약간", "많이", "다시", "자주"
    )

    /** 부사로 끊긴 조각이면 그 부사, 아니면 null. */
    fun danglingEnding(text: String): String? {
        val toks = stripEnd(text).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.size < 2) return null
        val last = toks.last()
        return if (last in DANGLING_ENDINGS) last else null
    }

    // 말이 끊겼을 때 안전과 직결된 대상이 들어 있으면 오래 기다리지 않습니다.
    private val SAFETY_SUBJECTS = listOf("브레이크", "제동", "핸들", "조향", "스티어링", "연기", "불꽃", "화재", "급발진", "가속", "페달")

    /** 안전과 직결된 대상(브레이크·핸들·연기 등)이 들어 있는 조각이면 true. */
    fun mentionsSafetySubject(text: String): Boolean {
        val v = SymptomTriage.compact(text)
        return SAFETY_SUBJECTS.any { v.contains(it) }
    }

    /** 위험 증상이면 true. 위험 증상은 절대 기다리지 않고 바로 안내합니다. */
    fun isSafetyFirst(triage: TriageResult): Boolean = triage.urgency == Urgency.STOP_NOW

    /**
     * 최종 결과를 받은 뒤 추가 발화를 기다려야 하는 이유. null이면 기다리지 않고 바로 처리합니다.
     * 문장 끝 표현뿐 아니라 증상 정보가 충분한지도 함께 봅니다.
     */
    fun holdReason(text: String, triage: TriageResult): String? {
        if (isSafetyFirst(triage)) return null

        unfinishedEnding(text)?.let { return "미완성 어미('$it')" }
        bareParticleEnding(text)?.let { return "조사로 끝남('$it')" }
        danglingEnding(text)?.let { return "부사로 끝남('$it')" }

        val length = SymptomTriage.compact(text).length
        if (triage.needsMoreInfo && length < 6) return "정보 부족(${length}글자)"

        return null
    }

    /**
     * 후속 질문 단계에서 추가 발화를 기다려야 하는 이유. null이면 기다리지 않습니다.
     * 모든 답변을 기다리지 않고, 접속어·망설임만 있거나 문장이 끊긴 경우만 해당합니다.
     * "네", "아니요", 완결된 문장은 기다리지 않고 바로 처리합니다.
     */
    fun followUpHoldReason(text: String, triage: TriageResult): String? {
        if (isSafetyFirst(triage)) return null

        if (AnswerCheck.isContinuationCue(text)) return "이어 말하기 표현"
        unfinishedEnding(text)?.let { return "미완성 어미('$it')" }
        bareParticleEnding(text)?.let { return "조사로 끝남('$it')" }
        danglingEnding(text)?.let { return "부사로 끝남('$it')" }
        return null
    }

    /** 말로 읽어 줄 짧은 안전 문장. 위험 증상이 아니면 null. 원인은 단정하지 않습니다. */
    fun safetySpeech(triage: TriageResult): String? {
        if (!isSafetyFirst(triage)) return null

        return when (triage.severity) {
            Severity.EMERGENCY -> triage.summary
            else -> "위험할 수 있어요. " + triage.summary
        }
    }

    /** 화면에 보여 줄 단계별 안전 안내. 단계가 없으면 null. */
    fun safetySteps(triage: TriageResult): String? {
        if (!isSafetyFirst(triage) || triage.steps.isEmpty()) return null

        return "안전 안내\n" +
                triage.steps.mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n")
    }

    /** 브레이크 주제의 마지막 안내. 위험 증상이 아니면 위험하다고 단정하지 않습니다. */
    fun brakeFollowUpAdvice(triage: TriageResult): String =
        if (isSafetyFirst(triage)) {
            triage.advice
        } else {
            "브레이크는 안전과 관련돼서 원인은 점검해 봐야 알 수 있어요. " +
                    "제동이 약해지거나 페달이 깊게 밟히면 바로 안전한 곳에 정차해 주세요."
        }
}
