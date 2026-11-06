# Verify per-connection write-queue backpressure (maxPendingWriteBytes) for SSE streams.
#
# Scenario A (fast consumer): a client that reads everything must receive every SSE frame
#   intact - chunked framing is not broken by the single-write frame change, and the
#   producer is never aborted.
# Scenario B (slow consumer): a client that stops reading must be disconnected by the
#   server once pendingWriteBytes crosses the cap; the server must stay alive, keep its
#   memory flat, and the producer loop must stop generating instead of spinning to the
#   full event count.
param(
    [int]$Port = 18360,
    [int]$FastEvents = 2000,
    [int]$SlowEvents = 500000,
    [int]$MaxDeliveredBytes = 8388608,
    [int]$MaxHeapGrowthMb = 200
)

$ErrorActionPreference = 'Stop'
$ROOT = 'G:\work\utils-support-parent-starter'
$MOD = "$ROOT\utils-support-core-parent\utils-support-common-starter"
$REPO = 'Z:\environment\maven\repository'
$COMPILE = "$ROOT\docs\perf\compile"

$jars = @(
    'org\slf4j\slf4j-api\1.7.36\slf4j-api-1.7.36.jar',
    'com\google\guava\guava\33.7.2-jre\guava-33.7.2-jre.jar',
    'com\google\guava\failureaccess\1.0.3\failureaccess-1.0.3.jar',
    'org\eclipse\collections\eclipse-collections\13.0.0\eclipse-collections-13.0.0.jar',
    'org\eclipse\collections\eclipse-collections-api\13.0.0\eclipse-collections-api-13.0.0.jar',
    'io\projectreactor\reactor-core\3.8.7\reactor-core-3.8.7.jar',
    'org\reactivestreams\reactive-streams\1.0.4\reactive-streams-1.0.4.jar',
    'com\fasterxml\jackson\core\jackson-databind\2.21.5\jackson-databind-2.21.5.jar',
    'com\fasterxml\jackson\core\jackson-core\2.21.5\jackson-core-2.21.5.jar',
    'com\fasterxml\jackson\core\jackson-annotations\2.21\jackson-annotations-2.21.jar',
    'com\fasterxml\jackson\datatype\jackson-datatype-jdk8\2.21.5\jackson-datatype-jdk8-2.21.5.jar',
    'org\apache\commons\commons-lang3\3.13.0\commons-lang3-3.13.0.jar'
)
$BASE = "$MOD\target\classes;$COMPILE"
foreach ($j in $jars) { $BASE += ";$REPO\$j" }

# 从 "key=value" 形式的输出中取值
function Get-Field($lines, $key) {
    foreach ($l in $lines) {
        if ("$l" -match "^$key=(.*)$") { return $Matches[1].Trim() }
    }
    return ''
}

$fail = 0
foreach ($type in @('nio', 'aio')) {
    $log = Join-Path $env:TEMP "sse-$type.log"
    if (Test-Path $log) { Remove-Item $log -Force }
    if (Test-Path "$log.err") { Remove-Item "$log.err" -Force }

    $srv = Start-Process -FilePath 'java' -ArgumentList @('-cp', $BASE, 'FnTestMain', $type, "$Port", '-1', '100') `
        -NoNewWindow -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    $ready = $false
    for ($i = 0; $i -lt 150; $i++) {
        Start-Sleep -Milliseconds 150
        if ((Test-Path $log) -and (Get-Content $log -Raw -ErrorAction SilentlyContinue) -match 'READY') { $ready = $true; break }
        if ($srv.HasExited) { break }
    }
    if (-not $ready) { Write-Host "[$type] SERVER FAILED"; $fail++; if (-not $srv.HasExited) { $srv.Kill() }; continue }

    Write-Host "=== $type ==="

    # ---- A) 快速消费者:必须收到全量帧,结构完整 ----
    $a = & java -cp $BASE SlowClientProbe "$Port" fast "$FastEvents" 512 2>&1
    $aOk = (Get-Field $a 'es_ok') -eq 'true'
    $aFraming = Get-Field $a 'es_framing'
    $aEvents = Get-Field $a 'es_events'
    Write-Host ("  [A] fast consumer   : " + $(if ($aOk) { 'PASS' } else { 'FAIL' }) + "  framing=$aFraming events=$aEvents")
    if (-not $aOk) { $a | Write-Host; $fail++ }

    # ---- B) 慢消费者:服务端必须主动断开,且投递字节有界 ----
    $srv.Refresh()
    $ws0 = $srv.WorkingSet64
    $b = & java -cp $BASE SlowClientProbe "$Port" slow "$SlowEvents" 512 2>&1
    $srv.Refresh()
    $ws1 = $srv.WorkingSet64
    $growthMb = [math]::Round(($ws1 - $ws0) / 1MB, 1)

    $bClosedBy = Get-Field $b 'es_closed_by'
    $bReceived = [int64](Get-Field $b 'es_received_bytes')
    $bExpected = Get-Field $b 'es_expected_bytes'
    $bOk = (Get-Field $b 'es_ok') -eq 'true'
    $bBound = ($bReceived -gt 0) -and ($bReceived -le $MaxDeliveredBytes)
    $bMem = ($growthMb -le $MaxHeapGrowthMb)
    $bPass = $bOk -and $bBound -and $bMem
    Write-Host ("  [B] slow consumer   : " + $(if ($bPass) { 'PASS' } else { 'FAIL' }) +
        "  closed_by=$bClosedBy received=$bReceived expected=$bExpected growthMb=$growthMb")
    if (-not $bPass) { $b | Write-Host; $fail++ }

    # ---- C) 生产端必须停止生成(日志中 generated 远小于 total) ----
    Start-Sleep -Milliseconds 300
    $gen = ''
    $tot = ''
    $body = Get-Content $log -Raw -ErrorAction SilentlyContinue
    # 服务端每处理一次 /sse 打一行,取最后一次(即慢消费者那次)
    $matches2 = [regex]::Matches($body, 'sse generated=(\d+) total=(\d+)')
    if ($matches2.Count -gt 0) {
        $last = $matches2[$matches2.Count - 1]
        $gen = $last.Groups[1].Value
        $tot = $last.Groups[2].Value
    }
    $cPass = ($gen -ne '') -and ([int64]$gen -lt [int64]$tot) -and ([int64]$gen -lt [int64]$SlowEvents)
    Write-Host ("  [C] producer stopped: " + $(if ($cPass) { 'PASS' } else { 'FAIL' }) + "  generated=$gen total=$tot")
    if (-not $cPass) { $fail++ }

    # ---- D) 断开慢消费者后服务端仍然健在(事件循环未被阻塞) ----
    $r = & curl.exe -s --max-time 5 -D - -o NUL --http1.1 "http://127.0.0.1:$Port/echo" 2>&1
    $dPass = "$r" -match 'HTTP/1.1 200' -and "$r" -match 'Content-Length: 11'
    Write-Host ("  [D] server alive    : " + $(if ($dPass) { 'PASS' } else { 'FAIL' }))
    if (-not $dPass) { $r | Write-Host; $fail++ }

    if (-not $srv.HasExited) { $srv.Kill(); $srv.WaitForExit(2000) | Out-Null }
    Start-Sleep -Seconds 1
}

Write-Host ""
if ($fail -eq 0) { Write-Host "ALL BACKPRESSURE CHECKS PASSED" } else { Write-Host "FAILURES: $fail" }
exit $fail
