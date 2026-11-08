# Run server with CPU sampling, drive load from a separate client JVM, print server CPU breakdown.
param(
    [string]$Type = 'nio',
    [int]$Conns = 200,
    [int]$Duration = 12,
    [int]$Loops = 4,
    # 透传给服务端 JVM 的 -D 选项(例如 -Option '-Dnio.combine=0'),用于同一脚本内 A/B
    [string]$Option = '',
    # 置于 classpath 最前的目录(例如 docs\perf\baseline\classes:与历史版本做同类代码 A/B)
    [string]$Prepend = ''
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
# 基准对照:把历史版本编译产物放到 classpath 最前,覆盖当前 target\classes 中的同名类
if ($Prepend) { $BASE = "$Prepend;$BASE" }
$CPP = $BASE

$port = 18160
$jvmArgs = @()
if ($Option) { $jvmArgs += $Option }
$jvmArgs += @('-cp', $CPP, 'HttpServerCpuMain', $Type, "$port", "$Loops", "$Duration")
$srvLog = Join-Path $env:TEMP "srv-cpu.log"
if (Test-Path $srvLog) { Remove-Item $srvLog -Force }
$srv = Start-Process -FilePath 'java' -ArgumentList $jvmArgs `
    -NoNewWindow -PassThru -RedirectStandardOutput $srvLog -RedirectStandardError "$srvLog.err"
for ($i = 0; $i -lt 100; $i++) {
    Start-Sleep -Milliseconds 150
    if ((Test-Path $srvLog) -and (Get-Content $srvLog -Raw -ErrorAction SilentlyContinue) -match 'READY') { break }
}
Write-Host "[srv-cpu] server up; driving load from client JVM"

$clLog = Join-Path $env:TEMP "cli-cpu.log"
if (Test-Path $clLog) { Remove-Item $clLog -Force }
& java -cp $BASE HttpClientMain $port $Conns $Duration 2>&1 | Tee-Object -FilePath $clLog | Out-Null

$srv.WaitForExit(30000) | Out-Null
Write-Host "--- server CPU report ---"
Get-Content $srvLog | Where-Object { $_ -match '\[cpu\]|%' } | Write-Host
Write-Host "--- client ---"
Get-Content $clLog | Where-Object { $_ -match '\[client\] result:' } | Write-Host
