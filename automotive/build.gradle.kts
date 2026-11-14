import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

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

    buildTypes {
        release {
            // 배포용 보호: local.properties 값과 상관없이 release 빌드는 시연 모드를 항상 끈다.
            buildConfigField("boolean", "DEMO_MODE", "false")
            optimization {
                enable = true
                packageScope = setOf(
                    "androidx.**",
                    "kotlin.**",
                    "kotlinx.**"
                )
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
