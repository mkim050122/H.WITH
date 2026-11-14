# H.WITH 제출용 Release APK 빌드·검증 도우미 (Windows PowerShell 5.1 / 7)
#
# 사용 (프로젝트 루트에서):
#   .\tools\build-release.ps1              # 심사용 시연 Release 빌드 + 서명/설정 검증 + apk\ 로 복사
#   .\tools\build-release.ps1 -Install     # 위 + 연결된 에뮬레이터에 설치·실행
#   .\tools\build-release.ps1 -NoR8        # 진단용: R8 최적화를 끈 시연 Release (apk\ 로 복사하지 않음)
#   .\tools\build-release.ps1 -Production  # 시연 모드가 꺼진 일반 Release (-PhwithDemoRelease 를 주지 않음)
#
# 서명 키: E:\Keys\hwith-release.jks (alias hwith, 저장소 밖). 이 스크립트는 키나 비밀번호를 만들거나 바꾸지 않습니다.
# 비밀번호는 실행할 때 직접 입력합니다(화면에 표시되지 않음). 파일·소스·Gradle 명령행 인자에 저장하지 않고,
# 빌드 동안만 환경변수(HWITH_STORE_PASSWORD, HWITH_KEY_PASSWORD)로 Gradle에 전달한 뒤 끝나면(실패해도) 지웁니다.
# 키 비밀번호가 키스토어 비밀번호와 같으면 키 비밀번호 입력에서 그냥 Enter를 누르세요.

param(
    [switch]$Install,
    [switch]$Production,
    [switch]$NoR8
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$root    = Split-Path -Parent $PSScriptRoot
$sdk     = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$outName = 'MIRAE_H.WITH_release.apk'
$outDir  = Join-Path $root 'apk'
$pkg     = 'com.autoresolve'
$keyFile = 'E:\Keys\hwith-release.jks'
$keyAlias = 'hwith'
$demo    = -not $Production
$relDir  = Join-Path $root 'automotive\build\outputs\apk\release'
$built   = Join-Path $relDir 'automotive-release.apk'

function Fail($msg) { Write-Host "[실패] $msg" -ForegroundColor Red; exit 1 }

Set-Location $root

# 0) 사전 점검
if (-not (Test-Path $keyFile)) { Fail "서명 키 파일이 없습니다: $keyFile" }
$git = Get-Command git -ErrorAction SilentlyContinue
if ($git) {
    $tracked = & git ls-files 'keystore.properties' '*.jks' '*.keystore' 2>$null
    if ($tracked) { Fail "서명 관련 파일이 Git에 추적되고 있습니다: $tracked  (git rm --cached 로 빼 주세요)" }
}
$bt = $null
if (Test-Path "$sdk\build-tools") {
    $bt = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version]($_.Name -replace '[^0-9.]','') } -Descending | Select-Object -First 1
}
if (-not $bt) { Fail "Android SDK build-tools 를 찾지 못했습니다 ($sdk). ANDROID_HOME 을 확인하세요." }

# 1) 이전 결과 제거 후 빌드 (오래된 APK를 새 결과로 착각하지 않도록)
if (Test-Path $relDir) { Remove-Item $relDir -Recurse -Force }
$gradleArgs = @(':automotive:assembleRelease')
if ($demo) { $gradleArgs += '-PhwithDemoRelease=true' }
if ($NoR8) { $gradleArgs += '-PhwithNoR8=true' }
Write-Host ("[1/5] Release 빌드: gradlew.bat " + ($gradleArgs -join ' ')) -ForegroundColor Cyan
# --no-daemon / --no-configuration-cache: 비밀번호가 데몬 메모리나 설정 캐시에 남지 않게 한다.
$gradleArgs += @('--no-daemon', '--no-configuration-cache')

function ConvertTo-PlainText([Security.SecureString]$sec) {
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}
$storeSec = Read-Host "키스토어 비밀번호 ($keyFile)" -AsSecureString
if ($storeSec.Length -eq 0) { Fail '비밀번호가 비어 있습니다.' }
$keySec = Read-Host '키 비밀번호 (키스토어 비밀번호와 같으면 Enter)' -AsSecureString
$gradleExit = 1
try {
    $env:HWITH_STORE_PASSWORD = ConvertTo-PlainText $storeSec
    $env:HWITH_KEY_PASSWORD   = if ($keySec.Length -eq 0) { $env:HWITH_STORE_PASSWORD } else { ConvertTo-PlainText $keySec }
    $env:HWITH_STORE_FILE     = $keyFile
    $env:HWITH_KEY_ALIAS      = $keyAlias
    & .\gradlew.bat @gradleArgs
    $gradleExit = $LASTEXITCODE
}
finally {
    foreach ($n in 'HWITH_STORE_PASSWORD','HWITH_KEY_PASSWORD','HWITH_STORE_FILE','HWITH_KEY_ALIAS') {
        Remove-Item "Env:$n" -ErrorAction SilentlyContinue
    }
    $storeSec.Dispose(); $keySec.Dispose()
}
if ($gradleExit -ne 0) { Fail 'Gradle 빌드 실패 (비밀번호가 틀렸다면 키스토어 오류 메시지가 위에 표시됩니다)' }
if (-not (Test-Path $built)) {
    Fail "서명된 APK가 없습니다: $built  (automotive-release-unsigned.apk 만 있다면 서명 설정이 적용되지 않은 것입니다)"
}

# 2) 빌드 설정 확인: BuildConfig 의 DEMO_MODE 가 의도한 값인지
Write-Host '[2/5] DEMO_MODE 확인' -ForegroundColor Cyan
$bc = Get-ChildItem (Join-Path $root 'automotive\build\generated\source\buildConfig\release') -Recurse -Filter BuildConfig.java -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $bc) { Fail 'release BuildConfig.java 를 찾지 못해 DEMO_MODE 를 확인할 수 없습니다.' }
$line = Select-String -Path $bc.FullName -Pattern 'boolean DEMO_MODE' | Select-Object -First 1
if (-not $line) { Fail 'BuildConfig 에서 DEMO_MODE 를 찾지 못했습니다.' }
$want = if ($demo) { 'true' } else { 'false' }
if ($line.Line -notmatch "DEMO_MODE\s*=\s*$want\s*;") { Fail "DEMO_MODE 가 기대한 값($want)이 아닙니다: $($line.Line.Trim())" }
Write-Host "    DEMO_MODE = $want (의도대로)"

# 3) 서명 검증
Write-Host '[3/5] 서명 검증 (apksigner)' -ForegroundColor Cyan
& "$($bt.FullName)\apksigner.bat" verify --verbose --print-certs $built
if ($LASTEXITCODE -ne 0) { Fail '서명 검증 실패' }

# 4) 패키지 정보
Write-Host '[4/5] 패키지 정보 (aapt2)' -ForegroundColor Cyan
$badging = & "$($bt.FullName)\aapt2.exe" dump badging $built
if ($LASTEXITCODE -ne 0) { Fail 'aapt2 dump badging 실패' }
$badging | Select-String -Pattern "^package:|^sdkVersion|^targetSdkVersion|^application-label:" | ForEach-Object { $_.Line }
if (-not ($badging | Select-String -Pattern "package: name='$pkg'" -Quiet)) { Fail "패키지명이 $pkg 가 아닙니다." }
if ($badging | Select-String -Pattern 'application-debuggable' -Quiet) { Fail 'debuggable APK 입니다. Release 가 아닙니다.' }

# 5) 복사 (진단용 -NoR8 빌드는 제출 폴더로 복사하지 않는다)
if ($NoR8) {
    $dest = $built
    Write-Host '[5/5] -NoR8 진단 빌드: apk\ 로 복사하지 않습니다. 설치 테스트용으로만 사용하세요.' -ForegroundColor Yellow
} else {
    Write-Host "[5/5] apk\$outName 로 복사" -ForegroundColor Cyan
    New-Item -ItemType Directory -Force $outDir | Out-Null
    $dest = Join-Path $outDir $outName
    Copy-Item $built $dest -Force
}
Get-Item $dest | Select-Object Name, Length, LastWriteTime | Format-List
Get-FileHash $dest -Algorithm SHA256 | Select-Object Hash | Format-List

if ($Install) {
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    if (-not (Test-Path $adb)) { Fail "adb 를 찾지 못했습니다: $adb" }
    Write-Host '[설치] adb install -r' -ForegroundColor Cyan
    & $adb install -r $dest
    if ($LASTEXITCODE -ne 0) { Fail '설치 실패 (기존에 다른 서명으로 설치된 앱이 있으면 adb uninstall com.autoresolve 후 다시 시도)' }
    & $adb logcat -c | Out-Null
    $monkeyOut = (& $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>&1) | Out-String
    Start-Sleep -Seconds 5

    # 실행 확인: 'AndroidRuntime' 단어만으로는 크래시로 보지 않는다(monkey 같은 셸 도구도 이 태그로 시작/종료 로그를 남긴다).
    # com.autoresolve 프로세스의 FATAL EXCEPTION, ANR, 프로세스 종료, Activity 실행 실패만 크래시로 판정한다.
    Write-Host '[실행 확인]' -ForegroundColor Cyan
    $log = @(& $adb logcat -d -v threadtime 2>&1 | ForEach-Object { "$_" })
    $problems = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $log.Count; $i++) {
        $l = $log[$i]
        if ($l -match 'FATAL EXCEPTION') {
            $next = ($log[($i+1)..([Math]::Min($i+4, $log.Count-1))] -join "`n")
            if ($next -match "Process:\s*$([regex]::Escape($pkg))\b") {
                $problems.Add("FATAL EXCEPTION ($pkg): " + (($log[$i..([Math]::Min($i+6, $log.Count-1))]) -join ' | '))
            }
        }
        elseif ($l -match "ANR in $([regex]::Escape($pkg))\b") { $problems.Add("ANR: $l") }
        elseif ($l -match "Process $([regex]::Escape($pkg)) \(pid \d+\) has died") { $problems.Add("프로세스 종료: $l") }
        elseif ($l -match "Force finishing activity .*$([regex]::Escape($pkg))/") { $problems.Add("Activity 강제 종료: $l") }
        elseif ($l -match "Unable to start activity .*$([regex]::Escape($pkg))" -or $l -match "Activity class \{$([regex]::Escape($pkg))/.*does not exist") { $problems.Add("Activity 실행 실패: $l") }
    }
    if ($monkeyOut -match 'No activities found|Error type|monkey aborted|Injection of .* failed') { $problems.Add("monkey 실행 실패: " + ($monkeyOut.Trim() -replace '\s+',' ')) }
    if ($problems.Count -gt 0) {
        $problems | Select-Object -First 8 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
        Fail '앱 실행 중 크래시/실행 실패 로그가 있습니다. 위 항목을 확인하세요.'
    }

    $pidOut = ((& $adb shell pidof $pkg 2>&1) | Out-String).Trim()
    if ($pidOut -notmatch '^\d+(\s+\d+)*$') { Fail "$pkg 프로세스가 실행 중이 아닙니다(pidof 결과: '$pidOut'). 실행 직후 종료된 것일 수 있습니다." }
    Write-Host "    프로세스 실행 중 (pid $pidOut)"

    $acts = ((& $adb shell dumpsys activity activities 2>&1) | Out-String)
    $resumed = ($acts -split "`n" | Where-Object { $_ -match 'ResumedActivity' -and $_ -match [regex]::Escape($pkg) } | Select-Object -First 1)
    if (-not $resumed) { Fail "$pkg 의 Activity 가 resumed 상태가 아닙니다. 앱이 화면 앞에 있지 않습니다." }
    Write-Host "    Activity 상태: $($resumed.Trim())"
    Write-Host '    프로세스/Activity 상태 확인됨. 단, 화면 표시는 이 스크립트가 확인하지 못합니다 - 에뮬레이터 화면을 직접 보고 확인해 주세요(실행 검증 미완료).' -ForegroundColor Yellow
}

Write-Host '완료. 이 출력만으로는 기능 검증이 끝난 것이 아닙니다. README 의 검증 체크리스트를 진행하세요.' -ForegroundColor Green
