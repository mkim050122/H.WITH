import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release 서명 정보: 비밀번호는 파일·소스·명령행 인자에 두지 않고 환경변수로만 받는다.
//   HWITH_STORE_PASSWORD (필수)  키스토어 비밀번호
//   HWITH_KEY_PASSWORD   (선택)  키 비밀번호. 없으면 키스토어 비밀번호를 쓴다.
//   HWITH_STORE_FILE     (선택)  기본 E:/Keys/hwith-release.jks (저장소 밖)
//   HWITH_KEY_ALIAS      (선택)  기본 hwith
// tools/build-release.ps1 이 비밀번호를 안전하게 입력받아 빌드 동안만 환경변수로 설정하고 끝나면 지운다.
// 비밀번호 환경변수가 없으면 서명 없이(unsigned) 빌드되며, 기존 Debug 빌드에는 영향이 없다.
val releaseStorePassword = providers.environmentVariable("HWITH_STORE_PASSWORD").orNull
val releaseKeyPassword = providers.environmentVariable("HWITH_KEY_PASSWORD").orNull ?: releaseStorePassword
val releaseStoreFile = providers.environmentVariable("HWITH_STORE_FILE").orNull?.takeIf { it.isNotBlank() }
    ?: "E:/Keys/hwith-release.jks"
val releaseKeyAlias = providers.environmentVariable("HWITH_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() } ?: "hwith"
// 심사용 시연 Release: 빌드할 때 -PhwithDemoRelease=true 를 명시한 경우에만 Release의 DEMO_MODE가 켜진다.
// 인자를 주지 않으면(기본) Release는 항상 false다. local.properties 값으로는 켜지지 않는다.
// 이 값을 gradle.properties 에 적어 두지 말 것(실수로 일반 Release에 시연 모드가 켜질 수 있음).
val demoRelease = providers.gradleProperty("hwithDemoRelease").orNull?.trim().equals("true", ignoreCase = true)
val hasReleaseKey = !releaseStorePassword.isNullOrEmpty() && file(releaseStoreFile).isFile

android {
    namespace = "com.autoresolve"

    compileSdk {
        version = release(35)
    }

    defaultConfig {
        applicationId = "com.autoresolve"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

        // 카카오 REST API 키: local.properties(kakao.rest.api.key=...)에서만 읽는다. 저장소에 커밋하지 말 것.
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val kakaoKey = localProps.getProperty("kakao.rest.api.key", "").trim()
        buildConfigField("String", "KAKAO_REST_API_KEY", "\"$kakaoKey\"")

        // 시연 모드: 개발용 명시 설정(local.properties 의 autoresolve.demo.mode=true)일 때만 켜진다. 기본값 false.
        // 빌드 종류(debug/release)나 실제 차량 여부와는 무관하며, 켜져 있어도 정차 확인이 아니다.
        val demoMode = localProps.getProperty("autoresolve.demo.mode", "false").trim().equals("true", ignoreCase = true)
        buildConfigField("boolean", "DEMO_MODE", demoMode.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        } else {
            logger.warn("[H.WITH] HWITH_STORE_PASSWORD 환경변수가 없거나 키스토어 파일이 없어 Release가 서명 없이 빌드됩니다.")
        }
    }

    buildTypes {
        release {
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
            // 배포용 보호: local.properties 값과 상관없이 release 빌드는 시연 모드를 끈다.
            // 예외: 심사용 시연 Release(-PhwithDemoRelease=true 를 명시)에서만 켠다.
            buildConfigField("boolean", "DEMO_MODE", demoRelease.toString())
            if (demoRelease) {
                logger.warn("[H.WITH] 시연 Release: DEMO_MODE=true (심사용. 일반 배포용 Release가 아닙니다)")
            }
            // R8 최적화 범위. kotlin.** 은 뺐다: 에뮬레이터에서 실행 즉시 종료되는 문제
            // (IllegalAccessError: Class kotlin.collections.i extended by class ah is inaccessible)가 있었고,
            // mapping.txt 상 kotlin.collections.CollectionsKt__MutableCollectionsJVMKt 만 기본 패키지(ah)로 옮겨져
            // 부모 클래스(kotlin.collections.i)에 접근하지 못하는 것이 원인이었다. 코틀린 stdlib은 최적화 대상에서 제외한다.
            // 진단용: -PhwithNoR8=true 로 빌드하면 R8 최적화를 끈다(제출용 APK에는 쓰지 말 것).
            val noR8 = providers.gradleProperty("hwithNoR8").orNull?.trim().equals("true", ignoreCase = true)
            optimization {
                enable = !noR8
                packageScope = setOf(
                    "androidx.**",
                    "kotlinx.**"
                )
            }
            if (noR8) {
                logger.warn("[H.WITH] 진단용: R8 최적화 꺼짐 (-PhwithNoR8=true). 제출용 APK로 쓰지 마세요.")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.app.automotive)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)

    // Pleos Vehicle SDK
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    // Pleos SpeechToText SDK
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    // Pleos TextToSpeech SDK
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    // Pleos NaviHelper SDK
    implementation("ai.pleos.playground:NaviHelper:3.0.5")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
