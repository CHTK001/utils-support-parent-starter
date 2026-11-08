# 从指定 git 版本提取 Server 关键类并编译到临时目录,用于「同类代码 A/B」基准对照。
#
# 用法:
#   powershell -File baseline.ps1 -Rev c08bf20593
#   powershell -File ..\..\..\srvcpu.ps1 -Type aio -Prepend $env:TEMP\perf-baseline-c08bf20593\classes
#
# 原理:把历史版本的 NioHttpServer/AioHttpServer/NioServerResponse 编到独立目录,
# 再用 srvcpu.ps1 -Prepend 放到 classpath 最前,覆盖 target\classes 中的同名类。
# 这样同一台机器、同一次开机内就能做「改动前 vs 改动后」的每请求 CPU 对照。
#
# 注意:JDK 23+ 默认关闭注解处理,必须显式 -proc:full,否则 lombok 的 @Slf4j 不生效,
#       历史源码里的 log 字段会解析到父类的 private 字段而编译失败。
param(
    [Parameter(Mandatory = $true)][string]$Rev,
    [string]$Out = ''
)

$ErrorActionPreference = 'Stop'
$ROOT = 'G:\work\utils-support-parent-starter'
$MOD = "$ROOT\utils-support-core-parent\utils-support-common-starter"
$REPO = 'Z:\environment\maven\repository'
$COMPILE = "$ROOT\docs\perf\compile"
$SRC = 'utils-support-core-parent/utils-support-common-starter/src/main/java/com/chua/common/support/network/server'

if (-not $Out) { $Out = Join-Path $env:TEMP "perf-baseline-$Rev" }
$srcDir = Join-Path $Out 'src'
$clsDir = Join-Path $Out 'classes'
New-Item -ItemType Directory -Force -Path $srcDir, $clsDir | Out-Null

$paths = @(
    "$SRC/aio/AioHttpServer.java",
    "$SRC/nio/NioHttpServer.java",
    "$SRC/nio/NioServerResponse.java"
)
foreach ($p in $paths) {
    $name = Split-Path $p -Leaf
    # cmd 重定向保留原始字节(UTF-8),避免 PowerShell 管道按本地代码页转码
    cmd /c "cd /d $ROOT && git show $Rev`:$p > `"$srcDir\$name`""
    if (-not (Test-Path "$srcDir\$name")) { throw "git show failed: $p @ $Rev" }
}

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
    'org\apache\commons\commons-lang3\3.13.0\commons-lang3-3.13.0.jar',
    'org\projectlombok\lombok\1.18.48\lombok-1.18.48.jar'
)
$BASE = "$MOD\target\classes;$COMPILE"
foreach ($j in $jars) { $BASE += ";$REPO\$j" }

& javac -proc:full -encoding UTF-8 -nowarn -d $clsDir -cp $BASE `
    "$srcDir\AioHttpServer.java" "$srcDir\NioHttpServer.java" "$srcDir\NioServerResponse.java"
if ($LASTEXITCODE -ne 0) { throw "javac failed (exit $LASTEXITCODE)" }

Write-Host "[baseline] $Rev -> $clsDir"
Write-Host "[baseline] 对照运行: srvcpu.ps1 -Type nio|aio -Prepend $clsDir"
