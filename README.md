# H.WITH

**팀명: MIRAE** · 현대자동차그룹 Pleos 차량용 앱 개발 공모전 출품작

> H.WITH는 차량 문제를 AI 기반 음성 상담으로 한 번에 파악하고, 필요한 부품과 정비 일정을 고려한 최적의 수리 경로부터 맞춤형 다음 차량 추천 및 시승까지 연결하여, 고객의 불편을 해결하고 브랜드 신뢰와 재구매를 높이는 차량 특화 AI 리텐션 플랫폼입니다.

위는 기획 의도이고, **실제로 구현·검증된 범위와 시연(데모) 범위는 아래 표와 각 절에 구분해서 적었습니다.** 이 문서는 확인하지 못한 기능을 완료라고 쓰지 않습니다.

## 한 줄 소개

차량에 문제가 생겼을 때 반복 설명 없이 음성으로 증상을 말하면, 위험도를 먼저 알려 주고 상담 내용을 정리해 서비스센터 접수(시연)까지 이어 주는 Pleos Connect 차량용(Android Automotive) 앱입니다.

## 구현 범위 한눈에 보기

| 기능 | 구현 | 실제 서비스 연동 | 비고 |
|---|---|---|---|
| 음성 상담 (Pleos STT/TTS) | 구현 | 해당 없음 | 에뮬레이터에서 개발자 테스트 |
| 증상 안전 분류 | 구현 (규칙 기반) | 해당 없음 | AI 모델이 아니라 키워드·패턴 규칙 |
| 서비스센터 비교·선택 | 구현 | **없음 (시연 데이터)** | 센터 5곳은 가상 데이터, 위치·대기시간도 시연용 |
| 정비 접수 | 구현 (시연 저장) | **없음** | 이 기기에만 저장, 실제 센터로 전송 안 됨 |
| 길안내 (NaviHelper) | 코드 연동 | 좌표가 있는 곳만 | 시승 매장 10곳은 `dealers.json`에 좌표가 있어 경로 요청 가능. 서비스센터 A~E(시연)는 좌표가 없어 안내를 막음 |
| 차량 정보 (Vehicle SDK) | 구현 | VIN 조회만 | 화면에는 VIN 끝 4자리만 표시 |
| 차량 모델 검색 추천 | 구현 | 해당 없음 | 차량 등록 화면. 앱 안의 모델 목록에서 찾음(서버 검색 아님) |
| 다음 차 추천 | 구현 | **속도 데이터 미연동** | 사용자 입력 + 시연용 주행 프로필 |
| 시승 매장 목록 | 구현 | 공식 지점 안내 기반 | 10곳, 주소·전화는 2026-11-12 확인. 좌표는 2026-11-14에 추가한 팀 제공 값 |
| 시승 예약 | 구현 (시연 저장) | **없음 (실제 예약 아님)** | 시연 모드에서만, 가상 정보만 허용 |
| Gleo 음성 비서 연동 | 구현 | ADB로 검증 | 실제 Gleo 음성 발화 검증은 아래 참고 |

## 주요 기능

### 1. 음성 상담
- Pleos `SpeechToText`(ON_DEVICE)와 `TextToSpeech`로 증상을 묻고 듣습니다. 증상 → 후속 질문(최대 2회) → 접수 동의 확인 순서입니다.
- 접수 동의는 "네", "접수해 주세요"처럼 명확할 때만 진행합니다. "네 근데 잠깐만"처럼 모호하면 다시 묻고, 부분 인식 결과만으로는 동의로 확정하지 않습니다.
- 마이크는 안내 음성이 끝난 뒤에 엽니다(안내 중에 열면 스피커로 나온 안내 음성이 답으로 인식됨). 마이크가 열리면 "삐" 소리가 나고, 그 전에는 화면에 기다려 달라는 문구를 보여 줍니다. 안내가 끝나기 전에 말한 내용은 인식되지 않습니다.
- 후속 질문의 답은 규칙으로 검사합니다(`AnswerCheck`). "반복돼요", "안 했어"처럼 질문의 말을 받은 짧은 답은 인정하고, "어", "그게" 같은 조각은 한 번 다시 묻습니다. 다시 물을 때는 무엇으로 들었는지 화면에 보여 줍니다.

### 2. 차량 고장 상담 및 안전 분류
- 증상을 규칙 기반으로 분류합니다(`SymptomTriage`): 운행 중단 권고(STOP_NOW), 조속 점검(INSPECT_SOON), 자가 확인(SELF_CHECK), 추가 정보 필요(NEED_MORE_INFO). 화재·연기, 브레이크·조향 이상 등은 가장 높은 위험(EMERGENCY)으로 먼저 안내합니다.
- 이 분류는 정비 전문가의 진단이 아니며, 원인을 확정하지 않습니다. 화면에도 원인 미확인 문구를 표시합니다.

### 3. 서비스센터 탐색과 시연용 접수
- **서비스센터 A~E는 모두 가상의 시연 데이터입니다.** 주소, 위치, 대기시간은 실제 정보가 아닙니다.
- 센터를 비교·선택한 뒤 접수 확인 화면에서 사용자가 직접 확인하면 접수 내용이 **이 기기에만 저장**됩니다(`SENT_DEMO`, "시연 접수 저장 완료"). **실제 서비스센터로 예약·접수·전송되지 않습니다.**

### 4. 운전 데이터 기반 다음 차 추천
- 추천은 사용자가 입력한 선호와 주행 요약값으로 계산합니다.
- **Vehicle SDK의 속도·주행거리 값을 수집하는 코드는 연결되어 있지 않습니다.** 주행 요약은 앱 안에서 고르는 "시연용 주행 프로필"로만 채워지며, 실제 운전 데이터가 아닙니다.
- (매니페스트에 `CAR_SPEED`, `CAR_MILEAGE` 권한이 선언되어 있으나, 현재 코드는 이 값을 읽지 않습니다.)

### 5. 시승 관련 기능
- 시승 가능 매장 10곳은 현대자동차·기아 공식 지점 안내 페이지를 출처로 한 `assets/dealers.json`입니다(주소·전화 확인일 2026-11-12).
- 10곳 모두 `dealers.json`에 좌표(`lat`, `lon`)가 있습니다. 이 좌표는 팀이 제공한 값(`coordSource`: "프로젝트 담당자 제공 좌표 (2026-11-14)")이며, 공식 출처나 지도상 위치와 대조해 검증한 기록은 없습니다.
- 매장을 고르고 "매장까지 길안내 시작"을 누르면 그 좌표로 Pleos `NaviHelper.requestRoute`를 호출하고 내비게이션 화면을 요청합니다. NaviHelper 초기화·연결에 실패하거나 요청 중 예외가 나면 주소 기반 지도 검색으로 넘깁니다. 앱이 직접 지도·핀·거리를 표시하지는 않습니다.
- 좌표는 시연 모드 여부나 빌드 종류와 관계없이 읽습니다. (Debug 전용 임시 좌표 파일 `dealer_coords_temp.json`을 읽는 코드는 남아 있지만 `dealers.json`에 좌표가 있으면 덮어쓰지 않으며, 이 파일은 저장소에 없습니다.)
- **시승 예약은 실제 예약이 아닙니다.** 예약 전송 기능은 연결되어 있지 않으며(`NotConnected`), 시연 모드에서만 "시연 예약"이 이 기기에 저장됩니다. 이름은 "시연"으로 시작해야 하고 연락처는 `010-0000-0000` 형식의 가상 번호만 허용합니다. 실제 개인정보는 입력할 수 없습니다.

### 6. 운전 중 제한
- 앱은 차량의 정차 여부를 확인하지 못합니다(Vehicle SDK의 속도·기어 신호는 연결하지 않았습니다). 그래서 기본 빌드에서는 개인정보 입력(시승 예약 작성)을 허용하지 않습니다.
- 시연 모드에서는 "정차 확인이 아니라 사용자의 선언"임을 화면에 밝히고, 가상 정보만 입력받습니다.

### 7. 차량 모델 검색 추천
- 차량 등록 화면("내 차량 등록")의 "차량 모델 검색" 입력칸에 글자를 입력하면, 선택한 제조사의 모델 중 맞는 것을 입력칸 아래에 최대 8개까지 보여 줍니다. 한 글자부터 동작하고, 조합 중인 한글도 포함합니다("아", "아ㅇ" → 아이오닉).
- 순서는 앞부분 일치 → 초성 일치("ㅇㅇㅇ") → 중간 일치입니다. 대소문자와 공백은 무시하고, 숫자만 입력하면("5") 찾지 않습니다. 영문·한글 별칭은 `ioniq`, `avante`, `casper`, `지브이`, `이브이`, `케이`만 있습니다.
- 추천을 누르면 입력칸과 화면 아래 "선택한 차량"에 반영되고 키보드와 목록이 닫힙니다. 맞는 모델이 없으면 "찾는 차량이 없어요 · 직접 입력하기"를 보여 줍니다. 입력칸이 비어 있으면 목록을 보여 주지 않습니다(전체 모델 목록을 훑어보는 기능은 없음).
- 모델 목록은 앱에 들어 있는 `VehicleCatalog`이고, 매칭은 `ModelSearch.kt`의 `matchModels`가 합니다. 서버 검색이 아닙니다.

## Pleos Connect 기반 개발 내용

코드에서 실제로 사용한 Pleos SDK 항목은 다음과 같습니다.

| SDK | 버전 | 사용 내용 |
|---|---|---|
| `ai.pleos.playground:Vehicle` | 2.3.2 | `Vehicle` 초기화, 차량 정보의 VIN 조회 |
| `ai.pleos.playground:SpeechToText` | 3.3.1 | `SpeechToText`(ON_DEVICE) 음성 인식 |
| `ai.pleos.playground:TextToSpeech` | 3.3.1 | `TextToSpeech`(ON_DEVICE) 음성 안내 |
| `ai.pleos.playground:NaviHelper` | 3.0.5 | 경로 요청(`requestRoute`), 내비 화면 요청 |

선언한 권한: `CAR_INFO`, `STT_SERVICE`, `TTS_SERVICE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP`, `CAR_SPEED`, `CAR_MILEAGE`, `RECORD_AUDIO`, `INTERNET`.

## Gleo 음성 비서 연동

- `assets/schema.json`에 3개 함수를 정의하고, `GleoActionActivity`가 받습니다: `REPORT_VEHICLE_PROBLEM`(증상 기록·안전 분류), `FIND_SERVICE_CENTER`(센터 탐색), `RECOMMEND_NEXT_CAR`(다음 차 추천).
- **검증 범위**
  - ADB 검증: `gleo-adb-test.ps1`로 intent와 파라미터를 직접 전달해, 앱이 콜백을 보내는지 logcat으로 확인했습니다(REPORT → FIND의 requestId 연결 포함).
  - **실제 Gleo 음성 비서에 말해서 앱이 호출되는 것은 이 문서 작성 시점에 확인된 기록이 없습니다.** ADB 결과를 실제 음성 비서 검증으로 간주하지 마세요.

## 개발 환경 및 실행 방법

- Android Studio, JDK 25(Gradle 데몬 JVM 설정 기준), Gradle 9.6.0 (wrapper), Android Gradle Plugin 9.5.1, Kotlin 2.2.10
- `compileSdk` 35 / `minSdk` 29 / `targetSdk` 35, 패키지명 `com.autoresolve`, 앱 이름 H.WITH
- Pleos SDK는 `https://nexus-playground.pleos.ai/repository/maven-releases/`에서 받습니다(`settings.gradle.kts`).
- 모듈: `automotive`(앱), `shared`, `mobile`

### Debug 빌드 실행
1. Pleos Connect 에뮬레이터(아래 테스트 환경)를 실행합니다.
2. 프로젝트 루트에서 `.\gradlew.bat :automotive:installDebug` 또는 Android Studio에서 `automotive`를 실행합니다.
3. (선택) 시연 기능을 보려면 `local.properties`에 `autoresolve.demo.mode=true`를 추가하고 Debug로 빌드합니다. 이 파일은 저장소에 올라가지 않습니다.
4. (선택) 주소 좌표 조회는 `local.properties`의 `kakao.rest.api.key`가 있을 때만 켜집니다. 없으면 꺼진 상태로 동작합니다. 키는 저장소에 넣지 마세요.

### Release 서명 (직접 해야 하는 절차)
서명 키와 비밀번호는 저장소에 없습니다. 서명 정보는 환경변수로만 Gradle에 전달합니다(`automotive/build.gradle.kts`).
- `HWITH_STORE_PASSWORD`: 키스토어 비밀번호. 이 값이 없으면 서명 없이 빌드됩니다.
- `HWITH_KEY_PASSWORD`: 키 비밀번호(선택). 없으면 키스토어 비밀번호를 씁니다.
- `HWITH_STORE_FILE`, `HWITH_KEY_ALIAS`: 키스토어 파일 경로와 키 alias(선택). 주지 않으면 기본값 `E:\Keys\hwith-release.jks`, `hwith`를 씁니다. 이 Windows 경로는 기본값일 뿐이며, 다른 환경에서는 두 변수로 지정합니다.

키 파일은 저장소 밖에 두고, 비밀번호는 파일이나 Gradle 명령행 인자에 저장하지 않습니다.
`tools/build-release.ps1`(Windows PowerShell용)은 실행할 때 비밀번호를 입력받아(화면에 표시되지 않음) 빌드하는 동안만 환경변수로 Gradle에 전달하고, 끝나면 지웁니다.

제출한 APK는 저장소 밖에 보관하는 팀 release 키로 서명했습니다. **이전에 제출했던 APK와 서명 키가 다릅니다.** 이전 APK가 설치된 기기에서는 덮어 설치(`adb install -r`)가 되지 않으므로, 먼저 `adb uninstall com.autoresolve`로 제거한 뒤 설치해 주세요(앱에 저장된 차량 등록·시연 접수·시연 예약 데이터는 지워집니다).
키가 아직 없다면 저장소 밖 경로에 `keytool -genkeypair -v -keystore <키스토어 경로> -alias <alias> -keyalg RSA -keysize 2048 -validity 10000`로 직접 만드세요.

### Release 빌드

**심사용 시연 Release** (제출 APK): 시연 모드를 켜려면 빌드할 때 `-PhwithDemoRelease=true`를 명시해야 합니다. 인자를 주지 않은 일반 Release는 시연 모드가 항상 꺼집니다(`local.properties` 값으로는 켜지지 않습니다). 이 값을 `gradle.properties`에 적어 두지 마세요.

```powershell
.\tools\build-release.ps1              # 시연 Release 빌드(서명 비밀번호 입력) + DEMO_MODE=true 확인 + 서명/패키지 검증 + apk\MIRAE_H.WITH_release.apk 복사
.\tools\build-release.ps1 -Install     # 위 + 연결된 에뮬레이터에 설치·실행(크래시 로그 확인)
.\tools\build-release.ps1 -Production  # 시연 모드가 꺼진 일반 Release (인자 없이 빌드)
```
스크립트를 쓰지 않을 때는 환경변수 `HWITH_STORE_PASSWORD`를 직접 설정한 PowerShell에서 `.\gradlew.bat :automotive:assembleRelease -PhwithDemoRelease=true` 후 `automotive\build\outputs\apk\release\automotive-release.apk`를 `apksigner verify --verbose --print-certs`로 확인합니다.

### 시연 Release의 제한 사항
시연 Release는 심사 때 기능을 보여 주기 위한 빌드이며, 실제 서비스가 아닙니다.
- 서비스센터 A~E, 위치, 대기시간은 가상 데이터이고 센터에는 좌표가 없어 길안내를 막습니다. 정비 접수는 이 기기에만 저장되며 실제 센터로 전송되지 않습니다.
- 시승 예약은 실제 예약이 아니라 이 기기에만 저장되는 시연 예약입니다. 이름은 "시연"으로 시작하고 연락처는 `010-0000-0000` 형식의 가상 정보만 허용하며, 실제 개인정보는 입력할 수 없습니다. 시승 예약 화면의 입력은 정차 확인이 아니라 사용자의 선언에 따릅니다(차량 정차 여부는 확인하지 못함). 실제 차량 운행 중에는 사용하지 마세요.
- 제출한 APK(`apk/MIRAE_H.WITH_release.apk`)에는 `dealers.json`의 시승 매장 좌표 10곳이 들어 있습니다(APK 안의 파일로 확인). 다만 Release에서 "매장까지 길안내 시작"을 눌러 실제로 경로 요청이 되는지는 확인하지 않았습니다.
- 운전 데이터는 실제 차량 값이 아니라 시연용 주행 프로필입니다.
- 시연 모드가 꺼진 일반 Release에서는 시승 예약 작성과 시연 예약 내역이 나타나지 않습니다.
- Release는 R8 최적화가 켜져 있습니다(코틀린 stdlib은 최적화 대상에서 제외). 초기에 R8 때문에 앱 시작 직후 종료되는 문제가 있었고 수정 후 홈 화면 실행까지 확인했습니다. 그 외 기능의 R8 호환은 아래 검증 상태의 확인 범위만 확인했습니다.

## 테스트 환경

| 항목 | 내용 |
|---|---|
| 에뮬레이터 | Pleos Connect **v2.1.0** (개발자 보고) |
| Android | **API 34** (개발자 보고) |
| 디스플레이 | 2560 × 1440 px (에뮬레이터 시스템 로그의 display device 정보, 2026-11-11 기록) |
| 앱 창 기준 | 1682 × 1210 dp (코드의 화면 배율 기준값, 에뮬레이터에서 실측한 앱 창 크기) |
| 시연 모드 | Debug 빌드 + `autoresolve.demo.mode=true`, 또는 Release 빌드 + `-PhwithDemoRelease=true` |

- 화면 배율은 앱 창 크기에 비례해 조정됩니다(기준 1682 × 1210 dp). 다른 크기의 에뮬레이터·실차 화면에서는 확인하지 않았습니다.
- 실제 차량(실기기)에서는 테스트하지 않았습니다.

## 검증 상태

| 항목 | 상태 |
|---|---|
| Debug 빌드의 에뮬레이터 동작 (음성 상담, 안전 분류, 시연 접수 등) | 개발자 테스트로 확인 |
| Gleo ADB 검증 | 확인 |
| 실제 Gleo 음성 비서 경유 검증 | 확인된 기록 없음 |
| Release 빌드 (`-PhwithDemoRelease=true`) | 확인 (2026-11-14 다시 빌드, BUILD SUCCESSFUL, `DEMO_MODE=true`, R8 켜짐) |
| Release APK 서명 | 확인 (2026-11-14 빌드, apksigner v2 검증 성공) |
| Release APK 설치 (Pleos Connect v2.1.0 에뮬레이터) | 확인 (2026-11-14 빌드, `adb uninstall com.autoresolve` 후 `adb install` 성공. 서명 키가 바뀌어 덮어 설치는 안 됨) |
| Release 앱 실행 | 확인 (2026-11-14 빌드: 실행 후 약 12초 동안 종료·크래시 없음, 첫 실행 화면 "먼저 차량을 등록해 주세요" 표시). 차량 등록 뒤의 홈 화면은 이 빌드에서 확인하지 않음 |
| Release의 시승 시연 예약 저장, 저장된 시연 예약 내역 조회 | 이전 제출 빌드에서 확인. 2026-11-14에 다시 빌드한 APK에서는 **미확인** |
| Release에서 음성 상담, 안전 분류, 서비스센터 비교·시연 접수, 다음 차 추천, Gleo 호출 | **Release에서는 미확인** (Debug 개발자 테스트 및 Gleo ADB 검증만 있음) |
| 차량 모델 검색 추천 (Debug, 에뮬레이터) | 확인 ("아" 입력 시 추천 표시, 추천 선택 시 입력칸·선택한 차량 반영). 그 밖의 검색어는 단위 테스트로만 확인 |
| 시승 매장 좌표 길안내 (Debug, 에뮬레이터) | 1곳(서울성동 드라이빙센터)만 확인: 경로 요청 호출과 경로 시작 이벤트. 나머지 9곳과 내비게이션 화면의 목적지 위치는 미확인 |
| 음성 상담의 마이크 여는 시점·준비음·후속 답변 검사 변경 (Debug, 에뮬레이터) | 일부 확인: 안내 음성이 답으로 인식되지 않음, 안내 종료 후 마이크가 열리는 시간(로그). 짧은 답("반복돼요") 인정은 단위 테스트로만 확인 |
| 단위 테스트 (`ModelSearchTest` 9개, `AnswerCheckTest` 4개) | 통과 (`:automotive:testDebugUnitTest`) |
| 차량 모델 검색 추천, 시승 매장 좌표 길안내, 음성 상담 변경 | **Release에서는 미확인** (제출 APK에 포함되어 있지만 Release에서 실행해 보지 않음. APK 안의 `dealers.json`에 좌표 10곳이 들어 있는 것만 확인) |
| 실제 차량 | 미확인 |

## Release APK 위치

`apk/MIRAE_H.WITH_release.apk`

이 APK는 `-PhwithDemoRelease=true`로 빌드한 **시연 Release**입니다. 2026-11-14에 커밋 `c20a5df`의 소스로 다시 빌드했으며 차량 모델 검색 추천, 시승 매장 좌표, 음성 상담 수정이 들어 있습니다(그 뒤의 커밋은 이 APK 파일과 README만 바꿉니다). Release 확인 범위는 위 "검증 상태"를 따르며, 확인하지 않은 기능은 검증 완료로 보지 않습니다. 제출 시점에 이 파일이 저장소에 있는지는 최종 커밋으로 확인해 주세요.

## 보안·공개 관련

- 서명 키, 서명 비밀번호, `local.properties`, API 키는 저장소에 포함하지 않습니다.
- `dealers.json`의 매장 정보는 공식 지점 안내 페이지를 출처로 하며, 서비스센터 A~E와 대기시간은 가상입니다.
- 정비 접수·시연 예약 내용은 기기 안(SharedPreferences)에만 저장되고, 서버로 전송하는 코드는 없습니다. 카카오 키가 설정된 빌드에서만 매장 주소를 지오코딩 API로 조회합니다(기본 꺼짐).
