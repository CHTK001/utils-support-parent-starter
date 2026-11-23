# run-1.3-throughput.ps1  (ASCII-only on purpose: no BOM / no codepage risk)
#
# 1.3 acceptance step: compile + run EngineThroughput harness against the
# installed 4.0.0.51 jars (post-1.2). Requires lib\ to exist (prepare-classpath).

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'lib'))) {
    throw 'lib\ missing - run prepare-classpath.ps1 first'
}
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'classes'))) {
    New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot 'classes') | Out-Null
}

Write-Host '[1/3] javac EngineThroughput.java'
& javac -J-Xms32m -J-Xmx384m -J-XX:MaxMetaspaceSize=192m -J-XX:ReservedCodeCacheSize=64m -J-XX:+UseSerialGC -encoding UTF-8 -d "classes" -cp "lib/*" "harness\EngineThroughput.java"
if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }

Write-Host '[2/3] java harness.EngineThroughput'
Remove-Item -LiteralPath 'EngineThroughput-report.txt' -ErrorAction SilentlyContinue
# PS 5.1 splits "-Dkey=value" at "=" for native calls; pass via array splatting so
# javac sees them intact. Harness writes its UTF-8 report itself; stdout is ASCII.
$javaArgs = @('-Dfile.encoding=UTF-8', '-cp', 'classes;lib/*', 'harness.EngineThroughput')
& java @javaArgs
$rc = $LASTEXITCODE

Write-Host "[3/3] java exit code = $rc"
if (Test-Path -LiteralPath 'EngineThroughput-report.txt') {
    Write-Host "report bytes = $((Get-Item -LiteralPath 'EngineThroughput-report.txt').Length)"
}
exit $rc