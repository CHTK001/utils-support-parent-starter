#requires -Version 7
<#
.SYNOPSIS
    全仓扫描 Java 源码的 UTF-8 合法性；可选 -Restore 从 HEAD 自动恢复被污染文件。
.DESCRIPTION
    背景：多会话并行开发中，曾出现以 GBK 编码覆写 UTF-8 文件导致中文注释损坏的事故。
    本脚本用严格 UTF-8 解码器逐字节校验，发现非法文件时可一键从 git HEAD 恢复正确版本。
.PARAMETER Restore
    开关：对检出的非法文件执行 git checkout HEAD -- <file> 自动恢复。
.EXAMPLE
    pwsh scripts/check-utf8.ps1            # 仅检测
    pwsh scripts/check-utf8.ps1 -Restore   # 检测并自动恢复
#>
param(
    [switch]$Restore
)

$ErrorActionPreference = 'Stop'
# 基于脚本自身位置定位仓库根（scripts/ 的上一级），与调用时的工作目录无关
$repoRoot = Split-Path -Parent $PSScriptRoot
$strict = [System.Text.UTF8Encoding]::new($false, $true)

$javaFiles = Get-ChildItem -Recurse -Path $repoRoot -Filter *.java |
    Where-Object { $_.FullName -notmatch '\\(target|build|node_modules)\\' }

$invalid = New-Object System.Collections.Generic.List[string]
foreach ($file in $javaFiles) {
    try {
        $null = $strict.GetString([System.IO.File]::ReadAllBytes($file.FullName))
    } catch {
        $invalid.Add($file.FullName)
    }
}

if ($invalid.Count -eq 0) {
    Write-Host "[PASS] 全部 $($javaFiles.Count) 个 Java 文件均为合法 UTF-8"
    exit 0
}

Write-Host "[FAIL] 检测到 $($invalid.Count) 个非 UTF-8 编码文件:" -ForegroundColor Red
foreach ($f in $invalid) {
    Write-Host ("  " + $f.Substring($repoRoot.Length + 1))
}

if (-not $Restore) {
    Write-Host "`n提示: 加参数 -Restore 可从 git HEAD 自动恢复以上文件。"
    exit 1
}

Write-Host "`n[RUN] 正在从 HEAD 恢复..."
foreach ($f in $invalid) {
    $rel = $f.Substring($repoRoot.Length + 1) -replace '\\', '/'
    git -C $repoRoot checkout HEAD -- "$rel" 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host ("  已恢复: " + $rel)
    } else {
        Write-Warning ("  无法从 HEAD 恢复(可能是新文件): " + $rel)
    }
}
Write-Host "[DONE] 恢复完成，建议重跑本脚本复核。"
