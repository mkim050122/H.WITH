package com.autoresolve

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale

class PleosTtsManager(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onSpeakingFinished: () -> Unit = {}
) {
    companion object {
        private const val TAG = "AutoResolveTTS"
    }

    private val handler = Handler(Looper.getMainLooper())

    private val engine = TextToSpeech(
        context.applicationContext,
        Mode.ON_DEVICE
    )

    private var ready = false
    private var released = false
    private var speaking = false
    private var pendingText: String? = null

    private fun updateStatus(message: String) {
        handler.post {
            if (!released) onStatus(message)
        }
    }

    private val eventListener = object : EventListener {

        override fun onReady() {
            Log.d(TAG, "TTS synthesis ready")
        }

        override fun onStart() {
            speaking = true
            Log.d(TAG, "TTS playback started")
            updateStatus("AutoResolve가 말하고 있어요...")
        }

        override fun onUpdatedRms(rms: Double) {
            // Audio level updates are optional for this UI.
        }

        override fun onDone() {
            speaking = false
            Log.d(TAG, "TTS playback finished")
            updateStatus("음성 안내 완료")

            handler.post {
                if (!released) onSpeakingFinished()
            }
        }

        override fun onStop() {
            speaking = false
            Log.d(TAG, "TTS playback stopped")
        }

        override fun onError(errorType: ErrorType) {
            speaking = false
            Log.e(TAG, "TTS error: $errorType")
            updateStatus("음성 안내 오류: $errorType")
        }
    }

    fun initialize() {
        engine.initialize(
            object : OnInitializeListener {

                override fun onSuccess() {
                    if (released) return

                    Log.d(TAG, "TTS initialized")
                    engine.addEventListener(eventListener)
                    engine.setLocale(Locale.KOREA)

                    ready = true
                    updateStatus("Pleos 음성 안내 준비 완료")

                    pendingText?.let { message ->
                        pendingText = null
                        speak(message)
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.e(
                        TAG,
                        "TTS initialization failed: $errorType"
                    )
                    updateStatus("TTS 초기화 실패: $errorType")
                }
            }
        )
    }

    fun speak(message: String) {
        if (released || message.isBlank()) return

        if (!ready) {
            pendingText = message
            Log.d(TAG, "TTS request queued until ready")
            return
        }

        try {
            Log.d(TAG, "TTS speak requested")
            engine.speak(message)
        } catch (error: Exception) {
            Log.e(TAG, "TTS speak failed", error)
            updateStatus("음성 안내 요청 실패")
        }
    }

    fun stop() {
        pendingText = null

        if (released || !ready) return

        try {
            engine.stop()
            speaking = false
        } catch (error: Exception) {
            Log.w(TAG, "TTS stop failed", error)
        }
    }

    fun release() {
        if (released) return

        pendingText = null

        if (ready) {
            try {
                engine.stop()
                engine.removeEventListener(eventListener)
            } catch (error: Exception) {
                Log.w(TAG, "TTS cleanup failed", error)
            }
        }

        released = true
        ready = false

        try {
            engine.release()
        } catch (error: Exception) {
            Log.w(TAG, "TTS release failed", error)
        }
    }
}
