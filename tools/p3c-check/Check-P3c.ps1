# P3C 质量门禁（Alibaba Java Coding Guidelines 黄山版 · 可机械校验部分）
#
# 用法：
#   pwsh -NoProfile -File tools/p3c-check/Check-P3c.ps1            # 检查整个仓库
#   pwsh -NoProfile -File tools/p3c-check/Check-P3c.ps1 -Quiet     # 只输出汇总
#
# 退出码：0 = 全部通过；1 = 存在违规（明细见输出）

[CmdletBinding()]
param(
    [string]$Root = (Join-Path $PSScriptRoot '..\..'),
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$rootFull = (Resolve-Path (Join-Path $Root '.')).Path
Set-Location $rootFull

# ---------------------------------------------------------------- 工具函数

function Get-JavaFiles {
    $list = git ls-files '*.java' 2>$null
    if (-not $list) { $list = Get-ChildItem -Recurse -Filter *.java | ForEach-Object { $_.FullName } }
    foreach ($f in $list) { if (Test-Path $f) { $f } }
}

function Read-Source([string]$path) {
    [System.IO.File]::ReadAllText((Join-Path (Get-Location) $path), [System.Text.Encoding]::UTF8)
}

# 返回每行是否位于文本块（"""）内部
function Get-TextBlockMask([string[]]$lines) {
    $mask = New-Object bool[] $lines.Count
    $inside = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $q = ([regex]::Matches($lines[$i], '"""')).Count
        if ($inside) {
            $mask[$i] = $true
            if ($q % 2 -eq 1) { $inside = $false }
            continue
        }
        if ($q % 2 -eq 1) { $mask[$i] = $true; $inside = $true; continue }
    }
    return , $mask
}

function Add-Violation([hashtable]$bucket, [string]$check, [string]$where, [string]$detail) {
    if (-not $bucket.ContainsKey($check)) { $bucket[$check] = New-Object System.Collections.ArrayList }
    [void]$bucket[$check].Add([pscustomobject]@{ Where = $where; Detail = $detail })
}

$violations = @{}
$files = @(Get-JavaFiles)
if (-not $Quiet) { Write-Host "扫描 $($files.Count) 个 Java 文件…" }

# 已知豁免（历史遗留 + 语法不可拆）
$longLineAllow = @(
    'AlibabaSmsMessagePush.java|import 语句不可折行',
    'SystemdServiceManager.java|lambda 内单条 append 链'
)

# ---------------------------------------------------------------- 逐文件检查

foreach ($f in $files) {
    if (-not (Test-Path $f)) { continue }
    $lines = [System.IO.File]::ReadAllLines((Join-Path (Get-Location) $f), [System.Text.Encoding]::UTF8)
    $leaf = [System.IO.Path]::GetFileName($f)
    $mask = Get-TextBlockMask $lines

    # 1) 编码：BOM / U+FFFD
    $bytes = [System.IO.File]::ReadAllBytes((Join-Path (Get-Location) $f))
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        Add-Violation $violations 'BOM' $leaf '文件含 UTF-8 BOM'
    }
    $raw = Read-Source $f
    if ($raw.Contains([char]0xFFFD)) { Add-Violation $violations 'U+FFFD' $leaf '含替换字符（编码损坏）' }

    # 2) 行级检查
    $javadocOpen = $false
    $javadocEmpty = $true
    $javadocStart = 0
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $x = $lines[$i]
        $t = $x.TrimStart()

        # 超长行（排除注释、文本块内容）
        if (-not $mask[$i] -and $x.Length -gt 120 -and -not $t.StartsWith('*') -and -not $t.StartsWith('//') -and -not $t.StartsWith('/*')) {
            $allowed = $false
            foreach ($a in $longLineAllow) { if ("$leaf|" -and $a.Split('|')[0] -eq $leaf) { $allowed = $true; break } }
            if (-not $allowed) {
                Add-Violation $violations 'LONG_LINE' ("{0}:{1}" -f $leaf, ($i + 1)) ("{0} 字符" -f $x.Length)
            }
        }

        # 行尾空白（文本块外）
        if (-not $mask[$i] -and $x -match '[ \t]+$') {
            Add-Violation $violations 'TRAILING_WS' ("{0}:{1}" -f $leaf, ($i + 1)) '行尾存在空白'
        }

        # 制表符缩进（文本块外）
        if (-not $mask[$i] -and $x -match '^\t') {
            Add-Violation $violations 'TAB_INDENT' ("{0}:{1}" -f $leaf, ($i + 1)) '使用制表符缩进'
        }

        # Javadoc 空块
        if ($t.StartsWith('/**')) {
            $javadocOpen = $true
            $javadocEmpty = $true
            $javadocStart = $i + 1
            $rest = $t.Substring(3)
            if ($rest -match '\S' -and $rest -notmatch '^\s*\*/') { $javadocEmpty = $false }
            if ($t -match '\*/') { $javadocOpen = $false }
            continue
        }
        if ($javadocOpen) {
            if ($t -match '^\*\s*\S' -and $t -notmatch '^\*\s*@') { $javadocEmpty = $false }
            if ($t -match '\*/') {
                $javadocOpen = $false
                if ($javadocEmpty) {
                    Add-Violation $violations 'EMPTY_JAVADOC' ("{0}:{1}" -f $leaf, $javadocStart) 'Javadoc 没有任何描述'
                }
            }
        }

        # 注解写在 Javadoc 之前：注解独立成行、无同行的声明代码，且下一行开始 Javadoc
        if ($t -match '^@[A-Za-z]\w*(\s*\([^)]*\))?\s*$' -and $i + 1 -lt $lines.Count) {
            $next = $lines[$i + 1].TrimStart()
            if ($next.StartsWith('/**')) {
                Add-Violation $violations 'ANNOTATION_BEFORE_DOC' ("{0}:{1}" -f $leaf, ($i + 1)) '注解位于 Javadoc 之前'
            }
        }

        # void 方法带 @return
        if ($t -match '@return' -and $i + 1 -lt $lines.Count) {
            for ($k = $i + 1; $k -le [Math]::Min($i + 6, $lines.Count - 1); $k++) {
                $sig = $lines[$k].Trim()
                if ($sig -eq '' -or $sig.StartsWith('*') -or $sig.StartsWith('@')) { continue }
                if ($sig -match '^\s*(public|private|protected)?\s*(static\s+)?(final\s+)?void\s+\w+\s*\(') {
                    Add-Violation $violations 'VOID_WITH_RETURN' ("{0}:{1}" -f $leaf, ($i + 1)) 'void 方法标注了 @return'
                }
                break
            }
        }

        # 真实空 catch：catch (...) { } 且体内无注释
        if (-not $mask[$i] -and $t -match 'catch\s*\([^)]*\)\s*\{\s*\}' -and $t -notmatch '"' -and $t -notmatch '//') {
            Add-Violation $violations 'EMPTY_CATCH' ("{0}:{1}" -f $leaf, ($i + 1)) '空 catch 且无说明'
        }
        if (-not $mask[$i] -and $t -match 'catch\s*\([^)]*\)\s*\{\s*$' -and $i + 1 -lt $lines.Count -and $lines[$i + 1].Trim() -eq '}') {
            Add-Violation $violations 'EMPTY_CATCH' ("{0}:{1}" -f $leaf, ($i + 1)) '空 catch 且无说明'
        }
    }

    # 3) 日志占位符（单行 log.xxx(...)，末位 Throwable 不算占位）
    foreach ($m in [regex]::Matches($raw, '(?m)^[ \t]*log\.(?:trace|debug|info|warn|error)\s*\((?<args>[^\r\n]*)\);\s*$')) {
        $args = $m.Groups['args'].Value
        $ph = ([regex]::Matches($args, '\{\}')).Count
        $parts = New-Object System.Collections.ArrayList
        $buf = New-Object System.Text.StringBuilder
        $depth = 0; $inStr = $false
        for ($j = 0; $j -lt $args.Length; $j++) {
            $ch = $args[$j]
            if ($inStr) {
                [void]$buf.Append($ch)
                if ($ch -eq [char]92) { $j++; if ($j -lt $args.Length) { [void]$buf.Append($args[$j]) }; continue }
                if ($ch -eq [char]34) { $inStr = $false }
                continue
            }
            if ($ch -eq [char]34) { $inStr = $true; [void]$buf.Append($ch); continue }
            if ($ch -eq [char]'(' -or $ch -eq [char]'{' -or $ch -eq [char]'[') { $depth++; [void]$buf.Append($ch); continue }
            if ($ch -eq [char]')' -or $ch -eq [char]'}' -or $ch -eq [char]']') { $depth--; [void]$buf.Append($ch); continue }
            if ($ch -eq [char]',' -and $depth -eq 0) { [void]$parts.Add($buf.ToString().Trim()); [void]$buf.Clear(); continue }
            [void]$buf.Append($ch)
        }
        $tail = $buf.ToString().Trim()
        if ($tail -ne '') { [void]$parts.Add($tail) }
        if ($parts.Count -lt 2) { continue }
        $argCount = $parts.Count - 1
        $last = $parts[$parts.Count - 1]
        if ($argCount -gt $ph -and $last -match '^[A-Za-z_][\w.]*$') { $argCount-- }
        if ($ph -ne $argCount) {
            $lineNo = ($raw.Substring(0, $m.Index) -split "`n").Count
            Add-Violation $violations 'LOG_PLACEHOLDER' ("{0}:{1}" -f $leaf, $lineNo) ("占位符 {0} 个，实参 {1} 个" -f $ph, $argCount)
        }
    }
}

# 4) 模块循环依赖（pom）
$poms = @{}
foreach ($f in (git ls-files '*/pom.xml')) {
    if (-not (Test-Path $f)) { continue }
    try { [xml]$x = Get-Content $f -Raw } catch { continue }
    $g = $x.project.groupId; if (-not $g) { $g = $x.project.parent.groupId }
    $a = $x.project.artifactId
    $deps = @()
    if ($x.project.dependencies -and $x.project.dependencies.dependency) {
        foreach ($d in $x.project.dependencies.dependency) {
            $dg = if ($d.groupId) { $d.groupId } else { $g }
            if ($dg -like 'com.chua*') { $deps += $d.artifactId }
        }
    }
    $poms[$a] = $deps
}
$state = @{}
function Visit-Module([string]$node, [System.Collections.ArrayList]$stack) {
    if ($state[$node] -eq 1) {
        Add-Violation $violations 'MODULE_CYCLE' $node (($stack + $node) -join ' -> ')
        return
    }
    if ($state[$node] -eq 2) { return }
    $state[$node] = 1
    [void]$stack.Add($node)
    if ($poms.ContainsKey($node)) { foreach ($d in $poms[$node]) { Visit-Module $d $stack } }
    $stack.RemoveAt($stack.Count - 1)
    $state[$node] = 2
}
foreach ($k in $poms.Keys) { $st = New-Object System.Collections.ArrayList; Visit-Module $k $st }

# ---------------------------------------------------------------- 输出

$order = @('LONG_LINE', 'TRAILING_WS', 'TAB_INDENT', 'EMPTY_JAVADOC', 'ANNOTATION_BEFORE_DOC',
    'VOID_WITH_RETURN', 'EMPTY_CATCH', 'LOG_PLACEHOLDER', 'BOM', 'U+FFFD', 'MODULE_CYCLE')
$names = @{
    LONG_LINE             = '1.3.7 超长行（>120 字符）'
    TRAILING_WS           = '1.3.1 行尾空白'
    TAB_INDENT            = '1.3.1 制表符缩进'
    EMPTY_JAVADOC         = '1.8 空 Javadoc'
    ANNOTATION_BEFORE_DOC = '1.8 注解位于 Javadoc 之前'
    VOID_WITH_RETURN      = '1.8 void 方法带 @return'
    EMPTY_CATCH           = '2.1 空 catch'
    LOG_PLACEHOLDER       = '2.4 日志占位符不匹配'
    BOM                   = '编码 BOM'
    'U+FFFD'              = '编码替换字符'
    MODULE_CYCLE          = '6.x 模块循环依赖'
}

Write-Host ''
Write-Host '================ P3C 质量门禁 ================'
$total = 0
foreach ($k in $order) {
    $items = if ($violations.ContainsKey($k)) { $violations[$k] } else { @() }
    $total += $items.Count
    $status = if ($items.Count -eq 0) { 'PASS' } else { 'FAIL' }
    Write-Host ("{0,-4} {1,-28} {2}" -f $status, $names[$k], $items.Count)
    if (-not $Quiet -and $items.Count -gt 0) {
        $items | Select-Object -First 10 | ForEach-Object { Write-Host ("       - {0}  {1}" -f $_.Where, $_.Detail) }
        if ($items.Count -gt 10) { Write-Host ("       … 另有 {0} 处" -f ($items.Count - 10)) }
    }
}
Write-Host '----------------------------------------------'
if ($total -eq 0) {
    Write-Host '结果：全部通过'
    exit 0
}
Write-Host ("结果：{0} 处违规" -f $total)
exit 1
