# H.WITH - Gleo 연동 adb 테스트 도우미 (Windows PowerShell 5.1 / PowerShell 7)
# 앱 데이터와 접수 내역을 지우지 않습니다. 읽기(logcat, run-as cat)와 Activity 실행만 합니다.
#
# 불러오기 (Android Studio Terminal, 프로젝트 폴더에서):   . .\gleo-adb-test.ps1
# 처음 한 번:  Test-GleoSetup     (adb, 앱 설치, 사용자 ID, 저장소 접근, 인코딩 점검)
# 본 테스트:   Test-GleoDelivery  (ACTION_PARAM 이 온전히 전달되는지만 확인)
#              Test-GleoFlow      (REPORT -> 저장 -> FIND -> 같은 requestId)
#
# 주의 1) Test-GleoFlow 는 접수 초안 1건을 새로 만듭니다. 접수 내역에 남고 삭제하지 않습니다. (시작 전 확인)
# 주의 2) 이 스크립트가 확인하는 것은 "앱이 콜백을 보냈는지(logcat)"까지입니다.
#         실제 Gleo가 그 콜백을 받아 말하는지는 Pleos 실기기에서만 확인할 수 있습니다.
# 주의 3) run-as 는 debug 빌드에서만 됩니다.
# 주의 4) Base64 는 이 테스트 스크립트가 adb 로 문자열을 안전하게 넘기는 수단일 뿐입니다.
#         앱이 받는 ACTION_PARAM 은 실제 Gleo와 같은 평범한 JSON 문자열입니다.

# 한글이 logcat/콘솔에서 깨져 보이는 것(예: 利앹긽)은 UTF-8 출력을 콘솔이 다른 코드페이지로 읽어서 생기는 표시 문제입니다.
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    [Console]::InputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" }
$pkg = "com.autoresolve"
$global:GleoFails = 0
$script:AppUser = $null
$script:LastPrefsDetail = ''

# ---------- 기본 점검 ----------

function Get-AppUser {
    # 오토모티브/에뮬레이터는 현재 사용자가 0이 아닐 수 있습니다. 앱 데이터는 그 사용자 폴더에 생깁니다.
    if ($null -ne $script:AppUser) { return $script:AppUser }
    $u = ((& $adb shell "am get-current-user 2>/dev/null") -join '').Trim()
    if ($u -notmatch '^\d+$') { $u = '0' }
    $script:AppUser = $u
    $script:AppUser
}

function Invoke-RunAs([string]$Command) {
    $u = Get-AppUser
    $cmd = 'run-as ' + $pkg + ' --user ' + $u + ' ' + $Command + ' 2>&1'
    (@(& $adb shell $cmd) -join "`n")
}

function Test-GleoSetup {
    Write-Host "== adb / 기기 =="
    if (-not (Test-Path $adb)) { Write-Host "  adb 를 찾지 못했습니다: $adb" -ForegroundColor Red; return }
    $state = ((& $adb get-state 2>&1) -join '').Trim()
    Write-Host "  adb get-state: $state"
    if ($state -ne 'device') { Write-Host "  에뮬레이터가 연결되지 않았습니다." -ForegroundColor Red; return }
    Write-Host "  현재 사용자: $(Get-AppUser)   (adb shell pm list users 로 확인 가능)"
    $inst = ((& $adb shell "pm list packages --user $(Get-AppUser) $pkg") -join '').Trim()
    Write-Host "  앱 설치(현재 사용자): $(if ($inst -match $pkg) { '예' } else { '아니오 - ' + $inst })"
    Write-Host "== 저장소(run-as) =="
    $ls = Invoke-RunAs 'ls'
    Write-Host "  앱 폴더: $(($ls -replace "`n",' '))"
    $sp = Invoke-RunAs 'ls shared_prefs'
    Write-Host "  shared_prefs: $(($sp -replace "`n",' '))"
    foreach ($f in 'autoresolve_service_requests.xml', 'autoresolve_active_consult.xml') {
        $r = Read-AppPrefs $f
        Write-Host ("  {0}: {1} {2}" -f $f, $r.State, $r.Detail)
    }
    Write-Host "== 인코딩 =="
    Write-Host ("  Console 출력 인코딩: {0}   (한글 확인: 증상이 아직)" -f [Console]::OutputEncoding.WebName)
}

# ---------- 호출 ----------

# 값 문자열을 영숫자 이외는 모두 \uXXXX 로 바꿉니다. 공백/따옴표/역슬래시/$/` 등이 셸에서 해석되거나
# 단어가 쪼개지는 일을 원천적으로 막습니다. (JSON 으로는 원래 글자와 완전히 같은 값입니다)
function ConvertTo-SafeJsonString([string]$Text) {
    $sb = New-Object System.Text.StringBuilder
    foreach ($ch in $Text.ToCharArray()) {
        $code = [int]$ch
        $isAlnum = (($code -ge 48 -and $code -le 57) -or ($code -ge 65 -and $code -le 90) -or ($code -ge 97 -and $code -le 122))
        if ($isAlnum) { [void]$sb.Append($ch) } else { [void]$sb.Append(('\u{0:x4}' -f $code)) }
    }
    $sb.ToString()
}

# 문자열 값만 가진 JSON 객체를 만듭니다. (이 앱의 Gleo 파라미터는 모두 문자열)
function New-GleoParamJson([hashtable]$Params) {
    if ($Params.Count -eq 0) { return '{}' }
    $parts = @()
    foreach ($k in $Params.Keys) {
        if ($k -notmatch '^[A-Za-z0-9_]+$') { throw "키는 영숫자/밑줄만 허용합니다: $k" }
        $parts += ('"' + $k + '":"' + (ConvertTo-SafeJsonString ([string]$Params[$k])) + '"')
    }
    '{' + ($parts -join ',') + '}'
}

# Gleo가 하듯이 Action + ACTION_PARAM(JSON 문자열)으로 호출합니다.
# 기기 셸에서는 base64 를 변수 p 로 풀어 넘깁니다. JSON 에 공백/글롭 문자가 없으므로 단어 분리가 일어나지 않습니다.
function Send-Gleo([string]$Action, [hashtable]$Params = @{}) {
    if ($Action -notmatch '^[A-Za-z0-9_.]+$') { throw "action 형식 오류: $Action" }
    $json = New-GleoParamJson $Params
    $b64 = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($json))
    Write-Host ">> $Action  $json" -ForegroundColor Cyan
    $remote = 'p=$(echo ' + $b64 + ' | base64 -d); am start --user ' + (Get-AppUser) + ' -n ' + $pkg + '/.GleoActionActivity -a ' + $Action + ' --es ACTION_PARAM $p'
    $out = (@(& $adb shell $remote) -join "`n")
    if ($out -match 'Error|Exception') { Write-Host "  am start 출력: $out" -ForegroundColor Yellow }
}

# ACTION_PARAM 없이 호출 (파라미터 누락 시험)
function Send-GleoNoParam([string]$Action) {
    Write-Host ">> $Action  (ACTION_PARAM 없음)" -ForegroundColor Cyan
    $remote = 'am start --user ' + (Get-AppUser) + ' -n ' + $pkg + '/.GleoActionActivity -a ' + $Action
    $out = (@(& $adb shell $remote) -join "`n")
    if ($out -match 'Error|Exception') { Write-Host "  am start 출력: $out" -ForegroundColor Yellow }
}

# ---------- JSON 배열 ----------

# Windows PowerShell 5.1 의 ConvertFrom-Json 은 JSON 배열을 "배열 하나"로 내보내서, @(... | ConvertFrom-Json) 하면
# 항목이 여러 개여도 Count 가 1 로 나옵니다. 항상 항목 단위로 풀어서 돌려줍니다. (PowerShell 7 에서도 동일하게 동작)
# 호출하는 쪽은 @(ConvertFrom-JsonArray $text) 로 받습니다.
function ConvertFrom-JsonArray([string]$Text) {
    if ([string]::IsNullOrWhiteSpace($Text)) { return }
    $parsed = ConvertFrom-Json -InputObject $Text
    foreach ($e in @($parsed)) {
        if ($e -is [System.Array]) { foreach ($x in $e) { $x } } else { $e }
    }
}

# ---------- 로그 읽기 ----------

function Get-GleoLog {
    @(& $adb logcat -d -v time -s AutoResolveGleo:V AutoResolveTriage:V AutoResolveActive:V | ForEach-Object { [string]$_ })
}

function Get-GleoCallbackLines {
    @(Get-GleoLog | Where-Object { $_ -like '*callback sent: *' })
}

# 콜백 로그 한 줄을 객체로 풉니다. data 는 "JSON 배열 문자열"이어야 합니다.
function ConvertFrom-GleoCallbackLine([string]$Text) {
    $marker = 'callback sent: '
    $i = $Text.IndexOf($marker)
    if ($i -lt 0) { return $null }
    $json = $Text.Substring($i + $marker.Length).Trim()
    try { $o = $json | ConvertFrom-Json } catch { return $null }
    $d = $null
    $isArrayString = $false
    if ($o.data) {
        $isArrayString = (($o.data -is [string]) -and $o.data.TrimStart().StartsWith('['))
        try { $d = @(ConvertFrom-JsonArray ([string]$o.data))[0] } catch { $d = $null }
    }
    [pscustomobject]@{
        Function = $o.functionName
        Status = $o.status
        Message = $o.message
        Data = $d
        DataIsJsonArrayString = $isArrayString
        Raw = $json
    }
}

# 호출 -> 새 로그가 생길 때까지 최대 10초 대기 -> 이번 호출에서 생긴 로그만 돌려줍니다.
# 반환: Callback(콜백 객체 또는 $null), Lines(이번 호출의 로그), Delivery(앱이 받은 ACTION_PARAM 정보)
function Invoke-GleoAndWait([string]$Action, [hashtable]$Params = @{}, [switch]$NoParam) {
    $before = @(Get-GleoLog)
    $lastBefore = if ($before.Count -gt 0) { $before[$before.Count - 1] } else { $null }
    if ($NoParam) { Send-GleoNoParam $Action } else { Send-Gleo $Action $Params }

    $new = @()
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 500
        $now = @(Get-GleoLog)
        $idx = if ($lastBefore) { [array]::LastIndexOf($now, $lastBefore) } else { -1 }
        $new = if ($idx -ge 0) { @($now | Select-Object -Skip ($idx + 1)) } else { $now }
        if (@($new | Where-Object { $_ -like '*callback sent: *' }).Count -gt 0) { break }
    }
    $cbLine = @($new | Where-Object { $_ -like '*callback sent: *' }) | Select-Object -Last 1
    $cb = if ($cbLine) { ConvertFrom-GleoCallbackLine $cbLine } else { $null }
    if (-not $cb) { Write-Host "!! 10초 안에 이번 호출의 콜백 로그가 없습니다." -ForegroundColor Red }

    $actionLine = @($new | Where-Object { $_ -like '*action=*paramKeys=*' }) | Select-Object -First 1
    $keys = $null
    if ($actionLine -and $actionLine -match 'paramKeys=\[(.*?)\]') {
        $keys = @($Matches[1].Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    }
    $symLine = @($new | Where-Object { $_ -like '*symptomLen=*' }) | Select-Object -First 1
    $symLen = $null
    if ($symLine -and $symLine -match 'symptomLen=(\d+)') { $symLen = [int]$Matches[1] }
    $parseFailed = (@($new | Where-Object { $_ -like '*Could not parse ACTION_PARAM*' -or $_ -like '*JSONException*' }).Count -gt 0)

    [pscustomobject]@{
        Callback = $cb
        Lines = $new
        Delivery = [pscustomobject]@{ Keys = $keys; SymptomLen = $symLen; ParseFailed = $parseFailed; ActionLine = $actionLine }
    }
}

# ---------- 보기 ----------

function Show-Gleo([int]$Last = 8) {
    Get-GleoLog | Where-Object { $_ -match 'action=|triage|registered|callback sent|fail|Exception|Could not' } | Select-Object -Last $Last
}

function Show-CallbackSummary([int]$Last = 6) {
    Get-GleoCallbackLines | Select-Object -Last $Last | ForEach-Object {
        $c = ConvertFrom-GleoCallbackLine $_
        if ($c) {
            $id = if ($c.Data -and $c.Data.requestId) { $c.Data.requestId } else { '(없음)' }
            "{0,-24} status={1,-8} requestId={2}" -f $c.Function, $c.Status, $id
        }
    }
}

# ---------- 저장소(읽기 전용) ----------

# State: ok(정상 읽음) / run_as_failed(조회 실패) / no_dir(shared_prefs 폴더 없음 = 이 사용자에게 저장된 적 없음)
#        no_file(폴더는 있으나 파일 없음) / parse_failed(내용 해석 실패)
function Read-AppPrefs([string]$File) {
    $text = Invoke-RunAs ('cat shared_prefs/' + $File)
    $res = [pscustomobject]@{ State = 'ok'; Xml = $null; Detail = '' }
    if ($text -match 'not debuggable|Unknown package|is not an application|run-as:.*(error|denied|fail)|Permission denied|more than one device|no devices') {
        $res.State = 'run_as_failed'; $res.Detail = ($text -replace "`n", ' ')
    }
    elseif ($text -match 'No such file or directory') {
        $dir = Invoke-RunAs 'ls shared_prefs'
        if ($dir -match 'No such file or directory') {
            $res.State = 'no_dir'
            $res.Detail = ('(사용자 ' + (Get-AppUser) + ' 에 shared_prefs 폴더 없음: 아직 저장된 적이 없거나, 앱이 다른 사용자로 실행 중)')
        } else {
            $res.State = 'no_file'; $res.Detail = ('(폴더 안 목록: ' + ($dir -replace "`n", ' ') + ')')
        }
    }
    elseif ($text -match '<map') {
        try { $res.Xml = [xml]$text } catch { $res.State = 'parse_failed'; $res.Detail = $_.Exception.Message }
    }
    else { $res.State = 'run_as_failed'; $res.Detail = ($text -replace "`n", ' ') }
    $script:LastPrefsDetail = $res.Detail
    $res
}

# 접수 목록. State 가 no_dir/no_file 이면 "아직 없음"(정상적인 빈 저장소), run_as_failed/parse_failed 이면 "조회 실패"입니다.
function Get-StoredRequestsEx {
    $r = Read-AppPrefs 'autoresolve_service_requests.xml'
    $items = @()
    if ($r.State -eq 'ok') {
        $node = $r.Xml.map.string | Where-Object { $_.name -eq 'items' }
        if ($node) {
            try { $items = @(ConvertFrom-JsonArray ([string]$node.'#text')) } catch { $r.State = 'parse_failed'; $r.Detail = $_.Exception.Message }
        }
    }
    [pscustomobject]@{ State = $r.State; Detail = $r.Detail; Items = $items }
}

function Test-StoreReadable($s) { return ($s.State -in @('ok', 'no_dir', 'no_file')) }

function Show-RequestIds {
    $s = Get-StoredRequestsEx
    switch ($s.State) {
        'run_as_failed' { Write-Host "조회 실패(run-as): $($s.Detail)" -ForegroundColor Red; return }
        'parse_failed'  { Write-Host "내용 해석 실패: $($s.Detail)" -ForegroundColor Red; return }
        'no_dir'        { Write-Host "저장된 접수 0건 (저장소 없음) $($s.Detail)"; return }
        'no_file'       { Write-Host "저장된 접수 0건 (파일 없음) $($s.Detail)"; return }
    }
    ("저장된 접수 {0}건" -f @($s.Items).Count) | Write-Host
    @($s.Items) | Sort-Object createdAt | ForEach-Object {
        "  {0}  {1,-18} {2,-9} {3}" -f $_.id, $_.part, $_.status, $_.symptom
    }
}

# 진행 중 접수. State 가 no_file/no_dir 이면 "아직 없음"(정상), run_as_failed/parse_failed 이면 조회 실패입니다.
function Get-ActiveEntriesEx {
    $r = Read-AppPrefs 'autoresolve_active_consult.xml'
    $entries = @()
    if ($r.State -eq 'ok') {
        $node = $r.Xml.map.string | Where-Object { $_.name -eq 'entries' }
        if ($node) {
            try { $entries = @(ConvertFrom-JsonArray ([string]$node.'#text')) } catch { $r.State = 'parse_failed'; $r.Detail = $_.Exception.Message }
        }
    }
    [pscustomobject]@{ State = $r.State; Detail = $r.Detail; Entries = $entries }
}

function Show-ActiveConsult {
    $a = Get-ActiveEntriesEx
    if (-not (Test-StoreReadable $a)) { Write-Host ("진행 중 접수 조회 실패: {0} {1}" -f $a.State, $a.Detail) -ForegroundColor Red; return }
    if (@($a.Entries).Count -eq 0) { Write-Host "진행 중 접수 없음 ($($a.State))"; return }
    $nowMs = [int64]((& $adb shell "date +%s").Trim()) * 1000
    @($a.Entries) | ForEach-Object {
        $age = [math]::Round(($nowMs - [int64]$_.at) / 60000.0, 1)
        "  {0}  source={1}  {2}분 전 (30분이 지나면 이어받지 않음)  차량키={3}" -f $_.id, $_.src, $age, $_.vehicle
    }
}

# ---------- 검사 ----------

function Test-Check([string]$Name, $Ok, [string]$Detail = '') {
    if ([bool]$Ok) { Write-Host ("  PASS  {0}" -f $Name) -ForegroundColor Green; return $true }
    $global:GleoFails++
    Write-Host ("  FAIL  {0}  {1}" -f $Name, $Detail) -ForegroundColor Red
    return $false
}

function Write-Skip([string]$Name, [string]$Why) {
    $global:GleoFails++
    Write-Host ("  SKIP(선행 단계 실패)  {0}  - {1}" -f $Name, $Why) -ForegroundColor Yellow
}

# 앱이 ACTION_PARAM 을 온전히 받았는지: 키 목록, 증상 길이, 파싱 오류 여부를 앱 로그로 확인합니다.
function Test-Delivery($Result, [string]$ExpectedSymptom) {
    $d = $Result.Delivery
    $okParse = Test-Check 'ACTION_PARAM 파싱 오류 없음' (-not $d.ParseFailed) 'Could not parse / JSONException 로그가 있음'
    $okKeys = Test-Check 'paramKeys 에 symptom 포함' ($d.Keys -and ($d.Keys -contains 'symptom')) ('paramKeys=[' + (($d.Keys) -join ',') + ']')
    $okLen = Test-Check ('앱이 받은 증상 길이 = 보낸 길이(' + $ExpectedSymptom.Length + ')') ($d.SymptomLen -eq $ExpectedSymptom.Length) ('앱 symptomLen=' + $d.SymptomLen)
    return ($okParse -and $okKeys -and $okLen)
}

# 전달 확인만 합니다. 공백/따옴표/역슬래시가 든 문장을 보내고 앱이 받은 길이를 비교합니다. (접수 건수 변화도 같이 표시)
function Test-GleoDelivery {
    $global:GleoFails = 0
    $cases = @(
        '브레이크를 밟을 때마다 끼익 소리가 나요',
        '차에서 "이상한" 소리가 나요 \ 그리고 100% 확실해요'
    )
    $before = Get-StoredRequestsEx
    foreach ($sym in $cases) {
        Write-Host "`n[전달 시험] $sym"
        $res = Invoke-GleoAndWait 'REPORT_VEHICLE_PROBLEM' @{ symptom = $sym }
        [void](Test-Check '콜백 수신' ($null -ne $res.Callback))
        [void](Test-Delivery $res $sym)
        if ($res.Callback) { Write-Host ("  status={0}" -f $res.Callback.Status) }
    }
    $after = Get-StoredRequestsEx
    if ((Test-StoreReadable $before) -and (Test-StoreReadable $after)) {
        Write-Host ("`n접수 건수: {0} -> {1}  (증상이 충분하면 접수 초안이 생길 수 있고, 삭제하지 않습니다)" -f @($before.Items).Count, @($after.Items).Count)
    } else { Write-Host "`n접수 건수 조회 실패: $($after.State) $($after.Detail)" -ForegroundColor Yellow }
    Write-Host ("결과: " + $(if ($global:GleoFails -eq 0) { "모두 통과" } else { "실패 $($global:GleoFails)건" })) -ForegroundColor $(if ($global:GleoFails -eq 0) { 'Green' } else { 'Red' })
}

# id -> "상태|부위|증상" 지도. 기존 접수가 그대로인지 비교하는 데 씁니다.
function Get-RequestSnapshot($Items) {
    $m = @{}
    foreach ($it in @($Items)) { $m[[string]$it.id] = ([string]$it.status + '|' + [string]$it.part + '|' + [string]$it.symptom) }
    $m
}

# REPORT -> 저장 -> FIND -> 같은 requestId 전체 흐름.
# 앱은 REPORT 가 성공할 때마다 "새 접수 id"를 만들어 접수 내역에 추가합니다(기존 접수를 재사용/갱신하지 않음).
# 같은 경로(gleo)·같은 차량의 이전 "진행 중" 표시는 새 접수로 넘어가지만, 이전 접수 자체는 접수 내역에 그대로 남습니다.
# 그래서 기대값은 "기존 접수 id 는 모두 그대로 + 새 id 1건 추가" 입니다. 앞 단계가 실패하면 뒤 단계는 성공으로 표시하지 않습니다.
function Test-GleoFlow([string]$Symptom = '브레이크를 밟을 때마다 끼익 소리가 나요') {
    Write-Host "※ 이 테스트는 접수 초안 1건을 새로 만듭니다. 접수 내역에 남고 삭제하지 않습니다." -ForegroundColor Yellow
    if ((Read-Host "계속하려면 Y 입력") -ne 'Y') { Write-Host "취소했습니다."; return }

    $global:GleoFails = 0
    $s0 = Get-StoredRequestsEx
    $a0 = Get-ActiveEntriesEx
    if (-not (Test-StoreReadable $s0)) { Write-Host "접수 저장소 조회 실패($($s0.State)): $($s0.Detail)  -> Test-GleoSetup 으로 원인을 확인하세요." -ForegroundColor Red; return }
    if (-not (Test-StoreReadable $a0)) { Write-Host "진행 중 접수 저장소 조회 실패($($a0.State)): $($a0.Detail)" -ForegroundColor Red; return }

    $items0 = @($s0.Items)
    $ids0 = @($items0 | ForEach-Object { [string]$_.id })
    $snap0 = Get-RequestSnapshot $items0
    $active0 = @($a0.Entries)
    Write-Host ("`n[시작 상태] 접수 {0}건: {1}" -f $ids0.Count, ($ids0 -join ', '))
    Write-Host ("            진행 중 접수 {0}건: {1}" -f $active0.Count, (@($active0 | ForEach-Object { $_.id + '(' + $_.src + ')' }) -join ', '))
    if ($active0.Count -eq 0) { Write-Host "  -> 진행 중 접수 없음: 새 접수 1건이 생겨야 합니다." }
    else { Write-Host "  -> 진행 중 접수 있음: 새 접수 id 가 생기고, 같은 경로·같은 차량의 진행 중 표시만 새 접수로 넘어가야 합니다. 이전 접수는 그대로 남아야 합니다." }

    Write-Host "`n[1] REPORT_VEHICLE_PROBLEM"
    $rep = Invoke-GleoAndWait 'REPORT_VEHICLE_PROBLEM' @{ symptom = $Symptom }
    $cb = $rep.Callback
    [void](Test-Check '콜백 수신' ($null -ne $cb))
    [void](Test-Delivery $rep $Symptom)
    $okStatus = $false; $reportId = $null
    if ($cb) {
        Write-Host ("  status={0}  message={1}" -f $cb.Status, $cb.Message)
        [void](Test-Check 'functionName=REPORT_VEHICLE_PROBLEM' ($cb.Function -eq 'REPORT_VEHICLE_PROBLEM'))
        [void](Test-Check 'data 가 JSON 배열 문자열' $cb.DataIsJsonArrayString)
        $okStatus = Test-Check 'REPORT status=success' ($cb.Status -eq 'success') ('status=' + $cb.Status)
        if ($cb.Data) { $reportId = [string]$cb.Data.requestId }
    }
    $okRid = Test-Check 'REPORT requestId 가 비어 있지 않음' (-not [string]::IsNullOrWhiteSpace($reportId)) 'requestId 없음'

    $okSaved = $false
    $items1 = @(); $snap1 = $null
    if ($okStatus -and $okRid) {
        Start-Sleep -Seconds 1
        $s1 = Get-StoredRequestsEx
        $a1 = Get-ActiveEntriesEx
        if ((Test-StoreReadable $s1) -and (Test-StoreReadable $a1)) {
            $items1 = @($s1.Items)
            $ids1 = @($items1 | ForEach-Object { [string]$_.id })
            $snap1 = Get-RequestSnapshot $items1
            Write-Host ("  REPORT 후 접수 {0}건: {1}" -f $ids1.Count, ($ids1 -join ', '))
            $saved = $items1 | Where-Object { [string]$_.id -ceq $reportId } | Select-Object -First 1
            $okSaved = Test-Check '새 접수 초안이 저장소에 있음(상태 SAVED)' ($saved -and $saved.status -eq 'SAVED') '해당 id 없음 또는 상태 다름'
            if ($saved) { [void](Test-Check '저장된 증상 = 입력한 증상' ([string]$saved.symptom -ceq $Symptom) ('저장된 값: ' + $saved.symptom)) }
            [void](Test-Check '새 requestId 가 기존 접수 id 와 겹치지 않음(재사용/덮어쓰기 아님)' ($ids0 -notcontains $reportId) ("기존 목록에 이미 있음: $reportId"))
            $lost = @($ids0 | Where-Object { $ids1 -notcontains $_ })
            [void](Test-Check '기존 접수가 하나도 사라지지 않음' ($lost.Count -eq 0) ('사라진 접수: ' + ($lost -join ', ')))
            $changed = @($ids0 | Where-Object { $snap1.ContainsKey($_) -and $snap1[$_] -cne $snap0[$_] })
            [void](Test-Check '기존 접수 내용(상태·부위·증상)이 바뀌지 않음' ($changed.Count -eq 0) ('바뀐 접수: ' + ($changed -join ', ')))
            [void](Test-Check ('접수 건수 = 기존 ' + $ids0.Count + ' + 1') ($ids1.Count -eq $ids0.Count + 1) ('실제 ' + $ids1.Count))

            # 진행 중 접수: 새 접수가 등록되어야 하고, 같은 경로·차량이 아닌 항목은 그대로여야 합니다.
            $act1 = @($a1.Entries)
            $newEntry = $act1 | Where-Object { [string]$_.id -ceq $reportId } | Select-Object -First 1
            [void](Test-Check '진행 중 접수에 새 접수가 등록됨' ($null -ne $newEntry) '진행 중 목록에 없음')
            if ($newEntry) {
                $mustKeep = @($active0 | Where-Object { -not ([string]$_.src -ceq [string]$newEntry.src -and [string]$_.vehicle -ceq [string]$newEntry.vehicle) })
                $keptIds = @($act1 | ForEach-Object { [string]$_.id })
                $dropped = @($mustKeep | Where-Object { $keptIds -notcontains [string]$_.id })
                [void](Test-Check '다른 경로/차량의 진행 중 접수는 그대로' ($dropped.Count -eq 0) ('사라진 항목: ' + (@($dropped | ForEach-Object { $_.id }) -join ', ')))
                $superseded = @($active0 | Where-Object { [string]$_.src -ceq [string]$newEntry.src -and [string]$_.vehicle -ceq [string]$newEntry.vehicle })
                if ($superseded.Count -gt 0) { Write-Host ("  참고: 같은 경로·차량의 이전 진행 중 표시 {0} 는 새 접수로 넘어감(이전 접수는 접수 내역에 남아 있음)" -f (@($superseded | ForEach-Object { $_.id }) -join ', ')) }
            }
        } else {
            Write-Host "  저장소 조회 실패($($s1.State) / $($a1.State))" -ForegroundColor Red
            $global:GleoFails++
        }
    } else {
        Write-Skip '접수 저장·기존 접수 보존 확인' 'REPORT 가 success + requestId 를 돌려주지 않음'
    }

    Write-Host "`n[2] FIND_SERVICE_CENTER"
    if (-not ($okStatus -and $okRid -and $okSaved)) {
        Write-Skip 'FIND 호출 및 requestId 일치 확인' 'REPORT/저장 단계가 통과하지 않아 FIND 연결 검증을 하지 않습니다'
    } else {
        $find = Invoke-GleoAndWait 'FIND_SERVICE_CENTER' @{ sortBy = 'nearest' }
        $fb = $find.Callback
        [void](Test-Check '콜백 수신' ($null -ne $fb))
        $findId = $null; $okFindStatus = $false
        if ($fb) {
            Write-Host ("  status={0}  message={1}" -f $fb.Status, $fb.Message)
            [void](Test-Check 'functionName=FIND_SERVICE_CENTER' ($fb.Function -eq 'FIND_SERVICE_CENTER'))
            [void](Test-Check 'data 가 JSON 배열 문자열' $fb.DataIsJsonArrayString)
            $okFindStatus = Test-Check 'FIND status=success' ($fb.Status -eq 'success') ('status=' + $fb.Status + ' reason=' + $(if ($fb.Data) { $fb.Data.reason }))
            if ($fb.Data) { $findId = [string]$fb.Data.requestId }
        }
        $okFindId = Test-Check 'FIND requestId 가 비어 있지 않음' (-not [string]::IsNullOrWhiteSpace($findId)) 'requestId 없음'
        if ($okFindStatus -and $okFindId) {
            [void](Test-Check 'FIND requestId = REPORT requestId' ($findId -ceq $reportId) ("REPORT=$reportId FIND=$findId"))
            [void](Test-Check 'navigationAvailable=false' ($fb.Data.navigationAvailable -eq $false))
            [void](Test-Check 'dataSource=demo_sample_data' ($fb.Data.dataSource -eq 'demo_sample_data'))
            [void](Test-Check 'reservationConfirmed=false' ($fb.Data.reservationConfirmed -eq $false))
        } else {
            Write-Skip 'requestId 일치 확인' 'FIND 가 success + requestId 를 돌려주지 않음'
        }

        Start-Sleep -Seconds 1
        $s2 = Get-StoredRequestsEx
        if (Test-StoreReadable $s2) {
            $snap2 = Get-RequestSnapshot @($s2.Items)
            [void](Test-Check ('FIND 후 접수 건수 = REPORT 직후(' + $snap1.Count + '건)') ($snap2.Count -eq $snap1.Count) ('실제 ' + $snap2.Count))
            $diff = @($snap1.Keys | Where-Object { -not $snap2.ContainsKey($_) -or $snap2[$_] -cne $snap1[$_] })
            [void](Test-Check 'FIND 가 어떤 접수도 바꾸거나 지우지 않음(새 접수도 없음)' ($diff.Count -eq 0) ('달라진 접수: ' + ($diff -join ', ')))
            [void](Test-Check '새 접수는 아직 센터에 전달되지 않음(SAVED)' ($snap2.ContainsKey($reportId) -and $snap2[$reportId].StartsWith('SAVED|')))
        } else { Write-Host "  저장소 조회 실패($($s2.State))" -ForegroundColor Red; $global:GleoFails++ }
    }

    Write-Host "`n앱 화면에서 직접 확인(이 스크립트로는 확인할 수 없음):"
    Write-Host "  - 서비스센터 안내 화면이 자동으로 열렸는지, 맨 위에 입력한 증상이 그대로 보이는지"
    Write-Host "  - 센터를 직접 고르고 '센터에 전달하기'를 직접 눌러야 접수되는지 (자동 접수 아님)"
    Write-Host "  - 접수 완료 화면에 길안내 버튼 없이 '시연용 정비소는 현재 길 안내를 지원하지 않아요.' 가 보이는지"
    Write-Host ("`n결과: " + $(if ($global:GleoFails -eq 0) { "모두 통과" } else { "실패/미실행 $($global:GleoFails)건" })) -ForegroundColor $(if ($global:GleoFails -eq 0) { 'Green' } else { 'Red' })
}

# asking / fail 콜백 확인. 접수는 만들지 않아야 합니다.
function Test-GleoCallbackKinds {
    $global:GleoFails = 0
    $s0 = Get-StoredRequestsEx
    $readable = Test-StoreReadable $s0
    $count0 = @($s0.Items).Count

    Write-Host "`n[asking] 증상 정보 부족 -> missingField=noise_condition"
    $a = (Invoke-GleoAndWait 'REPORT_VEHICLE_PROBLEM' @{ symptom = '차에서 이상한 소리가 나요' }).Callback
    [void](Test-Check 'status=asking' ($a -and $a.Status -eq 'asking') ('status=' + $(if ($a) { $a.Status })))
    [void](Test-Check 'missingField=noise_condition' ($a -and $a.Data -and $a.Data.missingField -eq 'noise_condition') ('missingField=' + $(if ($a -and $a.Data) { $a.Data.missingField })))

    Write-Host "`n[asking] ACTION_PARAM 없음 -> missingField=symptom"
    $b = (Invoke-GleoAndWait 'REPORT_VEHICLE_PROBLEM' -NoParam).Callback
    [void](Test-Check 'status=asking' ($b -and $b.Status -eq 'asking') ('status=' + $(if ($b) { $b.Status })))
    [void](Test-Check 'missingField=symptom' ($b -and $b.Data -and $b.Data.missingField -eq 'symptom'))

    Write-Host "`n[fail] 지원하지 않는 action"
    $c = (Invoke-GleoAndWait 'UNKNOWN_ACTION' @{}).Callback
    [void](Test-Check 'status=fail' ($c -and $c.Status -eq 'fail') ('status=' + $(if ($c) { $c.Status })))

    Start-Sleep -Seconds 1
    $s1 = Get-StoredRequestsEx
    if ($readable -and (Test-StoreReadable $s1)) {
        [void](Test-Check 'asking/fail 은 접수를 만들지 않음' (@($s1.Items).Count -eq $count0) ("전 $count0 / 후 " + @($s1.Items).Count))
    } else { Write-Host "  접수 건수 비교 불가(저장소 조회 실패: $($s1.State))" -ForegroundColor Yellow }
    Write-Host ("`n결과: " + $(if ($global:GleoFails -eq 0) { "모두 통과" } else { "실패 $($global:GleoFails)건" })) -ForegroundColor $(if ($global:GleoFails -eq 0) { 'Green' } else { 'Red' })
}

# 진행 중 접수(brake)가 있을 때 다른 부위(tire)를 말하면 임의로 고르지 않고 asking(part_mismatch) 이 되는지
function Test-GleoPartMismatch {
    $global:GleoFails = 0
    Write-Host "(먼저 Test-GleoFlow 로 brake 접수를 만들고 30분 안에 실행하세요)"
    $s0 = Get-StoredRequestsEx
    $count0 = @($s0.Items).Count
    $r = (Invoke-GleoAndWait 'FIND_SERVICE_CENTER' @{ part = 'tire' }).Callback
    [void](Test-Check 'status=asking, reason=part_mismatch' ($r -and $r.Status -eq 'asking' -and $r.Data -and $r.Data.reason -eq 'part_mismatch') ('status=' + $(if ($r) { $r.Status }) + ' reason=' + $(if ($r -and $r.Data) { $r.Data.reason })))
    Start-Sleep -Seconds 1
    $s1 = Get-StoredRequestsEx
    if ((Test-StoreReadable $s0) -and (Test-StoreReadable $s1)) { [void](Test-Check '접수 건수 그대로' (@($s1.Items).Count -eq $count0)) }
    Write-Host "  앱에서 '내 정비 접수' 화면이 열려야 합니다(눈으로 확인)."
}

Write-Host "준비 완료. 먼저 Test-GleoSetup, 그다음 Test-GleoDelivery, Test-GleoFlow. (그 밖: Test-GleoCallbackKinds / Test-GleoPartMismatch / Show-Gleo / Show-CallbackSummary / Show-RequestIds / Show-ActiveConsult)" -ForegroundColor Green
