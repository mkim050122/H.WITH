package com.autoresolve

import android.app.Activity
import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 화면이 갑자기 검게 변하는 문제 같은 것을 조사하기 위한 기록 도구입니다.
 * 앱 안의 모든 화면의 생명주기와 창 포커스, 디스플레이 켜짐/꺼짐 상태, 재생 중인 소리의 용도를
 * 태그 AutoResolveVoice로 남깁니다. 동작은 바꾸지 않습니다.
 *
 * 해석 방법
 *  - 검은 화면 때 onPause/onStop이 찍힌다   → 다른 화면이 앱을 가렸어요.
 *  - 포커스만 false로 바뀌고 onPause는 없다   → 시스템 창/오버레이가 위에 떴어요.
 *  - "디스플레이" 상태가 OFF/DOZE로 바뀐다    → 화면 자체가 꺼졌다 켜진 거예요.
 *  - 아무것도 안 찍힌다                     → 앱 밖(그래픽/에뮬레이터)에서 일어난 일이에요.
 */
object LifecycleTrace {

    private const val TAG = VoiceEngines.LOG_TAG
    private var installed = false
    private val focusHooked = HashSet<Int>()

    /** API 30+ 는 Activity.display, API 29 는 (deprecated) windowManager.defaultDisplay 로 디스플레이 ID를 구한다. 실패하면 -1. */
    @Suppress("DEPRECATION")
    private fun displayIdOf(a: Activity): Int {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                a.display?.displayId ?: -1
            } else {
                a.windowManager.defaultDisplay?.displayId ?: -1
            }
        } catch (e: Exception) {
            -1
        }
    }


    fun install(app: Application) {
        if (installed) return
        installed = true

        val main = Handler(Looper.getMainLooper())

        fun line(a: Activity, event: String) {
            Log.i(
                TAG,
                "[생명주기] ${a.javaClass.simpleName}@${System.identityHashCode(a)} $event " +
                        "(포커스=${a.hasWindowFocus()}, finishing=${a.isFinishing})"
            )
        }

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) = line(a, "onCreate")

            override fun onActivityStarted(a: Activity) {
                line(a, "onStart")

                val id = System.identityHashCode(a)
                if (focusHooked.add(id)) {
                    a.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener { focus ->
                        Log.i(
                            TAG,
                            "[창 포커스] ${a.javaClass.simpleName}@$id hasFocus=$focus"
                        )
                    }
                }
            }

            override fun onActivityResumed(a: Activity) = line(a, "onResume")
            override fun onActivityPaused(a: Activity) = line(a, "onPause")
            override fun onActivityStopped(a: Activity) = line(a, "onStop")
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit

            override fun onActivityDestroyed(a: Activity) {
                line(a, "onDestroy")
                focusHooked.remove(System.identityHashCode(a))
            }
        })

        // 디스플레이가 켜졌다/꺼졌다 하는지
        try {
            val dm = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

            dm.registerDisplayListener(object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {
                    Log.i(TAG, "[디스플레이] 추가 id=$displayId")
                }

                override fun onDisplayRemoved(displayId: Int) {
                    Log.i(TAG, "[디스플레이] 제거 id=$displayId")
                }

                override fun onDisplayChanged(displayId: Int) {
                    val d = dm.getDisplay(displayId)
                    Log.i(
                        TAG,
                        "[디스플레이] 변경 id=$displayId 상태=${stateName(d?.state)} 회전=${d?.rotation}"
                    )
                }
            }, main)
        } catch (e: Exception) {
            Log.w(TAG, "디스플레이 기록을 켜지 못했어요", e)
        }

        // 지금 어떤 용도의 소리가 재생되는지 (안내 소리, "삐" 소리 등)
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            am.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                    val text = configs?.joinToString(" | ") {
                        "용도=${it.audioAttributes.usage} 종류=${it.audioAttributes.contentType}"
                    }.orEmpty()

                    Log.i(TAG, "[재생 소리] ${configs?.size ?: 0}개 $text")
                }
            }, main)
        } catch (e: Exception) {
            Log.w(TAG, "재생 소리 기록을 켜지 못했어요", e)
        }
    }

    /**
     * 화면이 검게 보일 때 "화면은 만들어졌는데 그려지지 않는 건지", "창은 있는데 가려진 건지"를 가르는 기록.
     * 호출 직후, 0.3초, 1.5초, 4초 뒤에 루트 뷰 크기/보임/투명도/배경과 창 정보를 남기고,
     * 그동안 메인 스레드가 멈추는지(0.1초마다 확인)도 같이 봅니다.
     */
    fun probe(a: Activity, label: String) {
        val main = Handler(Looper.getMainLooper())
        val name = a.javaClass.simpleName
        val t0 = android.os.SystemClock.elapsedRealtime()

        fun snapshot(tag: String) {
            try {
                val decor = a.window.decorView
                val content = a.findViewById<android.view.ViewGroup>(android.R.id.content)
                val root = content?.getChildAt(0)
                val bg = (root?.background as? android.graphics.drawable.ColorDrawable)?.color
                val lp = a.window.attributes
                Log.i(
                    TAG,
                    "[화면 점검:$label/$tag] $name " +
                            "루트=${root?.width}x${root?.height} 보임=${root?.visibility} alpha=${root?.alpha} " +
                            "실제표시=${root?.isShown} 배경=${bg?.let { "#" + Integer.toHexString(it) }} " +
                            "자식=${(root as? android.view.ViewGroup)?.childCount} " +
                            "decor=${decor.width}x${decor.height} 붙음=${decor.isAttachedToWindow} " +
                            "창포커스=${a.hasWindowFocus()} 창타입=${lp.type} 창플래그=0x${Integer.toHexString(lp.flags)} " +
                            "창alpha=${lp.alpha}"
                )
                Log.i(
                    TAG,
                    "[화면 점검:$label/$tag] task=${a.taskId} display=${displayIdOf(a)} " +
                            "멀티윈도우=${a.isInMultiWindowMode} PIP=${a.isInPictureInPictureMode} " +
                            "창영역=${a.window.decorView.width} " +
                            "화면dp=${a.resources.configuration.screenWidthDp}x${a.resources.configuration.screenHeightDp}"
                )
            } catch (e: Exception) {
                Log.w(TAG, "[화면 점검] 실패", e)
            }
        }

        snapshot("지금")
        for (ms in longArrayOf(300, 1500, 4000)) {
            main.postDelayed({ snapshot("+${ms}ms") }, ms)
        }

        // 첫 그리기가 실제로 일어나는지
        val vto = a.window.decorView.viewTreeObserver
        vto.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            var count = 0
            override fun onDraw() {
                count++
                if (count <= 3) {
                    Log.i(
                        TAG,
                        "[첫 그리기] $name ${count}번째 draw, 진입 후 ${android.os.SystemClock.elapsedRealtime() - t0}ms"
                    )
                }
            }
        })

        // 메인 스레드가 멈추는지: 0.1초마다 확인해서 0.25초 넘게 늦으면 기록
        var last = android.os.SystemClock.elapsedRealtime()
        val ping = object : Runnable {
            override fun run() {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - last > 250) {
                    Log.w(TAG, "[메인 스레드 지연] $name ${now - last - 100}ms 막혔어요 (진입 후 ${now - t0}ms)")
                }
                last = now
                if (now - t0 < 6000) main.postDelayed(this, 100)
            }
        }
        main.postDelayed(ping, 100)
    }

    private fun stateName(state: Int?): String = when (state) {
        1 -> "OFF"
        2 -> "ON"
        3 -> "DOZE"
        4 -> "DOZE_SUSPEND"
        5 -> "VR"
        6 -> "ON_SUSPEND"
        null -> "알 수 없음"
        else -> state.toString()
    }
}
