package com.autoresolve

import com.autoresolve.AnswerCheck.Intent
import com.autoresolve.AnswerCheck.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerCheckTest {

    private class Q(val text: String, val kind: Kind, val hints: List<String>, vararg val answers: String)

    // VoiceConsultActivity 의 Topic(followUp1/2)과 followUpSpec()을 그대로 옮긴 것입니다.
    // (Topic 이 Activity 안의 private enum 이라 JVM 테스트에서 직접 쓸 수 없음. 질문을 바꾸면 여기도 맞춰 주세요.)
    private val yesNo = Kind.YES_NO
    private val questions = listOf(
        Q("브레이크가 평소보다 깊게 밟히거나 소리가 나나요?", yesNo, emptyList(), "밟혀요", "소리 나요"),
        Q("지금도 같은 증상인가요?", yesNo, emptyList(), "같아요", "지금도요"),
        Q("충전기 화면에 오류 문구가 보이나요?", yesNo, emptyList(), "보여요", "안 보여"),
        Q("다른 충전기에서도 똑같은가요?", yesNo, emptyList(), "똑같아요", "매번요"),
        Q("계기판에 불은 들어오나요?", yesNo, emptyList(), "들어와요", "안 들어와"),
        Q("스마트키 배터리는 최근에 확인하셨나요?", yesNo, emptyList(), "확인했어", "안 했어", "못 봤어"),
        Q(
            "바람이 아예 안 나오나요, 아니면 온도가 안 맞나요?", Kind.CHOICE,
            listOf("바람", "안나", "아예", "온도", "안맞", "둘다", "시원", "따뜻", "미지근"), "안 나와요", "온도요"
        ),
        Q("설정을 바꿔도 똑같은가요?", yesNo, emptyList(), "똑같아요", "바꿨어요"),
        Q("타이어가 눈에 띄게 주저앉아 있나요?", yesNo, emptyList(), "주저앉았어", "있어요"),
        Q("계기판에 공기압 경고등이 켜져 있나요?", yesNo, emptyList(), "켜졌어", "켜져 있어"),
        Q(
            "주행 중에만 그런가요, 서 있을 때도 그런가요?", Kind.CHOICE,
            listOf("주행", "달릴", "운전", "서있", "정차", "멈춰", "신호", "둘다", "항상"), "주행 중에", "서 있을 때"
        ),
        Q("속도를 올리거나 핸들을 돌릴 때 더 심해지나요?", yesNo, emptyList(), "심해져요", "돌릴 때요"),
        Q("어떤 모양의 경고등인지 말씀해 주실 수 있나요?", Kind.OPEN, emptyList(), "빨간색", "엔진 모양"),
        Q(
            "경고등이 계속 켜져 있나요, 깜빡이나요?", Kind.CHOICE,
            listOf("계속", "켜져", "깜빡", "깜박", "점멸", "둘다"), "깜빡여요", "계속요"
        ),
        Q(
            "증상이 주행 중에만 생기나요, 서 있을 때도 생기나요?", Kind.CHOICE,
            listOf("주행", "달릴", "운전", "서있", "정차", "멈춰", "둘다", "항상"), "주행 중", "서 있을 때"
        ),
        Q(
            "같은 증상이 반복되나요?", yesNo, emptyList(),
            "반복돼요", "반복돼", "반복되", "반복해", "또 생겨", "매번요", "맨날 그래요", "자꾸 그래", "똑같아요"
        )
    )

    private fun check(q: Q, answer: String) = AnswerCheck.validateFollowUp(answer, q.kind, q.hints, q.text)

    @Test
    fun everyQuestionAcceptsShortAnswers() {
        assertEquals(16, questions.size)
        for (q in questions) {
            for (a in listOf("네", "아니요", "몰라요") + q.answers) {
                val r = check(q, a)
                assertTrue("'${q.text}' ← '$a' 는 유효해야 함 (${r.reason})", r.valid)
            }
        }
    }

    @Test
    fun everyQuestionRejectsFragments() {
        for (q in questions) {
            for (a in listOf("어", "음 그", "그게", "돼요", "", "...", "아아아아")) {
                val r = check(q, a)
                assertFalse("'${q.text}' ← '$a' 는 무효여야 함 (${r.reason})", r.valid)
            }
        }
    }

    @Test
    fun misheardShortWordIsStillRejected() {
        // 실제 세션에서 앞머리가 잘려 "서 있을 때도"가 "시대를"로 인식된 사례. 다시 묻는 게 맞다.
        val q = questions[14]
        assertFalse(check(q, "시대를").valid)
    }

    @Test
    fun consentStaysExplicit() {
        assertEquals(Intent.YES, AnswerCheck.judgeConsent("네").intent)
        assertEquals(Intent.YES, AnswerCheck.judgeConsent("접수해 주세요").intent)
        assertEquals(Intent.NO, AnswerCheck.judgeConsent("아니요").intent)
        assertEquals(Intent.NO, AnswerCheck.judgeConsent("보내지 마").intent)
        assertEquals(Intent.AMBIGUOUS, AnswerCheck.judgeConsent("네 근데 잠깐만").intent)
        // 후속 질문에서 새로 인정한 짧은 답은 전송 동의가 아니다.
        for (a in listOf("반복돼요", "또 생겨", "매번요", "안 했어", "확인했어", "들어와요", "똑같아요", "전달할까요")) {
            assertEquals("'$a'", Intent.AMBIGUOUS, AnswerCheck.judgeConsent(a).intent)
        }
    }
}
