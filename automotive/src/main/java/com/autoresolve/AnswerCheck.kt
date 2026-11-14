package com.autoresolve

/**
 * 음성 상담 답변 검사 (안드로이드에 의존하지 않는 순수 로직).
 *
 *  1) validateFollowUp: 후속 질문의 답이 의미 있는 답변인지 검사합니다.
 *     - 글자 수만으로 판단하지 않습니다. "네/응/아니요/없어요/몰라요" 같은 짧은 정상 답은 허용하고,
 *       "어/그/아/음" 같은 군말·조각, 의미 없는 반복, 접속어만 있는 말은 거릅니다.
 *     - 질문 유형(예/아니오, 선택형, 서술형)마다 허용 답변이 다릅니다.
 *  2) judgeConsent: 서비스센터 전송 동의를 판정합니다.
 *     - 부분 문자열이 아니라 단어(띄어쓰기) 단위로 명시적 표현만 인정합니다.
 *     - 긍정·부정이 함께 있거나 애매하면 AMBIGUOUS → 전송하지 않습니다.
 */
object AnswerCheck {

    enum class Kind { YES_NO, CHOICE, OPEN }
    enum class Intent { YES, NO, AMBIGUOUS }

    data class Check(val valid: Boolean, val reason: String)
    data class Consent(val intent: Intent, val reason: String)

    // ---------------------------------------------------------------
    // 공통 도구
    // ---------------------------------------------------------------

    private val PUNCT = Regex("[.,!?~…·'\"‘’“”()\\-]")

    private fun tokens(text: String): List<String> =
        text.lowercase().split(Regex("\\s+"))
            .map { PUNCT.replace(it, "") }
            .filter { it.isNotEmpty() }

    private fun clean(text: String): String =
        PUNCT.replace(text.lowercase(), "").replace(Regex("\\s+"), "")

    private fun stripYo(t: String) = if (t.length > 1) t.removeSuffix("요") else t

    private fun inSet(t: String, set: Set<String>) = t in set || stripYo(t) in set

    // ---------------------------------------------------------------
    // 서비스센터 전송 동의 판정
    // ---------------------------------------------------------------

    // 긍정 표현 (끝의 "요"는 떼고 비교합니다)
    private val YES_WORDS = setOf(
        "네", "예", "응", "웅", "넵", "넹", "네네", "예예", "그래", "좋아", "좋습니다", "맞아", "맞습니다",
        "부탁해", "부탁드려", "부탁드립니다", "보내줘", "보내주세", "보내줄래", "보내", "전달해줘", "전달해주세",
        "전달해줄래", "해줘", "해주세", "그렇게해줘", "그렇게해주세", "오케이", "오케", "ok",
        "접수해", "접수해줘", "접수해주세", "접수해줄래"
    )

    // 부정 표현
    private val NO_WORDS = setOf(
        "아니", "아뇨", "아니에", "아니야", "아냐", "아니다", "싫어", "싫다", "괜찮아", "괜찮습니다", "됐어", "됐습니다",
        "나중에", "보내지마", "보내지마세", "보내지말아", "보내지말아줘", "전달하지마", "전달하지마세", "전달하지말아",
        "전달하지말아줘", "하지마", "하지마세", "하지말아", "하지말아줘", "안보내", "안보낼래", "안보낼게",
        "안할래", "안할게", "안해", "그만", "취소", "노",
        "취소해", "취소해줘", "취소해주세", "취소할래", "취소할게"
    )

    // 동의를 망설이거나 다른 말을 덧붙이는 표현. 이런 말이 섞이면 "네"가 있어도 동의로 보지 않고 다시 묻습니다.
    // (단어가 이 표현으로 "시작"하면 해당합니다: "비용은", "잠깐만요" 등)
    private val HESITATION_PREFIXES = listOf(
        "근데", "그런데", "그러니까", "그니까", "그게", "그치만", "하지만", "근뎅",
        "잠깐", "잠시", "기다려", "일단", "제말", "글쎄", "생각",
        "비용", "얼마", "가격", "언제", "왜", "궁금", "얼만"
    )

    private fun hasHesitation(toks: List<String>): String? {
        for (t in toks) {
            val c = stripYo(t)
            HESITATION_PREFIXES.firstOrNull { c.startsWith(it) }?.let { return t }
        }
        // 두 단어로 나뉜 "제 말은"
        for (i in 0 until toks.size - 1) {
            if (stripYo(toks[i] + toks[i + 1]).startsWith("제말")) return toks[i] + " " + toks[i + 1]
        }
        return null
    }

    fun judgeConsent(text: String): Consent {
        val toks = tokens(text)

        if (toks.isEmpty()) return Consent(Intent.AMBIGUOUS, "빈 답변")
        if (toks.size > 5) return Consent(Intent.AMBIGUOUS, "답변이 길어서 의사 표현으로 보기 어려움(${toks.size}단어)")

        var yes = 0
        var no = 0
        var other = 0
        val matched = mutableListOf<String>()
        val hesitation = hasHesitation(toks)
        val isQuestion = text.contains('?')

        var i = 0
        while (i < toks.size) {
            // 띄어 쓴 두 단어("보내지 마", "전달해 줘", "안 보내")를 먼저 합쳐서 봅니다.
            if (i + 1 < toks.size) {
                val joined = toks[i] + toks[i + 1]

                if (inSet(joined, NO_WORDS)) {
                    no++; matched.add("부정:$joined"); i += 2; continue
                }
                if (inSet(joined, YES_WORDS)) {
                    yes++; matched.add("긍정:$joined"); i += 2; continue
                }
            }

            val t = toks[i]

            when {
                inSet(t, NO_WORDS) -> { no++; matched.add("부정:$t") }
                inSet(t, YES_WORDS) -> { yes++; matched.add("긍정:$t") }
                else -> other++
            }

            i++
        }

        return when {
            yes > 0 && no > 0 -> Consent(Intent.AMBIGUOUS, "긍정과 부정이 함께 있음 $matched")
            // 명시적 거절은 망설이는 말이 섞여도 거절입니다. (전송하지 않는 쪽이라 안전)
            no > 0 -> Consent(Intent.NO, "명시적 부정 $matched")
            // 긍정에 망설임·질문이 섞이면 동의로 보지 않습니다.
            yes > 0 && hesitation != null -> Consent(Intent.AMBIGUOUS, "긍정에 망설임/추가 질문이 섞임('$hesitation') $matched")
            yes > 0 && isQuestion -> Consent(Intent.AMBIGUOUS, "긍정에 질문이 섞임 $matched")
            hesitation != null -> Consent(Intent.AMBIGUOUS, "망설임/보류 표현('$hesitation')")
            yes > 0 && other <= 2 -> Consent(Intent.YES, "명시적 긍정 $matched")
            yes > 0 -> Consent(Intent.AMBIGUOUS, "긍정 표현 외 다른 말이 많음(${other}단어) $matched")
            else -> Consent(Intent.AMBIGUOUS, "명시적 긍정/부정 표현 없음")
        }
    }

    // ---------------------------------------------------------------
    // 후속 질문 답변 검사
    // ---------------------------------------------------------------

    // 의미 없는 군말 글자 (이 글자들로만 이루어진 말은 답이 아님)
    private val FILLER_CHARS = "어음그저아에이으흠엄앗허흐오우네".toSet()

    // 접속어·군말 단어만 있는 경우
    private val CONNECTIVES = setOf(
        "그래서", "그리고", "그런데", "근데", "그러니까", "그러면", "그러다", "저기", "그게", "이게", "뭐지", "어음", "음그", "그럼"
    )

    // 예/아니오 계열 답변과 "모른다/자주/가끔" 같은 상태 표현 (2글자 이상, 포함 검사)
    private val ANSWER_CONTAINS = listOf(
        "있어", "없어", "있습", "없습", "몰라", "모르", "글쎄", "맞아", "맞습", "아니", "아뇨", "그래", "그렇",
        "아직", "전혀", "별로", "가끔", "계속", "항상", "자주", "종종", "안돼", "안나", "안켜", "괜찮", "좋아", "싫어", "됐어"
    )

    // 증상·상태를 설명할 때 쓰는 의미 있는 단어 (2글자 이상, 포함 검사)
    private val KEYWORDS = listOf(
        "소리", "소음", "진동", "떨림", "냄새", "연기", "경고", "깜빡", "깜박", "점멸", "바람", "온도", "에어컨", "히터",
        "충전", "시동", "배터리", "브레이크", "페달", "핸들", "타이어", "공기압", "펑크", "엔진", "주행", "운전", "달릴",
        "정차", "서있", "멈", "출발", "가속", "감속", "속도", "신호", "대기", "처음", "어제", "오늘", "아침", "저녁", "며칠",
        "점점", "심해", "갑자기", "이상", "화면", "계기판", "표시", "메시지", "오류", "빨간", "노란", "주황", "초록", "파란",
        "덜덜", "기름", "오일", "냉각", "뜨겁", "차갑", "시원", "따뜻", "미지근", "후진", "주차", "언덕", "왼쪽", "오른쪽",
        "앞쪽", "뒤쪽", "켜져", "꺼져", "켜짐", "꺼짐"
    )

    // ---------------------------------------------------------------
    // 이어 말하기 신호 (후속 질문 단계에서 "근데", "그게" 같은 말만 인식됐을 때)
    // ---------------------------------------------------------------

    // 문장을 이어 가려는 말. 이 중 하나는 반드시 있어야 하고, 나머지는 아래 보조 단어여도 됩니다.
    private val CUE_WORDS = setOf(
        "근데", "그런데", "그러니까", "그니까", "그게", "이게", "그치만", "하지만", "저기", "그럼",
        "잠깐", "잠깐만", "잠시", "잠시만", "제말은", "말은", "음", "어", "그래서", "그리고", "뭐지"
    )
    private val CUE_HELPERS = setOf("아니", "아뇨", "제", "그", "저", "그니까", "근데")

    /**
     * 이어서 말하려는 조각인지 봅니다. true면 바로 무효 처리하지 않고 추가 발화를 짧게 기다릴 수 있습니다.
     * ("아니요" 같은 단독 부정, "네"는 정상 답이라 해당하지 않습니다.) 사용자 발화 자체는 바꾸지 않습니다.
     */
    fun isContinuationCue(text: String): Boolean {
        val toks = tokens(text).map { stripYo(it) }
        if (toks.isEmpty() || toks.size > 4) return false
        if (toks.none { it in CUE_WORDS }) return false
        return toks.all { it in CUE_WORDS || it in CUE_HELPERS }
    }

    fun validateFollowUp(text: String, kind: Kind, hints: List<String> = emptyList()): Check {
        val t = clean(text)
        val toks = tokens(text)

        if (t.isEmpty()) return Check(false, "빈 답변")

        // 짧은 정상 답변 단어 (네/예/응/아니요 …): 한 글자 단어는 단어 전체가 일치할 때만 인정
        val polarityWord = toks.any { inSet(it, YES_WORDS) || inSet(it, NO_WORDS) }

        if (!t.any { it.isLetterOrDigit() }) return Check(false, "글자 없음")

        // 접속어만 있는 말: 문장이 끊긴 것
        if (stripYo(t) in CONNECTIVES) return Check(false, "접속어만 있고 내용이 없음('$t')")

        // 정상 답변 단어가 먼저 인정됩니다. ("응"은 군말이 아니라 긍정)
        if (polarityWord) return Check(true, "긍정/부정 표현")

        // 군말 글자뿐이거나, 같은 글자 반복
        val withoutFiller = t.filter { it !in FILLER_CHARS }
        if (withoutFiller.isEmpty()) return Check(false, "군말만 있음('$t')")
        if (t.length >= 4 && t.toSet().size <= 2) return Check(false, "같은 소리 반복('$t')")

        val answerWord = ANSWER_CONTAINS.firstOrNull { t.contains(it) }
        if (answerWord != null) return Check(true, "답변 표현 '$answerWord'")

        val hint = hints.firstOrNull { t.contains(clean(it)) }
        if (hint != null) return Check(true, "질문 유형(${kind.name}) 허용 답변 '$hint'")

        val keyword = KEYWORDS.firstOrNull { t.contains(it) }
        if (keyword != null) return Check(true, "증상·상태 단어 '$keyword'")

        // 사전에 없는 말: 충분히 길면 자유 서술로 봅니다. 너무 짧으면 확인합니다.
        return when {
            t.length >= 5 -> Check(true, "자유 서술(${t.length}글자)")
            t.length <= 2 -> Check(false, "1~2글자인데 의미 있는 답변이 아님('$t')")
            else -> Check(false, "3~4글자인데 허용 답변·증상 단어가 없음('$t')")
        }
    }
}
