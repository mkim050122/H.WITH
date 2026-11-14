package com.autoresolve

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit
import java.util.Locale

/**
 * 음성 인식(STT)과 음성 안내(TTS) 엔진을 앱 전체에서 한 번만 만들어 재사용합니다.
 *
 * 왜 이렇게 바꿨나
 * - 화면을 열 때마다 엔진을 새로 만들고 닫으면, 연속으로 열고 닫을 때
 *   (초기화 중에 release 호출, 이전 연결이 끝나기 전에 새 연결) 앱이 종료될 수 있어요.
 * - 매번 초기화하는 시간이 "마이크가 늦게 켜지는" 느낌의 큰 부분이에요.
 *
 * 사용법: 앱이 시작될 때 [warmUp]을 한 번 부르면 되고, 화면에서는 [sttCallbacks]/[ttsCallbacks]만
 * 붙였다 떼면 됩니다. 엔진은 앱 프로세스가 끝날 때까지 유지합니다.
 */
object VoiceEngines {

    private const val TAG = "VoiceEngines"

    /** 모든 측정 로그는 이 태그 하나로 봅니다: adb logcat -s AutoResolveVoice */
    const val LOG_TAG = "AutoResolveVoice"

    fun vlog(message: String) {
        Log.d(LOG_TAG, message)
    }

    const val BUILD_MARK = "voice-2026-11-13-dgn"

    // 직전에 stop()한 뒤 바로 request()하면 엔진이 불안정해서 두는 최소 간격
    private const val MIN_RESTART_GAP_MS = 300L
    private const val RMS_THROTTLE_MS = 200L

    /**
     * 안내 음성을 어떤 엔진으로 읽을지 정합니다.
     *  ANDROID: 단말(에뮬레이터)의 Android TTS. 주차 앱에서 소리가 잘 나온 방식이고, 속도·끊김 처리가 쉬워요.
     *  PLEOS  : Pleos TextToSpeech SDK(ON_DEVICE). 실제 차량에서는 이 쪽이 차량 음성과 같은 목소리예요.
     * ANDROID 초기화가 실패하면 자동으로 PLEOS로 넘어갑니다.
     */
    enum class TtsEngine { ANDROID, PLEOS }

    /** 어떤 안내 음성을 쓸지는 진단 옵션(VoiceDiag)에서 읽습니다. 기본은 Android TTS. */

    /**
     * 안내가 끝난 뒤 마이크를 요청하기까지 기다리는 시간(ms). 스피커 잔향이 마이크로 들어오지 않게 두는 여유입니다.
     * 마이크는 이 시간 + SDK 준비 시간(에뮬레이터 실측 57~583ms) 뒤에 열립니다.
     * 실측(Pleos Connect 에뮬레이터, 맥북 스피커·마이크): 500ms일 때 안내 종료→마이크 열림 566~1085ms,
     * 150ms일 때 258~460ms였고 안내 끝소리가 인식된 사례는 없었습니다.
     * 안내 끝소리가 답으로 인식되면 늘리고, 답 첫마디가 잘리면 줄이세요.
     */
    const val listenDelayMs = 150L

    interface SttCallbacks {
        fun onReady()
        fun onStarted()
        fun onUpdated(text: String, completed: Boolean)
        fun onRms(rms: Double)
        fun onEpd(on: Long, off: Long)
        fun onEnded()
        fun onError(reason: String)
    }

    interface TtsCallbacks {
        fun onStart()
        fun onDone()
        fun onError(reason: String)
    }

    private val main = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var stt: SpeechToText? = null
    private var tts: TextToSpeech? = null
    private var androidTts: android.speech.tts.TextToSpeech? = null
    @Volatile private var androidReady = false
    @Volatile private var androidFailed = false
    private var utteranceCounter = 0

    private val usingAndroidTts: Boolean
        get() = VoiceDiag.androidTts && !androidFailed

    @Volatile var sttReady = false
        private set
    @Volatile private var pleosTtsReady = false

    val ttsReady: Boolean
        get() = if (usingAndroidTts) androidReady else pleosTtsReady

    /** 화면이 바뀔 때 교체됩니다. 항상 메인 스레드에서 호출됩니다. */
    @Volatile var sttCallbacks: SttCallbacks? = null
    @Volatile var ttsCallbacks: TtsCallbacks? = null

    /** 엔진 준비 상태가 바뀔 때 알려 받고 싶은 화면이 붙입니다. */
    @Volatile var onStateChanged: (() -> Unit)? = null

    val ready: Boolean get() = sttReady && ttsReady

    @Volatile private var speaking = false
    private var pendingSpeak: String? = null

    // ---- 진단 전용 값(동작에는 쓰지 않습니다) ----
    private var requestSeq = 0
    private var lastStopSeq = 0
    private var outstandingRequest = false
    private var requestedWhileSpeaking = false
    @Volatile private var androidFailReason: String? = null

    // 앱이 보낸 request() 순번입니다. SDK 세션 ID가 아니라 앱 쪽 순번이에요. (진단용)
    val sdkRequestCount: Int get() = requestSeq
    private var lastEndOrStopAt = 0L
    // stop()을 부른 뒤 SDK의 종료 확인(onEnded/onError)을 아직 못 본 상태 (진단용)
    private var stopUnconfirmed = false
    private var pendingLabel = ""

    /** [진단·세션] 한 줄: 이벤트 순서, 그 시점의 sttSessionActive, 직전 종료·중지 이후 경과 시간. */
    private fun diagSession(event: String, activeBefore: Boolean, note: String = "") {
        val gap = if (lastEndOrStopAt == 0L) -1L else SystemClock.elapsedRealtime() - lastEndOrStopAt
        vlog(
            "[진단·세션] $event | 앱 요청순번 #$requestSeq(SDK 세션 ID 아님) | " +
                    "sttSessionActive 전=$activeBefore 후=$sttSessionActive | 직전 종료·중지 후 ${gap}ms" + note
        )
    }

    /** 지금 안내 음성을 재생 중인지(진단용). */
    val isSpeaking: Boolean get() = speaking

    /** 직전 stop() 뒤 경과 시간(ms). stop()을 부른 적이 없으면 -1. */
    fun msSinceLastStop(): Long =
        if (lastStopAt == 0L) -1L else SystemClock.elapsedRealtime() - lastStopAt

    /** 설정한 TTS 엔진과 실제로 쓰는 엔진을 구분해 보여 줍니다. (fallback 동작은 그대로입니다) */
    fun ttsEngineSummary(): String {
        val configured = if (VoiceDiag.androidTts) "Android" else "Pleos"
        val actual = if (usingAndroidTts) {
            if (androidReady) "Android" else "Android(초기화 중)"
        } else {
            if (pleosTtsReady) "Pleos" else "Pleos(초기화 중)"
        }
        val reason = if (VoiceDiag.androidTts && androidFailed) {
            androidFailReason ?: "Android TTS를 쓸 수 없음"
        } else {
            null
        }
        return "설정 엔진=$configured · 실제 사용 엔진=$actual" + (reason?.let { " · 전환 이유=$it" } ?: "")
    }

    // 인식 세션 상태: request()를 보냈고 아직 끝나지 않았으면 true
    @Volatile var sttSessionActive = false
        private set
    private var sttRequestedAt = 0L
    private var lastStopAt = 0L
    private var lastRmsPost = 0L
    private var startRunnable: Runnable? = null

    private var warmUpAt = 0L
    private var listenerAdded = false

    // ---- 인식 세션 한 번(request 1회)에 대한 콜백 집계. 중복 콜백과 마이크 입력 크기를 확인하려는 값 ----
    private var cbReady = 0
    private var cbStarted = 0
    private var cbPartial = 0
    private var cbFinal = 0
    private var cbEpd = 0
    private var lastStartedAt = 0L
    private var firstStartedAt = 0L
    private var readyAtMs = 0L
    private var rmsCount = 0
    private var rmsMax = 0.0
    private var rmsMaxBeforeReady = 0.0
    private var rmsSum = 0.0
    private var lastRmsLog = 0L
    private var endedAtMs = 0L

    // 인식이 끝나는 순간의 상태를 보려는 값 (끊김 원인 분석용)
    private var lastPartialAt = 0L
    private var lastPartialLen = 0
    private var lastEpdAt = 0L
    private var lastEpdOn = 0L
    private var lastEpdOff = 0L
    private var lastRmsValue = 0.0
    private var lastRmsAt = 0L
    private var lastLoudAt = 0L

    private fun ago(t: Long): String =
        if (t == 0L) "없음" else "${SystemClock.elapsedRealtime() - t}ms 전"

    private fun resetSessionStats() {
        lastPartialAt = 0L; lastPartialLen = 0
        lastEpdAt = 0L; lastEpdOn = 0L; lastEpdOff = 0L
        lastRmsValue = 0.0; lastRmsAt = 0L; lastLoudAt = 0L
        endedAtMs = 0L
        cbReady = 0; cbStarted = 0; cbPartial = 0; cbFinal = 0; cbEpd = 0
        lastStartedAt = 0L; firstStartedAt = 0L; readyAtMs = 0L
        rmsCount = 0; rmsMax = 0.0; rmsMaxBeforeReady = 0.0; rmsSum = 0.0; lastRmsLog = 0L
    }

    /** 세션 요약. 끝났을 때(종료·오류·중지) 한 줄로 남깁니다. */
    private fun logSessionSummary(why: String) {
        val avg = if (rmsCount > 0) rmsSum / rmsCount else 0.0
        vlog(
            "[세션 요약] 이유=$why | 요청→ready=${if (readyAtMs > 0) readyAtMs - sttRequestedAt else -1}ms, " +
                    "ready→첫 인식시작=${if (firstStartedAt > 0 && readyAtMs > 0) firstStartedAt - readyAtMs else -1}ms | " +
                    "콜백 수: ready=$cbReady, 인식시작=$cbStarted, EPD=$cbEpd, 부분결과=$cbPartial, 최종결과=$cbFinal | " +
                    "마이크 입력: 샘플 ${rmsCount}개, 최대 %.1f, 평균 %.1f, ready 전 최대 %.1f".format(rmsMax, avg, rmsMaxBeforeReady)
        )
    }

    fun warmUp(context: Context) {
        val app = context.applicationContext
        appContext = app
        VoiceDiag.init(app)

        if (warmUpAt == 0L) {
            warmUpAt = SystemClock.elapsedRealtime()
            Log.i(LOG_TAG, "빌드 표식 $BUILD_MARK (이 줄이 보이면 최신 코드가 실행 중이에요)")
        }

        if (stt == null) createStt(app)

        if (usingAndroidTts) {
            if (androidTts == null) createAndroidTts(app)
        } else if (tts == null) {
            createTts(app)
        }
    }

    // ---------------- Android TTS ----------------

    private fun createAndroidTts(app: Context) {
        var engine: android.speech.tts.TextToSpeech? = null

        engine = android.speech.tts.TextToSpeech(app) { status ->
            val e = engine
            if (status != android.speech.tts.TextToSpeech.SUCCESS || e == null) {
                Log.e(TAG, "Android TTS init failed: $status, falling back to Pleos TTS")
                androidFailReason = "Android TTS 초기화 실패($status)"
                androidFailed = true
                androidReady = false
                androidTts = null
                try { e?.shutdown() } catch (_: Exception) {}
                vlog("[진단·TTS 엔진] ${ttsEngineSummary()}")
                main.post {
                    createTts(app)
                    notifyState()
                }
                return@TextToSpeech
            }

            val lang = e.setLanguage(Locale.KOREA)
            if (lang == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                lang == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.e(TAG, "Android TTS has no Korean voice, falling back to Pleos TTS")
                androidFailReason = "Android TTS 한국어 음성 없음"
                androidFailed = true
                try { e.shutdown() } catch (_: Exception) {}
                androidTts = null
                vlog("[진단·TTS 엔진] ${ttsEngineSummary()}")
                main.post {
                    createTts(app)
                    notifyState()
                }
                return@TextToSpeech
            }

            // 차 안에서 알아듣기 쉽게 살짝 천천히, 안내(어시스턴트) 소리로 재생
            e.setSpeechRate(0.95f)
            e.setPitch(1.0f)
            // 소리 용도는 말할 때마다 진단 옵션에 맞춰 정합니다. (applyUsage)

            e.setOnUtteranceProgressListener(object :
                android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    speaking = true
                    vlog("[TTS] Android onStart")
                    main.post { ttsCallbacks?.onStart() }
                }

                override fun onDone(utteranceId: String?) {
                    speaking = false
                    vlog("[TTS] Android onDone")
                    main.post { ttsCallbacks?.onDone() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    speaking = false
                    main.post { ttsCallbacks?.onError("음성 안내 오류") }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    speaking = false
                }
            })

            androidTts = e
            androidReady = true
            vlog("TTS(Android) 초기화 완료: warmUp 후 ${SystemClock.elapsedRealtime() - warmUpAt}ms")
            notifyState()

            pendingSpeak?.let {
                pendingSpeak = null
                speak(it)
            }
        }

        androidTts = engine
    }

    // ---------------- STT ----------------

    private fun createStt(app: Context) {
        val engine = try {
            SpeechToText(app, SttMode.ON_DEVICE)
        } catch (e: Exception) {
            Log.e(TAG, "STT create failed", e)
            return
        }
        stt = engine

        engine.initialize(object : SttInit {
            override fun onSuccess() {
                try {
                    if (!listenerAdded) {
                        engine.addListener(sttListener)
                        listenerAdded = true
                    } else {
                        vlog("addListener 중복 호출을 막았어요")
                    }
                    engine.setLocale(Locale.KOREA)
                    sttReady = true
                    vlog("STT 초기화 완료: warmUp 후 ${SystemClock.elapsedRealtime() - warmUpAt}ms")
                } catch (e: Exception) {
                    Log.e(TAG, "STT setup failed", e)
                }
                notifyState()
            }

            override fun onFailure(errorType: SttError) {
                Log.e(TAG, "STT init failed: $errorType")
                // 다음 warmUp에서 다시 만들 수 있게 비웁니다.
                sttReady = false
                stt = null
                try { engine.release() } catch (_: Exception) {}
                notifyState()
            }
        })
    }

    private val sttListener = object : ResultListener {
        override fun onReady() {
            cbReady++
            val now = SystemClock.elapsedRealtime()
            val first = cbReady == 1
            if (first) readyAtMs = now

            vlog("[준비] onReady #$cbReady: 요청 후 ${since(sttRequestedAt)}ms (스레드 ${Thread.currentThread().name})")
            if (first) {
                diagSession("onReady", sttSessionActive)
                vlog(
                    "[진단·준비] 앱 요청순번 #$requestSeq: 요청→onReady ${since(sttRequestedAt)}ms " +
                            "(요청 당시 TTS 재생중=$requestedWhileSpeaking, 지금 TTS 재생중=$speaking)"
                )
            }

            // 같은 세션에서 또 오면 앱 상태를 다시 바꾸지 않도록 첫 번째만 전달합니다.
            if (first) main.post { sttCallbacks?.onReady() }
        }

        override fun onStartedRecognition() {
            cbStarted++
            val now = SystemClock.elapsedRealtime()
            val gap = if (lastStartedAt > 0) now - lastStartedAt else -1L
            lastStartedAt = now
            if (cbStarted == 1) firstStartedAt = now
            if (cbStarted == 1) {
                vlog(
                    "[진단·시작] 앱 요청순번 #$requestSeq: 요청→onStarted ${since(sttRequestedAt)}ms, " +
                            "onReady→onStarted ${if (readyAtMs > 0) now - readyAtMs else -1}ms"
                )
            }

            vlog(
                "[인식시작] onStartedRecognition #$cbStarted: 요청 후 ${since(sttRequestedAt)}ms, " +
                        "ready 후 ${if (readyAtMs > 0) now - readyAtMs else -1}ms, 직전과 간격 ${gap}ms"
            )

            if (cbStarted == 1) main.post { sttCallbacks?.onStarted() }
        }

        override fun onUpdated(stt: String, completed: Boolean) {
            if (completed) cbFinal++ else cbPartial++

            val arrivedAt = SystemClock.elapsedRealtime()
            val prevPartialAgo = ago(lastPartialAt)
            if (completed && endedAtMs > 0) {
                vlog("[진단·늦은 최종 결과] 앱 요청순번 #$requestSeq: 종료 신호 후 ${arrivedAt - endedAtMs}ms 만에 도착 (${stt.length}글자)")
            }
            if (!completed) {
                lastPartialAt = arrivedAt
                lastPartialLen = stt.length
            }

            vlog(
                (if (completed) "[최종결과]" else "[부분결과]") +
                        " 요청 후 ${since(sttRequestedAt)}ms, 글자 ${stt.length}" +
                        " | 직전 부분결과 $prevPartialAgo, 마지막 EPD ${ago(lastEpdAt)}, " +
                        "마지막 큰 소리 ${ago(lastLoudAt)}" +
                        if (endedAtMs > 0) {
                            " ← 종료 신호보다 ${arrivedAt - endedAtMs}ms 늦게 도착 (앞의 [세션 요약]에는 이 결과가 빠져 있어요)"
                        } else {
                            ""
                        }
            )
            main.post { sttCallbacks?.onUpdated(stt, completed) }
        }

        override fun onUpdatedEpdData(on: Long, off: Long) {
            cbEpd++
            lastEpdAt = SystemClock.elapsedRealtime()
            lastEpdOn = on
            lastEpdOff = off
            vlog("[음성감지 EPD] #$cbEpd on=$on off=$off: 요청 후 ${since(sttRequestedAt)}ms")
            main.post { sttCallbacks?.onEpd(on, off) }
        }

        override fun onUpdatedRms(rms: Double) {
            // 크기는 쓰레드 상관없이 집계하고, 화면으로 보내는 양과 로그는 줄입니다.
            rmsCount++
            rmsSum += rms
            if (rms > rmsMax) rmsMax = rms
            if (readyAtMs == 0L && rms > rmsMaxBeforeReady) rmsMaxBeforeReady = rms

            val now = SystemClock.elapsedRealtime()
            lastRmsValue = rms
            lastRmsAt = now
            // "큰 소리" = 이번 세션 최대 입력의 20% 이상 (말하는 중으로 보는 기준)
            if (rms > 1.0 && rms >= rmsMax * 0.2) lastLoudAt = now

            if (now - lastRmsLog >= 1000L) {
                lastRmsLog = now
                vlog("[마이크 입력] rms=%.1f (이번 세션 최대 %.1f, 샘플 %d개)".format(rms, rmsMax, rmsCount))
            }

            if (now - lastRmsPost < RMS_THROTTLE_MS) return
            lastRmsPost = now
            main.post { sttCallbacks?.onRms(rms) }
        }

        override fun onEndedRecognition() {
            val activeBefore = sttSessionActive
            sttSessionActive = false
            endedAtMs = SystemClock.elapsedRealtime()
            // 앱이 이번 세션에서 stop()을 부른 적이 없으면 SDK가 스스로 끝낸 것입니다.
            val sdkInitiated = lastStopAt < sttRequestedAt
            // 중지 뒤에 새 요청이 이미 걸려 있으면, 이 종료 신호가 어느 세션의 것인지 SDK가 알려 주지 않습니다.
            // (stop() 뒤 SDK의 종료 확인을 아직 못 본 상태에서 온 종료 신호만 '구분 불가'로 봅니다)
            val ambiguousOwner = stopUnconfirmed && lastStopSeq in 1 until requestSeq
            vlog(
                "[종료] onEndedRecognition: 요청 후 ${since(sttRequestedAt)}ms, " +
                        "종료 주체=${if (ambiguousOwner) "판별 불가(중지 #$lastStopSeq 이후 새 요청 #$requestSeq 가 있음)" else if (sdkInitiated) "SDK(앱은 stop() 안 부름)" else "앱이 stop() 호출한 뒤 SDK 확인"}"
            )
            vlog(
                "[진단·종료] 앱 요청순번 #$requestSeq: 요청→종료 ${since(sttRequestedAt)}ms, " +
                        "onReady→종료 ${if (readyAtMs > 0) endedAtMs - readyAtMs else -1}ms" +
                        if (ambiguousOwner) " · 늦은 종료 신호 의심: 새 요청 뒤에 도착해 sttSessionActive가 잘못 꺼졌을 수 있어요(값은 바꾸지 않음)" else ""
            )
            diagSession(
                "onEndedRecognition", activeBefore,
                if (ambiguousOwner) " | 의심: 이전 세션의 종료 확인일 수도, 현재 세션의 종료일 수도 있음(구분 불가)" else ""
            )
            // 구분 불가한 종료 신호는 '현재 요청이 끝났다'고 단정하지 않습니다. (진단용 표시만 유지)
            if (!ambiguousOwner) outstandingRequest = false
            stopUnconfirmed = false
            lastEndOrStopAt = endedAtMs
            vlog(
                "[종료 직전 상태] 마지막 부분결과 ${lastPartialLen}글자(${ago(lastPartialAt)}), " +
                        "마지막 EPD on=$lastEpdOn off=$lastEpdOff(${ago(lastEpdAt)}), " +
                        "마지막 마이크 입력 %.1f(${ago(lastRmsAt)}), 최대 %.1f, ".format(lastRmsValue, rmsMax) +
                        "마지막 큰 소리 ${ago(lastLoudAt)}, 결과: 부분 $cbPartial/최종 $cbFinal"
            )
            logSessionSummary("SDK가 인식을 끝냄")
            main.post { sttCallbacks?.onEnded() }
        }

        override fun onError(errorType: SttError) {
            val activeBefore = sttSessionActive
            sttSessionActive = false
            outstandingRequest = false
            stopUnconfirmed = false
            diagSession("onError($errorType)", activeBefore)
            lastEndOrStopAt = SystemClock.elapsedRealtime()
            Log.e(LOG_TAG, "[오류] onError: $errorType (요청 후 ${since(sttRequestedAt)}ms)")
            logSessionSummary("오류 $errorType")
            main.post { sttCallbacks?.onError("음성 인식 오류: $errorType") }
        }
    }

    private fun since(t: Long): Long =
        if (t == 0L) -1L else SystemClock.elapsedRealtime() - t

    /**
     * 마이크를 켭니다(request). 이미 켜져 있으면 다시 요청하지 않습니다.
     * 직전 stop()과 너무 가까우면 [MIN_RESTART_GAP_MS]만큼 늦춰서 보냅니다.
     * 요청을 받아들였으면 true.
     */
    fun requestListening(label: String = ""): Boolean {
        if (stt == null || !sttReady) {
            vlog("[진단·앱 요청 시도] $label → SDK request() 안 보냄 (STT 준비 안 됨)")
            return false
        }
        if (sttSessionActive || startRunnable != null) {
            vlog(
                "[진단·앱 요청 시도] $label → SDK request() 안 보냄 " +
                        "(sttSessionActive=$sttSessionActive, 지연 요청 대기=${startRunnable != null})"
            )
            return true
        }

        pendingLabel = label
        val wait = lastStopAt + MIN_RESTART_GAP_MS - SystemClock.elapsedRealtime()

        if (wait > 0) {
            vlog("[진단·앱 요청 시도] $label → SDK request()를 ${wait}ms 미룸 (MIN_RESTART_GAP_MS)")
            val task = Runnable {
                startRunnable = null
                doRequest()
            }
            startRunnable = task
            main.postDelayed(task, wait)
            return true
        }

        return doRequest()
    }

    private fun doRequest(): Boolean {
        val engine = stt ?: return false

        return try {
            val activeBefore = sttSessionActive
            resetSessionStats()
            sttRequestedAt = SystemClock.elapsedRealtime()
            sttSessionActive = true
            requestSeq++
            requestedWhileSpeaking = speaking
            // 중복 의심: 앞선 request()에 대한 종료·중지·오류 신호가 하나도 없는데 새 request()가 나가는 경우만.
            // (종료 신호가 이전 세션 것인지 현재 세션 것인지 SDK가 알려 주지 않으므로 '의심'으로만 기록합니다.)
            if (outstandingRequest) {
                vlog(
                    "[진단·중복 요청 의심] 앱 요청순번 #${requestSeq - 1}에 대한 종료·중지·오류 신호가 없는데 " +
                            "#$requestSeq request()가 나감 (라벨=$pendingLabel). 확정 아님: SDK 세션 ID가 없어 이전/현재 세션 구분 불가"
                )
            }
            outstandingRequest = true
            diagSession("request()", activeBefore, " | 라벨=$pendingLabel")
            engine.request()
            vlog("[시작요청] request() 보냄")
            vlog(
                "[진단·요청] #$requestSeq: 라벨=$pendingLabel, TTS 재생중=$speaking, 직전 stop() 후 ${msSinceLastStop()}ms, " +
                        "요청 시각(elapsed)=$sttRequestedAt"
            )
            true
        } catch (e: Exception) {
            sttSessionActive = false
            Log.e(LOG_TAG, "STT request failed", e)
            false
        }
    }

    /**
     * 인식을 멈춥니다(stop). 켜져 있는 세션이 있을 때만 실제로 부릅니다.
     * 이유는 모두 로그에 남습니다. stop()이 불리면 SDK가 on-device 엔진을 닫습니다(close).
     */
    fun abortListening(reason: String) {
        startRunnable?.let {
            main.removeCallbacks(it)
            startRunnable = null
        }

        if (!sttSessionActive) {
            vlog("STT stop() 건너뜀 ($reason): 켜진 세션 없음")
            return
        }

        val engine = stt ?: return
        sttSessionActive = false
        lastStopAt = SystemClock.elapsedRealtime()
        lastStopSeq = requestSeq
        outstandingRequest = false
        diagSession("stop() (이유=$reason)", true)
        lastEndOrStopAt = lastStopAt
        stopUnconfirmed = true

        vlog("[중지] stop() 호출: 이유=$reason, 요청 후 ${since(sttRequestedAt)}ms")
        logSessionSummary("앱이 중지: $reason")

        try {
            engine.stop()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "STT stop failed", e)
        }
    }

    // ---------------- TTS ----------------

    private fun createTts(app: Context) {
        val engine = try {
            TextToSpeech(app, TtsMode.ON_DEVICE)
        } catch (e: Exception) {
            Log.e(TAG, "TTS create failed", e)
            return
        }
        tts = engine

        engine.initialize(object : TtsInit {
            override fun onSuccess() {
                try {
                    engine.addEventListener(ttsListener)
                    engine.setLocale(Locale.KOREA)
                    pleosTtsReady = true
                    vlog("TTS(Pleos) 초기화 완료: warmUp 후 ${SystemClock.elapsedRealtime() - warmUpAt}ms")
                    vlog("[진단·TTS 엔진] ${ttsEngineSummary()}")
                } catch (e: Exception) {
                    Log.e(TAG, "TTS setup failed", e)
                }
                notifyState()

                pendingSpeak?.let {
                    pendingSpeak = null
                    speak(it)
                }
            }

            override fun onFailure(errorType: TtsError) {
                Log.e(TAG, "TTS init failed: $errorType")
                pleosTtsReady = false
                tts = null
                try { engine.release() } catch (_: Exception) {}
                notifyState()
            }
        })
    }

    private val ttsListener = object : EventListener {
        override fun onReady() = Unit

        override fun onStart() {
            speaking = true
            vlog("[TTS] Pleos onStart")
            main.post { ttsCallbacks?.onStart() }
        }

        override fun onUpdatedRms(rms: Double) = Unit

        override fun onDone() {
            speaking = false
            vlog("[TTS] Pleos onDone")
            main.post { ttsCallbacks?.onDone() }
        }

        override fun onStop() {
            speaking = false
        }

        override fun onError(errorType: TtsError) {
            speaking = false
            Log.e(TAG, "TTS error: $errorType")
            main.post { ttsCallbacks?.onError("음성 안내 오류: $errorType") }
        }
    }

    /**
     * 차 안에서 또렷하게 들리도록 문장을 다듬어 읽습니다.
     * 기호(·, /, →, 이모지)는 엔진이 어색하게 읽거나 끊기는 원인이라 말로 바꿔요.
     */
    fun forSpeech(text: String): String =
        text.replace("·", ", ")
            .replace("/", " 또는 ")
            .replace("→", " ")
            .replace(Regex("[^\\p{L}\\p{N}\\s.,?!]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private var lastUsage: VoiceDiag.TtsUsage? = null

    // 소리 용도를 진단 옵션에 맞게 바꿉니다. 바뀐 경우에만 설정합니다.
    private fun applyUsage(e: android.speech.tts.TextToSpeech) {
        val usage = VoiceDiag.ttsUsage
        if (usage == lastUsage) return
        lastUsage = usage

        val androidUsage = when (usage) {
            VoiceDiag.TtsUsage.MEDIA -> android.media.AudioAttributes.USAGE_MEDIA
            VoiceDiag.TtsUsage.ASSISTANT -> android.media.AudioAttributes.USAGE_ASSISTANT
            VoiceDiag.TtsUsage.NAVIGATION ->
                android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        }

        e.setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(androidUsage)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
    }

    fun speak(text: String) {
        val clean = forSpeech(text)
        if (clean.isBlank()) return

        // 진단: TTS를 꺼 두면 말하지 않고, 말이 끝난 것처럼 이어서 진행합니다.
        if (!VoiceDiag.ttsEnabled) {
            vlog("[진단] TTS 꺼짐 → 말하지 않고 건너뜀: '${clean.take(20)}'")
            main.postDelayed({ ttsCallbacks?.onDone() }, 700L)
            return
        }

        vlog("[TTS] speak() 호출 (${VoiceDiag.summary()} | ${ttsEngineSummary()}): '${clean.take(20)}…'")

        if (!ttsReady) {
            pendingSpeak = clean
            appContext?.let { warmUp(it) }
            return
        }

        if (usingAndroidTts) {
            val e = androidTts ?: return
            try {
                applyUsage(e)
                utteranceCounter++
                // QUEUE_FLUSH: 말하는 중에 새 문장이 오면 먼저 끊고 바로 이어 말합니다.
                e.speak(
                    clean,
                    android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                    null,
                    "autoresolve-$utteranceCounter"
                )
            } catch (ex: Exception) {
                Log.e(TAG, "Android TTS speak failed", ex)
                main.post { ttsCallbacks?.onError("음성 안내 요청 실패") }
            }
            return
        }

        try {
            if (speaking) {
                // 말하는 중에 새 문장이 오면 먼저 끊고 이어 말합니다. (겹쳐서 깨지는 소리 방지)
                tts?.stop()
                speaking = false
                main.postDelayed({ safeSpeak(clean) }, 150L)
            } else {
                safeSpeak(clean)
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak failed", e)
            main.post { ttsCallbacks?.onError("음성 안내 요청 실패") }
        }
    }

    private fun safeSpeak(clean: String) {
        try {
            tts?.speak(clean)
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak failed", e)
            main.post { ttsCallbacks?.onError("음성 안내 요청 실패") }
        }
    }

    fun stopSpeaking() {
        pendingSpeak = null

        if (usingAndroidTts) {
            try { androidTts?.stop() } catch (e: Exception) { Log.w(TAG, "TTS stop failed", e) }
            speaking = false
            return
        }

        val engine = tts ?: return
        if (!pleosTtsReady) return
        try {
            engine.stop()
        } catch (e: Exception) {
            Log.w(TAG, "TTS stop failed", e)
        }
        speaking = false
    }

    private fun notifyState() {
        main.post { onStateChanged?.invoke() }
    }
}
