package com.autoresolve

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface

/**
 * 디자인 토큰(Semantic Design Tokens). 화면 코드는 색을 직접 쓰지 않고 [Tokens]의 이름만 쓴다.
 * 색은 현대자동차 공식 CI(https://www.hyundai.com/kr/ko/info/ci/ci-color)의
 * Hyundai Blue #002C5F, Active Blue #00AAD2, Hyundai Light Sand #F6F3F2, White, Black을 중심으로 하고,
 * 경계선은 CI의 Hyundai Sand #E4DCD3, 오류는 CI의 Active Red #E63312를 쓴다.
 * 그 밖의 색(다크 표면, 보조 텍스트, 경고, 눌림)은 CI에 없는 파생색이라 아래에 이유를 적었다.
 */
data class Tokens(
    val isDark: Boolean,
    val backgroundPrimary: Int,       // 화면 배경
    val backgroundSurface: Int,       // 카드·바
    val backgroundElevated: Int,      // 팝업·강조 카드(예약 확장 영역 등)
    val textPrimary: Int,
    val textSecondary: Int,
    val textOnBrand: Int,             // brandPrimary 위의 글자
    val brandPrimary: Int,            // 주요 버튼·배너 채움
    val interactiveActive: Int,       // 선택·활성·AI·인터랙션
    val interactivePressed: Int,      // 눌림(정의만 해 두었고 아직 뷰에 연결하지 않음)
    val interactiveSelectedSurface: Int,
    val borderDefault: Int,
    val statusWarning: Int,           // 안전·경고 전용(브랜드색과 구분)
    val statusError: Int
)

object HyundaiCi {
    val BLUE = Color.parseColor("#002C5F")
    val ACTIVE_BLUE = Color.parseColor("#00AAD2")
    val LIGHT_SAND = Color.parseColor("#F6F3F2")
    val SAND = Color.parseColor("#E4DCD3")
    val ACTIVE_RED = Color.parseColor("#E63312")
    const val WHITE = Color.WHITE
    const val BLACK = Color.BLACK
}

object AppTheme {
    enum class Mode { SYSTEM, LIGHT, DARK }

    private const val PREFS = "autoresolve_theme"
    private const val KEY = "mode"

    // 라이트: 배경 Light Sand, 카드 White, 글자 Hyundai Blue, 주요 버튼 Hyundai Blue, 활성 Active Blue.
    private val LIGHT = Tokens(
        isDark = false,
        backgroundPrimary = HyundaiCi.LIGHT_SAND,
        backgroundSurface = HyundaiCi.WHITE,
        backgroundElevated = HyundaiCi.WHITE,
        textPrimary = HyundaiCi.BLUE,
        textSecondary = Color.parseColor("#5C5C5C"),            // Black 계열 중립 회색(Light Sand 위 대비 약 6:1)
        textOnBrand = HyundaiCi.WHITE,
        brandPrimary = HyundaiCi.BLUE,
        interactiveActive = HyundaiCi.ACTIVE_BLUE,
        interactivePressed = Color.parseColor("#007FA0"),       // Active Blue를 어둡게 한 파생색
        interactiveSelectedSurface = Color.parseColor("#E0F4F9"), // Active Blue 12% 틴트
        borderDefault = HyundaiCi.SAND,
        statusWarning = Color.parseColor("#B45F06"),             // CI 밖 경고 전용 색
        statusError = HyundaiCi.ACTIVE_RED
    )

    // 다크: 배경 Black. Hyundai Blue(#002C5F)는 검정과 명도 차가 작아 카드 표면을 Hyundai Blue 기반으로 밝게 올리고,
    // 카드 경계선을 따로 두며, 주요 버튼은 Active Blue 채움 + 검정 글자(대비 약 8:1)로 읽히게 했다.
    private val DARK = Tokens(
        isDark = true,
        backgroundPrimary = HyundaiCi.BLACK,
        backgroundSurface = Color.parseColor("#0A2347"),         // Hyundai Blue 기반 어두운 표면
        backgroundElevated = Color.parseColor("#103261"),
        textPrimary = HyundaiCi.WHITE,
        textSecondary = Color.parseColor("#B5BDC7"),
        textOnBrand = HyundaiCi.BLACK,
        brandPrimary = HyundaiCi.ACTIVE_BLUE,
        interactiveActive = HyundaiCi.ACTIVE_BLUE,
        interactivePressed = Color.parseColor("#4FC6E3"),
        interactiveSelectedSurface = Color.parseColor("#0B3B57"),
        borderDefault = Color.parseColor("#24FFFFFF"),          // 유리 카드 느낌의 반투명 흰색 테두리
        statusWarning = Color.parseColor("#FFB547"),
        statusError = Color.parseColor("#FF6A4D")
    )

    /**
     * 화면 배경. 라이트는 Light Sand 단색, 다크는 검정에 Active Blue·Hyundai Blue 계열의 은은한 빛(승인된 시안).
     * 모든 화면이 같은 배경을 쓰도록 한 곳에서 만든다.
     */
    fun pageBackground(context: Context, t: Tokens): android.graphics.drawable.Drawable {
        if (!t.isDark) return android.graphics.drawable.ColorDrawable(t.backgroundPrimary)
        val d = context.resources.displayMetrics.density
        fun glow(cx: Float, cy: Float, radiusDp: Float, c: Int) =
            android.graphics.drawable.GradientDrawable().apply {
                gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
                gradientRadius = radiusDp * d
                setGradientCenter(cx, cy)
                colors = intArrayOf(c, c and 0x00FFFFFF)
            }
        return android.graphics.drawable.LayerDrawable(
            arrayOf<android.graphics.drawable.Drawable>(
                android.graphics.drawable.ColorDrawable(Color.parseColor("#02060F")),
                glow(0.85f, 0f, 700f, Color.parseColor("#3800AAD2")),
                glow(0f, 1f, 650f, Color.parseColor("#591E46A0"))
            )
        )
    }

    @Volatile private var cached: Mode? = null

    fun mode(context: Context): Mode {
        cached?.let { return it }
        val m = try {
            Mode.valueOf(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, Mode.SYSTEM.name) ?: Mode.SYSTEM.name
            )
        } catch (e: Exception) {
            Mode.SYSTEM
        }
        cached = m
        return m
    }

    fun setMode(context: Context, mode: Mode) {
        cached = mode
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode.name).commit()
    }

    fun next(mode: Mode): Mode = when (mode) {
        Mode.SYSTEM -> Mode.LIGHT
        Mode.LIGHT -> Mode.DARK
        Mode.DARK -> Mode.SYSTEM
    }

    fun label(mode: Mode): String = when (mode) {
        Mode.SYSTEM -> "시스템 설정 따름"
        Mode.LIGHT -> "라이트(시연용 고정)"
        Mode.DARK -> "다크(시연용 고정)"
    }

    /**
     * Activity.attachBaseContext 에서 호출. 시스템 설정을 따를 때는 그대로 두고(Android 표준 uiMode),
     * 시연용으로 라이트/다크를 고정했을 때만 uiMode를 덮어쓴 Context를 돌려준다.
     * 이렇게 하면 values-night 리소스와 아래 [tokens] 가 같은 기준을 본다.
     * (Pleos SDK에서 시스템 테마를 읽는 API는 공식 문서에서 찾지 못해 쓰지 않는다.)
     */
    fun wrap(base: Context): Context {
        val m = mode(base)
        if (m == Mode.SYSTEM) return base
        val cfg = Configuration(base.resources.configuration)
        val night = if (m == Mode.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(cfg)
    }

    /**
     * 고정(라이트/다크)이면 그 값, 시스템을 따르면 시스템 설정의 야간 여부.
     * 시스템 값은 Activity에 덮어쓴 설정이 아니라 Resources.getSystem()에서 읽어, 고정을 풀었을 때 되돌아가도록 한다.
     */
    fun tokens(context: Context): Tokens {
        val dark = when (mode(context)) {
            Mode.LIGHT -> false
            Mode.DARK -> true
            Mode.SYSTEM -> (android.content.res.Resources.getSystem().configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        return if (dark) DARK else LIGHT
    }
}

/**
 * 타이포그래피. Hyundai Sans는 공개 라이선스·배포 조건을 확인하지 못했고 프로젝트에 글꼴 파일도 없어 쓰지 않는다.
 * Pleos 디자인 문서의 시스템 UI 글꼴은 Asta Sans(한·영)이라, 기본 시스템 글꼴을 그대로 쓴다.
 * 나중에 사용 권한이 확인된 글꼴 파일을 res/font 에 넣으면 [family] 한 곳만 바꾸면 전체에 적용된다.
 * 크기는 sp이고 문서의 최소 12sp 이상이다.
 */
object AppType {
    /** 차량 화면에서 읽기 쉽도록 모든 글자 크기에 곱하는 배율(기존 화면 크기 대비 +10%). */
    const val SCALE = 1.1f
    const val HEADLINE = 30f
    const val TITLE = 24f
    const val BODY = 19f
    const val BUTTON = 21f
    const val LABEL = 17f
    const val CAPTION = 14f

    /** null이면 시스템 글꼴. 교체 시: ResourcesCompat.getFont(context, R.font.hyundai_sans) 등으로 설정. */
    var family: Typeface? = null

    fun apply(tv: android.widget.TextView, bold: Boolean) {
        val f = family
        tv.typeface = if (f != null) Typeface.create(f, if (bold) Typeface.BOLD else Typeface.NORMAL)
        else Typeface.create(tv.typeface ?: Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        tv.letterSpacing = 0f // 한글은 자간 0, 영문도 시스템 기본 유지
    }
}
