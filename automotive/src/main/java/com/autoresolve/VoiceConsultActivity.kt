package com.autoresolve

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 음성 차량 상담.
 *
 * 운전자가 화면을 계속 보지 않아도 되도록 흐름을 소리로 알려 줍니다.
 *  - 마이크가 실제로 켜지면 "삐" 소리가 납니다. 이 소리가 나면 말하면 됩니다.
 *  - 말이 인식되면 낮은 "딩" 소리가 나고 AI가 바로 이어서 답합니다.
 *  - 상담이 끝나면 "서비스센터에 전달할까요?"를 물어보고 "네"라고만 해도 전달됩니다.
 *
 * 엔진은 [VoiceEngines]가 앱 전체에서 한 번만 만들어 재사용합니다.
 */
class VoiceConsultActivity : Activity() {

    companion object {
        private const val TAG = VoiceEngines.LOG_TAG
        private const val AUDIO_REQUEST = 4902

        // SDK가 요청 후 준비되기까지 약 5~6초 걸리는 것이 측정됐습니다. 안내를 말하는 동안 미리 요청해서
        // 이 시간을 겹치게 하고, 그래도 늦으면 이 시간까지 기다립니다.
        private const val READY_TIMEOUT_MS = 15000L
        private const val RECOGNITION_TIMEOUT_MS = 20000L
        private const val FINAL_RESULT_GRACE_MS = 1200L
        private const val MAX_PREARM = 1

        // STT 요청 이유 (진단 로그용)
        private const val REASON_FIRST = "최초 사전 준비"
        private const val REASON_REARM = "TTS 안내 중 종료 후 재준비"
        private const val REASON_GATE = "TTS 종료 후 openGate 재요청"
        private const val REASON_RETRY = "인식 오류 후 재시도"
        private const val REASON_CONT = "불완전 발화 이어받기"
        private const val REASON_PERM = "마이크 권한 허용 후 재요청"

        // 마이크가 열릴 때 "삐" 소리를 낼지. 이 소리가 마이크로 다시 들어가 인식을 방해하는지
        // 확인하려고 지금은 꺼 둡니다. 인식이 잘 되면 true로 바꾸세요.
        private const val UI_SETTLE_MS = 400L
        private const val READY_BEEP = false
        private const val TTS_TIMEOUT_MS = 30000L
        private const val MAX_RETRY = 2

        // 최종 결과 없이 끝났을 때, 부분 결과가 이 글자 수보다 짧으면 바로 답하지 않고 다시 묻습니다.
        // ---- 발화 이어받기: 증상 설명 중 잠깐 쉬어도 말을 이어갈 수 있게 합니다 ----
        // 최종 결과를 받은 뒤 바로 답하지 않고, 다음 인식을 켜서 이 시간 동안 추가 발화를 기다립니다.
        private const val CONT_WINDOW_MS = 3000L        // onReady 이후 추가 발화를 기다리는 시간
        private const val CONT_WINDOW_SAFETY_MS = 1500L // 브레이크·핸들·연기처럼 안전과 직결된 조각은 더 짧게 기다립니다
        private const val CONT_READY_MS = 4000L         // 다음 인식이 이 시간 안에 준비 안 되면 기존 텍스트로 진행
        private const val CONT_SPEECH_CAP_MS = 15000L   // 이어 말하기 시작한 뒤 최대 대기
        // 한 질문에서 합칠 수 있는 최대 횟수. 기본 1회: 한 번 이어받기에 성공하면 바로 제출합니다.
        // (미완성 어미일 때만 2회로 늘리는 조건부 확장은 looksUnfinished()를 shouldHold()에 연결하면 됩니다.)
        private const val CONT_MAX_MERGES = 1

        // 문장이 이어질 것 같은 끝맺음. 지금은 로그에만 씁니다. (조건부 이어받기 확장용)
        private val UNFINISHED_ENDINGS = listOf(
            "그리고", "그런데", "근데", "그래서", "그러니까", "그러면",
            "는데", "은데", "인데", "니까", "다가", "지만", "면서", "해서", "어서", "아서", "하고", "으면"
        )
        private const val CONT_TOTAL_MS = 25000L        // 한 질문에서 이어받기 전체 시간 상한

        private const val MIN_PARTIAL_CHARS = 3
        private val SHORT_ANSWERS = setOf("네", "예", "응", "어", "아니", "싫어")

        private const val MIC_HELP =
            "마이크 입력이 없어요. 에뮬레이터 Extended Controls의 Microphone에서 " +
                    "호스트 오디오 입력을 허용했는지 확인해 주세요."
    }

    // 색은 홈 화면과 같은 디자인 토큰(AppTheme.kt)을 쓴다(색만 통일, 동작은 변경 없음). 화면을 열 때의 테마가 적용된다.
    private val tk get() = AppTheme.tokens(this)
    private val navy get() = tk.brandPrimary            // 주요 버튼 채움
    private val onFill get() = tk.textOnBrand           // 채움 위 글자
    private val textMain get() = tk.textPrimary
    private val surface get() = tk.backgroundSurface
    private val listeningBlue get() = tk.interactivePressed
    private val muted get() = tk.textSecondary
    private val backgroundColor get() = tk.backgroundPrimary
    private val paleBlue get() = tk.interactiveSelectedSurface
    private val border get() = tk.borderDefault

    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null

    private lateinit var statusView: TextView
    private lateinit var liveView: TextView
    private lateinit var micButton: TextView
    private lateinit var scroll: ScrollView
    private lateinit var messagesView: LinearLayout

    // 진행 상태
    private var autoMode = false
    private var listening = false
    private var waitingFinal = false
    private var speechPending = false
    private var finished = false
    private var resultSubmitted = false
    private var autoStarted = false

    // 화면이 실제로 사용자 앞에 나타난 뒤에만 AI 안내를 시작합니다.
    private var uiShown = false

    // 발화 이어받기 상태 (세션 번호와 질문 번호로 늦게 온 콜백/타이머를 걸러냅니다)
    private var questionId = 0          // AI가 새 질문을 할 때마다 올라감
    private var contSid = 0             // 이어받기 세션 번호
    private var contActive = false      // 임시 저장된 텍스트가 있고 추가 발화를 기다리는 중
    private var contWaitReady = false   // 다음 인식의 onReady를 기다리는 중
    private var contWindowOpen = false  // onReady 후 추가 발화 대기 구간
    private var contQuestionId = -1
    private var contStartedAt = 0L
    private var contRequestedAt = 0L
    private var heldText = ""
    private var contWindowMs = CONT_WINDOW_MS
    private var mergeCount = 0

    // 이번 질문에서 STT request()를 보낸 횟수와 진단용 시각 (STT 동작에는 영향 없음)
    private var questionRequests = 0
    private val questionReasons = ArrayList<String>()
    private var questionStartSdk = 0
    private var retryArm = false
    private var endedLocalAt = 0L
    // 마지막으로 처리한 인식 결과가 "최종 결과"였는지. 부분 결과만으로는 전송 동의를 확정하지 않습니다.
    private var lastDeliveredFinal = true
    // 후속 질문 답변 검증 상태 (STT 인식 실패 retryCount와는 별도로 관리)
    private var clarifyPhase: Phase? = null
    private var clarifyCount = 0              // 같은 질문에서 "답변 내용이 불명확해서" 다시 물은 횟수
    private var unconfirmedAnswers = 0        // 두 번째에도 확인하지 못한 답변 수
    private var pendingNote = ""              // 다음 AI 응답 앞에 붙일 안내 (답변을 확인하지 못함)
    private var voiceConsent = false          // 서비스센터 전송에 대한 명확한 음성 동의가 있었는지

    // 이어받기 진단 기록 (세션별 시간과 최종 요약용)
    private var contSessionOpen = false
    private var contReadyAtAbs = 0L
    private var contSpeechAtAbs = 0L
    private var contResultAtAbs = 0L
    private var contPreReadyMax = 0.0
    private val contSessionLines = ArrayList<String>()
    private var sumFirstFinalAt = 0L
    private var sumSubmitAt = 0L
    private var sumPending = false      // 다음 TTS onStart에서 요약 이어서 기록

    private var contReadyTask: Runnable? = null
    private var contWindowTask: Runnable? = null
    private var contSpeechTask: Runnable? = null

    private var latestPartial = ""
    private var retryCount = 0
    private var readyReceived = false
    private var lastRmsUpdate = 0L

    // 안내가 끝나기 전에 미리 마이크를 요청하고, 안내가 끝나야 말을 받아들입니다(gate).
    private var gateOpen = false
    private var sessionReady = false
    private var micOpened = false

    // 오래된 STT 콜백 차단: SDK 콜백에는 세션 번호가 없어서, "우리가 요청한 이번 세션이 준비됐는지"로 거릅니다.
    private var sttRequested = false      // 이 화면이 STT를 요청했고 아직 중지하지 않음
    private var readyForRequest = false   // 이번 요청의 onReady를 받음 (요청할 때마다 초기화, 종료 신호로는 지우지 않음)
    private var closed = false            // 화면이 멈춘 뒤에는 마이크를 다시 켜지 않음
    private var staleDropped = 0
    private var triagePart: PartCategory? = null   // 위험 증상으로 확인된 부위. 접수에만 쓰입니다.
    private var safetyDetail: String? = null
    private var armCount = 0
    private var pendingSend = false
    private var prematureLogged = false

    // 단계별 시간 측정값 (Logcat 태그 AutoResolveVoice)
    private var entryAt = 0L
    private var sdkReadyLogged = false
    private var ttsDoneAt = 0L
    private var requestAt = 0L
    private var readyAt = 0L
    private var speechDetectedAt = 0L
    private var maxRms = 0.0
    private var turns = 0

    private var timeout: Runnable? = null
    private var finalGrace: Runnable? = null
    private var ttsTimeout: Runnable? = null
    private var readyTimeout: Runnable? = null

    // 대화 상태
    private enum class Phase { SYMPTOM, FOLLOW_UP_1, FOLLOW_UP_2, CONFIRM_SEND, DONE }

    private var phase = Phase.SYMPTOM
    private var topic: Topic = Topic.GENERAL
    private var initialSymptom = ""
    private var unknownCount = 0
    private var confirmAsked = 0
    private var ackIndex = 0

    private val transcript = mutableListOf<String>()
    private val sessionId = System.currentTimeMillis()

    // ---------------------------------------------------------------
    // 대화 주제. 주제를 알면 정비 접수 부품도 함께 정해집니다.
    // ---------------------------------------------------------------
    private enum class Topic(
        val part: PartCategory,
        val keywords: List<String>,
        val followUp1: String,
        val followUp2: String,
        val advice: String
    ) {
        BRAKE(
            PartCategory.BRAKE,
            listOf("브레이크", "제동", "멈추지", "안멈", "페달이"),
            "브레이크가 평소보다 깊게 밟히거나 소리가 나나요?",
            "지금도 같은 증상인가요?",
            "제동 문제는 위험해요. 안전한 곳에 세우고 운행을 멈춰 주세요. 긴급출동을 부르는 걸 권해요."
        ),
        CHARGING(
            PartCategory.BATTERY_CHARGING,
            listOf("충전"),
            "충전기 화면에 오류 문구가 보이나요?",
            "다른 충전기에서도 똑같은가요?",
            "충전기 문제인지 차 문제인지는 아직 단정할 수 없어요. 다른 충전기에서도 안 되면 차량 점검이 필요해요."
        ),
        STARTING(
            PartCategory.ELECTRICAL,
            listOf("시동", "스마트키", "키가", "시동버튼"),
            "계기판에 불은 들어오나요?",
            "스마트키 배터리는 최근에 확인하셨나요?",
            "계기판이 아예 안 켜지면 12볼트 배터리 쪽일 수 있어요. 반복되면 점검을 받아 주세요."
        ),
        AC(
            PartCategory.AIR_CONDITIONING,
            listOf("에어컨", "히터", "냉방", "난방", "바람이", "공조"),
            "바람이 아예 안 나오나요, 아니면 온도가 안 맞나요?",
            "설정을 바꿔도 똑같은가요?",
            "먼저 송풍 세기와 온도 설정을 확인해 보세요. 그래도 같으면 공조 점검을 받아 주세요."
        ),
        TIRE(
            PartCategory.TIRE,
            listOf("타이어", "공기압", "펑크", "휠"),
            "타이어가 눈에 띄게 주저앉아 있나요?",
            "계기판에 공기압 경고등이 켜져 있나요?",
            "타이어가 많이 꺼졌거나 손상됐다면 운행하지 말고 지원을 받아 주세요."
        ),
        NOISE(
            PartCategory.ENGINE,
            listOf("소음", "소리", "진동", "떨림", "덜덜", "삐걱", "엔진"),
            "주행 중에만 그런가요, 서 있을 때도 그런가요?",
            "속도를 올리거나 핸들을 돌릴 때 더 심해지나요?",
            "소리나 진동은 원인이 다양해요. 계속되면 점검을 받아 주세요."
        ),
        WARNING(
            PartCategory.ELECTRICAL,
            listOf("경고", "경고등", "불이들어", "메시지"),
            "어떤 모양의 경고등인지 말씀해 주실 수 있나요?",
            "경고등이 계속 켜져 있나요, 깜빡이나요?",
            "경고등은 종류마다 달라요. 빨간색이거나 깜빡이면 안전한 곳에 정차해 주세요."
        ),
        GENERAL(
            PartCategory.OTHER,
            emptyList(),
            "증상이 주행 중에만 생기나요, 서 있을 때도 생기나요?",
            "같은 증상이 반복되나요?",
            "증상만으로 원인을 단정하기는 어려워요. 반복되면 정비사에게 확인받는 게 좋아요."
        );

        companion object {
            fun detect(compact: String): Topic? =
                values().firstOrNull { t ->
                    t.keywords.any { compact.contains(it) }
                }
        }
    }

    private val acks = listOf("네, 알겠어요.", "그렇군요.", "확인했어요.", "알겠어요.")

    private val ttsCallbacks = object : VoiceEngines.TtsCallbacks {
        override fun onStart() {
            if (isFinishing || isDestroyed) return

            if (sumPending) {
                sumPending = false
                val now = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "[이어받기 요약] 최초 최종 결과 → TTS onStart: ${now - sumFirstFinalAt}ms " +
                            "(최종 제출 후 ${now - sumSubmitAt}ms)"
                )
            }

            setState("AI가 말하는 중", navy, "AI가 말하는 중")
        }

        override fun onDone() {
            if (isFinishing || isDestroyed || !speechPending) return

            cancelTtsTimeout()
            speechPending = false
            ttsDoneAt = SystemClock.elapsedRealtime()
            Log.d(TAG, "TTS 종료")

            // "서비스센터로 넘어갈게요" 안내를 끝까지 말한 뒤에 화면을 넘깁니다.
            if (pendingSend) {
                pendingSend = false
                handler.postDelayed({ sendAfterVoiceConsent() }, 300L)
                return
            }

            if (autoMode && !finished) {
                val q = questionId
                handler.postDelayed({ if (q == questionId && !closed) openGate() }, VoiceEngines.listenDelayMs)
            }
        }

        override fun onError(reason: String) {
            if (isFinishing || isDestroyed) return
            Log.w(TAG, reason)

            // 소리를 못 읽어도 대화가 멈추지 않게, 글자로 보여 주고 바로 듣기로 넘어갑니다.
            if (speechPending) {
                cancelTtsTimeout()
                speechPending = false
                ttsDoneAt = SystemClock.elapsedRealtime()
                statusView.text = "$reason (글자로 안내할게요)"

                if (pendingSend) {
                    pendingSend = false
                    handler.postDelayed({ sendAfterVoiceConsent() }, 800L)
                    return
                }

                if (autoMode && !finished) {
                    val q = questionId
                    handler.postDelayed({ if (q == questionId && !closed) openGate() }, VoiceEngines.listenDelayMs)
                }
            }
        }
    }

    private val sttCallbacks = object : VoiceEngines.SttCallbacks {

        override fun onReady() {
            if (closed || !sttRequested) {
                dropStale("onReady")
                return
            }

            sessionReady = true
            readyForRequest = true
            readyAt = SystemClock.elapsedRealtime()

            Log.d(TAG, "① STT 요청 → onReady: ${readyAt - requestAt}ms (안내 말하는 중이었는지: ${!gateOpen})")

            if (contActive && contWaitReady) {
                onContReady()
                return
            }

            if (gateOpen && listening) markMicOpen("onReady")
        }

        override fun onStarted() {
            if (closed || !sttRequested) {
                dropStale("onStarted")
                return
            }

            Log.d(TAG, "onStartedRecognition (gate=$gateOpen)")
        }

        override fun onUpdated(text: String, completed: Boolean) {
            // 이전 질문·이전 화면의 늦은 결과가 지금 질문의 답으로 들어오지 않게 합니다.
            if (closed || !sttRequested || !readyForRequest) {
                dropStale(if (completed) "onUpdated(최종)" else "onUpdated(부분)")
                return
            }

            // 안내를 말하는 동안 들어온 소리는 안내 자신의 소리일 수 있어서 버립니다.
            if (!gateOpen) {
                Log.d(TAG, "안내 중 인식 결과는 버림 (${text.trim().length}글자)")
                return
            }

            if (resultSubmitted || (!listening && !waitingFinal)) {
                Log.w(
                    TAG,
                    "[늦은 결과 버림] ${if (completed) "최종" else "부분"} 글자 ${text.trim().length} " +
                            "(이미 제출함=$resultSubmitted) — 이 세션의 결과는 이미 처리됐어요."
                )
                return
            }

            val phrase = text.trim()
            if (phrase.isEmpty()) return

            if (completed && endedLocalAt > 0L) {
                val late = SystemClock.elapsedRealtime() - endedLocalAt
                Log.i(
                    TAG,
                    "[진단·종료 후 최종 결과] 질문 $questionId: 종료 신호 후 ${late}ms 만에 최종 결과 도착 " +
                            "(유예 ${FINAL_RESULT_GRACE_MS}ms ${if (late <= FINAL_RESULT_GRACE_MS) "안" else "밖"}, ${phrase.length}글자)"
                )
            }

            markSpeechDetected("onUpdated")

            latestPartial = phrase
            liveView.text = "인식 중: $phrase"

            if (completed) submitText(phrase)
        }

        override fun onEpd(on: Long, off: Long) {
            if (closed || !sttRequested || !readyForRequest) return
            if (gateOpen && listening) markSpeechDetected("EPD on=$on off=$off")
        }

        override fun onRms(rms: Double) {
            if (closed || !sttRequested) return
            if (rms > maxRms) maxRms = rms

            // 이어받기 준비 전에 들어온 소리(그 말은 인식에서 빠졌을 수 있음)를 기록합니다.
            if (contActive && contWaitReady && rms > contPreReadyMax) contPreReadyMax = rms

            // 마이크가 열리기 전에 소리가 들어오면 그 말은 인식에서 빠질 수 있습니다.
            if (rms > 1.0 && !micOpened && gateOpen && !prematureLogged) {
                prematureLogged = true
                Log.w(TAG, "마이크가 준비되기 전에 소리가 들어왔어요(rms=$rms). 그 말은 인식되지 않을 수 있어요.")
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastRmsUpdate < 400L) return
            lastRmsUpdate = now

            if (gateOpen && listening && latestPartial.isBlank() &&
                !resultSubmitted && micOpened
            ) {
                statusView.text = "듣고 있어요 · 마이크 입력 %.0f".format(rms)
            }
        }

        override fun onEnded() {
            // 이번 요청이 준비되기 전에 온 종료 신호는 이전 세션의 것입니다. 준비 타임아웃이 안전망입니다.
            if (closed || !sttRequested || !readyForRequest) {
                dropStale("onEnded")
                return
            }

            val now = SystemClock.elapsedRealtime()
            val afterReady = if (readyAt > 0) now - readyAt else -1L

            Log.d(
                TAG,
                "STT 종료 신호: onReady 후 ${afterReady}ms, 최대 마이크 입력 $maxRms, " +
                        "인식된 글자 ${latestPartial.length}, gate=$gateOpen"
            )

            sessionReady = false
            endedLocalAt = now

            // 안내를 말하는 중에 인식 세션이 끝난 경우: 안내가 끝날 때까지 다시 준비해 둡니다.
            if (!gateOpen) {
                if (speechPending && armCount < MAX_PREARM) {
                    armCount++
                    handler.postDelayed({
                        if (!closed && speechPending && !gateOpen) armStt(REASON_REARM)
                    }, 350L)
                }
                return
            }

            if (resultSubmitted || (!listening && !waitingFinal)) return

            listening = false
            waitingFinal = true

            finalGrace?.let(handler::removeCallbacks)

            val task = Runnable {
                if (!resultSubmitted && waitingFinal) {
                    if (latestPartial.isNotBlank()) {
                        Log.w(TAG, "최종 결과 없이 종료 → 유예 ${FINAL_RESULT_GRACE_MS}ms 지남, 부분 결과(${latestPartial.length}글자)로 진행 시도")
                        submitPartialOrRetry()
                    } else {
                        handleRecognitionProblem(
                            if (afterReady in 0..2000 && maxRms <= 0.0) {
                                "마이크가 열리자마자 인식이 끝났어요. 마이크 소리가 전혀 들어오지 않았어요."
                            } else {
                                "들린 말이 없었어요."
                            }
                        )
                    }
                }
            }
            finalGrace = task
            handler.postDelayed(task, FINAL_RESULT_GRACE_MS)
        }

        override fun onError(reason: String) {
            if (closed || !sttRequested) {
                dropStale("onError($reason)")
                return
            }

            sessionReady = false

            if (!gateOpen) {
                Log.w(TAG, "안내 중 인식 오류: $reason")
                if (speechPending && armCount < MAX_PREARM) {
                    armCount++
                    handler.postDelayed({
                        if (!closed && speechPending && !gateOpen) armStt(REASON_REARM)
                    }, 350L)
                }
                return
            }

            if (!resultSubmitted) handleRecognitionProblem(reason)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        entryAt = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "빌드 표식 ${VoiceEngines.BUILD_MARK} · VoiceConsultActivity 시작 " +
                    "(READY_BEEP=$READY_BEEP, ${VoiceDiag.summary()})"
        )

        buildUi()
        Log.i(TAG, "[진단·TTS 엔진] ${VoiceEngines.ttsEngineSummary()}")
        LifecycleTrace.probe(this, "onCreate")

        try {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator unavailable", e)
        }

        addMessage(false, "안녕하세요. 차량에 어떤 문제가 있으세요?")

        // 홈에서 미리 켜 두었다면 바로 준비 상태입니다.
        if (!VoiceDiag.uiOnly) VoiceEngines.warmUp(applicationContext)
    }

    override fun onTopResumedActivityChanged(isTopResumed: Boolean) {
        super.onTopResumedActivityChanged(isTopResumed)
        Log.i(TAG, "[최상단 화면] VoiceConsultActivity isTopResumed=$isTopResumed")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.i(TAG, "[창 포커스 변경] VoiceConsultActivity hasFocus=$hasFocus")
    }

    override fun onResume() {
        super.onResume()

        if (!uiShown) {
            LifecycleTrace.probe(this, "onResume")
            // 첫 화면이 그려지고 창이 자리 잡을 시간을 조금 준 뒤 안내를 시작합니다.
            window.decorView.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                uiShown = true
                Log.i(
                    TAG,
                    "[화면 표시 확인] 안내 시작 허용 (포커스=${hasWindowFocus()}, 진입 후 ${SystemClock.elapsedRealtime() - entryAt}ms)"
                )
                onEnginesChanged()
            }, UI_SETTLE_MS)
        }
    }

    override fun onStart() {
        super.onStart()
        closed = false

        VoiceEngines.sttCallbacks = sttCallbacks
        VoiceEngines.ttsCallbacks = ttsCallbacks
        VoiceEngines.onStateChanged = { onEnginesChanged() }

        onEnginesChanged()
    }

    // 원인 분리용 진단 옵션. 항목을 누르면 바뀌고 다시 목록이 열립니다.
    private fun showDiagDialog() {
        val items = arrayOf(
            "TTS(안내 음성): ${if (VoiceDiag.ttsEnabled) "켜짐" else "꺼짐"}",
            "STT(음성 인식): ${if (VoiceDiag.sttEnabled) "켜짐" else "꺼짐"}",
            "TTS 엔진: ${VoiceEngines.ttsEngineSummary()}",
            "Android TTS 소리 용도: ${VoiceDiag.ttsUsage.label}"
        )

        AlertDialog.Builder(this)
            .setTitle("진단 옵션 · ${VoiceEngines.BUILD_MARK}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> VoiceDiag.ttsEnabled = !VoiceDiag.ttsEnabled
                    1 -> VoiceDiag.sttEnabled = !VoiceDiag.sttEnabled
                    2 -> VoiceDiag.androidTts = !VoiceDiag.androidTts
                    3 -> {
                        val all = VoiceDiag.TtsUsage.values()
                        VoiceDiag.ttsUsage = all[(VoiceDiag.ttsUsage.ordinal + 1) % all.size]
                    }
                }
                Log.i(TAG, "[진단 옵션] ${VoiceDiag.summary()}")
                showDiagDialog()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    // 엔진이 준비되면 버튼을 한 번 더 누르지 않아도 바로 상담을 시작합니다.
    private fun onEnginesChanged() {
        if (isFinishing || isDestroyed) return

        if (VoiceDiag.uiOnly) {
            // 진단: UI만 표시. TTS/STT/엔진을 전혀 시작하지 않습니다.
            statusView.text = "진단: UI만 표시 중 (TTS·STT 시작 안 함) [${VoiceEngines.BUILD_MARK}]"
            return
        }

        if (!uiShown) {
            statusView.text = "화면을 준비하는 중..."
            return
        }

        if (VoiceEngines.ready) {
            if (!sdkReadyLogged) {
                sdkReadyLogged = true
                Log.d(TAG, "⓪ 화면 진입 → SDK 초기화 완료: ${SystemClock.elapsedRealtime() - entryAt}ms")
            }

            if (!autoMode && !finished && !autoStarted && phase == Phase.SYMPTOM) {
                autoStarted = true
                beginConsultation()
            } else if (!autoMode) {
                statusView.text = "준비 완료"
                updateButton()
            }
        } else {
            statusView.text = "음성 엔진을 준비하는 중..."
        }
    }

    // ---------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------

    // 창 크기에 비례하는 화면 배율(기준 창 1682x1210dp에서 1.0). 홈 화면과 같은 기준이다.
    private fun uiScale(): Float {
        val c = resources.configuration
        val w = if (c.screenWidthDp > 0) c.screenWidthDp else 1682
        val h = if (c.screenHeightDp > 0) c.screenHeightDp else 1210
        return minOf(w / 1682f, h / 1210f).coerceIn(0.6f, 2.5f)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density * uiScale()).toInt().coerceAtLeast(1)

    // 예전 글자 크기 값을 시안 ⑬의 글자 단계(48/42/38/36/34/30/28)로 맞춘다.
    private fun typeStep(size: Float): Float = when {
        size >= 23f -> 48f
        size >= 22f -> 42f
        size >= 21f -> 38f
        size >= 19.5f -> 36f
        size >= 18.5f -> 34f
        size >= 17f -> 30f
        else -> 28f
    }

    private fun shape(color: Int, outline: Int? = null, radiusDp: Int = 11): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (outline != null) setStroke(dp(2), outline)
        }

    private fun text(
        value: String,
        size: Float = 20f,
        bold: Boolean = false,
        color: Int = textMain
    ): TextView = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, typeStep(size) * uiScale())
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun button(
        label: String,
        primary: Boolean = false,
        size: Float = 20f,
        action: () -> Unit
    ): TextView = text(label, size, true, if (primary) onFill else textMain).apply {
        gravity = Gravity.CENTER
        background = shape(
            if (primary) navy else surface,
            if (primary) null else border,
            if (primary) 60 else 52
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    // "‹  [집 아이콘 28dp]  홈" 버튼. 정비 접수 화면의 "‹ 홈"(흰 바탕, 테두리, 둥근 모서리 12dp, 굵은 남색 글씨)과 같은 모양이고
    // 높이만 64dp로 키웠습니다. 누르면 상담을 정리(STT/TTS 중지)하고 이 화면을 닫아 홈(MainActivity)으로 돌아갑니다.
    private fun homeButton(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = dp(64)
        setPadding(dp(10), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), border)
        }
        isClickable = true
        isFocusable = true
        contentDescription = "홈"

        addView(text("‹", 26f, true).apply { setPadding(0, 0, dp(8), 0) })

        addView(
            ImageView(this@VoiceConsultActivity).apply {
                setImageDrawable(HouseDrawable(textMain, surface))
            },
            LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(8) }
        )

        addView(text("홈", 21f, true))

        setOnClickListener {
            Log.i(TAG, "[홈] 버튼 → 상담 정리 후 홈으로")
            stopConsultation()
            finish()
        }
    }

    // 집 모양을 코드로 그립니다. (별도 이미지 파일 없이 색을 앱 색상에 맞춤)
    private class HouseDrawable(private val color: Int, private val doorColor: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        override fun draw(canvas: Canvas) {
            val l = bounds.left.toFloat()
            val t = bounds.top.toFloat()
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()

            paint.color = color

            val roof = Path().apply {
                moveTo(l + w * 0.5f, t + h * 0.05f)
                lineTo(l + w * 0.99f, t + h * 0.50f)
                lineTo(l + w * 0.01f, t + h * 0.50f)
                close()
            }
            canvas.drawPath(roof, paint)
            canvas.drawRect(l + w * 0.17f, t + h * 0.46f, l + w * 0.83f, t + h * 0.95f, paint)

            paint.color = doorColor
            canvas.drawRect(l + w * 0.42f, t + h * 0.62f, l + w * 0.58f, t + h * 0.95f, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun buildUi() {
        // outer: 맨 위 제목줄(홈 버튼 + 제목) + 아래 상담 내용(root)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
            setPadding(dp(40), dp(8), dp(40), dp(24))
        }

        // 제목줄(시안 ⑬): 왼쪽 원형 뒤로(홈) 버튼 · 제목 48 · 오른쪽 "홈" 버튼. 둘 다 상담을 정리하고 홈으로 돌아간다.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(40), dp(24), dp(40), dp(8))
            setBackgroundColor(backgroundColor)
        }

        val goHome = {
            Log.i(TAG, "[홈] 버튼 → 상담 정리 후 홈으로")
            stopConsultation()
            finish()
        }

        val backBtn = FrameLayout(this).apply {
            background = shape(surface, border, 44)
            isClickable = true
            isFocusable = true
            contentDescription = "홈"
            setOnClickListener { goHome() }
            addView(
                ImageView(this@VoiceConsultActivity).apply {
                    setImageResource(R.drawable.ic_chev_left)
                    setColorFilter(textMain)
                },
                FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER)
            )
        }
        header.addView(backBtn, LinearLayout.LayoutParams(dp(88), dp(88)))

        header.addView(
            text("차량 음성 상담", 23f, true).apply {
                // 맨 위 제목을 더 두껍게(굵은 글꼴 + 글자 둘레 선)
                paint.style = Paint.Style.FILL_AND_STROKE
                paint.strokeWidth = 0.9f * resources.displayMetrics.density * uiScale()
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), 0, 0, 0)
                // 제목을 길게 누르면 원인 분리용 진단 옵션이 열립니다.
                setOnLongClickListener {
                    showDiagDialog()
                    true
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val homePill = text("홈", 19f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(40), 0, dp(40), 0)
            background = shape(surface, border, 44)
            isClickable = true
            isFocusable = true
            contentDescription = "홈"
            setOnClickListener { goHome() }
        }
        header.addView(homePill, LinearLayout.LayoutParams(-2, dp(88)))

        outer.addView(header, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        outer.addView(root, LinearLayout.LayoutParams(-1, 0, 1f))

        // 실제로 화면에 배치·표시되는지 한 번 기록합니다. (그려지지 않음/가림/잘림을 구분하려는 점검)
        header.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (header.width == 0) return
                header.viewTreeObserver.removeOnGlobalLayoutListener(this)

                val d = resources.displayMetrics.density
                val home = header.getChildAt(0)
                val title = header.getChildAt(1)

                val loc = IntArray(2)
                home.getLocationOnScreen(loc)
                val visible = android.graphics.Rect()
                val shown = home.getGlobalVisibleRect(visible)

                Log.i(
                    TAG,
                    "[홈 버튼 점검] 빌드 ${VoiceEngines.BUILD_MARK} · 제목줄 ${header.width}x${header.height}px · " +
                            "홈 버튼 ${home.width}x${home.height}px (${(home.width / d).toInt()}x${(home.height / d).toInt()}dp) " +
                            "보임=${home.visibility} 왼쪽=${home.left} 오른쪽=${home.right} · " +
                            "화면 좌표=(${loc[0]},${loc[1]}) 실제 보이는 영역=${if (shown) visible.toShortString() else "없음"} · " +
                            "제목 ${title.width}x${title.height}px 왼쪽=${title.left} · " +
                            "창 ${window.decorView.width}x${window.decorView.height}px, " +
                            "화면 ${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}px 밀도=$d"
                )
            }
        })

        statusView = text("음성 엔진을 준비하는 중... [${VoiceEngines.BUILD_MARK}]", 17f, false, muted)
        root.addView(
            statusView,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        )

        micButton = button("음성 상담 시작", true, 22f) { onMicClicked() }
        getDrawable(R.drawable.ic_mic)?.mutate()?.let { d ->
            d.setTint(onFill)
            d.setBounds(0, 0, dp(48), dp(48))
            micButton.setCompoundDrawablesRelative(d, null, null, null)
            micButton.compoundDrawablePadding = dp(20)
        }
        micButton.setPadding(dp(40), 0, dp(40), 0)
        root.addView(
            micButton,
            LinearLayout.LayoutParams(-1, dp(120)).apply { bottomMargin = dp(22) }
        )

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        actions.addView(
            button("처음부터") { restartConsultation() },
            LinearLayout.LayoutParams(0, dp(104), 1f).apply { rightMargin = dp(10) }
        )
        actions.addView(
            button("상담 기록") { showHistory() },
            LinearLayout.LayoutParams(0, dp(104), 1f).apply {
                leftMargin = dp(10); rightMargin = dp(10)
            }
        )
        actions.addView(
            button("정비 안내") { showRepairInformation() },
            LinearLayout.LayoutParams(0, dp(104), 1f).apply { leftMargin = dp(10) }
        )

        root.addView(
            actions,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) }
        )

        liveView = text("준비되면 자동으로 시작해요.", 18f, false, muted).apply {
            background = shape(paleBlue, tk.interactiveActive, 28)
            setPadding(dp(32), dp(20), dp(32), dp(20))
        }
        root.addView(
            liveView,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) }
        )

        scroll = ScrollView(this).apply {
            isFillViewport = true
            background = shape(surface, border, 32)
        }

        messagesView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(36), dp(36), dp(36))
        }
        scroll.addView(messagesView)

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(outer)
    }

    private fun setState(status: String, buttonColor: Int, buttonLabel: String) {
        statusView.text = status
        micButton.text = buttonLabel
        micButton.background = shape(buttonColor)
        micButton.setTextColor(onFill)
    }

    private fun updateButton() {
        micButton.background = shape(navy)
        micButton.setTextColor(onFill)
        micButton.text = when {
            finished -> "새 상담 시작"
            autoMode -> "음성 상담 중지"
            else -> "음성 상담 시작"
        }
    }

    private fun addMessage(user: Boolean, message: String) {
        val who = if (user) "운전자" else "H.WITH"

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(24), dp(36), dp(24))
            background = if (user) shape(navy, null, 32) else shape(backgroundColor, border, 32)
        }

        bubble.addView(text(who, 15f, true, if (user) onFill else muted))
        bubble.addView(text(message, 21f, false, if (user) onFill else textMain).apply { setPadding(0, dp(6), 0, 0) })

        messagesView.addView(
            bubble,
            LinearLayout.LayoutParams(-2, -2).apply {
                bottomMargin = dp(24)
                gravity = if (user) Gravity.END else Gravity.START
            }
        )

        transcript.add("$who: $message")
        saveHistory()

        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun beep(toneType: Int, ms: Int) {
        try {
            tone?.startTone(toneType, ms)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------
    // 기록
    // ---------------------------------------------------------------

    private var saveJob: Runnable? = null

    // 메시지마다 바로 저장하면 화면이 멈칫할 수 있어서, 잠깐 모아 한 번만 저장합니다.
    private fun saveHistory() {
        saveJob?.let(handler::removeCallbacks)

        val job = Runnable { writeHistory() }
        saveJob = job
        handler.postDelayed(job, 600L)
    }

    private fun writeHistory() {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
            .format(Date(sessionId))

        val content = "[$date] H.WITH 음성 상담\n\n" +
                transcript.joinToString("\n")

        getSharedPreferences("autoresolve_voice_history", MODE_PRIVATE)
            .edit()
            .putString("session_$sessionId", content)
            .apply()
    }

    // ---------------------------------------------------------------
    // 타이머
    // ---------------------------------------------------------------

    private fun clearTimers() {
        timeout?.let(handler::removeCallbacks)
        finalGrace?.let(handler::removeCallbacks)
        ttsTimeout?.let(handler::removeCallbacks)
        readyTimeout?.let(handler::removeCallbacks)

        timeout = null
        finalGrace = null
        ttsTimeout = null
        readyTimeout = null
    }

    private fun cancelTtsTimeout() {
        ttsTimeout?.let(handler::removeCallbacks)
        ttsTimeout = null
    }

    // ---------------------------------------------------------------
    // 연속 상담 흐름
    // ---------------------------------------------------------------

    private fun onMicClicked() {
        when {
            finished -> restartConsultation()
            autoMode -> stopConsultation()
            !VoiceEngines.ready -> {
                statusView.text = "음성 엔진을 준비하는 중이에요. 잠시만요."
                VoiceEngines.warmUp(applicationContext)
            }
            else -> beginConsultation()
        }
    }

    private fun beginConsultation() {
        autoMode = true
        updateButton()

        speakAndThenListen(
            if (phase == Phase.SYMPTOM && transcript.size <= 1) {
                "어떤 문제가 있으세요?"
            } else {
                "이어서 말씀해 주세요."
            }
        )
    }

    private fun restartConsultation() {
        stopConsultation()

        phase = Phase.SYMPTOM
        topic = Topic.GENERAL
        initialSymptom = ""
        triagePart = null
        unknownCount = 0
        confirmAsked = 0
        finished = false
        retryCount = 0

        addMessage(false, "처음부터 다시 들을게요. 어떤 문제가 있으세요?")

        autoMode = true
        updateButton()
        speakAndThenListen("어떤 문제가 있으세요?")
    }

    private fun speakAndThenListen(message: String) {
        if (!autoMode && !finished) return

        clearTimers()

        if (questionId > 0) {
            Log.i(
                TAG,
                "[진단·질문별 요청 요약] 질문 $questionId: 앱 시도 ${questionRequests}회 " +
                        "(${questionReasons.joinToString(" / ")}), SDK request() 실제 호출 ${VoiceEngines.sdkRequestCount - questionStartSdk}회 " +
                        "(MIN_RESTART_GAP로 미뤄진 요청은 이 시점에 빠졌을 수 있어요)"
            )
        }
        questionRequests = 0
        questionReasons.clear()
        questionStartSdk = VoiceEngines.sdkRequestCount
        val armReason = if (retryArm) REASON_RETRY else REASON_FIRST
        retryArm = false

        // 새 질문이 시작되면 이전 질문의 이어받기는 끝냅니다. (다른 질문의 답이 섞이지 않게)
        questionId++
        voiceConsent = false
        resetContinuation()

        speechPending = true
        gateOpen = false
        listening = false
        waitingFinal = false
        resultSubmitted = false
        latestPartial = ""
        micOpened = false
        prematureLogged = false
        armCount = 0

        setState("AI가 말하는 중", navy, "AI가 말하는 중")
        liveView.text = "말이 끝나면 '삐' 소리가 나요. 그때 말씀하세요."

        VoiceEngines.speak(message)

        // 안내를 말하는 동안 마이크를 미리 준비합니다. (준비에 몇 초 걸려서, 말과 겹치게 해 대기를 줄임)
        armStt(armReason)

        val task = Runnable {
            if (speechPending) {
                speechPending = false
                ttsDoneAt = SystemClock.elapsedRealtime()
                Log.w(TAG, "TTS 종료 신호가 오지 않아 계속 진행합니다.")

                if (autoMode && !finished) openGate()
            }
        }
        ttsTimeout = task
        handler.postDelayed(task, TTS_TIMEOUT_MS)
    }

    // 안내를 끝까지 말한 뒤에 센터 화면으로 넘어갑니다. (말이 끝나는 신호가 안 오면 8초 뒤에 넘어감)
    private fun speakThenSend(line: String) {
        clearTimers()
        resetContinuation()

        pendingSend = true
        speechPending = true
        gateOpen = false

        VoiceEngines.speak(line)

        val task = Runnable {
            if (pendingSend) {
                pendingSend = false
                speechPending = false
                sendAfterVoiceConsent()
            }
        }
        ttsTimeout = task
        handler.postDelayed(task, 8000L)
    }

    // 마이크 세션을 미리 요청합니다. 켜져 있는 세션이 있으면 먼저 닫고 새로 엽니다.
    private fun armStt(reason: String): Boolean {
        if (closed || isFinishing || isDestroyed) {
            Log.w(TAG, "[STT 요청 건너뜀] 화면이 닫히는 중이라 마이크를 켜지 않아요.")
            return false
        }

        if (!VoiceDiag.sttEnabled) {
            Log.d(TAG, "[진단] STT 꺼짐 → 마이크를 요청하지 않음")
            return false
        }

        if (!VoiceEngines.sttReady) {
            Log.w(TAG, "STT가 아직 준비되지 않아 미리 요청하지 못했어요.")
            return false
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_REQUEST)
            return false
        }

        val activeBefore = VoiceEngines.sttSessionActive
        if (VoiceEngines.sttSessionActive) {
            VoiceEngines.abortListening("새 질문을 위해 이전 세션 정리")
        }

        sessionReady = false
        readyForRequest = false
        sttRequested = false
        maxRms = 0.0
        speechDetectedAt = 0L
        readyAt = 0L
        requestAt = SystemClock.elapsedRealtime()

        endedLocalAt = 0L
        val ok = VoiceEngines.requestListening("질문 $questionId·$reason")
        if (!ok) Log.e(TAG, "STT request 실패")
        sttRequested = ok

        if (ok) {
            questionRequests++
            questionReasons.add(reason)
            val tag = when (reason) {
                REASON_FIRST -> "최초 요청"
                REASON_RETRY -> "재시도 요청"
                REASON_CONT -> "이어받기 요청"
                else -> "복구 재시작"
            }
            // 이 줄은 '앱이 요청을 시도했다'는 기록입니다. SDK request()가 실제로 나갔는지는 [진단·SDK 요청]/[진단·세션]을 보세요.
            Log.i(
                TAG,
                "[진단·$tag] 질문 $questionId · 앱 시도 ${questionRequests}회째 · 원인=$reason · " +
                        "TTS 재생중=${VoiceEngines.isSpeaking} · gateOpen=$gateOpen · sttSessionActive(시도 전)=$activeBefore · " +
                        "직전 stop() 후 ${VoiceEngines.msSinceLastStop()}ms · 시각=${SystemClock.elapsedRealtime()}"
            )
        }
        return ok
    }

    // 안내가 끝났으니 지금부터의 말을 받습니다.
    private fun openGate() {
        if (closed || !autoMode || finished || isFinishing || isDestroyed) return

        if (!VoiceDiag.sttEnabled) {
            // 진단: STT 없이 안내만 재생해 보는 경우
            autoMode = false
            updateButton()
            statusView.text = "진단: STT 꺼짐 · 안내만 재생했어요"
            return
        }

        gateOpen = true
        listening = true
        waitingFinal = false
        resultSubmitted = false
        latestPartial = ""

        val ok = when {
            VoiceEngines.sttSessionActive -> true
            // 안내 중에 세션이 끝났거나 아직 요청하지 못한 경우
            else -> armStt(REASON_GATE)
        }

        if (!ok && checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            listening = false
            gateOpen = false
            recognitionFailed("마이크를 켜지 못했어요.")
            return
        }

        if (sessionReady) {
            markMicOpen("미리 준비됨")
        } else {
            setState("마이크 연결 중...", listeningBlue, "연결 중...")
            liveView.text = "마이크가 준비되면 '삐' 소리가 나요."

            val readyTask = Runnable {
                if (listening && gateOpen && !sessionReady && !resultSubmitted) {
                    handleRecognitionProblem("마이크 준비가 ${READY_TIMEOUT_MS / 1000}초 안에 끝나지 않았어요.")
                }
            }
            readyTimeout = readyTask
            handler.postDelayed(readyTask, READY_TIMEOUT_MS)
        }
    }

    // 마이크가 실제로 말을 받을 수 있는 순간입니다. 소리로 알립니다.
    private fun markMicOpen(source: String) {
        if (micOpened) return
        micOpened = true

        val now = SystemClock.elapsedRealtime()

        Log.d(
            TAG,
            "★ 안내 종료 → 말할 수 있음($source): ${now - ttsDoneAt}ms " +
                    "(요청 ${requestAt - ttsDoneAt}ms, 안내 종료 기준)"
        )

        readyTimeout?.let(handler::removeCallbacks)
        readyTimeout = null

        if (READY_BEEP) beep(ToneGenerator.TONE_PROP_BEEP, 140)
        setState("말씀하세요", listeningBlue, "말씀하세요")
        liveView.text = "안내가 끝난 뒤 ${now - ttsDoneAt}ms 만에 마이크가 열렸어요."

        val task = Runnable {
            if ((listening || waitingFinal) && !resultSubmitted) {
                if (latestPartial.isNotBlank()) {
                    submitPartialOrRetry()
                } else {
                    handleRecognitionProblem("말씀이 들리지 않았어요.")
                }
            }
        }
        timeout = task
        handler.postDelayed(task, RECOGNITION_TIMEOUT_MS)
    }

    private fun markSpeechDetected(source: String) {
        if (speechDetectedAt != 0L) return

        speechDetectedAt = SystemClock.elapsedRealtime()

        if (contActive && contWindowOpen) onContSpeech(source)

        Log.d(
            TAG,
            "② onReady → 음성 감지($source): " +
                    "${if (readyAt > 0) speechDetectedAt - readyAt else -1}ms"
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == AUDIO_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                armStt(REASON_PERM)
                if (gateOpen) openGate()
            } else {
                recognitionFailed("마이크 권한이 필요해요.")
            }
        }
    }

    // 늦게 도착한 콜백을 걸러 낸 횟수를 남깁니다. (SDK가 세션 번호를 주지 않아 순서로 판단합니다)
    private fun dropStale(kind: String) {
        staleDropped++
        Log.w(
            TAG,
            "[오래된 STT 콜백 차단] $kind (요청됨=$sttRequested, 이번 요청 준비됨=$readyForRequest, 화면 닫힘=$closed, " +
                    "누적 ${staleDropped}회, 마지막 종료 신호 후 ${if (endedLocalAt > 0L) SystemClock.elapsedRealtime() - endedLocalAt else -1}ms)"
        )
    }

    private fun stopRecognition(reason: String) {
        sttRequested = false
        listening = false
        waitingFinal = false
        gateOpen = false

        // 켜진 세션이 있을 때만 stop()을 부르고, 이유를 로그에 남깁니다.
        VoiceEngines.abortListening(reason)
    }

    // 최종 결과 없이 부분 결과만 있을 때: 너무 짧으면(1~2글자) 바로 답하지 않고 기존 재질문 경로로 보냅니다.
    private fun submitPartialOrRetry() {
        val phrase = latestPartial.trim()
        val chars = phrase.count { !it.isWhitespace() }

        if (chars < MIN_PARTIAL_CHARS && phrase !in SHORT_ANSWERS) {
            Log.w(TAG, "부분 결과가 너무 짧아요(${chars}글자) → 바로 답하지 않고 다시 물어요")
            handleRecognitionProblem("말씀이 너무 짧게만 들렸어요.")
            return
        }

        submitText(phrase, false)
    }

    private fun submitText(phrase: String, isFinal: Boolean = true) {
        if (phrase.isBlank() || resultSubmitted) return
        lastDeliveredFinal = isFinal

        var text = phrase

        // 이어받기 중이던 질문이면 이전에 저장해 둔 텍스트와 합칩니다. (같은 질문일 때만)
        if (contActive) {
            if (contQuestionId != questionId) {
                Log.w(TAG, "[이어받기 종료] 질문이 바뀌어 저장된 텍스트를 버려요 (저장 질문 $contQuestionId, 현재 $questionId)")
                resetContinuation()
            } else {
                contResultAtAbs = SystemClock.elapsedRealtime()
                closeContSession("추가 발화 최종 결과 도착")
                text = mergeHeld(phrase)
            }
        }

        // 증상을 자유롭게 설명하는 단계에서는 바로 답하지 않고 추가 발화를 잠깐 기다립니다.
        if (shouldHold(text)) {
            resultSubmitted = true
            startContinuation(text)
            return
        }

        deliverText(text)
    }

    // 이어받기를 해도 되는 경우: 증상 설명 단계이고, 위험/명령 표현이 아니고, 횟수·시간 상한 안이며,
    // 발화가 실제로 중간에 끊긴 것으로 보일 때만입니다. (완결된 발화는 기다리지 않고 바로 처리)
    private fun shouldHold(text: String): Boolean {
        val followUp = phase == Phase.FOLLOW_UP_1 || phase == Phase.FOLLOW_UP_2
        if ((phase != Phase.SYMPTOM && !followUp) || !autoMode || finished || pendingSend || speechPending) return false
        if (!VoiceDiag.sttEnabled || VoiceDiag.uiOnly) return false
        if (mergeCount >= CONT_MAX_MERGES) return false
        if (contActive && SystemClock.elapsedRealtime() - contStartedAt >= CONT_TOTAL_MS) return false

        // 명령은 기다리지 않고 바로 처리합니다.
        val v = compact(text)
        if (hasAny(v, "잘못들", "잘못인식", "다시말", "아니그게", "서비스센터", "처음부터", "상담종료", "그만")) return false

        // 위험 증상 여부와 증상 정보의 충분성은 공용 분류(SymptomTriage)로 봅니다.
        val triage = triageOf(text)
        // 후속 질문 단계는 접속어·끊긴 문장만 기다리고, 네/아니요와 완결 문장은 바로 처리합니다.
        val reason = if (followUp) VoiceDecision.followUpHoldReason(text, triage) else VoiceDecision.holdReason(text, triage)

        if (reason == null) {
            Log.i(
                TAG,
                "[이어받기 생략] 질문 $questionId(${phase.name}): ${text.trim().length}글자, 위험도=${triage.severity.name}, " +
                        "분류=${triage.urgency.wire} → 추가 대기 없이 바로 처리"
            )
            return false
        }

        Log.i(TAG, "[이어받기 필요] 질문 $questionId(${phase.name}): $reason → 다음 인식을 켜서 이어받기")
        return true
    }

    private fun isEvOrPhev(): Boolean {
        val profile = VehicleProfileStore(this).load()
        return profile != null && profile.powertrain in listOf(VehicleCatalog.EV, VehicleCatalog.PHEV)
    }

    // 음성 상담도 앱의 다른 곳과 같은 공용 분류(SymptomTriage)를 씁니다.
    private fun triageOf(text: String, partHint: PartCategory? = null): TriageResult =
        SymptomTriage.analyze(text, partHint, isEvOrPhev())

    private fun mergeHeld(newText: String): String {
        mergeCount++
        val merged = (heldText.trim() + " " + newText.trim()).trim()
        Log.i(
            TAG,
            "[텍스트 병합] 질문 $questionId · 이어받기 #$contSid: 이전 ${heldText.length}글자 + 새 ${newText.trim().length}글자 " +
                    "= ${merged.length}글자 (합친 횟수 $mergeCount/$CONT_MAX_MERGES)"
        )
        return merged
    }

    // 최종 결과를 임시 저장하고, 다음 인식을 켜서 추가 발화를 기다립니다. (AI 응답은 아직 시작하지 않음)
    private fun startContinuation(text: String) {
        val now = SystemClock.elapsedRealtime()

        if (!contActive) {
            contActive = true
            contStartedAt = now
            contQuestionId = questionId
        }

        // 이전 이어받기 세션의 대기 타이머가 남아 있으면 정리합니다.
        contWindowTask?.let(handler::removeCallbacks)
        contSpeechTask?.let(handler::removeCallbacks)
        contWindowTask = null
        contSpeechTask = null

        contSid++
        contSessionOpen = true
        contReadyAtAbs = 0L
        contSpeechAtAbs = 0L
        contResultAtAbs = 0L
        contPreReadyMax = 0.0

        heldText = text
        contWindowMs = if (VoiceDecision.mentionsSafetySubject(text)) CONT_WINDOW_SAFETY_MS else CONT_WINDOW_MS
        contWaitReady = true
        contWindowOpen = false
        contRequestedAt = now
        val sid = contSid

        Log.i(
            TAG,
            "[이어받기 시작] 질문 $questionId · 이어받기 #$sid: 임시 저장 ${text.length}글자, " +
                    "합친 횟수 $mergeCount/$CONT_MAX_MERGES, 이어받기 시작 후 ${now - contStartedAt}ms"
        )

        // 지금 세션은 끝내고, 새 세션을 요청합니다. 준비되기 전에는 말이 녹음되지 않는다는 점을 화면에 알립니다.
        clearTimers()
        stopRecognition("이어받기: 다음 인식 준비")
        resultSubmitted = false
        latestPartial = ""
        micOpened = false
        prematureLogged = true
        retryCount = 0

        setState("내용 확인 중", navy, "내용 확인 중...")
        liveView.text = "인식: $text"

        if (!armStt(REASON_CONT)) {
            Log.w(TAG, "[이어받기 종료] 다음 인식을 요청하지 못해 저장된 텍스트로 진행 (#$sid)")
            finalizeHeld(false)
            return
        }

        val readyTask = Runnable {
            if (contActive && sid == contSid && contWaitReady) {
                Log.w(TAG, "[이어받기 종료] 준비 타임아웃 ${CONT_READY_MS}ms (#$sid) → 저장된 텍스트로 진행")
                finalizeHeld(false)
            }
        }
        contReadyTask = readyTask
        handler.postDelayed(readyTask, CONT_READY_MS)
    }

    // 다음 인식이 실제로 준비된 순간부터 짧은 추가 발화 대기 구간이 시작됩니다.
    private fun onContReady() {
        val sid = contSid
        val now = SystemClock.elapsedRealtime()

        contWaitReady = false
        contReadyAtAbs = now
        contReadyTask?.let(handler::removeCallbacks)
        contReadyTask = null

        gateOpen = true
        listening = true
        waitingFinal = false
        resultSubmitted = false
        latestPartial = ""
        speechDetectedAt = 0L
        contWindowOpen = true

        Log.i(
            TAG,
            "[이어받기 준비] 이어받기 #$sid: 요청 후 ${now - contRequestedAt}ms 만에 onReady, " +
                    "추가 발화 대기 ${contWindowMs}ms 시작"
        )

        setState("더 말씀하셔도 돼요", listeningBlue, "더 말씀하셔도 돼요")
        liveView.text = "인식: $heldText"

        val windowTask = Runnable {
            if (contActive && sid == contSid && contWindowOpen) {
                Log.i(TAG, "[이어받기 종료] 추가 발화 없음 (#$sid, 대기 ${contWindowMs}ms) → 저장된 텍스트로 진행")
                finalizeHeld(false)
            }
        }
        contWindowTask = windowTask
        handler.postDelayed(windowTask, contWindowMs)
    }

    // 대기 구간 안에 음성이 들어왔어요. 말하는 도중에 자르지 않도록 짧은 대기 타이머는 취소합니다.
    private fun onContSpeech(source: String) {
        val sid = contSid
        contSpeechAtAbs = SystemClock.elapsedRealtime()
        contWindowOpen = false
        contWindowTask?.let(handler::removeCallbacks)
        contWindowTask = null

        Log.i(TAG, "[이어받기 추가 발화] 이어받기 #$sid: 음성 감지($source), 최종 결과를 기다려요")
        statusView.text = "듣고 있어요"

        val cap = Runnable {
            if (contActive && sid == contSid) {
                Log.w(TAG, "[이어받기 종료] 추가 발화 대기 상한 ${CONT_SPEECH_CAP_MS}ms (#$sid) → 지금까지 내용으로 진행")
                finalizeHeld(true)
            }
        }
        contSpeechTask = cap
        handler.postDelayed(cap, CONT_SPEECH_CAP_MS)
    }

    // 저장해 둔 텍스트(와, 있으면 마지막 부분 결과)로 상담을 계속합니다.
    private fun finalizeHeld(includePartial: Boolean) {
        if (!contActive) return

        var text = heldText
        val partial = latestPartial.trim()

        closeContSession(if (includePartial) "대기 상한 도달" else "추가 발화 없음 또는 준비/인식 실패")

        if (includePartial && partial.count { !it.isWhitespace() } >= MIN_PARTIAL_CHARS) {
            text = mergeHeld(partial)
            lastDeliveredFinal = false
        }

        stopRecognition("이어받기 종료")
        resultSubmitted = false
        deliverText(text)
    }

    private fun resetContinuation() {
        contReadyTask?.let(handler::removeCallbacks)
        contWindowTask?.let(handler::removeCallbacks)
        contSpeechTask?.let(handler::removeCallbacks)
        contReadyTask = null
        contWindowTask = null
        contSpeechTask = null

        contActive = false
        contWaitReady = false
        contWindowOpen = false
        contSessionOpen = false
        contSessionLines.clear()
        heldText = ""
        mergeCount = 0
    }

    // 이어받기 세션 하나의 시간 기록을 한 줄로 저장합니다. (최종 요약에 합쳐서 출력)
    private fun closeContSession(outcome: String) {
        if (!contSessionOpen) return
        contSessionOpen = false

        val r = contReadyAtAbs
        val pre = if (contPreReadyMax > 1.0) "있음(최대 %.1f)".format(contPreReadyMax) else "없음"

        contSessionLines.add(
            "#$contSid: 새 STT 요청→onReady ${if (r > 0) (r - contRequestedAt).toString() + "ms" else "미도착"}" +
                    ", 추가 발화 감지 ${if (contSpeechAtAbs > 0 && r > 0) "onReady+" + (contSpeechAtAbs - r) + "ms" else "없음"}" +
                    ", 최종 결과 ${if (contResultAtAbs > 0 && r > 0) "onReady+" + (contResultAtAbs - r) + "ms" else "없음"}" +
                    ", 준비 전 음성 입력 가능성 $pre, 결과=$outcome"
        )
    }

    // 문장이 이어질 것 같은 끝맺음이면 그 어미를, 아니면 null. (지금은 로그 전용)
    private fun looksUnfinished(text: String): String? = VoiceDecision.unfinishedEnding(text)

    private fun deliverText(phrase: String) {
        if (phrase.isBlank() || resultSubmitted) return

        if (contActive) {
            val now = SystemClock.elapsedRealtime()
            val ending = looksUnfinished(phrase)

            Log.i(
                TAG,
                "[이어받기 요약] 질문 $questionId: 최초 최종 결과 → 최종 제출 ${now - contStartedAt}ms, " +
                        "이어받기 성공 ${mergeCount}회, 최종 병합 ${phrase.length}글자, " +
                        "끝맺음=${if (ending != null) "미완성 가능('$ending')" else "완결로 보임"}"
            )
            contSessionLines.forEach { Log.i(TAG, "[이어받기 요약] 세션 $it") }

            sumFirstFinalAt = contStartedAt
            sumSubmitAt = now
            sumPending = true
        }

        Log.i(
            TAG,
            "[최종 제출] 질문 $questionId: ${phrase.length}글자, 합친 횟수 $mergeCount, " +
                    "이어받기 ${if (contActive) "사용(총 ${SystemClock.elapsedRealtime() - contStartedAt}ms)" else "안 함"}"
        )
        resetContinuation()

        resultSubmitted = true
        retryCount = 0
        turns++

        val now = SystemClock.elapsedRealtime()
        Log.d(
            TAG,
            "③ 음성 시작 → 인식 완료: ${if (speechDetectedAt > 0) now - speechDetectedAt else -1}ms, " +
                    "안내 종료 → 인식 완료: ${now - ttsDoneAt}ms, 글자 ${phrase.length}"
        )

        clearTimers()
        stopRecognition("인식 결과를 받음")

        // 위험 증상은 후속 질문의 답으로 검사하지 않고 바로 안전 안내로 갑니다.
        val urgentSymptom = (phase == Phase.SYMPTOM || phase == Phase.FOLLOW_UP_1 || phase == Phase.FOLLOW_UP_2) &&
                VoiceDecision.isSafetyFirst(triageOf(phrase))

        // 후속 질문의 답이 불완전하면 다음 질문으로 넘어가지 않고 다시 묻습니다.
        if (!urgentSymptom && !passFollowUpCheck(phrase)) return

        beep(ToneGenerator.TONE_PROP_ACK, 120)
        liveView.text = "인식: $phrase"

        addMessage(true, phrase)

        val aiStart = SystemClock.elapsedRealtime()
        replyTo(phrase)
        Log.d(
            TAG,
            "④ AI 응답 생성: ${SystemClock.elapsedRealtime() - aiStart}ms " +
                    "(앱 안의 규칙 기반 응답이라 외부 AI 호출 없음)"
        )
    }

    // 한두 번은 자연스럽게 다시 묻고, 계속 실패하면 원인을 알려 줍니다.
    private fun handleRecognitionProblem(reason: String) {
        // 이어받기 중에 추가 인식이 비거나 실패하면, 다시 묻지 않고 저장해 둔 텍스트로 진행합니다.
        if (contActive) {
            Log.w(TAG, "[이어받기 종료] 추가 발화 없이 끝남: $reason (세션 #$contSid) → 저장된 텍스트로 진행")
            finalizeHeld(false)
            return
        }

        Log.w(TAG, "인식 문제: $reason (최대 마이크 입력 $maxRms)")

        clearTimers()
        stopRecognition("인식 문제: $reason")

        if (autoMode && !finished && retryCount < MAX_RETRY) {
            retryCount++
            retryArm = true
            Log.w(TAG, "retry $retryCount: $reason")

            speakAndThenListen(
                if (retryCount == 1) "잘 못 들었어요. 한 번 더 말씀해 주세요."
                else "다시 한 번만 말씀해 주세요."
            )
            statusView.text = "$reason ($retryCount/$MAX_RETRY)"
        } else {
            retryCount = 0
            recognitionFailed(
                if (maxRms <= 0.0) "$reason\n$MIC_HELP" else reason
            )
        }
    }

    private fun recognitionFailed(reason: String) {
        clearTimers()
        stopRecognition("인식 실패 처리")

        speechPending = false
        autoMode = false

        updateButton()
        statusView.text = reason
        liveView.text = "다시 하려면 '음성 상담 시작'을 눌러 주세요."
    }

    private fun stopConsultation() {
        autoMode = false
        speechPending = false

        resetContinuation()
        sumPending = false
        voiceConsent = false
        clearTimers()
        stopRecognition("상담 중지")
        VoiceEngines.stopSpeaking()

        statusView.text = "음성 상담 중지됨"
        liveView.text = "원할 때 다시 시작할 수 있어요."
        updateButton()
    }

    // ---------------------------------------------------------------
    // 대화
    // ---------------------------------------------------------------

    private fun compact(input: String): String =
        input.lowercase().replace("\\s+".toRegex(), "")

    private fun hasAny(value: String, vararg words: String): Boolean =
        words.any { value.contains(it) }

    private fun ack(): String {
        ackIndex++
        return acks[ackIndex % acks.size]
    }

    // 후속 질문 유형: 예/아니오, 선택형, 서술형. 선택형은 의미 있는 답 단어(hints)를 함께 줍니다.
    private fun followUpSpec(): Pair<AnswerCheck.Kind, List<String>> {
        val first = phase == Phase.FOLLOW_UP_1

        return when (topic) {
            Topic.AC ->
                if (first) AnswerCheck.Kind.CHOICE to listOf("바람", "안나", "아예", "온도", "안맞", "둘다", "시원", "따뜻", "미지근")
                else AnswerCheck.Kind.YES_NO to emptyList()

            Topic.NOISE ->
                if (first) AnswerCheck.Kind.CHOICE to listOf("주행", "달릴", "운전", "서있", "정차", "멈춰", "신호", "둘다", "항상")
                else AnswerCheck.Kind.YES_NO to emptyList()

            Topic.WARNING ->
                if (first) AnswerCheck.Kind.OPEN to emptyList()
                else AnswerCheck.Kind.CHOICE to listOf("계속", "켜져", "깜빡", "깜박", "점멸", "둘다")

            Topic.GENERAL ->
                if (first) AnswerCheck.Kind.CHOICE to listOf("주행", "달릴", "운전", "서있", "정차", "멈춰", "둘다", "항상")
                else AnswerCheck.Kind.YES_NO to emptyList()

            else -> AnswerCheck.Kind.YES_NO to emptyList()
        }
    }

    // TODO(다음 작업) 음성 개선 후보 두 가지 (이번 UI 작업에서는 수정하지 않음)
    //  1) "창문이 잘 안 내려가"가 gate=false(안내 중)에 들어와 버려진 사례: 안내가 끝나기 전에 말한 내용을 임시로 보관했다가
    //     게이트가 열릴 때 처리할지 검토.
    //  2) 후속 질문 "같은 증상이 반복되나요?"에 대한 "반복해"가 무효 처리된 사례: AnswerCheck의 답변/증상 단어 목록에
    //     "반복", "자꾸", "매번" 등을 추가할지 검토.
    // 후속 질문(FOLLOW_UP_1/2)의 답변 내용이 유효한지 검사합니다. true면 정상 진행.
    // 질문당 재질문은 1회. 두 번째도 무효면 "답변을 확인하지 못함"으로 기록하고 사용자 발화는 대화 기록에 넣지 않습니다.
    private fun passFollowUpCheck(phrase: String): Boolean {
        if (phase != Phase.FOLLOW_UP_1 && phase != Phase.FOLLOW_UP_2) return true

        val (kind, hints) = followUpSpec()
        val result = AnswerCheck.validateFollowUp(phrase, kind, hints)

        Log.i(
            TAG,
            "[답변 검증] 질문 $questionId · ${phase.name} · 유형=${kind.name} · 입력 ${phrase.trim().length}글자 · " +
                    "결과=${if (result.valid) "유효" else "무효"} · 이유=${result.reason}"
        )

        if (result.valid) {
            clarifyCount = 0
            clarifyPhase = null
            return true
        }

        // 지금 단계가 바뀌었으면 재질문 횟수를 새로 셉니다.
        if (clarifyPhase != phase) {
            clarifyPhase = phase
            clarifyCount = 0
        }

        turns--   // 정상 답변이 아니므로 상담 횟수에 넣지 않음

        val question = if (phase == Phase.FOLLOW_UP_1) topic.followUp1 else topic.followUp2

        if (clarifyCount < 1) {
            clarifyCount++
            Log.w(TAG, "[답변 재질문] 질문 $questionId · 재질문 ${clarifyCount}/1 · ${phase.name} (STT 인식 실패와 별개)")

            liveView.text = "내용을 확인하지 못했어요. 한 번만 더 말씀해 주세요."
            speakAndThenListen("잘 못 들었어요. $question")
            return false
        }

        // 두 번째에도 확인하지 못함: 답으로 받아들이지 않고, 안전하게 다음 단계로 넘어갑니다.
        // (서비스센터 전달은 마지막 확인 질문에서 명확한 "네"를 들어야만 진행됩니다.)
        unconfirmedAnswers++
        clarifyCount = 0
        clarifyPhase = null

        Log.w(
            TAG,
            "[답변 확인 실패] 질문 $questionId · ${phase.name} · 두 번째 답변도 유효하지 않아 '답변을 확인하지 못함'으로 처리 " +
                    "(누적 ${unconfirmedAnswers}회, 발화는 대화 기록에 넣지 않음)"
        )

        liveView.text = "답변을 확인하지 못했어요."
        pendingNote = "답변을 확인하지 못했어요. 다음으로 넘어갈게요."
        replyTo("")   // 사용자 발화 없이 다음 단계 질문으로
        return false
    }

    // 브레이크 주제의 마지막 안내: 위험 증상이 아니면 위험하다고 단정하지 않습니다.
    private fun finalAdvice(): String =
        if (topic == Topic.BRAKE) {
            VoiceDecision.brakeFollowUpAdvice(triageOf(initialSymptom, PartCategory.BRAKE))
        } else {
            topic.advice
        }

    private fun replyTo(utterance: String) {
        val value = compact(utterance)
        var answer: String

        // 증상을 말하는 단계에서는 매번 공용 분류로 위험 여부부터 봅니다.
        val symptomPhase = phase == Phase.SYMPTOM || phase == Phase.FOLLOW_UP_1 || phase == Phase.FOLLOW_UP_2
        val triage = if (value.isNotEmpty() && symptomPhase) triageOf(utterance) else null
        val safety = triage?.takeIf { VoiceDecision.isSafetyFirst(it) }

        when {
            // 정정 요청
            hasAny(value, "잘못들", "잘못인식", "다시말", "아니그게") &&
                    phase != Phase.CONFIRM_SEND -> {
                phase = Phase.SYMPTOM
                topic = Topic.GENERAL
                initialSymptom = ""
                triagePart = null
                answer = "알겠어요. 다시 말씀해 주세요. 어떤 문제가 있으세요?"
            }

            // 위험 증상은 질문보다 안전 안내가 먼저 (공용 분류 SymptomTriage의 위험도에 맞춥니다)
            safety != null -> {
                topic = if (safety.part == PartCategory.BRAKE) Topic.BRAKE else (Topic.detect(value) ?: Topic.WARNING)
                triagePart = safety.part
                // 접수에 위험 표현이 남도록, 후속 답변에서 나온 경우에는 처음 증상 뒤에 덧붙입니다.
                initialSymptom = if (phase == Phase.SYMPTOM || initialSymptom.isBlank()) {
                    utterance
                } else {
                    "$initialSymptom · $utterance"
                }
                Log.i(
                    TAG,
                    "[안전 분류] 질문 $questionId: 위험도=${safety.severity.name}, 분류=${safety.urgency.wire}, " +
                            "일치 표현 ${safety.matched.size}개"
                )
                answer = (VoiceDecision.safetySpeech(safety) ?: "위험할 수 있어요.") +
                        " 서비스센터에 지금 내용을 전달할까요?"
                safetyDetail = VoiceDecision.safetySteps(safety)
                phase = Phase.CONFIRM_SEND
            }

            phase == Phase.SYMPTOM -> {
                val detected = Topic.detect(value)

                if (detected == null && unknownCount == 0 && (value.length < 8 || triage?.needsMoreInfo == true)) {
                    unknownCount++
                    answer = "조금만 더 자세히 말씀해 주세요. 어디에서 어떤 증상이 있나요?"
                } else {
                    topic = detected ?: Topic.GENERAL
                    initialSymptom = utterance
                    phase = Phase.FOLLOW_UP_1
                    answer = "${ack()} ${topic.followUp1}"
                }
            }

            phase == Phase.FOLLOW_UP_1 -> {
                phase = Phase.FOLLOW_UP_2
                answer = topic.followUp2
            }

            phase == Phase.FOLLOW_UP_2 -> {
                phase = Phase.CONFIRM_SEND
                answer = "${finalAdvice()} " +
                        "서비스센터에 지금 내용을 전달할까요?"
            }

            // 마지막: 서비스센터 전달 여부를 말로 답합니다.
            else -> {
                // 부분 문자열이 아니라 단어 단위의 명시적 표현만 인정합니다. 애매하면 전송하지 않습니다.
                // 부분 결과만으로는 전송 동의를 확정하지 않고 다시 묻습니다.
                val consent = if (!lastDeliveredFinal) {
                    AnswerCheck.Consent(AnswerCheck.Intent.AMBIGUOUS, "부분 결과만 있어 동의를 확정하지 않음")
                } else {
                    AnswerCheck.judgeConsent(utterance)
                }
                Log.i(
                    TAG,
                    "[전송 의사 판정] 질문 $questionId: ${consent.intent.name} " +
                            "(${when (consent.intent) {
                                AnswerCheck.Intent.YES -> "긍정"
                                AnswerCheck.Intent.NO -> "부정"
                                AnswerCheck.Intent.AMBIGUOUS -> "모호함"
                            }}) · 이유=${consent.reason} · 입력 ${utterance.trim().length}글자"
                )
                val yes = consent.intent == AnswerCheck.Intent.YES
                val no = consent.intent == AnswerCheck.Intent.NO

                answer = when {
                    yes -> {
                        voiceConsent = true
                        val line = "네, 서비스센터 선택 화면으로 넘어갈게요."
                        addMessage(false, line)
                        speakThenSend(line)
                        phase = Phase.DONE
                        finished = true
                        autoMode = false
                        updateButton()
                        return
                    }

                    no -> {
                        Log.i(TAG, "[전송 차단] 사용자가 전달을 원하지 않아 전송하지 않아요.")
                        phase = Phase.DONE
                        "알겠어요. 필요하면 정비 안내 버튼으로 언제든 전달할 수 있어요."
                    }

                    confirmAsked >= 1 -> {
                        Log.w(TAG, "[전송 차단] 두 번 물었지만 명확한 동의가 없어 전송하지 않아요.")
                        phase = Phase.DONE
                        "알겠어요. 전달하지 않을게요. 필요하면 정비 안내 버튼을 눌러 주세요."
                    }

                    else -> {
                        Log.w(TAG, "[전송 차단] 답변이 모호해서 전송하지 않고 다시 확인해요.")
                        confirmAsked++
                        "서비스센터에 전달할까요? 네 또는 아니요로 말씀해 주세요."
                    }
                }
            }
        }

        if (pendingNote.isNotEmpty()) {
            answer = pendingNote + " " + answer
            pendingNote = ""
        }

        addMessage(false, answer)

        // 단계별 안전 안내는 화면에만 따로 보여 주고, 말로는 짧은 문장만 읽습니다.
        safetyDetail?.let {
            addMessage(false, it)
            safetyDetail = null
        }

        if (phase == Phase.DONE) {
            finished = true
            autoMode = false
            speechPending = false
            updateButton()
            statusView.text = "상담 완료 · 정비 안내에서 서비스센터로 전달할 수 있어요"
            VoiceEngines.speak(answer)
        } else {
            speakAndThenListen(answer)
        }
    }

    // ---------------------------------------------------------------
    // 정비 안내
    // ---------------------------------------------------------------

    private fun showHistory() {
        writeHistory()

        val data = getSharedPreferences("autoresolve_voice_history", MODE_PRIVATE)
            .getString("session_$sessionId", "")
            .orEmpty()

        AlertDialog.Builder(this)
            .setTitle("현재 상담 기록")
            .setMessage(data.ifBlank { "아직 상담 기록이 없어요." })
            .setPositiveButton("닫기", null)
            .setNeutralButton("공유") { _, _ -> share(data) }
            .show()
    }

    private fun showRepairInformation() {
        val summary = buildString {
            appendLine("H.WITH 정비 상담")
            appendLine("초기 증상: " + initialSymptom.ifBlank { "확인되지 않음" })
            appendLine()
            transcript.forEach { appendLine(it) }
            appendLine()
            appendLine("정확한 진단은 전문가 확인이 필요합니다.")
        }

        AlertDialog.Builder(this)
            .setTitle("전문 정비 안내")
            .setMessage(
                "상담 내용을 저장하고 서비스센터를 골라 전달할 수 있어요. " +
                        "센터에서 같은 설명을 다시 하지 않아도 돼요.\n" +
                        "(시연 모드: 실제 센터로는 전송되지 않아요.)"
            )
            .setPositiveButton("서비스센터 연결") { _, _ -> sendToServiceCenter() }
            .setNeutralButton("상담 내용 공유") { _, _ -> share(summary) }
            .setNegativeButton("닫기", null)
            .show()
    }

    // 음성 대화로 넘어가는 경우의 안전장치: 명확한 동의(네)를 확인하지 못했으면 절대 넘기지 않습니다.
    // (화면의 "서비스센터 연결" 버튼을 직접 누른 경우는 사용자의 명시적 선택이라 이 검사를 거치지 않습니다.)
    private fun sendAfterVoiceConsent() {
        if (!voiceConsent) {
            Log.w(TAG, "[전송 차단] 명확한 음성 동의가 없어 서비스센터로 넘기지 않았어요.")
            return
        }

        voiceConsent = false
        sendToServiceCenter()
    }

    // 상담 내용을 접수로 저장하고, 센터 선택 화면(MainActivity)으로 넘깁니다.
    private fun sendToServiceCenter() {
        if (isFinishing || isDestroyed) return

        val profile = VehicleProfileStore(this).load()

        val vehicle = if (profile == null) {
            "차량 미등록"
        } else {
            val brand = VehicleCatalog.brands[profile.manufacturer] ?: profile.manufacturer

            "$brand ${profile.model} · ${profile.year}년식 · " +
                    VehicleCatalog.getPowertrainLabel(profile.powertrain)
        }

        val request = ServiceRequest(
            id = newRequestId(),
            createdAt = System.currentTimeMillis(),
            vehicle = vehicle,
            part = triagePart ?: topic.part,
            symptom = initialSymptom.ifBlank { "음성 상담 내용 참조" },
            diagnosisSummary = "",
            transcript = transcript.joinToString("\n"),
            centerId = null,
            centerName = null,
            status = RequestStatus.SAVED
        )

        ServiceRequestStore(this).save(request)
        writeHistory()

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_REQUEST_ID, request.id)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )

        finish()
    }

    private fun share(value: String) {
        if (value.isBlank()) return

        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, value)
            }
            startActivity(Intent.createChooser(intent, "상담 내용 공유"))
        } catch (_: Exception) {
            Toast.makeText(this, "공유할 수 있는 앱이 없어요.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------
    // 수명 주기 (엔진은 release 하지 않습니다)
    // ---------------------------------------------------------------

    override fun onStop() {
        closed = true
        Log.d(
            TAG,
            "⑤ 전체 음성 상담 시간: ${SystemClock.elapsedRealtime() - entryAt}ms (대화 ${turns}번)"
        )

        stopConsultation()
        writeHistory()

        if (VoiceEngines.sttCallbacks === sttCallbacks) VoiceEngines.sttCallbacks = null
        if (VoiceEngines.ttsCallbacks === ttsCallbacks) VoiceEngines.ttsCallbacks = null
        VoiceEngines.onStateChanged = null

        super.onStop()
    }

    override fun onDestroy() {
        autoMode = false
        clearTimers()
        handler.removeCallbacksAndMessages(null)
        saveJob = null

        try {
            tone?.release()
        } catch (_: Exception) {
        }
        tone = null

        super.onDestroy()
    }
}
