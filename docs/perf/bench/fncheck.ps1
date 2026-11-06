# Functional verification across response header paths (fast/slow/custom/status/big), NIO and AIO.
param(
    [int]$Port = 18340
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

$fail = 0
foreach ($type in @('nio', 'aio')) {
    $log = Join-Path $env:TEMP "fn-$type.log"
    if (Test-Path $log) { Remove-Item $log -Force }
    $srv = Start-Process -FilePath 'java' -ArgumentList @('-cp', $BASE, 'FnTestMain', $type, "$Port") `
        -NoNewWindow -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    $ready = $false
    for ($i = 0; $i -lt 150; $i++) {
        Start-Sleep -Milliseconds 150
        if ((Test-Path $log) -and (Get-Content $log -Raw -ErrorAction SilentlyContinue) -match 'READY') { $ready = $true; break }
        if ($srv.HasExited) { break }
    }
    if (-not $ready) { Write-Host "[$type] SERVER FAILED"; $fail++; if (-not $srv.HasExited) { $srv.Kill() }; continue }

    Write-Host "=== $type ==="

    # 1) fast path
    $r = & curl.exe -s -D - -o NUL --http1.1 "http://127.0.0.1:$Port/echo" 2>&1
    $ok1 = ($r -match 'HTTP/1.1 200') -and ($r -match 'Content-Length: 11')
    Write-Host ("  [1] fast 200        : " + $(if ($ok1) { 'PASS' } else { 'FAIL' }))
    if (-not $ok1) { $r | Write-Host; $fail++ }

    # 2) slow path with 3 custom headers
    $r = & curl.exe -s -D - -o NUL --http1.1 "http://127.0.0.1:$Port/headers" 2>&1
    $ok2 = ($r -match 'X-Custom-A: alpha') -and ($r -match 'X-Custom-B: beta') -and ($r -match 'Cache-Control: no-store') -and ($r -match 'Content-Length: 12')
    Write-Host ("  [2] custom headers  : " + $(if ($ok2) { 'PASS' } else { 'FAIL' }))
    if (-not $ok2) { $r | Write-Host; $fail++ }

    # 3) custom status 201
    $r = & curl.exe -s -D - -o NUL --http1.1 "http://127.0.0.1:$Port/created" 2>&1
    $ok3 = ($r -match 'HTTP/1.1 201') -and ($r -match 'X-Custom-A: created') -and ($r -match 'Content-Length: 4')
    Write-Host ("  [3] status 201      : " + $(if ($ok3) { 'PASS' } else { 'FAIL' }))
    if (-not $ok3) { $r | Write-Host; $fail++ }

    # 4) user overrides system headers
    $r = & curl.exe -s -D - -o NUL --http1.1 "http://127.0.0.1:$Port/typed" 2>&1
    $body = & curl.exe -s --http1.1 "http://127.0.0.1:$Port/typed" 2>&1
    $ok4 = ($r -match 'Content-Type: application/json') -and ($r -match 'Content-Length: 11') -and ($body -match 'ok')
    Write-Host ("  [4] override hdrs   : " + $(if ($ok4) { 'PASS' } else { 'FAIL' }))
    if (-not $ok4) { $r | Write-Host; $fail++ }

    # 5) empty body
    $r = & curl.exe -s -D - -o NUL --http1.1 "http://127.0.0.1:$Port/empty" 2>&1
    $ok5 = ($r -match 'HTTP/1.1 200') -and ($r -match 'Content-Length: 0')
    Write-Host ("  [5] empty body      : " + $(if ($ok5) { 'PASS' } else { 'FAIL' }))
    if (-not $ok5) { $r | Write-Host; $fail++ }

    # 6) big body 100000 bytes (above combine threshold)
    $len = (& curl.exe -s --http1.1 "http://127.0.0.1:$Port/big" 2>&1 | Out-String).Trim().Length
    $r = & curl.exe -s -D - -o NUL --http1.1 "http://127.0.0.1:$Port/big" 2>&1
    $ok6 = ($r -match 'Content-Length: 100000')
    Write-Host ("  [6] big body 100000 : " + $(if ($ok6) { 'PASS' } else { 'FAIL' }) + "  len=$len")
    if (-not $ok6) { $r | Write-Host; $fail++ }

    # 7) keep-alive reuse: 10 requests, single TCP connection
    # curl 的 -o 只作用于第一个 URL,后续响应体会混入 stdout,故对每个 URL 都指定输出丢弃
    $ka = & curl.exe -s -o NUL -w "%{num_connects} " --http1.1 `
        "http://127.0.0.1:$Port/echo" -o NUL "http://127.0.0.1:$Port/echo" -o NUL `
        "http://127.0.0.1:$Port/echo" -o NUL "http://127.0.0.1:$Port/echo" -o NUL `
        "http://127.0.0.1:$Port/echo" 2>&1
    $conns = ("$ka" -split ' ' | Where-Object { $_ -match '^\d+$' })
    $ok7 = ($conns.Count -ge 5) -and ($conns[0] -eq '1') -and (($conns[1..4] | Where-Object { $_ -ne '0' }).Count -eq 0)
    Write-Host ("  [7] keep-alive reuse: " + $(if ($ok7) { 'PASS' } else { 'FAIL' }) + "  connects=[$ka]")
    if (-not $ok7) { $fail++ }

    # 8) 404 slow path (no custom headers, non-200)
    $r = & curl.exe -s -D - -o NUL --http1.1 "http://127.0.0.1:$Port/missing" 2>&1
    $ok8 = ($r -match 'HTTP/1.1 404') -and ($r -match 'Content-Length: 9')
    Write-Host ("  [8] 404             : " + $(if ($ok8) { 'PASS' } else { 'FAIL' }))
    if (-not $ok8) { $r | Write-Host; $fail++ }

    # 9) SSE streaming: 5 events, chunked framing intact (curl de-chunks)
    $hdr = & curl.exe -s -D - -o NUL --http1.1 --max-time 5 "http://127.0.0.1:$Port/sse?n=5" 2>&1
    $body = & curl.exe -s --http1.1 --max-time 5 "http://127.0.0.1:$Port/sse?n=5" 2>&1
    $dataLines = @($body | Where-Object { $_ -match '^data: ' }).Count
    $ok9 = ($hdr -match 'Content-Type: text/event-stream') -and ($hdr -match 'Transfer-Encoding: chunked') -and ($dataLines -eq 5)
    Write-Host ("  [9] sse stream      : " + $(if ($ok9) { 'PASS' } else { 'FAIL' }) + "  data_lines=$dataLines")
    if (-not $ok9) { $body | Write-Host; $fail++ }

    if (-not $srv.HasExited) { $srv.Kill(); $srv.WaitForExit(2000) | Out-Null }
    Start-Sleep -Seconds 1
}

Write-Host ""
if ($fail -eq 0) { Write-Host "ALL FUNCTIONAL CHECKS PASSED" } else { Write-Host "FAILURES: $fail" }
exit $fail
