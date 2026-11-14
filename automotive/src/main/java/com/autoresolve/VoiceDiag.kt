package com.autoresolve

import android.content.Context
import android.content.SharedPreferences

/**
 * 음성 상담에서 문제가 생겼을 때 원인을 나누어 보기 위한 진단 옵션입니다.
 * 음성 상담 화면 제목("‹ 차량 음성 상담")을 길게 누르면 바꿀 수 있고, 값은 기기에 저장됩니다.
 *
 * 기본값은 평소 동작과 같습니다. (TTS 켜짐, STT 켜짐, Android TTS, 소리 용도 = 미디어)
 */
object VoiceDiag {

    /** Android TTS가 소리를 낼 때 시스템에 알리는 용도. 차량 시스템이 용도에 따라 다르게 반응할 수 있어요. */
    enum class TtsUsage(val label: String) {
        MEDIA("미디어 (기본)"),
        ASSISTANT("어시스턴트"),
        NAVIGATION("내비게이션 안내")
    }

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences("autoresolve_voice_diag", Context.MODE_PRIVATE)
        }
    }

    private fun bool(key: String, default: Boolean) =
        prefs?.getBoolean(key, default) ?: default

    private fun put(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    var ttsEnabled: Boolean
        get() = bool("tts", true)
        set(v) = put("tts", v)

    /** true면 음성 상담 화면만 보여주고 TTS/STT/엔진 시작을 모두 하지 않습니다. (검은 화면이 UI 문제인지 구분용) */
    var uiOnly: Boolean
        get() = bool("ui_only", false)
        set(v) = put("ui_only", v)

    var sttEnabled: Boolean
        get() = bool("stt", true)
        set(v) = put("stt", v)

    /** true면 Android TTS, false면 Pleos TTS SDK */
    var androidTts: Boolean
        get() = bool("android_tts", true)
        set(v) = put("android_tts", v)

    var ttsUsage: TtsUsage
        get() = TtsUsage.values().firstOrNull {
            it.name == prefs?.getString("usage", TtsUsage.MEDIA.name)
        } ?: TtsUsage.MEDIA
        set(v) {
            prefs?.edit()?.putString("usage", v.name)?.apply()
        }

    fun summary(): String =
        "UI만=${uiOnly}, TTS=${if (ttsEnabled) "켜짐" else "꺼짐"}, STT=${if (sttEnabled) "켜짐" else "꺼짐"}, " +
                "TTS엔진=${if (androidTts) "Android" else "Pleos"}, 소리용도=${ttsUsage.name}"
}
