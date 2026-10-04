# prepare-classpath.ps1  (ASCII-only on purpose: no BOM / no codepage risk)
#
# Resolves the acceptance harness classpath from pom.xml and materialises it as
# a flat lib\ directory of hard links, so the runner can use the short wildcard
# form "classes;lib/*" instead of a 28 KB -cp string (cmd caps a command line at
# 8191 characters, which silently truncates any trailing redirection).

param(
    [string]$WorkDir = $PSScriptRoot
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $WorkDir

Write-Host '[1/2] resolving classpath from pom.xml'
& mvn -q -f (Join-Path $WorkDir 'pom.xml') dependency:build-classpath "-Dmdep.outputFile=$(Join-Path $WorkDir 'cp.txt')"
if ($LASTEXITCODE -ne 0) { throw "mvn dependency:build-classpath failed: $LASTEXITCODE" }

$cpFile = Join-Path $WorkDir 'cp.txt'
if (-not (Test-Path -LiteralPath $cpFile)) { throw "classpath file not produced: $cpFile" }
$cp = [System.IO.File]::ReadAllText($cpFile)

$lib = Join-Path $WorkDir 'lib'
if (Test-Path -LiteralPath $lib) { Remove-Item -LiteralPath $lib -Recurse -Force }
New-Item -ItemType Directory -Force -Path $lib | Out-Null

$entries = @($cp -split ';' | Where-Object { ($_.Trim() -ne '') -and (Test-Path -LiteralPath $_) })
$used = @{}
$made = 0
foreach ($e in $entries) {
    $name = Split-Path -Leaf $e
    if ($used.ContainsKey($name)) {
        $used[$name] = $used[$name] + 1
        $name = $used[$name].ToString() + '-' + $name
    } else { $used[$name] = 0 }
    $dest = Join-Path $lib $name
    if (Test-Path -LiteralPath $dest) { continue }
    try { New-Item -ItemType HardLink -Path $dest -Target $e -ErrorAction Stop | Out-Null; $made++ }
    catch { Copy-Item -LiteralPath $e -Destination $dest -Force; $made++ }
}

# The H2 JDBC driver is declared provided/optional by the starter, so it is not
# part of the resolved classpath but the acceptance run needs it.
$h2 = Join-Path $env:USERPROFILE '.m2\repository\com\h2database\h2\2.4.240\h2-2.4.240.jar'
$h2alt = 'D:\maven-repo\com\h2database\h2\2.4.240\h2-2.4.240.jar'
$h2src = if (Test-Path -LiteralPath $h2) { $h2 } elseif (Test-Path -LiteralPath $h2alt) { $h2alt } else { $null }
if ($h2src) {
    $h2dest = Join-Path $lib 'h2-2.4.240.jar'
    if (-not (Test-Path -LiteralPath $h2dest)) {
        try { New-Item -ItemType HardLink -Path $h2dest -Target $h2src -ErrorAction Stop | Out-Null }
        catch { Copy-Item -LiteralPath $h2src -Destination $h2dest -Force }
    }
} else {
    Write-Warning 'H2 driver jar not found; the h2 suite will fail. Add com.h2database:h2 to pom.xml.'
}

Write-Host "[2/2] lib jars = $((Get-ChildItem -LiteralPath $lib -Filter '*.jar').Count)  (linked $made)"
