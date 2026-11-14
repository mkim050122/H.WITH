
package com.autoresolve

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var profileStore: VehicleProfileStore
    private lateinit var vehicleRepository: VehicleInfoRepository
    private lateinit var requestStore: ServiceRequestStore
    private lateinit var drivingStore: DrivingStatsStore
    private lateinit var container: LinearLayout

    private var recognizer: SpeechRecognizer? = null
    private var voiceTarget: EditText? = null
    private var agentMode = false
    private var pendingVoiceStart = false

    // 테마·화면 크기가 바뀌면 지금 화면을 그대로 다시 그리기 위한 정보(각 화면 함수가 자기 자신을 등록)
    private var lastRender: (() -> Unit)? = null
    private var preserveHook: (() -> Unit)? = null
    private var contentRoot: View? = null
    private var scrollRef: ScrollView? = null

    // 색은 디자인 토큰(AppTheme.kt)에서만 가져온다. 라이트/다크는 Activity의 uiMode를 따른다.
    private val tk get() = AppTheme.tokens(this)
    private val page get() = tk.backgroundPrimary
    private val surface get() = tk.backgroundSurface
    private val textMain get() = tk.textPrimary
    private val gray get() = tk.textSecondary
    private val brandFill get() = tk.brandPrimary
    private val onBrand get() = tk.textOnBrand
    private val active get() = tk.interactiveActive
    private val border get() = tk.borderDefault
    private val selectedBlue get() = tk.interactiveSelectedSurface

    companion object {
        private const val AUDIO_REQUEST = 4001

        // 음성 상담 화면에서 접수한 내용을 열 때 쓰는 값
        const val EXTRA_REQUEST_ID = "autoresolve_request_id"

        // Gleo AI 같은 외부 호출로 특정 화면을 열 때 쓰는 값
        const val EXTRA_SORT = "autoresolve_sort"
        const val EXTRA_OPEN = "autoresolve_open"
        const val OPEN_NEXT_CAR = "next_car"
        const val OPEN_REQUEST_HISTORY = "request_history"
        const val EXTRA_URGENT = "autoresolve_urgent"
    }

    // 시연용 테마 고정(라이트/다크)을 uiMode로 반영. 시스템 설정을 따를 때는 그대로 둔다.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        android.util.Log.i(
            VoiceEngines.LOG_TAG,
            "빌드 표식 ${VoiceEngines.BUILD_MARK} · 앱 시작 (MainActivity)"
        )

        // 화면이 가려지거나 꺼지는 순간을 조사하기 위한 기록 (동작은 바꾸지 않아요)
        VoiceDiag.init(applicationContext)
        LifecycleTrace.install(application)

        profileStore = VehicleProfileStore(this)
        requestStore = ServiceRequestStore(this)
        drivingStore = DrivingStatsStore(this)
        vehicleRepository = VehicleInfoRepository(this)
        vehicleRepository.initialize()

        // 음성 상담 화면에 들어갔을 때 바로 쓸 수 있도록 미리 준비해 둡니다.
        showHome()

        // 첫 화면을 먼저 그린 뒤 무거운 SDK 준비를 시간차로 합니다. (한꺼번에 하면 화면이 멈칫해요)
        val deferred = android.os.Handler(android.os.Looper.getMainLooper())
        deferred.postDelayed({ VoiceEngines.warmUp(applicationContext) }, 700L)
        deferred.postDelayed({ NaviLauncher.warmUp(applicationContext) }, 2500L)
        handleLaunchIntent(intent)
    }

    // API 30+ 는 Activity.display, API 29(minSdk)는 (deprecated) windowManager.defaultDisplay 로 디스플레이 ID를 구한다. 실패하면 -1.
    @Suppress("DEPRECATION")
    private fun displayIdOfSelf(): Int {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                display?.displayId ?: -1
            } else {
                windowManager.defaultDisplay?.displayId ?: -1
            }
        } catch (e: Exception) {
            -1
        }
    }

    private fun openVoiceConsult(from: String) {
        android.util.Log.i(
            VoiceEngines.LOG_TAG,
            "[화면 전환] startActivity(VoiceConsultActivity) 호출 · $from · task=$taskId display=${displayIdOfSelf()} (${VoiceDiag.summary()})"
        )
        try {
            startActivity(Intent(this, VoiceConsultActivity::class.java))
        } catch (e: Exception) {
            android.util.Log.e(VoiceEngines.LOG_TAG, "[화면 전환] startActivity 실패", e)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        android.util.Log.i(VoiceEngines.LOG_TAG, "[창 포커스 변경] MainActivity hasFocus=$hasFocus")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    // 앱이 꺼져 있을 때(onCreate)와 켜져 있을 때(onNewIntent) 모두 같은 방식으로 처리합니다.
    private fun handleLaunchIntent(launch: Intent?) {
        if (launch == null) return

        val requestId = launch.getStringExtra(EXTRA_REQUEST_ID)

        if (requestId != null) {
            val sort = runCatching {
                CenterSort.valueOf(
                    launch.getStringExtra(EXTRA_SORT) ?: CenterSort.NEAREST.name
                )
            }.getOrDefault(CenterSort.NEAREST)

            val urgentFromCaller = launch.getBooleanExtra(EXTRA_URGENT, false)

            requestStore.find(requestId)?.let {
                showCenterFinder(it, triageOf(it).isSafetyFirst || urgentFromCaller, sort)
            }
            return
        }

        if (launch.getStringExtra(EXTRA_OPEN) == OPEN_NEXT_CAR) {
            showNextCarPrefs()
        }

        // Gleo가 어떤 접수인지 확실하지 않을 때: 기존 "내 정비 접수" 화면에서 사용자가 직접 고르게 합니다.
        if (launch.getStringExtra(EXTRA_OPEN) == OPEN_REQUEST_HISTORY) {
            showRequestHistory()
        }
    }

    override fun onDestroy() {
        stopRecognition()
        releaseDealerResources()
        vehicleRepository.release()
        super.onDestroy()
    }

    // 창 크기에 비례하는 화면 배율. 기준 창은 실제 에뮬레이터에서 잰 앱 창 1682x1210dp(승인된 시안 기준)이고,
    // 이 크기에서 배율은 1.0이다. 창이 달라지면 같은 비율로 키우고 줄인다(0.6~2.5배).
    private fun uiScale(): Float {
        val c = resources.configuration
        val w = if (c.screenWidthDp > 0) c.screenWidthDp else 1682
        val h = if (c.screenHeightDp > 0) c.screenHeightDp else 1210
        return minOf(w / 1682f, h / 1210f).coerceIn(0.6f, 2.5f)
    }

    // 승인된 시안 이전부터 있던 화면들(진단·정비 접수 등)의 글자를 키우는 배율
    private val TEXT_BOOST = 1.3f

    // 승인된 시안(홈·다음 차 추천 결과)은 기준 수치를 그대로 쓰고(1.0), 그 밖의 화면은 길이와 글자를 이 배율만큼 더 키운다.
    private val LEGACY_BOOST = 1.3f
    private var legacyBoost = LEGACY_BOOST

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density * uiScale() * legacyBoost)
            .toInt()
            .coerceAtLeast(1)
    }

    // 카드 테두리: 라이트는 Hyundai Sand, 다크는 반투명 흰색(승인된 시안의 같은 카드 규칙).
    private fun background(
        color: Int,
        outline: Int? = null,
        radiusDp: Int = 20
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (outline != null) {
                setStroke(dp(1), outline)
            }
        }
    }

    // 눌렀을 때 살짝 어두워지는 피드백(클릭은 그대로 전달)
    private fun <T : View> T.pressable(): T {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.alpha = 0.82f
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.alpha = 1f
            }
            false
        }
        return this
    }

    // 예전 글자 크기 값(16~30)을 시안의 5단계로 맞춘다. 반환값은 기준 창(1682x1210)에서의 sp.
    private fun typeStep(size: Float): Float = when {
        size >= 26f -> 48f   // 화면 제목
        size >= 22f -> 40f   // 섹션 제목·팝업 제목
        size >= 19f -> 34f   // 본문·입력·목록·버튼
        size >= 17f -> 30f   // 보조·작은 버튼
        else -> 28f          // 설명·출처(최소)
    }

    // 배율(legacyBoost)을 곱하지 않는 기준 창 길이. 시안의 수치를 그대로 쓸 때 쓴다.
    private fun ad(value: Int): Int =
        (value * resources.displayMetrics.density * uiScale()).toInt().coerceAtLeast(1)

    private fun text(
        value: String,
        size: Float = 20f,
        bold: Boolean = false,
        color: Int = textMain
    ): TextView {
        return TextView(this).apply {
            this.text = value
            // 승인된 화면(홈·추천 결과)은 시안 수치 그대로, 그 밖의 화면은 5단계 글자 크기(48/40/34/30/28)로만 쓴다.
            val finalSize = if (legacyBoost > 1f) typeStep(size) else size * AppType.SCALE * TEXT_BOOST
            setTextSize(TypedValue.COMPLEX_UNIT_SP, finalSize * uiScale())
            setTextColor(color)
            includeFontPadding = true
            AppType.apply(this, bold)
        }
    }

    // 입력칸을 화면 위쪽으로 올린다(키보드가 열렸을 때).
    private fun scrollFocusedToTop(scroll: ScrollView) {
        val f = currentFocus as? EditText ?: return
        var y = 0
        var v: View = f
        while (v.parent !== scroll) {
            y += v.top
            v = v.parent as? View ?: break
        }
        scroll.smoothScrollTo(0, (y - ad(120)).coerceAtLeast(0))
    }

    // 화면 맨 위 제목을 더 두껍게: 굵은 글꼴 위에 글자 둘레 선을 더해 글꼴에 따라 달라지지 않게 한다.
    private fun <T : TextView> T.heavy(): T {
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = 0.9f * resources.displayMetrics.density * uiScale()
        return this
    }

    // 글자 크기를 기준 창 sp 그대로(5단계 밖의 시안 수치) 지정한다.
    private fun <T : TextView> T.abs(sp: Float): T {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * uiScale())
        return this
    }

    // 시스템 기본 팝업(AlertDialog) 대신 앱 디자인으로 그리는 팝업(시안 ⑫).
    // 파란 제목 띠 · 항목 높이 116/글자 38 · 선택 항목은 짙은 파랑 채움+체크 · 뒤 화면 60% 어둡게.
    private fun showAppDialog(
        title: String,
        subtitle: String? = null,
        message: String? = null,
        items: List<String> = emptyList(),
        selectedIndex: Int = -1,
        positive: String? = null,
        negative: String = "닫기",
        onPick: ((Int) -> Unit)? = null,
        onPositive: (() -> Unit)? = null
    ) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = background(surface, null, 44)
            clipToOutline = true
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(brandFill)
            setPadding(ad(48), ad(36), ad(48), ad(36))
        }
        head.addView(text(title, 23f, true, onBrand).abs(44f).heavy())
        if (subtitle != null) {
            head.addView(
                text(subtitle, 17f, false, onBrand).abs(28f).apply { alpha = 0.85f },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ad(6) }
            )
        }
        card.addView(head, LinearLayout.LayoutParams(-1, -2))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ad(48), ad(32), ad(48), ad(40))
        }
        if (message != null) {
            body.addView(text(message, 19f).abs(34f), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ad(24) })
        }
        items.forEachIndexed { i, label ->
            val picked = i == selectedIndex
            val last = i == items.lastIndex
            val fg = if (picked) onBrand else if (last && positive == null && negative == "닫기" && label.contains("해제")) gray else textMain
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ad(36), 0, ad(36), 0)
                background = if (picked) background(brandFill, null, 30) else background(surface, border, 30)
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss(); onPick?.invoke(i) }
                pressable()
                // 왼쪽 표시: 선택 항목은 체크 원, 나머지는 빈 원
                val mark = FrameLayout(this@MainActivity).apply {
                    background = if (picked) background(onBrand, null, 22)
                    else GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(ad(3), gray)
                    }
                    if (picked) addView(
                        icon(R.drawable.ic_check, 24, brandFill),
                        FrameLayout.LayoutParams(ad(30), ad(30), Gravity.CENTER)
                    )
                }
                addView(mark, LinearLayout.LayoutParams(ad(44), ad(44)).apply { rightMargin = ad(24) })
                addView(text(label, 21f, picked, fg).abs(38f), LinearLayout.LayoutParams(0, -2, 1f))
            }
            body.addView(row, LinearLayout.LayoutParams(-1, ad(116)).apply { bottomMargin = ad(16) })
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val no = sizedButton(negative, false, 21f) { dialog.dismiss() }.abs(36f).apply {
            setTextColor(brandFill)
            background = GradientDrawable().apply {
                setColor(surface)
                setStroke(ad(2), brandFill)
                cornerRadius = ad(56).toFloat()
            }
        }
        buttons.addView(no, LinearLayout.LayoutParams(0, ad(112), 1f))
        if (positive != null) {
            val yes = sizedButton(positive, true, 21f) { dialog.dismiss(); onPositive?.invoke() }.abs(36f)
            buttons.addView(yes, LinearLayout.LayoutParams(0, ad(112), 1f).apply { leftMargin = ad(20) })
        }
        body.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ad(8) })
        card.addView(body, LinearLayout.LayoutParams(-1, -2))

        val scroller = ScrollView(this).apply { addView(card) }
        dialog.setContentView(scroller)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            val maxW = (resources.configuration.screenWidthDp * resources.displayMetrics.density * 0.9f).toInt()
            setLayout(minOf(ad(1100), maxW), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.68f)
        }
        dialog.show()
    }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun screen(
        title: String,
        stickyHome: Boolean = true,
        showTitle: Boolean = true,
        approved: Boolean = false,
        footer: (() -> View)? = null
    ) {
        legacyBoost = if (approved) 1f else LEGACY_BOOST
        // 화면을 옮기면 열려 있던 키보드를 닫는다(홈에 키보드가 남던 문제)
        try {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(window.decorView.windowToken, 0)
        } catch (e: Exception) {
            Log.w("AutoResolveKeyboard", "[키보드] 닫기 실패: ${e.message}")
        }
        // 어떤 화면 전환이든(홈 버튼 포함) 시승 매장 화면의 NaviHelper·지오코딩 요청을 정리
        releaseDealerResources()
        stopRecognition()
        voiceTarget = null
        preserveHook = null
        // 창 배경도 토큰 색으로(다시 그리는 순간 다른 색이 비치지 않도록)
        window.setBackgroundDrawable(AppTheme.pageBackground(this, tk))

        val scroll = ScrollView(this).apply {
            background = AppTheme.pageBackground(this@MainActivity, tk)
            isFillViewport = true
        }

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(30))
            isFocusableInTouchMode = true
        }

        // 키보드가 차지한 높이만큼 아래에 빈 공간을 두어, 입력칸을 위로 올려도 목록이 키보드에 가리지 않게 한다.
        val imeSpacer = View(this)
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        wrapper.addView(imeSpacer, LinearLayout.LayoutParams(-1, 0))
        scroll.addView(wrapper)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
                if (imeSpacer.layoutParams.height != imeBottom) {
                    Log.i("AutoResolveKeyboard", "[키보드] 높이(IME inset)=${imeBottom}px")
                    imeSpacer.layoutParams = LinearLayout.LayoutParams(-1, imeBottom)
                    if (imeBottom > 0) scroll.post { scrollFocusedToTop(scroll) }
                }
            }
            v.onApplyWindowInsets(insets)
        }

        if (stickyHome) {
            // 스크롤해도 위에 고정되는 홈 버튼과 제목
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = AppTheme.pageBackground(this@MainActivity, tk)
            }

            // 머리줄(시안 ⑦): 뒤로(홈) 원형 버튼 · 화면 제목 48 · 홈 버튼
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ad(40), ad(24), ad(40), ad(8))
            }

            val backBtn = FrameLayout(this).apply {
                contentDescription = "홈으로"
                background = background(surface, border, 44)
                isClickable = true
                isFocusable = true
                setOnClickListener { showHome() }
                pressable()
                addView(
                    icon(R.drawable.ic_chev_left, 34, textMain),
                    FrameLayout.LayoutParams(ad(44), ad(44), Gravity.CENTER)
                )
            }
            bar.addView(backBtn, LinearLayout.LayoutParams(ad(88), ad(88)))

            bar.addView(
                text(title, 28f, true).heavy().apply {
                    setPadding(ad(24), 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            val homePill = text("홈", 21f).apply {
                gravity = Gravity.CENTER
                setPadding(ad(40), 0, ad(40), 0)
                background = background(surface, border, 44)
                isClickable = true
                isFocusable = true
                setOnClickListener { showHome() }
                pressable()
            }
            bar.addView(homePill, LinearLayout.LayoutParams(-2, ad(88)))

            root.addView(bar, LinearLayout.LayoutParams(-1, -2))
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

            // 선택 하단 고정 영역: 스크롤 영역 아래에 두어 긴 내용이 있어도 버튼이 항상 보입니다.
            // 아래쪽 시스템 바 높이(inset)만큼 비워 Pleos 시스템 UI와 겹치지 않게 합니다.
            if (footer != null) {
                val footerHost = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(page)
                    setPadding(ad(40), ad(12), ad(40), ad(14))
                }
                footerHost.addView(
                    View(this).apply { setBackgroundColor(border) },
                    LinearLayout.LayoutParams(-1, 1).apply { bottomMargin = ad(12) }
                )
                footerHost.addView(footer(), LinearLayout.LayoutParams(-1, -2))
                footerHost.setOnApplyWindowInsetsListener { v, insets ->
                    @Suppress("DEPRECATION")
                    val bottom = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        insets.getInsets(
                            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                        ).bottom
                    } else {
                        insets.systemWindowInsetBottom
                    }
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, ad(14) + bottom)
                    insets
                }
                root.addView(footerHost, LinearLayout.LayoutParams(-1, -2))
            }

            contentRoot = root
            setContentView(root)
            container.requestFocus()
        } else {
            contentRoot = scroll
            setContentView(scroll)
            container.requestFocus()

            if (showTitle) addText(title, 28f, true, 15)
        }
        scrollRef = scroll
    }

    private fun addText(
        value: String,
        size: Float = 20f,
        bold: Boolean = false,
        bottom: Int = 10
    ): TextView {
        val view = text(value, size, bold)

        container.addView(
            view,
            LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(bottom)
            }
        )

        return view
    }

    private fun buttonView(
        title: String,
        primary: Boolean = true,
        onClick: () -> Unit
    ): TextView = sizedButton(title, primary, if (primary) 24f else 21f, onClick)

    private fun sizedButton(
        title: String,
        primary: Boolean,
        size: Float,
        onClick: () -> Unit
    ): TextView {
        return text(
            title,
            size,
            true,
            if (primary) onBrand else textMain
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = background(
                if (primary) brandFill else surface,
                if (primary) null else border,
                24
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            pressable()
        }
    }

    private fun addButton(
        title: String,
        primary: Boolean = true,
        onClick: () -> Unit
    ) {
        container.addView(
            buttonView(title, primary, onClick),
            LinearLayout.LayoutParams(
                -1,
                if (primary) ad(124) else ad(112)
            ).apply {
                bottomMargin = dp(12)
            }
        )
    }

    private fun addQuickRow(
        left: String,
        right: String,
        leftAction: () -> Unit,
        rightAction: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        row.addView(
            buttonView(left, false, leftAction),
            LinearLayout.LayoutParams(0, dp(88), 1f).apply {
                rightMargin = dp(6)
            }
        )

        row.addView(
            buttonView(right, false, rightAction),
            LinearLayout.LayoutParams(0, dp(88), 1f).apply {
                leftMargin = dp(6)
            }
        )

        container.addView(
            row,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            }
        )
    }

    // Create a text field that does not open CarIME by default.
    private fun createInput(
        hint: String,
        multiline: Boolean
    ): EditText {
        return EditText(this).apply {
            this.hint = hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f * uiScale())
            setTextColor(textMain)
            setHintTextColor(gray)
            setPadding(dp(17), dp(12), dp(17), dp(12))
            background = background(surface, border)

            inputType = if (multiline) {
                InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else {
                InputType.TYPE_CLASS_TEXT
            }

            if (multiline) {
                minLines = 2
                maxLines = 4
                gravity = Gravity.TOP
            } else {
                setSingleLine(true)
                gravity = Gravity.CENTER_VERTICAL
            }

            imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                    if (multiline) {
                        EditorInfo.IME_ACTION_DONE
                    } else {
                        EditorInfo.IME_ACTION_SEARCH
                    }

            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = true

            // 처음에는 키보드를 자동으로 띄우지 않지만, 입력칸을 누르면 키보드를 연다.
            showSoftInputOnFocus = false
            setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    (v as? EditText)?.let { openKeyboard(it) }
                }
                false
            }
        }
    }

    // 입력칸에 포커스를 주고 키보드를 연다. 결과를 로그(AutoResolveKeyboard)로 남겨 에뮬레이터/차량에서 키보드가 안 뜰 때 원인을 볼 수 있게 한다.
    private fun openKeyboard(input: EditText) {
        input.showSoftInputOnFocus = true
        input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(input)
        input.post {
            val shown = imm.showSoftInput(input, 0)
            var insets = "미사용"
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.show(android.view.WindowInsets.Type.ime())
                insets = "ime 표시 요청"
            }
            Log.i(
                "AutoResolveKeyboard",
                "[키보드] showSoftInput=$shown insets=$insets 창포커스=${window.decorView.hasWindowFocus()} 사용가능IME수=${imm.enabledInputMethodList.size}"
            )
        }
    }

    private fun addVoiceInput(
        hint: String,
        multiline: Boolean = false
    ): EditText {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val input = createInput(hint, multiline)

        row.addView(
            input,
            LinearLayout.LayoutParams(
                0,
                dp(if (multiline) 115 else 80),
                1f
            ).apply {
                rightMargin = dp(8)
            }
        )

        val mic = FrameLayout(this).apply {
            contentDescription = "음성 입력 시작"
            background = background(surface, border)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startRecognition(input, false)
            }
            addView(
                icon(R.drawable.ic_mic, 34, textMain),
                FrameLayout.LayoutParams(ad(44), ad(44), Gravity.CENTER)
            )
        }

        row.addView(
            mic,
            LinearLayout.LayoutParams(
                dp(80),
                dp(if (multiline) 115 else 80)
            )
        )

        wrapper.addView(row)

        val manual = text(
            "직접 입력하기",
            18f,
            false,
            gray
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            isClickable = true
            isFocusable = true

            setOnClickListener {
                openKeyboard(input)
            }
        }

        wrapper.addView(
            manual,
            LinearLayout.LayoutParams(-1, dp(48))
        )

        container.addView(
            wrapper,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            }
        )

        return input
    }

    private fun stopRecognition() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun startRecognition(
        target: EditText,
        asAgent: Boolean
    ) {
        voiceTarget = target
        agentMode = asAgent

        if (checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingVoiceStart = true
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                AUDIO_REQUEST
            )
            return
        }

        beginRecognition(target)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode != AUDIO_REQUEST) return

        val target = voiceTarget
        val shouldStart = pendingVoiceStart
        pendingVoiceStart = false

        if (
            shouldStart &&
            grantResults.firstOrNull() ==
            PackageManager.PERMISSION_GRANTED &&
            target != null &&
            target.isAttachedToWindow
        ) {
            beginRecognition(target)
        } else {
            agentMode = false
            toast("마이크 권한을 확인해 주세요.")
        }
    }

    private fun beginRecognition(target: EditText) {
        stopRecognition()

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            agentMode = false
            toast("사용 가능한 음성 인식 서비스가 없어요.")
            return
        }

        try {
            val speech = SpeechRecognizer
                .createSpeechRecognizer(this)

            recognizer = speech

            speech.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(
                        params: Bundle?
                    ) {
                        toast("말씀해 주세요.")
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(
                        rmsdB: Float
                    ) {}

                    override fun onBufferReceived(
                        buffer: ByteArray?
                    ) {}

                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        agentMode = false
                        stopRecognition()
                        toast("음성 인식 오류: $error")
                    }

                    override fun onResults(results: Bundle?) {
                        val spoken = results
                            ?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()

                        val wasAgent = agentMode
                        agentMode = false

                        stopRecognition()

                        if (spoken.isBlank()) {
                            toast("음성을 인식하지 못했어요.")
                            return
                        }

                        if (target.isAttachedToWindow) {
                            target.setText(spoken)
                            target.setSelection(
                                target.text.length
                            )
                        }

                        if (wasAgent) {
                            saveVoiceRecord(spoken)
                            analyzeSymptom(spoken)
                        }
                    }

                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) {}

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {}
                }
            )

            val speechIntent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    "ko-KR"
                )
                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    3
                )
            }

            speech.startListening(speechIntent)

        } catch (e: Exception) {
            agentMode = false
            stopRecognition()
            toast("음성 인식을 시작할 수 없어요.")
        }
    }

    private fun saveVoiceRecord(symptom: String) {
        val prefs = getSharedPreferences(
            "autoresolve_voice_history",
            MODE_PRIVATE
        )

        val timestamp = SimpleDateFormat(
            "yyyy-MM-dd HH:mm",
            Locale.KOREA
        ).format(Date())

        val previous = prefs.getString(
            "records",
            ""
        ).orEmpty()

        val record = "$timestamp\n$symptom"

        prefs.edit().putString(
            "records",
            if (previous.isBlank()) {
                record
            } else {
                "$previous\n\n$record"
            }
        ).apply()
    }

    private fun showVoiceHistory() {
        screen("음성 상담 기록")
        lastRender = { showVoiceHistory() }

        val voicePrefs = getSharedPreferences(
            "autoresolve_voice_history",
            MODE_PRIVATE
        )

        // VoiceConsultActivity는 session_ 키로 저장하므로 함께 보여준다.
        val sessions = voicePrefs.all
            .filterKeys { it.startsWith("session_") }
            .toSortedMap(reverseOrder())
            .values
            .map { it.toString() }

        val homeRecords =
            voicePrefs.getString("records", "").orEmpty()

        val history = (sessions + listOf(homeRecords))
            .filter { it.isNotBlank() }
            .joinToString("\n\n──────────\n\n")

        addText(
            if (history.isBlank()) {
                "아직 저장된 음성 상담 기록이 없어요."
            } else {
                history
            },
            19f
        )

        addButton("홈으로") {
            showHome()
        }
    }

    // ───────── 홈 화면(승인된 시안) ─────────
    // 구조: 머리줄(브랜드·화면 모드) / AI 상담 카드 + 내 차 요약 / 기능 카드 3개 / 보조 버튼 줄.
    // 글자 크기는 시안의 sp 값을 그대로 쓰고, 세로 길이는 창 높이(dp)에 비례해 늘리고 줄인다(시안 기준 높이 600dp).
    // 가로 화면(너비 720dp 이상)은 시안과 같은 가로 배치, 좁은 창은 세로로 쌓는다.
    private fun lp(
        w: Int, h: Int, weight: Float = 0f,
        l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0
    ) = LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private fun vdp(value: Int): Int = dp(value)

    // 시안의 글자 크기를 그대로 쓰기 위한 함수(text()가 곱하는 배율을 상쇄)
    private fun tx(value: String, sp: Float, bold: Boolean = false, color: Int = textMain): TextView =
        text(value, sp / (AppType.SCALE * TEXT_BOOST), bold, color)

    private fun icon(res: Int, sizeDp: Int, color: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(color)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    // 카드 구분은 테두리(background()가 처리)만 쓴다. 안드로이드 기본 그림자(elevation)는 카드 가장자리에 딱딱한 선처럼 보여서 쓰지 않는다.
    private fun <T : View> T.lift(): T = this

    private fun glassCard(radius: Int = 24, color: Int = surface): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = background(color, border, radius)
        }

    // 화면에 남는 세로 공간을 지정한 뷰들의 높이에 나눠 준다(내용이 화면보다 짧을 때만, 처음 배치가 끝난 뒤 한 번).
    private fun fillRemaining(grow: List<View>) {
        val s = scrollRef ?: return
        val c = container
        s.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View?, l: Int, t: Int, r: Int, b: Int,
                oldL: Int, oldT: Int, oldR: Int, oldB: Int
            ) {
                if (s.height <= 0 || c.childCount == 0) return
                s.removeOnLayoutChangeListener(this)
                s.post {
                    val last = c.getChildAt(c.childCount - 1)
                    val extra = s.height - (last.bottom + c.paddingBottom)
                    val sum = grow.sumOf { it.height }
                    Log.i("AutoResolveTheme", "[남는 공간] 창높이=${s.height}px 남는높이=${extra}px")
                    if (extra > dp(8) && sum > 0) {
                        grow.forEach { g ->
                            val params = g.layoutParams
                            params.height = g.height + (extra.toLong() * g.height / sum).toInt()
                            g.layoutParams = params
                        }
                    }
                }
            }
        })
    }

    private fun showHome() {
        screen("H.WITH", false, false, approved = true)
        lastRender = { showHome() }

        val profile = profileStore.load()

        if (profile == null) {
            addText("먼저 차량을 등록해 주세요.", 24f, true)
            addButton("내 차량 등록") {
                showVehicleProfile()
            }
            return
        }

        val wide = resources.configuration.screenWidthDp >= 720
        val brandName = VehicleCatalog.brands[profile.manufacturer] ?: profile.manufacturer
        val requestCount = requestStore.loadAll().size
        val drivingReady = drivingStore.snapshot()?.reliable == true
        container.setPadding(dp(40), dp(40), dp(40), dp(40))
        Log.i(
            "AutoResolveTheme",
            "[홈 배치] 창 ${resources.configuration.screenWidthDp}x${resources.configuration.screenHeightDp}dp " +
                "화면 ${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}px " +
                "densityDpi=${resources.displayMetrics.densityDpi} fontScale=${resources.configuration.fontScale} " +
                "wide=$wide 배율=${uiScale()} 빌드=${VoiceEngines.BUILD_MARK}"
        )

        val heroColor = if (tk.isDark) tk.backgroundElevated else tk.brandPrimary
        val heroSub = if (tk.isDark) tk.textSecondary else Color.parseColor("#C9D6E6")
        val heroBtn = if (tk.isDark) active else Color.WHITE
        val heroBtnText = if (tk.isDark) Color.BLACK else brandFill

        // ① 머리줄(높이 96): 로고 · 이름 · 화면 모드
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = background(if (tk.isDark) active else brandFill, null, 20)
            addView(icon(R.drawable.ic_car, 40, if (tk.isDark) Color.BLACK else Color.WHITE))
        }
        header.addView(logo, lp(dp(72), dp(72)))
        header.addView(tx("H.WITH", 48f, true), lp(-2, -2, l = 22))
        if (wide) {
            header.addView(tx("차량 문제, 말로 해결", 28f, false, gray), lp(-2, -2, l = 26))
        }
        header.addView(View(this), lp(0, 1, 1f))
        val themeMode = AppTheme.mode(this)
        val themeButton = glassCard(40).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(34), 0, dp(34), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // 현재 화면과 입력 내용을 그대로 두고 색만 다시 입힌다(화면 재시작 없음).
                AppTheme.setMode(this@MainActivity, AppTheme.next(themeMode))
                rerender()
            }
            pressable()
            addView(icon(R.drawable.ic_moon, 36, textMain))
            addView(
                tx(
                    when (themeMode) {
                        AppTheme.Mode.LIGHT -> "화면 모드 · 라이트"
                        AppTheme.Mode.DARK -> "화면 모드 · 다크"
                        AppTheme.Mode.SYSTEM -> "화면 모드 · 자동"
                    },
                    28f
                ),
                lp(-2, -2, l = 16)
            )
        }
        header.addView(themeButton, lp(-2, dp(80)))
        container.addView(header, lp(-1, dp(96), b = 28))

        // ② AI 상담 카드(높이 470)
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(56), dp(48), dp(56), dp(48))
            background = background(heroColor, null, 44)
        }
        val heroText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroText.addView(tx("AI 음성 상담", 28f, true, heroSub))
        heroText.addView(tx("차에 이상이 있나요?\n말씀해 주세요", 60f, true, Color.WHITE), lp(-1, -2, t = 16))
        heroText.addView(tx("증상을 말하면 원인과 다음 단계를 안내합니다", 32f, false, heroSub), lp(-1, -2, t = 16))
        hero.addView(heroText, lp(0, -2, 1f, r = 32))
        val voiceButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = background(heroBtn, null, 64)
            isClickable = true
            isFocusable = true
            setOnClickListener { openVoiceConsult("홈 버튼") }
            pressable()
            addView(icon(R.drawable.ic_mic, 48, heroBtnText))
            addView(tx(if (VoiceDiag.uiOnly) "UI만" else "상담", 40f, true, heroBtnText), lp(-2, -2, l = 14))
        }
        // 길게 누르면 "화면만 표시" 진단 모드를 켜고 끕니다. (검은 화면이 UI 문제인지 구분용)
        voiceButton.setOnLongClickListener {
            VoiceDiag.uiOnly = !VoiceDiag.uiOnly
            android.util.Log.i(VoiceEngines.LOG_TAG, "[진단 옵션] ${VoiceDiag.summary()}")
            android.widget.Toast.makeText(
                this,
                if (VoiceDiag.uiOnly) "진단: 음성 상담 화면만 표시 (TTS/STT 끔)" else "진단 해제: 평소처럼 동작",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            true
        }
        hero.addView(voiceButton, LinearLayout.LayoutParams(dp(280), dp(128)))
        // 네이비 상담 카드 어디를 눌러도 음성 상담으로 간다(버튼 길게 누르기 진단 기능은 그대로)
        hero.isClickable = true
        hero.isFocusable = true
        hero.contentDescription = "AI 음성 상담 시작"
        hero.setOnClickListener { openVoiceConsult("홈 카드") }
        hero.pressable()

        // 내 차 요약 카드: 값이 있는 것만(등록한 차량, 정비 접수 건수, 주행 데이터 수집 여부)
        val summary = glassCard(40).apply {
            setPadding(dp(40), dp(36), dp(40), dp(36))
            isClickable = true
            isFocusable = true
            setOnClickListener { showVehicleProfile() }
            pressable()
        }
        val summaryTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        summaryTop.addView(tx("내 차", 32f, true), lp(0, -2, 1f))
        summaryTop.addView(icon(R.drawable.ic_chev_right, 36, gray))
        summary.addView(summaryTop)
        summary.addView(View(this), lp(-1, 0, 1f))
        summary.addView(
            tx("$brandName ${profile.model}", 52f, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        )
        summary.addView(
            tx("${profile.year}년식 · ${VehicleCatalog.getPowertrainLabel(profile.powertrain)}", 28f, false, gray),
            lp(-1, -2, t = 6)
        )
        summary.addView(View(this), lp(-1, 0, 1f))
        val summaryBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        summaryBottom.addView(
            View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(active)
                }
            },
            lp(dp(18), dp(18), r = 14)
        )
        summaryBottom.addView(
            tx("정비 접수 ${requestCount}건\n주행 데이터 ${if (drivingReady) "수집됨" else "수집 전"}", 28f)
        )
        summary.addView(summaryBottom)

        val growRows = ArrayList<View>()
        if (wide) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(hero, lp(0, -1, 2f, r = 28))
            row.addView(summary, lp(0, -1, 1f))
            container.addView(row, lp(-1, dp(470), b = 28))
            growRows.add(row)
        } else {
            container.addView(hero, lp(-1, -2, b = 20))
            container.addView(summary, lp(-1, dp(300), b = 28))
        }

        // ③ 기능 카드 3개(높이 350)
        fun featureCard(iconRes: Int, title: String, sub: String, subSp: Float = 30f, action: () -> Unit) = glassCard(36).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(40), dp(12), dp(40), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            pressable()
            val box = LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER
                background = background(selectedBlue, null, 30)
                addView(icon(iconRes, 60, textMain))
            }
            addView(box, LinearLayout.LayoutParams(dp(108), dp(108)))
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tx(title, 44f, true))
            val subView = tx(sub, subSp, false, gray)
            // 한글이 글자 중간에서 끊기지 않고 어절(띄어쓰기) 단위로 줄바꿈되게 한다(Android 13 이상)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                subView.setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
            }
            col.addView(subView, lp(-1, -2, t = 6))
            addView(col, lp(0, -2, 1f, l = 28))
            // 설명이 길어 카드 높이를 넘으면 설명 글자만 2sp씩 줄여 카드 안에 들어오게 한다(최소 24)
            post {
                val limit = height - paddingTop - paddingBottom
                if (limit <= 0 || col.width <= 0) return@post
                var sz = subSp
                fun fits(): Boolean {
                    col.measure(
                        View.MeasureSpec.makeMeasureSpec(col.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    return col.measuredHeight <= limit
                }
                while (!fits() && sz > 24f) {
                    sz -= 2f
                    subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sz * uiScale())
                }
                col.requestLayout()
            }
        }
        val features = listOf(
            featureCard(R.drawable.ic_pulse, "차량 진단", "고장 부위로 원인 찾기") { showPartSelect() },
            featureCard(
                R.drawable.ic_wrench, "정비 접수",
                if (requestCount == 0) "접수 내역이 없어요" else "접수 ${requestCount}건 보기"
            ) { showRequestHistory() },
            featureCard(R.drawable.ic_car, "나랑 맞을CAR?", "드라이빙 취향과 운전 데이터를 분석해 맞는 차를 추천해요", 26f) { showNextCarPrefs() }
        )
        if (wide) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            features.forEachIndexed { i, f -> row.addView(f, lp(0, -1, 1f, r = if (i < 2) 28 else 0)) }
            container.addView(row, lp(-1, dp(350), b = 28))
            growRows.add(row)
        } else {
            features.forEach { container.addView(it, lp(-1, dp(200), b = 20)) }
        }

        // ④ 보조 버튼 줄(높이 130)
        fun pill(iconRes: Int, label: String, action: () -> Unit) = glassCard(65).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            pressable()
            addView(icon(iconRes, 44, textMain))
            addView(tx(label, 34f), lp(-2, -2, l = 18))
        }
        val secondary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pills = ArrayList<LinearLayout>()
        pills.add(pill(R.drawable.ic_history, "최근 상담 기록") { showVoiceHistory() })
        if (BuildConfig.DEMO_MODE) {
            pills.add(pill(R.drawable.ic_calendar, "시연 예약 내역") { showDemoReservations("", "", "", "", null) })
        }
        pills.forEachIndexed { i, p ->
            secondary.addView(p, lp(0, -1, 1f, r = if (i < pills.size - 1) 28 else 0))
        }
        container.addView(secondary, lp(-1, dp(130)))
        if (wide) {
            growRows.add(secondary)
            fillRemaining(growRows)
        }
    }

    // 테마·창 크기가 바뀌었을 때 지금 화면을 같은 내용으로 다시 그린다(입력칸 글자와 스크롤 위치 유지).
    private fun rerender() {
        val render = lastRender ?: return
        try {
            preserveHook?.invoke()
            val saved = ArrayList<Pair<CharSequence, CharSequence?>>()
            fun collect(v: View?) {
                if (v is EditText) saved.add(v.text.toString() to v.hint)
                else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
            }
            collect(contentRoot)
            val sy = scrollRef?.scrollY ?: 0
            render()
            var idx = 0
            fun restore(v: View?) {
                if (v is EditText) {
                    val prev = saved.getOrNull(idx++)
                    if (prev != null && prev.second?.toString() == v.hint?.toString() && prev.first.isNotEmpty()) {
                        v.setText(prev.first)
                    }
                } else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) restore(v.getChildAt(i))
            }
            restore(contentRoot)
            scrollRef?.post { scrollRef?.scrollTo(0, sy) }
        } catch (e: Exception) {
            Log.w("AutoResolveTheme", "[화면 다시 그리기 실패] ${e.javaClass.simpleName}")
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 시스템 라이트/다크 변경, 분할·회전 등 창 크기 변경: Activity를 다시 만들지 않고(STT/내비 연결 유지) 화면만 다시 그린다.
        rerender()
    }

    private fun addDropdown(
        title: String,
        current: String,
        options: List<String>,
        onSelect: (String) -> Unit
    ) {
        addText(title, 19f, true, 6)

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val field = text("$current   ⌄", 20f).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            background = background(surface, border)
            isClickable = true
            isFocusable = true
        }

        wrapper.addView(
            field,
            LinearLayout.LayoutParams(-1, dp(80))
        )

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = background(surface, border)
        }

        val scroll = ScrollView(this)

        val items = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        options.forEach { option ->
            val optionView = text(
                if (option == current) "✓  $option" else option,
                20f
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(10), 0)
                background = background(
                    if (option == current) selectedBlue else surface
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { onSelect(option) }
            }

            items.addView(
                optionView,
                LinearLayout.LayoutParams(-1, dp(74))
            )
        }

        scroll.addView(items)
        list.addView(
            scroll,
            LinearLayout.LayoutParams(-1, dp(220))
        )

        wrapper.addView(list)

        field.setOnClickListener {
            val opening = list.visibility != View.VISIBLE
            list.visibility =
                if (opening) View.VISIBLE else View.GONE
            field.text =
                "$current   ${if (opening) "⌃" else "⌄"}"
        }

        container.addView(
            wrapper,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(14)
            }
        )
    }

    private fun showVehicleProfile(
        requestedBrand: String? = null,
        requestedModel: String? = null,
        requestedYear: String? = null,
        requestedPowertrain: String? = null
    ) {
        screen("내 차량 등록")
        lastRender = { showVehicleProfile(requestedBrand, requestedModel, requestedYear, requestedPowertrain) }

        val saved = profileStore.load()
        val brands = VehicleCatalog.brands.keys.toList()

        if (brands.isEmpty()) {
            addText("제조사 목록이 없어요.")
            return
        }

        val brand = requestedBrand?.takeIf {
            it in brands
        } ?: saved?.manufacturer?.takeIf {
            it in brands
        } ?: brands.first()

        val models = VehicleCatalog.getModels(brand)

        if (models.isEmpty()) {
            addText("차량 모델 목록이 없어요.")
            return
        }

        val model = requestedModel?.takeIf {
            VehicleCatalog.getModel(brand, it) != null
        } ?: saved?.model?.takeIf {
            VehicleCatalog.getModel(brand, it) != null
        } ?: models.first().name

        val years = (2027 downTo 2000)
            .map { it.toString() }

        val year = requestedYear?.takeIf {
            it in years
        } ?: saved?.year?.takeIf {
            it in years
        } ?: "2026"

        val powertrains = VehicleCatalog
            .getModel(brand, model)
            ?.powertrains.orEmpty()

        if (powertrains.isEmpty()) {
            addText("동력 방식 정보가 없어요.")
            return
        }

        val powertrain = requestedPowertrain?.takeIf {
            it in powertrains
        } ?: saved?.powertrain?.takeIf {
            it in powertrains
        } ?: powertrains.first()

        addDropdown(
            "제조사",
            VehicleCatalog.brands[brand] ?: brand,
            brands.map { VehicleCatalog.brands[it] ?: it }
        ) { selected ->
            val nextBrand = brands.firstOrNull {
                VehicleCatalog.brands[it] == selected
            } ?: brand

            val nextModel =
                VehicleCatalog.getModels(nextBrand).first()

            showVehicleProfile(
                nextBrand,
                nextModel.name,
                year,
                nextModel.powertrains.first()
            )
        }

        addText("차량 모델 검색", 19f, true, 6)

        val modelSearch = addVoiceInput(
            "예: 캐스퍼, 아이오닉 5, GV70"
        )
        // 목록에서 고른 뒤 다시 그릴 때는 고른 모델명을 입력칸에 채워 둔다. (감시자를 붙이기 전이라 목록은 열리지 않음)
        if (requestedModel != null) modelSearch.setText(model)

        // 입력하는 동안에만 입력칸 바로 아래에 추천 목록을 보여 준다. (매칭은 ModelSearch.kt 의 matchModels)
        val modelList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = background(surface, border)
            visibility = View.GONE
        }

        container.addView(
            modelList,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(15)
            }
        )

        fun suggestionRow(label: String, bold: Boolean, onClick: () -> Unit) {
            modelList.addView(
                text(label, 20f, bold).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(10), 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onClick() }
                },
                LinearLayout.LayoutParams(-1, dp(74))
            )
        }

        fun updateModels(query: String) {
            modelList.removeAllViews()
            modelList.visibility = if (query.isBlank()) View.GONE else View.VISIBLE
            if (query.isBlank()) return

            // 키보드가 올라오면 보이는 영역이 좁아져 목록이 가려진다. 입력칸을 맨 위로 올려 목록이 보이게 한다.
            scrollRef?.let { sc ->
                sc.post {
                    val r = android.graphics.Rect()
                    modelSearch.getDrawingRect(r)
                    sc.offsetDescendantRectToMyCoords(modelSearch, r)
                    sc.smoothScrollTo(0, r.top - dp(6))
                }
            }

            val matches = matchModels(query, models.map { it.name })

            if (matches.isEmpty()) {
                suggestionRow("찾는 차량이 없어요 · 직접 입력하기", false) { openKeyboard(modelSearch) }
            }

            matches.forEach { name ->
                suggestionRow(if (name == model) "✓  $name" else name, name == model) {
                    Log.i("AutoResolveRegister", "[모델 선택] $name")
                    val vehicle = VehicleCatalog.getModel(brand, name) ?: return@suggestionRow
                    (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.hideSoftInputFromWindow(modelSearch.windowToken, 0)

                    // 화면을 다시 그리면서 입력칸 채우기 · 선택한 차량 갱신 · 목록 닫기가 함께 된다.
                    showVehicleProfile(
                        brand,
                        name,
                        year,
                        if (powertrain in vehicle.powertrains) powertrain else vehicle.powertrains.first()
                    )
                }
            }
        }

        // 조합 중인 한글(아직 확정되지 않은 글자)도 Editable 에 들어 있어서 글자를 칠 때마다 불린다.
        modelSearch.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {}

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    updateModels(s?.toString().orEmpty())
                }

                override fun afterTextChanged(
                    s: Editable?
                ) {}
            }
        )

        addDropdown(
            "연식",
            "${year}년식",
            years.map { "${it}년식" }
        ) { selection ->
            showVehicleProfile(
                brand,
                model,
                selection.removeSuffix("년식"),
                powertrain
            )
        }

        addDropdown(
            "동력 방식",
            VehicleCatalog.getPowertrainLabel(powertrain),
            powertrains.map {
                VehicleCatalog.getPowertrainLabel(it)
            }
        ) { selection ->
            val nextPowertrain = powertrains.first {
                VehicleCatalog.getPowertrainLabel(it) ==
                        selection
            }

            showVehicleProfile(
                brand,
                model,
                year,
                nextPowertrain
            )
        }

        addText(
            "실제 판매 연식과 세부 사양은 추가 확인이 필요해요.",
            16f
        )

        // 시안 ⑪: 선택한 차량 · 취소 · 저장하기를 화면 아래에 고정(스크롤해도 항상 보임)
        val brandLabelText = VehicleCatalog.brands[brand] ?: brand
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(surface)
            setPadding(ad(40), ad(24), ad(40), ad(28))
        }
        val summaryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        summaryBox.addView(text("선택한 차량", 16f, false, gray).abs(26f))
        summaryBox.addView(
            text(
                "$brandLabelText $model · ${year}년식 · ${VehicleCatalog.getPowertrainLabel(powertrain)}",
                21f, true
            ).abs(36f)
        )
        bottomBar.addView(summaryBox, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ad(24) })
        val cancelBtn = sizedButton("취소", false, 21f) { showHome() }.abs(36f)
        bottomBar.addView(cancelBtn, LinearLayout.LayoutParams(ad(260), ad(112)).apply { rightMargin = ad(24) })
        val saveBtn = sizedButton("차량 정보 저장하기", true, 21f) {
            profileStore.save(
                VehicleProfile(
                    manufacturer = brand,
                    model = model,
                    year = year,
                    powertrain = powertrain
                )
            )
            toast("차량 정보가 저장됐어요.")
            showHome()
        }.abs(38f)
        bottomBar.addView(saveBtn, LinearLayout.LayoutParams(ad(460), ad(112)))

        val rootBox = contentRoot as? LinearLayout
        val sc = scrollRef
        if (rootBox != null && sc != null) {
            // 스크롤 위치를 알 수 있게 오른쪽 스크롤 막대와 "아래로 더 있어요" 표시를 넣는다
            sc.isVerticalScrollBarEnabled = true
            sc.isScrollbarFadingEnabled = false
            sc.scrollBarStyle = View.SCROLLBARS_OUTSIDE_INSET
            sc.scrollBarSize = ad(14)
            sc.verticalScrollbarThumbDrawable = background(active, null, 7)
            sc.verticalScrollbarTrackDrawable = background(border, null, 7)
            val idx = rootBox.indexOfChild(sc)
            rootBox.removeView(sc)
            val frame = FrameLayout(this)
            frame.addView(sc, FrameLayout.LayoutParams(-1, -1))
            val pageEdge = if (tk.isDark) Color.parseColor("#02060F") else tk.backgroundPrimary
            val hint = text("아래로 더 있어요", 16f, false, gray).abs(26f).apply {
                gravity = Gravity.CENTER
                isClickable = false
                setPadding(0, ad(24), 0, ad(8))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(pageEdge and 0x00FFFFFF, pageEdge)
                )
                getDrawable(R.drawable.ic_chev_down)?.mutate()?.let { d ->
                    d.setTint(gray)
                    d.setBounds(0, 0, ad(32), ad(32))
                    setCompoundDrawablesRelative(null, null, d, null)
                    compoundDrawablePadding = ad(8)
                }
            }
            frame.addView(hint, FrameLayout.LayoutParams(-1, ad(80), Gravity.BOTTOM))
            fun refreshHint() {
                hint.visibility = if (sc.canScrollVertically(1)) View.VISIBLE else View.GONE
            }
            sc.setOnScrollChangeListener { _, _, _, _, _ -> refreshHint() }
            sc.viewTreeObserver.addOnGlobalLayoutListener { refreshHint() }
            rootBox.addView(frame, idx, LinearLayout.LayoutParams(-1, 0, 1f))
            rootBox.addView(View(this).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(-1, ad(2)))
            rootBox.addView(bottomBar, LinearLayout.LayoutParams(-1, -2))
        } else {
            addButton("차량 정보 저장하기") { saveBtn.performClick() }
            addButton("취소", false) { showHome() }
        }
    }

    // 분류 규칙은 SymptomTriage(공용)를 씁니다. 화면 이동은 예전과 같습니다.
    //  위험 → 안전 확인 화면 / 질문 트리가 있는 증상 → 자가 확인 / 그 밖 → 추가 확인(전문 점검 안내)
    private fun analyzeSymptom(description: String) {
        val powertrain = profileStore.load()?.powertrain

        val triage = SymptomTriage.analyze(
            description,
            null,
            powertrain in listOf(VehicleCatalog.EV, VehicleCatalog.PHEV)
        )

        Log.i(
            "AutoResolveTriage",
            "[분류] urgency=${triage.urgency.wire} part=${triage.part.name} " +
                    "issue=${triage.selfCheckIssue} matched=${triage.matched}"
        )

        val issue = triage.selfCheckIssue

        when {
            triage.urgency == Urgency.STOP_NOW -> showSafetyWarning(description)
            issue != null -> showDiagnosis(issue)
            else -> showUnknownSymptom(description)
        }
    }

    private fun showSafetyWarning(
        symptom: String,
        part: PartCategory? = null
    ) {
        screen("안전 확인")
        lastRender = { showSafetyWarning(symptom, part) }

        addText(
            "위험할 수 있는 증상이 포함되어 있어요.",
            23f,
            true
        )

        addText(
            "안전한 곳에 정차하고 운행을 계속하지 마세요. " +
                    "브레이크·조향·화재 관련 이상은 " +
                    "자가 수리보다 긴급 지원이 우선이에요."
        )

        addButton("전문 지원 안내") {
            showServiceReferral(
                symptom,
                null,
                emptyList(),
                true,
                part
            )
        }

        addButton("홈으로", false) {
            showHome()
        }
    }

    private fun showUnknownSymptom(symptom: String) {
        screen("추가 확인 필요")
        lastRender = { showUnknownSymptom(symptom) }

        addText("입력한 증상", 21f, true)
        addText(symptom)

        addText(
            "현재 검증된 자가 확인 절차가 없는 증상이에요. " +
                    "원인을 임의로 단정하지 않고 " +
                    "전문 점검을 안내할게요."
        )

        addButton("전문 점검 안내") {
            showServiceReferral(
                symptom,
                null,
                emptyList()
            )
        }

        addButton("홈으로", false) {
            showHome()
        }
    }

    private fun showDiagnosis(issue: String) {
        val flow = DiagnosisEngine.getFlow(issue)

        if (flow == null) {
            showUnknownSymptom(issue)
            return
        }

        val answers = mutableListOf<Pair<String, String>>()

        fun render(nodeId: String) {
            val question = flow.questions[nodeId]

            if (question != null) {
                screen(flow.title)
                lastRender = { render(nodeId) }
                addText(question.text, 23f, true, 18)

                question.options.forEach { option ->
                    addButton(option.label, false) {
                        answers.add(
                            question.text to option.label
                        )
                        render(option.nextId)
                    }
                }

                addButton("진단 취소", false) {
                    showHome()
                }
                return
            }

            val result = flow.results[nodeId]

            if (result == null) {
                showUnknownSymptom(flow.title)
                return
            }

            screen("자가 확인 결과")
            addText(result.title, 23f, true)
            addText(result.explanation)
            addText("추천 조치", 21f, true)
            addText(result.nextAction)

            addText(
                "참고용 결과이며 실제 고장을 확정하지는 않아요.",
                16f
            )

            if (!result.needsProfessionalHelp) {
                addButton("문제가 해결됐어요") {
                    screen("확인 완료")
                    addText(
                        "같은 문제가 반복되면 전문 점검을 받아 주세요."
                    )
                    addButton("홈으로") {
                        showHome()
                    }
                }
            }

            addButton("전문 점검 받기") {
                showServiceReferral(
                    flow.title,
                    result,
                    answers,
                    false,
                    partForIssue(issue)
                )
            }

            addButton("다시 확인하기", false) {
                showDiagnosis(issue)
            }

            addButton("홈으로", false) {
                showHome()
            }
        }

        render(flow.startId)
    }

    private fun showRepairReport(
        symptom: String,
        result: DiagnosisResult?,
        answers: List<Pair<String, String>>
    ) {
        screen("정비 상담 요약")
        lastRender = { showRepairReport(symptom, result, answers) }

        val profile = profileStore.load()

        val report = buildString {
            appendLine("H.WITH 정비 상담 요약")
            appendLine()

            if (profile != null) {
                appendLine(
                    "차량: " +
                            "${VehicleCatalog.brands[profile.manufacturer] ?: profile.manufacturer} " +
                            profile.model
                )
                appendLine("연식: ${profile.year}")
                appendLine(
                    "동력 방식: " +
                            VehicleCatalog.getPowertrainLabel(
                                profile.powertrain
                            )
                )
            }

            appendLine()
            appendLine("증상: $symptom")

            answers.forEachIndexed { index, answer ->
                appendLine()
                appendLine("${index + 1}. ${answer.first}")
                appendLine("답변: ${answer.second}")
            }

            if (result != null) {
                appendLine()
                appendLine("예비 결과: ${result.title}")
                appendLine("추천 조치: ${result.nextAction}")
            }

            appendLine()
            appendLine(
                "정확한 고장 원인은 전문 정비사의 확인이 필요합니다."
            )
        }

        addText(report, 19f)

        addButton("상담 내용 공유하기") {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, report)
            }

            try {
                startActivity(
                    Intent.createChooser(
                        intent,
                        "정비 상담 내용 공유"
                    )
                )
            } catch (e: Exception) {
                toast("공유 가능한 앱이 없어요.")
            }
        }

        addButton("홈으로", false) {
            showHome()
        }
    }

    // ---------------------------------------------------------------
    // 서비스센터 연결 · 정비 접수 · 다음 차 추천
    // ---------------------------------------------------------------

    private var prefSeatDelta = 0
    private var prefBudgetDelta = 0
    private var prefPerf = 2
    private var prefUsage = UsageLevel.OFTEN

    private fun vehicleLabel(): String {
        val profile = profileStore.load() ?: return "차량 미등록"
        val brand =
            VehicleCatalog.brands[profile.manufacturer]
                ?: profile.manufacturer

        return "$brand ${profile.model} · ${profile.year}년식 · " +
                VehicleCatalog.getPowertrainLabel(profile.powertrain)
    }

    private fun partForIssue(issue: String): PartCategory {
        return when (issue) {
            "charging" -> PartCategory.BATTERY_CHARGING
            "starting" -> PartCategory.ELECTRICAL
            "ac" -> PartCategory.AIR_CONDITIONING
            "tire" -> PartCategory.TIRE
            else -> PartCategory.OTHER
        }
    }

    private fun guessPart(description: String): PartCategory =
        SymptomTriage.guessPart(description)

    private fun shareText(value: String) {
        if (value.isBlank()) return

        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, value)
            }

            startActivity(
                Intent.createChooser(intent, "정비 접수 내용 공유")
            )
        } catch (e: Exception) {
            toast("공유 가능한 앱이 없어요.")
        }
    }

    private fun addCard(
        value: String,
        onClick: (() -> Unit)? = null
    ) {
        val card = text(value, 19f, false).apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = background(surface, border)

            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

        container.addView(
            card,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            }
        )
    }

    private fun showPartSelect() {
        screen("고장 부위 선택")
        lastRender = { showPartSelect() }

        addText(
            "고장 난 부위를 고르면 처리할 수 있는 서비스센터를 바로 안내해요.",
            19f
        )

        PartCategory.values().toList().chunked(2).forEach { pair ->
            val left = pair[0]
            val right = pair.getOrNull(1)

            if (right != null) {
                addQuickRow(
                    left.label,
                    right.label,
                    { onPartSelected(left) },
                    { onPartSelected(right) }
                )
            } else {
                addButton(left.label, false) {
                    onPartSelected(left)
                }
            }
        }

        addButton("홈으로", false) {
            showHome()
        }
    }

    private fun onPartSelected(part: PartCategory) {
        when {
            part.urgent ->
                showSafetyWarning("${part.label} 문제", part)

            // 목록에 없으면 AI 음성 상담으로 이어집니다.
            part == PartCategory.OTHER ->
                openVoiceConsult("부품 목록 '기타'")

            else ->
                showServiceReferral(
                    "${part.label} 문제",
                    null,
                    emptyList(),
                    false,
                    part
                )
        }
    }

    private fun buildRequest(
        symptom: String,
        result: DiagnosisResult?,
        answers: List<Pair<String, String>>,
        part: PartCategory
    ): ServiceRequest {
        val summary = buildString {
            if (result != null) {
                appendLine("예비 결과: ${result.title}")
                appendLine("추천 조치: ${result.nextAction}")
            }

            answers.forEachIndexed { index, answer ->
                appendLine("${index + 1}. ${answer.first} → ${answer.second}")
            }
        }.trim()

        return ServiceRequest(
            id = newRequestId(),
            createdAt = System.currentTimeMillis(),
            vehicle = vehicleLabel(),
            part = part,
            symptom = symptom,
            diagnosisSummary = summary,
            transcript = "",
            centerId = null,
            centerName = null,
            status = RequestStatus.SAVED
        )
    }

    private fun showServiceReferral(
        symptom: String,
        result: DiagnosisResult?,
        answers: List<Pair<String, String>>,
        urgent: Boolean = false,
        part: PartCategory? = null
    ) {
        val resolvedPart = part ?: guessPart(symptom)

        showCenterFinder(
            buildRequest(symptom, result, answers, resolvedPart),
            urgent,
            CenterSort.NEAREST
        )
    }

    /** 저장된 접수의 증상으로 위험도를 다시 판단합니다. 부위(브레이크 등)만으로는 긴급으로 보지 않습니다. */
    private fun triageOf(request: ServiceRequest): TriageResult {
        val profile = profileStore.load()
        val electric = profile != null &&
                profile.powertrain in listOf(VehicleCatalog.EV, VehicleCatalog.PHEV)

        return SymptomTriage.analyze(request.symptom, request.part, electric)
    }

    /**
     * 위험도에 따른 안내 띠. 점검 권장(브레이크일 때만) / 운행 중단 / 긴급.
     * 긴급·운행 중단은 센터 선택보다 위에 두되, 자세한 안내는 눌렀을 때만 펼쳐 화면을 과하게 차지하지 않게 합니다.
     */
    private fun addSafetyBanner(request: ServiceRequest, triage: TriageResult) {
        val severity = triage.severity
        val brake = request.part == PartCategory.BRAKE || triage.part == PartCategory.BRAKE

        if (severity == Severity.NOTICE && !brake) return

        var title = ""
        var body = ""
        var accent = active
        var fill = selectedBlue

        when (severity) {
            Severity.EMERGENCY -> {
                title = "긴급 상황일 수 있어요"
                body = triage.summary
                accent = tk.statusError
                fill = Color.argb(
                    40, Color.red(accent), Color.green(accent), Color.blue(accent)
                )
            }

            Severity.STOP -> {
                title = "운행을 멈추고 안전한 곳에 정차하세요"
                body = triage.summary
                accent = tk.statusWarning
                fill = Color.argb(
                    40, Color.red(accent), Color.green(accent), Color.blue(accent)
                )
            }

            Severity.NOTICE -> {
                title = "전문 점검을 권장해요"
                body = triage.summary.ifEmpty {
                    "원인은 점검해 봐야 알 수 있어요. 제동이 약해지면 바로 안전한 곳에 정차하세요."
                }
                accent = active
                fill = selectedBlue
            }
        }

        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = background(fill, accent)
        }

        banner.addView(text(title, 22f, true, if (severity == Severity.NOTICE) textMain else accent))
        banner.addView(text(body, 17f, false, textMain))

        if (severity != Severity.NOTICE) {
            banner.addView(
                sizedButton("안전 안내 자세히 보기", false, 17f) {
                    showAppDialog(
                        title = triage.headline,
                        message = safetyDetailText(triage)
                    )
                },
                LinearLayout.LayoutParams(-1, ad(80)).apply { topMargin = dp(8) }
            )
        }

        container.addView(
            banner,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        )
    }

    /** 안전 안내 상세 문구. 단계 목록이 있으면 번호를 붙여 나누고, 없으면 기존 안내 문장을 그대로 씁니다. */
    private fun safetyDetailText(triage: TriageResult): String =
        if (triage.steps.isEmpty()) {
            triage.advice
        } else {
            triage.steps
                .mapIndexed { index, step -> "${index + 1}. $step" }
                .joinToString("\n") + "\n\n원인은 점검해 봐야 알 수 있어요."
        }

    /** 접수 확인 화면 맨 위의 안전 안내. 운행 중단·긴급일 때만 보입니다. */
    private fun addSendSafetyNote(triage: TriageResult) {
        if (triage.severity == Severity.NOTICE) return

        val accent = if (triage.severity == Severity.EMERGENCY) tk.statusError else tk.statusWarning
        val fill = Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = background(fill, accent)
        }

        box.addView(text(triage.headline, 19f, true, accent))

        // 제동 불능 등 즉각적인 위험은 차량 제어와 긴급 구조 요청 안내가 먼저입니다.
        if (triage.severity == Severity.EMERGENCY && triage.summary.isNotEmpty()) {
            box.addView(text(triage.summary, 17f, true, textMain))
        }

        box.addView(
            text(
                "안전을 확보하고 운행을 중단한 뒤 정비를 요청하세요. " +
                        "직접 운전해서 센터로 이동하지 마세요.",
                17f,
                false,
                textMain
            )
        )

        container.addView(
            box,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        )
    }

    /** 센터 한 곳의 비교 카드. 이동 시간과 시연용 대기 시간을 나눠 보여 줍니다. */
    private fun centerCompareCard(
        request: ServiceRequest,
        item: RankedCenter,
        tag: String?,
        urgent: Boolean,
        sort: CenterSort,
        severity: Severity
    ): LinearLayout {
        val center = item.center
        val shortName = "센터 " + center.name.substringAfterLast(' ')
        val distance = String.format(Locale.KOREA, "%.1f", center.distanceKm)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = background(surface, border)
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text(shortName, 22f, true), LinearLayout.LayoutParams(0, -2, 1f))

        if (tag != null) {
            head.addView(
                text(tag, 16f, true).apply {
                    setPadding(dp(10), dp(2), dp(10), dp(2))
                    background = background(selectedBlue, active, 14)
                },
                LinearLayout.LayoutParams(-2, -2)
            )
        }
        card.addView(head)

        val minutes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        minutes.addView(text("${item.totalMinutes}분", 26f, true))
        minutes.addView(text("  예상 합계 (시연용)", 16f, false, gray))
        card.addView(minutes)

        card.addView(text("이동 약 ${item.travelMinutes}분 · ${distance}km", 17f, false, gray))
        card.addView(text("대기 약 ${center.waitMinutes}분 (시연용)", 17f, false, gray))

        // 버튼은 접수를 끝내지 않고 접수 내용 확인 화면으로만 이동합니다.
        // 위험 증상일 때는 안전 안내가 먼저 보이도록 채움 없는 버튼으로 낮추되, 눌러서 쓸 수는 있습니다.
        val label = when (severity) {
            Severity.NOTICE -> "이 센터 선택"
            Severity.STOP -> "조치 후 접수 확인"
            Severity.EMERGENCY -> "안전 조치 후 확인"
        }

        card.addView(
            sizedButton(label, severity == Severity.NOTICE, 19f) {
                showSendRequest(request, center, urgent, sort)
            }.apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                minHeight = ad(96)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        )

        return card
    }

    private fun showCenterFinder(
        request: ServiceRequest,
        urgent: Boolean,
        sort: CenterSort
    ) {
        screen("서비스센터 안내")
        lastRender = { showCenterFinder(request, urgent, sort) }

        // 위험도는 부위가 아니라 증상으로 정합니다. 호출한 쪽의 urgent 값은 화면 이동에만 전달합니다.
        val triage = triageOf(request)
        addSafetyBanner(request, triage)

        // 차량·증상 요약(두 줄). 전체 내용은 "접수 내용 보기"에서 봅니다.
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = background(surface, border)
        }
        info.addView(
            text(request.vehicle, 19f, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        )
        info.addView(
            text("${request.part.label} · ${request.symptom}", 17f, false, gray).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        )
        container.addView(
            info,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        )

        val profile = profileStore.load()
        val electric = profile != null &&
                profile.powertrain in listOf(
                    VehicleCatalog.EV,
                    VehicleCatalog.PHEV
                )
        val needsEv =
            electric && request.part == PartCategory.BATTERY_CHARGING

        addQuickRow(
            (if (sort == CenterSort.NEAREST) "✓ " else "") +
                    CenterSort.NEAREST.label,
            (if (sort == CenterSort.FASTEST) "✓ " else "") +
                    CenterSort.FASTEST.label,
            { showCenterFinder(request, urgent, CenterSort.NEAREST) },
            { showCenterFinder(request, urgent, CenterSort.FASTEST) }
        )

        addText(
            "시연용 추정치예요. 실제 이동·대기시간과 다를 수 있어요.",
            16f,
            false,
            8
        )

        val ranked = ServiceCenterRanker.rank(
            DemoServiceCenters.all,
            request.part,
            needsEv,
            sort
        )

        if (ranked.isEmpty()) {
            addText(
                "이 부위를 처리할 수 있는 센터를 찾지 못했어요. " +
                        "고객센터나 긴급출동으로 문의해 주세요."
            )
        } else {
            val nearestId = ranked.minByOrNull { it.center.distanceKm }?.center?.id
            val fastestId = ranked.minByOrNull { it.totalMinutes }?.center?.id

            fun tagOf(item: RankedCenter): String? = when {
                ranked.size < 2 -> null
                item.center.id == fastestId && item.center.id == nearestId -> "가장 가깝고 빠름"
                item.center.id == fastestId -> "예상 가장 빠름"
                item.center.id == nearestId -> "가장 가까움"
                else -> null
            }

            val shown = ranked.take(2)

            // 창 폭이 충분하면 가로로 나란히, 좁으면 세로로 쌓아 글자와 버튼이 잘리지 않게 합니다.
            val availablePx = resources.configuration.screenWidthDp *
                    resources.displayMetrics.density - dp(44)
            val sideBySide = shown.size > 1 && availablePx >= ad(900)

            if (sideBySide) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                shown.forEachIndexed { index, item ->
                    row.addView(
                        centerCompareCard(request, item, tagOf(item), urgent, sort, triage.severity),
                        LinearLayout.LayoutParams(0, -2, 1f).apply {
                            if (index > 0) leftMargin = dp(10)
                        }
                    )
                }

                container.addView(
                    row,
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
                )
            } else {
                shown.forEach { item ->
                    container.addView(
                        centerCompareCard(request, item, tagOf(item), urgent, sort, triage.severity),
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
                    )
                }
            }

            // 3곳 이상이면 나머지는 한 줄 목록으로 둡니다.
            ranked.drop(2).forEachIndexed { index, item ->
                addCard(
                    "${index + 3}. ${item.center.name} · 예상 합계 ${item.totalMinutes}분\n" +
                            "이동 약 ${item.travelMinutes}분 + 대기 약 ${item.center.waitMinutes}분 (시연용)"
                ) {
                    showSendRequest(request, item.center, urgent, sort)
                }
            }
        }

        addQuickRow(
            "나중에 보내기 (저장)",
            "접수 내용 보기",
            {
                requestStore.save(request)
                toast("접수 내역에 저장했어요.")
                showHome()
            },
            { showRequestDetail(request) }
        )
    }

    // 접수 확인 화면의 접기/펼치기 상태. 같은 접수·센터를 보는 동안만 유지하고, 다른 접수를 열면 접힌 상태로 시작합니다.
    private var sendDetailKey: String? = null
    private var sendDetailOpen = false
    private var sendCompact = false

    private fun showSendRequest(
        request: ServiceRequest,
        center: ServiceCenter,
        urgent: Boolean,
        sort: CenterSort
    ) {
        val key = request.id + "|" + center.id
        if (sendDetailKey != key) {
            sendDetailKey = key
            sendDetailOpen = false
            sendCompact = false
        }

        screen(
            "센터에 전달",
            footer = { buildSendFooter(request, center, urgent, sort) }
        )
        lastRender = { showSendRequest(request, center, urgent, sort) }
        container.setPadding(dp(22), dp(8), dp(22), dp(10))

        // 위쪽: 긴급·운행 중단 안전 안내(접히지 않음)
        addSendSafetyNote(triageOf(request))

        // 요약: 센터, 접수번호, 차량, 최초 증상
        addSendSummary(request, center, sendDetailOpen || sendCompact)

        // 상세: 자가 확인 내용과 상담 기록(기본은 접힘)
        val detail = addSendDetail(request)

        addText(
            "시연 모드예요. 실제 서비스센터로는 접수되지 않고, " +
                    "차량 정보와 증상 기록은 이 기기에만 저장돼요.",
            16f,
            false,
            0
        )

        fitSendLayout(detail.first, detail.second)
    }

    private fun addSendSummary(request: ServiceRequest, center: ServiceCenter, compact: Boolean) {
        val info = String.format(Locale.KOREA, "%.1fkm · 대기 %d분 (예시)", center.distanceKm, center.waitMinutes)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = background(surface, border)
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            text(center.name, 19f, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(-2, -2, 0f)
        )
        head.addView(
            text(info, 16f, false, gray).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(14), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        card.addView(head)

        if (compact) {
            card.addView(
                text("${request.id} · ${request.vehicle} · ${request.symptom}", 16f, false, gray).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            )
        } else {
            val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(sendKeyValue("접수번호", request.id, 1), LinearLayout.LayoutParams(0, -2, 1f))
            grid.addView(sendKeyValue("차량", request.vehicle, 1), LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            card.addView(
                sendKeyValue("최초 증상", request.symptom, 2),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) }
            )
        }

        container.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun sendKeyValue(label: String, value: String, maxLines: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(label, 16f, false, gray))
            addView(
                text(value, 19f, true).apply {
                    this.maxLines = maxLines
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            )
        }

    /** 접수에 포함되는 자가 확인 내용과 상담 기록 전체. 내용은 바꾸지 않고 그대로 보여 줍니다. */
    private fun sendDetailText(request: ServiceRequest): String = buildString {
        appendLine("접수에 포함되는 항목 (이 기기에만 저장돼요)")
        appendLine("접수 시각 · 차량 · 고장 부위 · 증상 · 선택한 센터 · 자가 확인 내용 · 상담 기록")
        appendLine("이름과 연락처는 포함되지 않아요.")

        if (request.diagnosisSummary.isNotBlank()) {
            appendLine()
            appendLine("자가 확인 내용")
            appendLine(request.diagnosisSummary)
        }

        if (request.transcript.isNotBlank()) {
            appendLine()
            appendLine("상담 기록")
            appendLine(request.transcript)
        }

        if (request.diagnosisSummary.isBlank() && request.transcript.isBlank()) {
            appendLine()
            appendLine("저장된 자가 확인 내용과 상담 기록이 없어요.")
        }
    }.trim()

    /** 접기/펼치기 카드. 펼쳤을 때만 안쪽 ScrollView가 생기며, 반환값의 두 번째 값이 그 ScrollView입니다. */
    private fun addSendDetail(request: ServiceRequest): Pair<LinearLayout, ScrollView?> {
        val open = sendDetailOpen

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = background(surface, border)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), if (open) dp(10) else dp(2))
            isClickable = true
            isFocusable = true
            contentDescription = if (open) "상담 내용 접기" else "상담 내용 자세히 보기"
            setOnClickListener {
                sendDetailOpen = !sendDetailOpen
                lastRender?.invoke()
            }
            pressable()
        }
        header.addView(text(if (open) "▾" else "▸", 19f, true, active))
        header.addView(
            text("상담 내용 자세히 보기", 19f, true).apply { setPadding(dp(12), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        header.addView(
            text(if (open) "펼침" else "접힘", 16f, true, gray).apply {
                setPadding(dp(12), dp(2), dp(12), dp(2))
                background = background(selectedBlue, border, 14)
            }
        )
        card.addView(header, LinearLayout.LayoutParams(-1, -2))

        var scroller: ScrollView? = null

        if (!open) {
            card.addView(
                text("접수 내용은 그대로 포함돼요 · 자가 확인 내용, 상담 기록", 16f, false, gray).apply {
                    setPadding(dp(18), 0, dp(18), dp(10))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(-1, -2)
            )
        } else {
            card.addView(
                View(this).apply { setBackgroundColor(border) },
                LinearLayout.LayoutParams(-1, 1)
            )

            scroller = ScrollView(this).apply {
                isVerticalScrollBarEnabled = true
                isFillViewport = false
                addView(
                    text(sendDetailText(request), 17f, false).apply {
                        setPadding(dp(18), dp(10), dp(18), dp(12))
                    }
                )
            }
            card.addView(scroller, LinearLayout.LayoutParams(-1, ad(160)))
        }

        container.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        return Pair(card, scroller)
    }

    /**
     * 실제 가용 높이(스크롤 영역 높이) 기준으로 접수 확인 화면을 맞춥니다.
     * - 펼침: 상세 영역 안쪽 ScrollView 높이를 "남는 높이"로 계산(최소 ad(120)). 안전 안내와 하단 버튼은 그대로 보입니다.
     * - 접힘: 내용이 넘치면 한 번만 요약을 2줄로 줄여 다시 그립니다. 그래도 넘치면 가운데 영역만 스크롤됩니다.
     */
    private fun fitSendLayout(card: LinearLayout, detailScroller: ScrollView?) {
        val scroll = scrollRef ?: return
        val owner = container

        scroll.post {
            if (container !== owner) return@post
            val viewport = scroll.height
            if (viewport <= 0) return@post

            var others = owner.paddingTop + owner.paddingBottom
            var cardMargins = 0

            for (i in 0 until owner.childCount) {
                val child = owner.getChildAt(i)
                val lp = child.layoutParams as LinearLayout.LayoutParams
                val margins = lp.topMargin + lp.bottomMargin
                if (child === card) {
                    cardMargins = margins
                } else {
                    others += child.height + margins
                }
            }

            if (detailScroller != null) {
                val chrome = card.height - detailScroller.height
                val target = (viewport - others - cardMargins - chrome).coerceAtLeast(ad(120))
                if (kotlin.math.abs(detailScroller.layoutParams.height - target) > 2) {
                    detailScroller.layoutParams = LinearLayout.LayoutParams(-1, target)
                }
            } else if (!sendCompact && others + card.height + cardMargins > viewport + 1) {
                sendCompact = true
                lastRender?.invoke()
            }
        }
    }

    private fun buildSendFooter(
        request: ServiceRequest,
        center: ServiceCenter,
        urgent: Boolean,
        sort: CenterSort
    ): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val go = sizedButton("센터에 전달하기", true, 24f) {
            dispatchSendRequest(request, center)
        }.apply { minHeight = ad(112) }

        val other = sizedButton("다른 센터 보기", false, 21f) {
            sendDetailKey = null
            showCenterFinder(request, urgent, sort)
        }.apply { minHeight = ad(112) }

        row.addView(go, LinearLayout.LayoutParams(0, -2, 1.5f).apply { rightMargin = dp(14) })
        row.addView(other, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    // 사용자가 「센터에 전달하기」를 눌렀을 때만 실행됩니다. 동작은 이전과 같습니다.
    private fun dispatchSendRequest(request: ServiceRequest, center: ServiceCenter) {
        DemoServiceDispatcher.dispatch(request, center) { ok ->
            runOnUiThread {
                if (ok) {
                    val sent = request.copy(
                        centerId = center.id,
                        centerName = center.name,
                        status = RequestStatus.SENT_DEMO
                    )

                    requestStore.save(sent)
                    showSendComplete(sent)
                } else {
                    toast("전달에 실패했어요. 다시 시도해 주세요.")
                }
            }
        }
    }

    // 센터 이름 끝소리에 맞춰 "을/를"을 고릅니다. 한글 받침이 있으면 "을", 그 밖에는 "를"입니다.
    private fun withObjectParticle(name: String): String {
        val last = name.trimEnd().lastOrNull() ?: return "를"
        val hasFinal = last in '\uAC00'..'\uD7A3' && (last.code - 0xAC00) % 28 != 0
        return if (hasFinal) "을" else "를"
    }

    private fun showSendComplete(request: ServiceRequest) {
        screen("시연 접수 저장 완료")
        lastRender = { showSendComplete(request) }

        addText("접수번호  ${request.id}", 23f, true)

        // 시연에서는 실제 센터로 전송하지 않고 이 기기에만 저장하므로, 그대로 알립니다.
        val savedCenter = request.centerName ?: "서비스센터"
        addText(
            savedCenter + withObjectParticle(savedCenter) +
                    " 선택한 접수 내용이 이 기기에 저장됐어요. " +
                    "실제 센터로는 전송되지 않았어요."
        )

        addText(
            "정확한 고장 원인은 전문 정비사의 확인이 필요해요.",
            16f
        )

        if (NaviLauncher.hasPlace(request.centerId)) {
            addButton("🧭 길안내 시작") {
                NaviLauncher.navigate(
                    this,
                    request.centerId!!,
                    request.centerName ?: "서비스센터"
                ) { message -> toast(message) }
            }
        } else {
            // 검증된 센터 좌표가 없으면 길안내를 시작하지 않고 이유만 알립니다.
            addText(NaviLauncher.NO_NAVIGATION_NOTICE, 16f)
        }

        addButton("정비 접수 내역", false) {
            showRequestHistory()
        }

        addButton("홈으로", false) {
            showHome()
        }
    }

    private fun showRequestHistory() {
        screen("내 정비 접수")
        lastRender = { showRequestHistory() }

        val items = requestStore.loadAll()

        if (items.isEmpty()) {
            addText("아직 접수 내역이 없어요.")
        } else {
            addText("저장된 접수 내용은 이 기기 안에만 있어요.", 16f)

            items.forEach { request ->
                addCard(
                    "${request.id} · ${request.status.label}\n" +
                            "${request.part.label} · " +
                            "${formatDateTime(request.createdAt)}\n" +
                            (request.centerName ?: "센터 미선택")
                ) {
                    showRequestDetail(request)
                }
            }
        }

        addButton("홈으로", false) {
            showHome()
        }

        if (items.isNotEmpty()) {
            addButton("접수 내역 전체 삭제", false) {
                showAppDialog(
                    title = "접수 내역 삭제",
                    message = "저장된 정비 접수 내역을 모두 지울까요? 되돌릴 수 없어요.",
                    positive = "삭제",
                    negative = "취소",
                    onPositive = {
                        requestStore.clear()
                        showRequestHistory()
                    }
                )
            }
        }
    }

    private fun showRequestDetail(request: ServiceRequest) {
        screen("접수 내용")
        lastRender = { showRequestDetail(request) }

        addText("${request.id} · ${request.status.label}", 19f, true)
        addCard(request.toReport())

        if (request.status == RequestStatus.SAVED) {
            addButton("센터 선택해서 전달") {
                showCenterFinder(request, triageOf(request).isSafetyFirst, CenterSort.NEAREST)
            }
        }

        addButton("내용 공유하기", false) {
            shareText(request.toReport())
        }

        addButton("이 접수 삭제", false) {
            requestStore.delete(request.id)
            showRequestHistory()
        }

        addButton("목록으로", false) {
            showRequestHistory()
        }
    }

    private fun addChoice(
        title: String,
        options: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit
    ) {
        addText(title, 20f, true, 6)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val views = mutableListOf<TextView>()

        fun paint(index: Int) {
            views.forEachIndexed { i, view ->
                val on = i == index
                view.setTextColor(if (on) onBrand else textMain)
                view.background = background(
                    if (on) brandFill else surface,
                    if (on) null else border
                )
            }
        }

        options.forEachIndexed { index, label ->
            val view = text(label, 18f, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    paint(index)
                    onSelect(index)
                }
            }

            views.add(view)

            row.addView(
                view,
                LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                    if (index > 0) leftMargin = dp(6)
                }
            )
        }

        paint(selected)

        container.addView(
            row,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(14)
            }
        )
    }

    private fun buildVehicleRecord(): VehicleRecord? {
        val profile = profileStore.load() ?: return null
        val history = requestStore.loadAll()

        return VehicleRecord(
            model = profile.model,
            year = profile.year.toString().toIntOrNull(),
            powertrain = profile.powertrain,
            chargingTroubleCount =
                history.count { it.part == PartCategory.BATTERY_CHARGING },
            serviceRequestCount = history.size
        )
    }

    private fun showNextCarPrefs() {
        screen("나랑 맞을CAR?")
        lastRender = { showNextCarPrefs() }

        val record = buildVehicleRecord()

        if (record == null) {
            addText("먼저 내 차량을 등록하면 차량 기록을 바탕으로 추천해 드려요.")
            addButton("내 차량 등록") {
                showVehicleProfile()
            }
            return
        }

        val age = record.year?.let {
            Calendar.getInstance().get(Calendar.YEAR) - it
        }

        // 이미 기록에 있는 정보는 묻지 않고 보여주기만 합니다.
        addCard(
            "이미 알고 있는 내 차량 기록\n" +
                    "${vehicleLabel()}\n" +
                    (if (age != null && age >= 0) "${age}년째 타고 있어요\n" else "") +
                    "정비 접수 ${record.serviceRequestCount}건" +
                    if (record.chargingTroubleCount > 0) {
                        " (충전 ${record.chargingTroubleCount}건)"
                    } else {
                        ""
                    }
        )

        val driving = drivingStore.snapshot()?.takeIf { it.reliable }

        if (driving != null) {
            addCard(
                (if (drivingStore.demoProfile() != null) {
                    "주행 데이터 (시연용 값)\n"
                } else {
                    "내 주행 데이터에서 읽은 값\n"
                }) + driving.summaryLines().joinToString("\n")
            )
        }

        addText(
            if (driving != null) {
                "주행 데이터에 있는 건 묻지 않고, 나머지만 물어볼게요. " +
                        "현대·기아·제네시스 안에서 골라드려요."
            } else {
                "기록에 없는 것만 물어볼게요. " +
                        "현대·기아·제네시스 안에서 골라드려요."
            },
            18f
        )

        val relative = listOf("지금보다 낮게", "지금과 비슷", "지금보다 높게")

        addChoice(
            "운전 포지션",
            relative,
            prefSeatDelta + 1
        ) { prefSeatDelta = it - 1 }

        addChoice(
            "예산",
            relative,
            prefBudgetDelta + 1
        ) { prefBudgetDelta = it - 1 }

        if (driving == null) {
            addChoice(
                "주행 성향",
                NextCarRecommender.performanceLabels,
                prefPerf - 1
            ) { prefPerf = it + 1 }

            addChoice(
                "차를 타는 빈도",
                UsageLevel.values().map { it.label },
                prefUsage.ordinal
            ) { prefUsage = UsageLevel.values()[it] }

            addText(
                "차량 속도·주행거리 데이터가 쌓이면 이 두 질문은 자동으로 사라져요. " +
                        "고른 값은 이 기기 안에서만 사용해요.",
                16f
            )
        }

        addButton("시연용 주행 데이터 선택", false) {
            showDemoDrivingPicker()
        }

        addButton("추천 보기") {
            showNextCarResult()
        }
    }

    // 실제 속도 데이터가 연결되기 전에 추천 결과가 어떻게 달라지는지 보여 주기 위한 선택입니다.
    private fun showDemoDrivingPicker() {
        val profiles = DemoDrivingProfile.values()
        val labels = profiles.map { it.label } + "시연 데이터 해제"

        showAppDialog(
            title = "시연용 주행 데이터",
            subtitle = "고르면 추천 결과가 달라져요",
            items = labels,
            selectedIndex = profiles.indexOfFirst { it == drivingStore.demoProfile() },
            onPick = { which ->
                drivingStore.setDemo(profiles.getOrNull(which))
                showNextCarPrefs()
            }
        )
    }

    // 추천 결과 가로 넘김에서 마지막으로 보던 쪽(테마·창 크기가 바뀌어 다시 그려도 유지)
    private var carouselPage = 0

    private fun showNextCarResult() {
        screen("다음 차 추천 결과", false, false, approved = true)
        lastRender = { showNextCarResult() }

        val record = buildVehicleRecord()

        if (record == null) {
            addText("차량 기록이 없어 추천할 수 없어요.")
            addButton("홈으로") { showHome() }
            return
        }

        val driving = drivingStore.snapshot()?.takeIf { it.reliable }

        val prefs = NextCarPrefs(
            record = record,
            seatDelta = prefSeatDelta,
            budgetDelta = prefBudgetDelta,
            performance = driving?.performance() ?: prefPerf,
            usage = driving?.usageLevel() ?: prefUsage,
            driving = driving
        )

        val recs = NextCarRecommender.recommend(prefs)
        container.setPadding(dp(40), dp(40), dp(40), dp(32))

        // 머리줄: 뒤로(조건 다시 고르기) · 제목 · 순위 · 홈
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = glassCard(40).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "조건 다시 고르기"
            setOnClickListener { showNextCarPrefs() }
            pressable()
            lift()
            addView(icon(R.drawable.ic_chev_left, 40, textMain))
        }
        header.addView(back, lp(dp(80), dp(80)))
        header.addView(tx("나랑 맞을CAR?", 48f, true).heavy(), lp(0, -2, 1f, l = 22))
        val counter = tx("", 32f, false, gray)
        header.addView(counter, lp(-2, -2, r = 22))
        val homeButton = glassCard(40).apply {
            gravity = Gravity.CENTER
            setPadding(dp(34), 0, dp(34), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { showHome() }
            pressable()
            lift()
            addView(tx("홈", 32f))
        }
        header.addView(homeButton, lp(-2, dp(80)))
        container.addView(header, lp(-1, dp(80)))

        if (recs.isEmpty()) {
            addText("지금 조건으로 추천할 차량이 없어요. 조건을 다시 골라 주세요.", 28f)
            addButton("조건 다시 고르기", false) { showNextCarPrefs() }
            return
        }

        // 가로로 넘기는 사진 카드(가운데가 크게, 양옆 카드는 일부만 보임)
        val cardH = dp(600)
        val hsv = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val track = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hsv.addView(track, FrameLayout.LayoutParams(-2, -1))
        // 남는 세로 공간은 가로 캐러셀이 모두 가진다(가중치 1). 최소 높이만 보장하고, ScrollView가 화면을 채우도록 해 둔 상태에서 동작한다.
        hsv.minimumHeight = cardH
        container.addView(hsv, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(24)
            bottomMargin = dp(20)
        })

        // 점 표시 · 화살표 · 안내 문구
        val dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prevArrow = icon(R.drawable.ic_chev_left, 44, gray).apply { contentDescription = "이전 차량" }
        val nextArrow = icon(R.drawable.ic_chev_right, 44, gray).apply { contentDescription = "다음 차량" }
        dotsRow.addView(prevArrow)
        val dots = ArrayList<View>()
        val dotBox = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        recs.forEach { _ ->
            val d = View(this)
            dots.add(d)
            dotBox.addView(d, lp(dp(14), dp(14), l = 7, r = 7))
        }
        dotsRow.addView(dotBox, lp(-2, -2, l = 22, r = 22))
        dotsRow.addView(tx("옆으로 밀어서 다음 차량 보기", 32f, false, gray), lp(-2, -2, r = 22))
        dotsRow.addView(nextArrow)
        container.addView(dotsRow, lp(-1, dp(64)))
        container.addView(
            tx("시연용 분류 기준이에요. 실제 제원과 가격은 공식 자료로 확인하세요.", 26f, false, gray)
                .apply { gravity = Gravity.CENTER },
            lp(-1, -2, t = 8)
        )

        var step = 0
        val cards = ArrayList<View>()

        fun currentPage(): Int =
            if (step <= 0) 0 else ((hsv.scrollX + step / 2) / step).coerceIn(0, recs.size - 1)

        fun refresh() {
            val p = currentPage()
            carouselPage = p
            counter.text = "추천 ${p + 1}순위 · ${p + 1} / ${recs.size}"
            dots.forEachIndexed { i, d ->
                d.layoutParams = lp(if (i == p) dp(50) else dp(14), dp(14), l = 7, r = 7)
                d.background = background(if (i == p) active else border, null, 7)
            }
            // 가운데에서 멀수록 흐리게(승인된 시안의 양옆 카드 표현)
            cards.forEachIndexed { i, c ->
                val dist = if (step > 0) Math.abs(hsv.scrollX - i * step).toFloat() / step else 0f
                c.alpha = 1f - 0.45f * dist.coerceIn(0f, 1f)
            }
        }

        fun snapTo(page: Int) {
            hsv.smoothScrollTo(page.coerceIn(0, recs.size - 1) * step, 0)
        }
        prevArrow.setOnClickListener { snapTo(currentPage() - 1) }
        nextArrow.setOnClickListener { snapTo(currentPage() + 1) }
        hsv.setOnScrollChangeListener { _, _, _, _, _ -> refresh() }
        hsv.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP ||
                e.actionMasked == android.view.MotionEvent.ACTION_CANCEL
            ) {
                hsv.post { snapTo(currentPage()) }
            }
            false
        }

        // 가로 너비가 정해진 뒤 카드 크기를 계산해 한 번만 만든다.
        var builtW = 0
        var builtH = 0
        hsv.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w <= 0 || h <= 0 || (w == builtW && h == builtH)) return@addOnLayoutChangeListener
            builtW = w
            builtH = h
            hsv.post {
                track.removeAllViews()
                cards.clear()
                val cardW = (w * 0.64f).toInt()
                val gap = dp(20)
                step = cardW + gap
                val side = (w - cardW) / 2
                track.setPadding(side, 0, side, 0)
                recs.forEachIndexed { i, rec ->
                    val brand = VehicleCatalog.brands[rec.car.brand] ?: rec.car.brand
                    val card = carCard(rec, brand, h)
                    cards.add(card)
                    track.addView(
                        card,
                        LinearLayout.LayoutParams(cardW, h).apply {
                            if (i < recs.size - 1) rightMargin = gap
                        }
                    )
                }
                hsv.scrollTo(carouselPage.coerceIn(0, recs.size - 1) * step, 0)
                refresh()
            }
        }
    }

    // 추천 카드 한 장: 위쪽에 차량 사진, 아래쪽 반투명 패널에 모델명·분류·시승 매장 버튼.
    private fun carCard(rec: CarRecommendation, brandLabel: String, heightPx: Int): FrameLayout {
        val dark = tk.isDark
        val glass = if (dark) Color.parseColor("#B8051430") else Color.parseColor("#E0FFFFFF")
        val panelH = (heightPx * 0.354f).toInt()

        val card = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (dark) intArrayOf(Color.parseColor("#103261"), Color.parseColor("#0A2347"), Color.parseColor("#0B3B57"))
                else intArrayOf(Color.WHITE, HyundaiCi.SAND)
            ).apply {
                cornerRadius = dp(40).toFloat()
                setStroke(dp(2), border)
            }
            clipToOutline = true
        }

        val photo = ImageView(this).apply {
            val res = CarImages.drawableFor(rec.car.name)
            if (res != null) {
                setImageResource(res)
                contentDescription = "$brandLabel ${rec.car.name}"
            } else {
                setImageResource(R.drawable.ic_car)
                setColorFilter(active)
                contentDescription = "$brandLabel ${rec.car.name} (사진 없음)"
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(36), dp(100), dp(36), dp(12))
        }
        card.addView(photo, FrameLayout.LayoutParams(-1, heightPx - panelH, Gravity.TOP))

        // 위쪽 칩: 추천 이유 최대 2개(한 줄씩)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rec.reasons.take(2).forEachIndexed { i, reason ->
            chips.addView(
                tx(reason.trimEnd('.'), 30f).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(26), dp(12), dp(26), dp(12))
                    background = background(glass, border, 30)
                },
                lp(-2, -2, t = if (i == 0) 0 else 12)
            )
        }
        card.addView(chips, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(30), dp(30), dp(30), 0)
        })

        // 아래 패널
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(40), 0, dp(40), 0)
            setBackgroundColor(glass)
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(
            tx(rec.car.name, 58f, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        )
        info.addView(
            tx("$brandLabel · ${rec.car.body} · ${rec.car.powertrains.joinToString("/") { VehicleCatalog.getPowertrainLabel(it) }}", 32f, false, gray)
                .apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            lp(-1, -2, t = 6)
        )
        panel.addView(info, lp(0, -2, 1f, r = 24))
        val findStore = tx("시승 매장 보기", 38f, true, onBrand).apply {
            gravity = Gravity.CENTER
            background = background(brandFill, null, 60)
            isClickable = true
            isFocusable = true
            setOnClickListener { showTestDriveStores(rec.car.brand, brandLabel, rec.car.name) }
            pressable()
        }
        panel.addView(findStore, LinearLayout.LayoutParams(dp(380), dp(120)))
        card.addView(panel, FrameLayout.LayoutParams(-1, panelH, Gravity.BOTTOM))

        return card
    }

    private var dealerNavi: DealerNavi? = null
    private var selectedStore: DealerStore? = null
    private var geoCall: KakaoGeocoder.Call? = null
    private var dealerScreenToken = 0

    // 시승 매장 화면 자원 정리: 이 화면이 직접 만든 NaviHelper(DealerNavi)와 진행 중 지오코딩만 대상.
    // NaviLauncher(서비스센터)의 NaviHelper는 별개 인스턴스라 건드리지 않는다.
    private fun releaseDealerResources() {
        dealerScreenToken++ // 아직 도착하지 않은 요청 결과를 무효화
        geoCall?.cancel()
        geoCall = null
        dealerNavi?.release()
        dealerNavi = null
        selectedStore = null
    }

    // 시승 매장 찾기 (추천 결과에서 brand/모델명을 받아 옴). 시승 예약은 앱에서 완료되지 않음.
    // 구조: 상단 고정(홈·제목·차량명·지역 검색) / 중앙 매장 목록만 스크롤 / 하단 고정 액션 패널.
    // 목록 갱신·매장 선택은 화면 전환이 아니므로 screen()을 다시 부르지 않는다(자원 정리 대상 아님).
    private fun showTestDriveStores(
        brandCode: String,
        brandLabel: String,
        carName: String,
        region: String = "",
        restoreAddress: String? = null,
        autoNavigate: Boolean = false
    ) {
        screen("시승 매장 찾기") // 이전 화면의 NaviHelper·지오코딩은 screen()에서 정리됨
        lastRender = { showTestDriveStores(brandCode, brandLabel, carName, region, selectedStore?.address ?: restoreAddress, false) }
        val token = dealerScreenToken
        val root = findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout

        // ── 상단 고정 영역(차량명 + 지역 검색)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(22), dp(2), dp(22), dp(12))
        }
        header.addView(text("$brandLabel $carName", 22f, true))
        header.addView(text("시승 가능 여부 확인 필요 · 이 앱에서 예약은 완료되지 않아요", 16f, false, gray))
        val regionInput = createInput("지역 입력 (예: 강남구)", false).apply {
            setSingleLine(true)
            setText(region)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        searchRow.addView(regionInput, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(header, 1, LinearLayout.LayoutParams(-1, -2))

        // ── 하단 고정 액션 패널
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(22), dp(10), dp(22), dp(12))
        }
        val summary = text("", 19f, true)
        val toggle = sizedButton("접기", false, 18f) { }
        val moreBtn = sizedButton("더보기", false, 18f) { }
        fun chev(btn: TextView, up: Boolean) {
            val d = getDrawable(if (up) R.drawable.ic_chev_up else R.drawable.ic_chev_down)?.mutate() ?: return
            d.setTint(textMain)
            d.setBounds(0, 0, ad(32), ad(32))
            btn.setCompoundDrawablesRelative(null, null, d, null)
            btn.compoundDrawablePadding = ad(10)
        }
        chev(toggle, false)
        chev(moreBtn, false)
        val summaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        summaryRow.addView(summary, LinearLayout.LayoutParams(0, -2, 1f))
        summaryRow.addView(moreBtn, LinearLayout.LayoutParams(-2, ad(88)).apply { rightMargin = ad(16) })
        summaryRow.addView(toggle, LinearLayout.LayoutParams(-2, ad(88)))
        panel.addView(summaryRow, LinearLayout.LayoutParams(-1, -2))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = text("", 18f, true).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(selectedBlue)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(2), active)
            }
            visibility = View.GONE
            // 어디서 status.text를 바꿔도 내용이 있을 때만 눈에 띄게 표시
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
                }
            })
        }
        body.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(8) })
        panel.addView(body, LinearLayout.LayoutParams(-1, -2))
        root.addView(View(this).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(-1, dp(1)))
        root.addView(panel, LinearLayout.LayoutParams(-1, -2))

        fun setCollapsed(c: Boolean) {
            body.visibility = if (c) View.GONE else View.VISIBLE
            toggle.text = if (c) "펼치기" else "접기"
            chev(toggle, c)
        }
        toggle.setOnClickListener { setCollapsed(body.visibility == View.VISIBLE) }
        // 키보드가 열리는 입력 중에는 패널을 접어 목록·입력창이 가려지지 않게 함
        regionInput.setOnFocusChangeListener { _, hasFocus -> setCollapsed(hasFocus) }

        // ── 목록(스크롤) 카드: 선택 여부를 배경·테두리로 구분
        val cards = ArrayList<Pair<String, TextView>>()
        fun styleCards() {
            val sel = selectedStore?.address
            cards.forEach { (addr, tv) ->
                tv.background = if (addr == sel) {
                    GradientDrawable().apply {
                        setColor(selectedBlue)
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(3), active)
                    }
                } else background(surface, border)
            }
        }

        // ── 동작
        fun mapSearch(store: DealerStore) {
            Log.i("AutoResolveDealerNav", "[지도에서 보기 클릭] ${store.name}")
            status.text = TestDriveLinks.openMapSearch(this, store).message
        }
        // 좌표 없이 주소로 길안내 요청(google.navigation) → 안 되면 지도 검색 → 안 되면 원인 표시
        fun addressRoute(store: DealerStore, prefix: String) {
            status.text = prefix + MapIntents.routeByAddress(this, store).message
        }

        fun ensureNavi() {
            if (dealerNavi == null) {
                dealerNavi = DealerNavi(this) { msg ->
                    runOnUiThread { if (token == dealerScreenToken && !isFinishing && !isDestroyed) status.text = msg }
                }
            }
        }

        fun startNavigation() {
            val navTag = "AutoResolveDealerNav"
            Log.i(navTag, "[길안내 버튼 클릭]")
            val store = selectedStore
            if (store == null) {
                Log.w(navTag, "[선택 매장 확인] 선택된 매장 없음")
                status.text = "먼저 매장을 선택하세요."
                return
            }
            Log.i(
                navTag,
                "[선택 매장 확인] ${store.name} / ${store.brandCode} / lat=${store.lat} lon=${store.lon} " +
                        "좌표확인=${store.canNavigate} 좌표출처=${store.coordSource ?: "없음"} DEMO_MODE=${BuildConfig.DEMO_MODE}"
            )
            Log.i(navTag, "[카카오 API 키 설정 여부] ${if (KakaoGeocoder.enabled) "설정됨" else "설정 안 됨"}")
            if (store.canNavigate) {
                ensureNavi()
                dealerNavi?.start(store)
            } else if (KakaoGeocoder.enabled) {
                status.text = "주소 좌표를 확인하는 중이에요…"
                geoCall?.cancel() // 연속 탭 시 이전 요청 취소
                geoCall = KakaoGeocoder.lookup(store.address) { r ->
                    runOnUiThread {
                        // 화면을 떠났거나, 그 사이 다른 매장을 골랐으면 무시
                        if (token != dealerScreenToken || isFinishing || isDestroyed) return@runOnUiThread
                        if (selectedStore?.address != store.address) return@runOnUiThread
                        geoCall = null
                        when (r) {
                            is AddressMatcher.Result.Ok -> {
                                // 메모리에만 보관(저장 안 함). 화면을 나가면 사라짐
                                val verified = store.withVerifiedCoord(
                                    r.lat, r.lon, "카카오 로컬 API 실시간 조회(${store.verifiedAt} 앱 실행 중)"
                                )
                                selectedStore = verified
                                status.text = "주소 일치 확인: ${r.matchedAddress}. 좌표는 저장하지 않아요."
                                ensureNavi()
                                dealerNavi?.start(verified)
                            }
                            is AddressMatcher.Result.Fail -> {
                                Log.w(navTag, "[지오코딩 실패 → 주소 길안내로 대체] ${r.reason}")
                                addressRoute(store, "좌표를 확인하지 못했어요(${r.reason}). ")
                            }
                        }
                    }
                }
            } else {
                // 키가 없으면 좌표 변환·NaviHelper 경로 요청은 진행할 수 없음 → 주소 기반 길안내로 대체
                Log.w(navTag, "[좌표 없음 + 키 없음] NaviHelper 경로 요청 불가 → 주소 길안내 시도")
                addressRoute(store, "이 빌드에는 주소 좌표를 확인할 키가 없어 내비게이션(NaviHelper) 길안내는 쓸 수 없어요. ")
            }
        }

        fun selectStore(store: DealerStore) {
            if (selectedStore?.address == store.address) return
            geoCall?.cancel(); geoCall = null      // 이전 매장의 좌표 조회 취소
            dealerNavi?.cancelPending()            // 이전 매장으로 대기 중이던 길안내 폐기
            selectedStore = store
            status.text = ""
            summary.text = "선택: ${store.name}\n${store.address}"
            styleCards()
            // 길안내 가능성이 있으면 미리 연결해 첫 탭 지연을 줄임
            if (store.canNavigate || KakaoGeocoder.enabled) ensureNavi()
        }

        // ── 핵심 버튼 3개(같은 줄) + 보조 버튼 줄
        fun bigButton(title: String, onClick: () -> Unit): TextView = buttonView(title, true, onClick)
        val primaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addToRow(row: LinearLayout, v: View, h: Int) {
            row.addView(v, LinearLayout.LayoutParams(0, dp(h), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        }
        val btnInquiry = bigButton("시승 가능 여부 문의") {
            val st = selectedStore
            if (st == null) status.text = "먼저 매장을 선택하세요."
            else showTestDriveInquiry(brandCode, brandLabel, carName, regionInput.text.toString(), st)
        }
        val btnApply = bigButton("시승 신청서 작성") {
            val st = selectedStore
            if (st == null) status.text = "먼저 매장을 선택하세요."
            else showTestDriveForm(brandCode, brandLabel, carName, regionInput.text.toString(), st)
        }
        val btnNavi = bigButton("매장까지 길안내 시작") { startNavigation() }
        addToRow(primaryRow, btnInquiry, 76)
        addToRow(primaryRow, btnApply, 76)
        addToRow(primaryRow, btnNavi, 76)
        body.addView(primaryRow, LinearLayout.LayoutParams(-1, -2))

        // 보조 기능은 「더보기」 메뉴 안(기본 숨김)
        val auxRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        addToRow(auxRow, buttonView("지도에서 보기", false) {
            val st = selectedStore
            if (st == null) status.text = "먼저 매장을 선택하세요." else mapSearch(st)
        }, 64)
        addToRow(auxRow, buttonView("주소 복사", false) {
            val st = selectedStore
            status.text = if (st == null) "먼저 매장을 선택하세요."
            else if (TestDriveLinks.copyAddress(this, st)) "✔ 주소가 복사되었습니다\n${st.address}"
            else "주소를 복사하지 못했어요. 위 주소를 직접 확인해 주세요."
        }, 64)
        addToRow(auxRow, buttonView("공식 판매점 찾기", false) {
            Log.i("AutoResolveTestDrive", "[공식 판매점 찾기 클릭] brand=$brandCode")
            status.text = TestDriveLinks.open(this, brandCode, TestDriveLinks.Kind.FIND_STORE)
                ?: "브라우저로 공식 판매점 페이지를 열었어요. 화면에 안 보이면 브라우저가 없는 것일 수 있어요."
        }, 64)
        addToRow(auxRow, buttonView("공식 사이트 신청 안내", false) {
            showTestDriveGuide(brandCode, brandLabel, carName, regionInput.text.toString(), selectedStore)
        }, 64)
        addToRow(auxRow, buttonView("추천 결과로", false) { showNextCarResult() }, 64)
        body.addView(auxRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        moreBtn.setOnClickListener {
            val show = auxRow.visibility != View.VISIBLE
            auxRow.visibility = if (show) View.VISIBLE else View.GONE
            chev(moreBtn, show)
        }

        // 매장 미선택 시 매장이 필요한 버튼은 흐리게(온라인 시승 신청은 매장 없이도 가능)
        fun refreshEnabledLook() {
            val on = selectedStore != null
            btnInquiry.alpha = if (on) 1f else 0.45f
            btnApply.alpha = if (on) 1f else 0.45f
            btnNavi.alpha = if (on) 1f else 0.45f
            if (!on) summary.text = "매장을 선택하면 문의·길안내를 바로 쓸 수 있어요"
        }

        // ── 목록 채우기(화면 전환 없이 제자리 갱신)
        fun renderList(query: String) {
            container.removeAllViews()
            cards.clear()
            val stores = DealerDirectory.search(this, brandCode, query)
            val sel = selectedStore
            if (sel != null && stores.none { it.address == sel.address }) {
                geoCall?.cancel(); geoCall = null
                dealerNavi?.cancelPending()
                selectedStore = null
            }
            if (stores.isEmpty()) {
                container.addView(
                    text("앱에 확인된 $brandLabel 판매점 정보가 없어요. 「공식 판매점 찾기」로 공식 페이지에서 확인하세요.", 18f),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
                )
            }
            stores.forEach { store ->
                val card = text(
                    (if (store.demo) "[시연용 데이터]\n" else "") +
                            "${store.name}\n${store.address}\n" +
                            "전화: ${store.phone ?: "정보 없음"} · 시승 가능 여부 확인 필요\n" +
                            "출처 확인일 ${store.verifiedAt}",
                    19f, false
                ).apply {
                    setPadding(dp(18), dp(14), dp(18), dp(14))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        regionInput.clearFocus()
                        selectStore(store)
                        refreshEnabledLook()
                    }
                }
                container.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                cards.add(store.address to card)
            }
            styleCards()
            refreshEnabledLook()
            selectedStore?.let { summary.text = "선택: ${it.name}\n${it.address}" }
        }

        val doSearch = {
            regionInput.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(regionInput.windowToken, 0)
            renderList(regionInput.text.toString())
        }
        searchRow.addView(
            buttonView("검색", false) { doSearch() },
            LinearLayout.LayoutParams(dp(110), dp(60)).apply { leftMargin = dp(8) }
        )
        regionInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }

        renderList(region)
        // 온라인 시승 신청 안내 화면에서 돌아온 경우 선택 복원
        restoreAddress?.let { addr ->
            DealerDirectory.search(this, brandCode, region).firstOrNull { it.address == addr }?.let {
                selectStore(it)
                refreshEnabledLook()
                styleCards()
            }
        }
        // 이 화면은 입력창에 포커스가 가지 않도록(키보드 자동 열림 방지)
        container.requestFocus()
        // 문의 화면에서 「매장까지 길안내」를 눌러 돌아온 경우: 사용자가 고른 그 매장으로만 시작
        if (autoNavigate && selectedStore != null) startNavigation()
    }

    // 온라인 시승 신청 안내: 앱은 예약을 완료하지 않고 공식 사이트로만 연결. 개인정보 입력 폼 없음.
    private fun showTestDriveGuide(
        brandCode: String,
        brandLabel: String,
        carName: String,
        region: String,
        store: DealerStore?
    ) {
        screen("온라인 시승 신청 안내")
        lastRender = { showTestDriveGuide(brandCode, brandLabel, carName, region, store) }
        addText("$brandLabel $carName", 24f, true, 6)
        addCard(
            if (store != null) {
                "선택한 매장\n${store.name}\n${store.address}\n전화: ${store.phone ?: "정보 없음"}"
            } else {
                "선택한 매장 없음 (공식 사이트에서 직접 고르세요)"
            }
        )
        addText("시승 가능 여부 확인 필요", 19f, true, 6)
        addText(
            "이 앱에서는 시승 예약이 완료되지 않아요. 실제 신청은 $brandLabel 공식 사이트에서 직접 진행해야 해요.",
            18f
        )
        addText(
            "• 공식 사이트에서 모델과 장소를 다시 선택해야 할 수 있어요.\n" +
                    "• 본인 인증과 개인정보 입력이 필요할 수 있어요. 이 앱에는 입력하지 않아요.\n" +
                    "• 차량 화면에서는 웹 신청이 어려울 수 있어요. 주차한 뒤 진행하거나 휴대폰·PC에서 신청하세요.\n" +
                    "• 운전 중에는 사용하지 마세요.",
            18f
        )
        val guideStatus = addNotice()
        addButton("공식 시승 신청 페이지 열기", true) {
            Log.i("AutoResolveTestDrive", "[공식 시승 신청 버튼 클릭] brand=$brandCode")
            val err = TestDriveLinks.open(this, brandCode, TestDriveLinks.Kind.TEST_DRIVE)
            guideStatus.text = err ?: "브라우저로 열었어요. 열리지 않으면 아래 주소를 복사해 휴대폰에서 열어 주세요."
        }
        val applyUrl = TestDriveLinks.urlFor(brandCode, TestDriveLinks.Kind.TEST_DRIVE)
        if (applyUrl != null) {
            addCard("공식 신청 주소\n$applyUrl")
            addButton("신청 주소 복사", false) {
                guideStatus.text = if (TestDriveLinks.copyText(this, "시승 신청 주소", applyUrl)) "✔ 주소가 복사되었습니다" else "복사하지 못했어요. 위 주소를 직접 확인해 주세요."
            }
        }
        if (store?.phone != null) {
            addButton("매장에 전화로 문의", false) {
                val err = TestDriveLinks.dial(this, store.phone)
                if (err != null) guideStatus.text = err
            }
        }
        addButton("매장 선택으로 돌아가기", false) {
            showTestDriveStores(brandCode, brandLabel, carName, region, store?.address)
        }
        addButton("추천 결과로 돌아가기", false) { showNextCarResult() }
    }

    // 내용이 있을 때만 눈에 띄게 보이는 안내 박스(복사 성공, 오류 원인 등)
    private fun addNotice(): TextView {
        val v = text("", 18f, true).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(selectedBlue)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(2), active)
            }
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
                }
            })
        }
        container.addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        return v
    }

    // 시승 신청서(앱 안 작성). 접수처가 없어 이 기기 메모리에만 임시 보관하고 어디로도 전송하지 않는다.
    private var parkedConfirmed = false      // 정차 확인(자동 주행 감지는 아직 연결되지 않음 → 사용자 확인)
    private var draftHolder: TestDriveDraft? = null
    private val submitter: TestDriveSubmitter = NoTestDriveSubmitter
    private val demoStore by lazy { DemoReservationStore(this) }

    private fun showTestDriveForm(
        brandCode: String,
        brandLabel: String,
        carName: String,
        region: String,
        store: DealerStore
    ) {
        screen("시승 신청서 작성")
        lastRender = { showTestDriveForm(brandCode, brandLabel, carName, region, store) }
        addText("$brandLabel $carName", 24f, true, 6)

        // 운전 중 개인정보 입력 제한.
        // Vehicle SDK에서 속도 API는 확인되지 않았고, 기어(Gear) 신호는 권한·값 매핑이 미확인이라 연결하지 않았다.
        // 따라서 정차 여부를 앱이 확인할 수 없다.
        //  - 시연 모드(local.properties autoresolve.demo.mode=true 로 명시한 개발용 빌드): 사용자 선언 후 작성 가능. 정차 확인이 아님을 화면에 명시.
        //  - 기본(시연 모드 꺼짐, 실차 포함 모든 빌드): 정차를 확인할 수 없으므로 개인정보 입력을 허용하지 않음.
        if (!(BuildConfig.DEMO_MODE && parkedConfirmed)) {
            if (BuildConfig.DEMO_MODE) {
                addCard(
                    "[시연 모드] 개발용 설정으로 켜진 시연 화면이에요.\n" +
                            "이 앱은 차량이 정차했는지 확인하지 못해요. 아래 버튼은 정차 확인이 아니라 " +
                            "사용자의 선언일 뿐이며, 운전 중 제한을 충족하는 기능이 아니에요.\n" +
                            "시연 모드를 끈 빌드에서는 이 입력 기능이 막혀요."
                )
                addButton("시연용으로 작성 화면 열기", true) {
                    parkedConfirmed = true
                    Log.i("AutoResolveTestDrive", "[신청서] 시연 모드 선언(정차 확인 아님)")
                    showTestDriveForm(brandCode, brandLabel, carName, region, store)
                }
            } else {
                addCard(
                    "이 앱은 차량이 정차했는지 확인할 수 없어서, 앱 안에서 이름·연락처 입력은 지원하지 않아요.\n" +
                            "시승 신청은 안전한 곳에 정차한 뒤 공식 사이트나 매장 전화로 진행해 주세요."
                )
                addButton("공식 사이트에서 신청하는 방법", true) {
                    showTestDriveGuide(brandCode, brandLabel, carName, region, store)
                }
            }
            addButton("돌아가기", false) {
                showTestDriveInquiry(brandCode, brandLabel, carName, region, store)
            }
            return
        }

        var draft = draftHolder?.takeIf { it.isFor(carName, store.address) }
            ?: TestDriveDraft.of(brandLabel, carName, store)

        val banner = text("시연용 신청서 · 실제 예약 아님", 19f, true, onBrand).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = background(brandFill)
        }
        container.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        addCard("선택한 매장\n${store.name}\n${store.address}\n전화: ${store.phone ?: "정보 없음"}\n시승 가능 여부 확인 필요")

        fun field(label: String, hint: String, value: String, phone: Boolean = false): EditText {
            addText(label, 18f, true, 4)
            val e = createInput(hint, false).apply {
                setSingleLine(true)
                setText(value)
                if (phone) inputType = InputType.TYPE_CLASS_PHONE
            }
            container.addView(e, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            return e
        }
        val dateIn = field("희망 시승 날짜", "예: 11월 20일", draft.date)
        val timeIn = field("희망 시승 시간", "예: 오후 2시", draft.time)
        val nameIn = field("신청자 이름", "이름", draft.applicantName)
        val phoneIn = field("연락처", "예: 010-0000-0000", draft.applicantPhone, phone = true)

        addCard(
            "시연용 안내 · 실제 예약 아님\n" +
                    "• 이 신청서는 H.WITH 시연용이에요. 현대·기아나 매장으로 전송되지 않아요.\n" +
                    "• 이름·연락처는 가상 정보만 입력하세요 (이름은 '시연'으로 시작, 연락처는 010-0000-0000 형식). 실제 개인정보 입력 금지.\n" +
                    "• 접수하면 가상 정보와 시연 접수번호가 이 기기 앱 저장소에 저장돼요. '내 시연 예약 내역'에서 취소·전체 삭제할 수 있어요.\n" +
                    "• 실제 시승 차량이나 시간이 확보된 것이 아니에요."
        )
        val notice = addNotice()
        var consent = draft.consentGiven
        val consentBtn = buttonView(if (consent) "☑ 위 내용에 동의합니다" else "☐ 위 내용에 동의합니다", false) { }
        consentBtn.setOnClickListener {
            consent = !consent
            consentBtn.text = if (consent) "☑ 위 내용에 동의합니다" else "☐ 위 내용에 동의합니다"
        }
        container.addView(consentBtn, LinearLayout.LayoutParams(-1, dp(76)).apply { bottomMargin = dp(10) })

        fun capture(): TestDriveDraft = draft.copy(
            date = dateIn.text.toString().trim(),
            time = timeIn.text.toString().trim(),
            applicantName = nameIn.text.toString().trim(),
            applicantPhone = phoneIn.text.toString().trim(),
            consentGiven = consent
        )

        // 테마·창 크기가 바뀌어 화면을 다시 그릴 때 작성 중인 값(동의 체크 포함)을 임시 보관함에 넣어 둔다.
        preserveHook = { draftHolder = capture() }
        addButton("가상 시연 정보 채우기", false) {
            nameIn.setText(DemoRules.SAMPLE_NAME)
            phoneIn.setText(DemoRules.SAMPLE_PHONE)
        }
        addButton("작성 내용 임시 저장", false) {
            val d = capture()
            val missing = d.missingFields()
            if (missing.isNotEmpty()) {
                draft = d.copy(status = TestDriveDraft.Status.EDITING)
                draftHolder = draft
                notice.text = "아직 비어 있어요: ${missing.joinToString(", ")}"
            } else {
                draft = d.copy(status = TestDriveDraft.Status.SAVED_LOCALLY)
                draftHolder = draft
                banner.text = "임시 저장됨 · 시연용 · 실제 예약 아님"
                notice.text = "임시 저장했어요(앱 종료 시 사라짐). 접수·예약은 되지 않았고 어디로도 전송되지 않았어요."
                // 개인정보는 로그에 남기지 않음
                Log.i("AutoResolveTestDrive", "[신청서] 임시 저장(메모리) 필드 입력 완료, 전송 없음")
            }
        }
        // 시연 예약 접수: 앱 내부 저장만 한다(외부 전송 없음, 실제 예약 아님). 시연 모드에서만 이 화면에 도달한다.
        addButton("시연 예약 접수 (실제 예약 아님)", true) {
            val d = capture()
            val problems = d.missingFields() + DemoRules.problems(d)
            if (problems.isNotEmpty()) {
                notice.text = "확인해 주세요: ${problems.joinToString(" / ")}"
            } else {
                val r = demoStore.create(d)
                if (r == null) {
                    notice.text = "시연 예약을 저장하지 못했어요. (시연 모드가 꺼져 있거나 저장 오류)"
                } else {
                    draftHolder = null
                    Log.i("AutoResolveTestDrive", "[시연 예약] 접수번호 ${r.id} 저장(앱 내부, 외부 전송 없음)")
                    showDemoReservationDone(brandCode, brandLabel, carName, region, store, r)
                }
            }
        }
        addButton("내 시연 예약 내역", false) {
            draftHolder = capture()
            showDemoReservations(brandCode, brandLabel, carName, region, store)
        }
        addButton("공식 사이트에서 신청하는 방법", false) {
            draftHolder = capture()
            showTestDriveGuide(brandCode, brandLabel, carName, region, store)
        }
        addButton("작성 내용 지우기", false) {
            draftHolder = null
            parkedConfirmed = false
            showTestDriveForm(brandCode, brandLabel, carName, region, store)
        }
        addButton("문의 화면으로 돌아가기", false) {
            draftHolder = capture()
            showTestDriveInquiry(brandCode, brandLabel, carName, region, store)
        }
    }

    // 시연 예약 접수 결과 화면. 앱 내부에만 저장된 데모 데이터이며 실제 예약이 아니다.
    private fun showDemoReservationDone(
        brandCode: String,
        brandLabel: String,
        carName: String,
        region: String,
        store: DealerStore,
        r: DemoReservation
    ) {
        screen("시연 예약 접수")
        lastRender = { showDemoReservationDone(brandCode, brandLabel, carName, region, store, r) }
        val banner = text("시연용 접수 · 실제 예약 아님", 24f, true, onBrand).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = background(brandFill)
        }
        container.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        addText("시연용 시승 신청이 저장되었습니다", 24f, true, 8)
        addCard(
            "예약번호: ${r.id}\n" +
                    "차량: ${r.brandLabel} ${r.carName}\n" +
                    "매장: ${r.storeName}\n" +
                    "희망 날짜·시간: ${r.date} ${r.time}\n" +
                    "상태: 시연용 접수 · 실제 예약 아님\n\n" +
                    "현대·기아와 매장에는 전달되지 않았고, 시승 차량이나 시간이 확보된 것이 아니에요."
        )
        val notice = addNotice()
        addButton("내 시연 예약 내역", true) {
            showDemoReservations(brandCode, brandLabel, carName, region, store)
        }
        addButton("시연 신청 취소", false) {
            if (demoStore.cancel(r.id)) {
                Log.i("AutoResolveTestDrive", "[시연 예약] 취소 ${r.id}")
                showDemoReservations(brandCode, brandLabel, carName, region, store)
            } else {
                notice.text = "이미 취소됐거나 찾을 수 없어요."
            }
        }
        addButton("매장까지 길안내", false) {
            showTestDriveStores(brandCode, brandLabel, carName, region, store.address, true)
        }
        addButton("홈으로", false) { showHome() }
    }

    // 내 시연 예약 내역: 조회 · 개별 취소 · 전체 삭제. 시연 모드에서만 데이터가 보인다.
    private fun showDemoReservations(
        brandCode: String,
        brandLabel: String,
        carName: String,
        region: String,
        store: DealerStore?
    ) {
        screen("내 시연 예약 내역")
        lastRender = { showDemoReservations(brandCode, brandLabel, carName, region, store) }
        addText("시연용 접수 · 실제 예약 아님 · 이 기기에만 저장", 19f, true, 8)
        val items = demoStore.list()
        if (items.isEmpty()) {
            addText("저장된 시연 예약이 없어요.", 20f)
        }
        for (r in items) {
            addCard(
                "${r.id}  [${if (r.cancelled) "취소됨 ${r.cancelledAt}" else "시연용 접수"}]\n" +
                        "${r.brandLabel} ${r.carName}\n" +
                        "${r.storeName}\n" +
                        "${r.date} ${r.time}\n" +
                        "접수 ${r.createdAt}"
            )
            if (!r.cancelled) {
                addButton("이 시연 신청 취소", false) {
                    demoStore.cancel(r.id)
                    showDemoReservations(brandCode, brandLabel, carName, region, store)
                }
            }
        }
        if (items.isNotEmpty()) {
            addButton("시연 예약 기록 전체 삭제", false) {
                showAppDialog(
                    title = "시연 예약 전체 삭제",
                    message = "저장된 시연 예약 기록을 모두 지울까요? 되돌릴 수 없어요. (실제 예약과는 무관해요.)",
                    positive = "삭제",
                    negative = "취소",
                    onPositive = {
                        demoStore.clear()
                        showDemoReservations(brandCode, brandLabel, carName, region, store)
                    }
                )
            }
        }
        if (store != null) {
            addButton("신청서로 돌아가기", true) {
                showTestDriveForm(brandCode, brandLabel, carName, region, store)
            }
        }
        addButton("홈으로", store == null) { showHome() }
    }

    // 시승 문의 화면(별도 화면): 선택한 차량·매장 정보를 그대로 보여 주고 전화 앱만 연결(자동 발신·예약 없음)
    private fun showTestDriveInquiry(
        brandCode: String,
        brandLabel: String,
        carName: String,
        region: String,
        store: DealerStore
    ) {
        screen("시승 가능 여부 문의")
        lastRender = { showTestDriveInquiry(brandCode, brandLabel, carName, region, store) }
        addText("$brandLabel $carName", 26f, true, 8)
        addCard(
            "공식 매장\n${store.name}\n\n" +
                    "주소\n${store.address}\n\n" +
                    "전화번호\n${store.phone ?: "정보 없음"}"
        )
        val notice = text("시승 가능 여부 확인 필요", 22f, true, onBrand).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = background(brandFill)
        }
        container.addView(notice, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        addText(
            "이 매장에 $carName 시승 차량이 있는지는 확인되지 않았어요. 전화로 모델과 시승 가능 시간을 문의하세요. " +
                    "이 앱은 예약을 대신 완료하지 않아요.",
            18f
        )
        val inquiryStatus = addNotice()

        container.addView(
            buttonView("매장에 전화하기", true) {
                Log.i("AutoResolveTestDrive", "[매장 전화 버튼] ${store.name}")
                val err = TestDriveLinks.dial(this, store.phone)
                inquiryStatus.text = err ?: "전화 앱을 열었어요. 번호를 확인하고 직접 발신하세요."
            }.apply { alpha = if (store.phone == null) 0.45f else 1f },
            LinearLayout.LayoutParams(-1, dp(88)).apply { bottomMargin = dp(10) }
        )
        addButton("시승 신청서 작성", true) {
            showTestDriveForm(brandCode, brandLabel, carName, region, store)
        }
        addButton("매장까지 길안내", true) {
            showTestDriveStores(brandCode, brandLabel, carName, region, store.address, autoNavigate = true)
        }
        addButton("매장 목록으로 돌아가기", false) {
            showTestDriveStores(brandCode, brandLabel, carName, region, store.address)
        }
    }
}
