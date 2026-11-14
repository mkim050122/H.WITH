package com.autoresolve

// 차량 모델 검색어 매칭. UI 없이 테스트할 수 있게 순수 Kotlin으로만 둔다. (ModelSearchTest)

private const val MAX_SUGGESTIONS = 8

private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

// 겹모음·겹받침은 입력 순서대로 풀어 둔다. ("오" 다음에 ㅏ를 치면 "와", "안" 다음에 ㅈ을 치면 "앉"이 되므로)
private val JUNG = listOf(
    "ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ", "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅗㅏ", "ㅗㅐ", "ㅗㅣ", "ㅛ",
    "ㅜ", "ㅜㅓ", "ㅜㅔ", "ㅜㅣ", "ㅠ", "ㅡ", "ㅡㅣ", "ㅣ"
)
private val JONG = listOf(
    "", "ㄱ", "ㄲ", "ㄱㅅ", "ㄴ", "ㄴㅈ", "ㄴㅎ", "ㄷ", "ㄹ", "ㄹㄱ", "ㄹㅁ", "ㄹㅂ", "ㄹㅅ", "ㄹㅌ",
    "ㄹㅍ", "ㄹㅎ", "ㅁ", "ㅂ", "ㅂㅅ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"
)

// 카탈로그 표기(왼쪽)를 다른 표기(오른쪽)로도 찾을 수 있게 한다. 카탈로그 데이터는 바꾸지 않는다.
private val ALIASES = listOf(
    "아이오닉" to "ioniq",
    "아반떼" to "avante",
    "캐스퍼" to "casper",
    "gv" to "지브이",
    "ev" to "이브이",
    "k" to "케이"
)

private fun normalize(value: String): String =
    value.lowercase().filterNot { it.isWhitespace() || it == '-' || it == '_' }

private fun isSyllable(c: Char) = c in '가'..'힣'

/** 한글 음절을 초성·중성·종성으로 풉니다. 그 밖의 글자(낱자모, 영문, 숫자)는 그대로 둡니다. */
private fun jamo(value: String): String = buildString {
    for (c in value) {
        if (isSyllable(c)) {
            val i = c - '가'
            append(CHO[i / 588]).append(JUNG[i / 28 % 21]).append(JONG[i % 28])
        } else {
            append(c)
        }
    }
}

private fun chosung(value: String): String =
    value.map { if (isSyllable(it)) CHO[(it - '가') / 588] else it }.joinToString("")

/**
 * 검색어에 맞는 모델 이름을 최대 8개 돌려줍니다. 대소문자와 공백은 무시합니다.
 * 순서: ① 앞부분 일치(조합 중인 마지막 글자 포함: "아ㅇ", "앙" → 아이오닉) ② 초성 일치("ㅇㅇㅇ") ③ 중간 일치.
 * 같은 순위 안에서는 [models]의 순서를 유지합니다. 글자 없이 숫자만 있으면("5") 너무 넓어서 찾지 않습니다.
 */
fun matchModels(query: String, models: List<String>): List<String> {
    val q = normalize(query)
    if (q.none { it.isLetter() }) return emptyList()

    val jq = jamo(q)
    val choOnly = q.all { it in 'ㄱ'..'ㅎ' }

    fun rank(key: String): Int {
        val jk = jamo(key)
        return when {
            jk.startsWith(jq) -> 0
            choOnly && chosung(key).startsWith(q) -> 1
            jk.contains(jq) || (choOnly && chosung(key).contains(q)) -> 2
            else -> 3
        }
    }

    return models
        .map { name ->
            val key = normalize(name)
            val keys = listOf(key) + ALIASES.filter { key.contains(it.first) }.map { key.replace(it.first, it.second) }
            name to keys.minOf(::rank)
        }
        .filter { it.second < 3 }
        .sortedBy { it.second } // 안정 정렬: 같은 순위는 카탈로그 순서 그대로
        .take(MAX_SUGGESTIONS)
        .map { it.first }
}
