@echo off
REM ============================================================================
REM  run-acceptance.bat  (ASCII only)
REM
REM  Engine acceptance harness runner.
REM
REM  1. resolves the classpath into .\lib (prepare-classpath.ps1)
REM  2. compiles the harness programs
REM  3. runs them in sequence; each program writes its own UTF-8 report and
REM     exits 0 only when every assertion passed
REM
REM  Usage:  run-acceptance.bat            (all stages)
REM          run-acceptance.bat 1 2        (selected stages only)
REM ============================================================================
setlocal
chcp 65001 > nul
cd /d "%~dp0"

where pwsh > nul 2>&1
if errorlevel 1 (
    echo [ERROR] pwsh 7 not found on PATH. PowerShell 5.1 is forbidden in this
    echo         repo: it decodes BOM-less UTF-8 scripts as ANSI and corrupts
    echo         non-ASCII selectors. Install PowerShell 7 and retry.
    exit /b 2
)

pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0prepare-classpath.ps1"
if errorlevel 1 (
    echo [ERROR] classpath preparation failed
    exit /b 2
)

if not exist "classes" mkdir "classes"
if not exist "target" mkdir "target"
javac -J-Xms32m -J-Xmx384m -J-XX:MaxMetaspaceSize=192m -J-XX:ReservedCodeCacheSize=64m -J-XX:+UseSerialGC -encoding UTF-8 -d "classes" -cp "lib/*" ^
    "harness\EngineFunctional.java" ^
    "harness\EngineAcceptance.java" ^
    "harness\EngineSeriesKv.java" ^
    "harness\EngineEmbedded.java" ^
    "harness\EngineGaps.java" ^
    "harness\EngineRemaining.java" ^
    "harness\EngineQueryBuilder.java" ^
    "harness\EnginePrometheus.java" ^
    "harness\EngineInfluxdb.java" ^
    "harness\EngineDatalake.java" ^
    "harness\EngineRocksdbSeries.java" ^
    "harness\EngineQuestdb.java" ^
    "harness\EngineInfluxDbEngine.java" ^
    "harness\EngineExternalConfig.java" ^
    "harness\EngineSolr.java" ^
    "harness\EngineRedisEntity.java"
if errorlevel 1 (
    echo [ERROR] harness compilation failed
    exit /b 3
)

set JOPT=-Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED ^
 -Xms64m -Xmx512m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=96m ^
 -XX:CICompilerCount=2 -XX:+UseSerialGC ^
 --add-opens java.base/java.lang.reflect=ALL-UNNAMED ^
 --add-opens java.base/java.lang=ALL-UNNAMED ^
 --add-opens java.base/java.util=ALL-UNNAMED ^
 --add-opens java.base/java.nio=ALL-UNNAMED ^
 --add-exports java.base/sun.nio.ch=ALL-UNNAMED ^
 --add-opens jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED ^
 --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED ^
 --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED ^
 --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED

set STAGES=%*
if "%STAGES%"=="" set STAGES=1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16
set FAILED=0

REM exact token match: iterating avoids "10" also matching "1"
for %%S in (%STAGES%) do (
    if "%%S"=="1" call :run 1 EngineAcceptance
    if "%%S"=="2" call :run 2 EngineFunctional
    if "%%S"=="3" call :run 3 EngineSeriesKv
    if "%%S"=="4" call :run 4 EngineEmbedded
    if "%%S"=="5" call :run 5 EngineGaps
    if "%%S"=="6" call :run 6 EngineRemaining
    if "%%S"=="7" call :run 7 EngineQueryBuilder
    if "%%S"=="8" call :run 8 EnginePrometheus
    if "%%S"=="9" call :run 9 EngineInfluxdb
    if "%%S"=="10" call :run 10 EngineDatalake
    if "%%S"=="11" call :run 11 EngineRocksdbSeries
    if "%%S"=="12" call :run 12 EngineQuestdb
    if "%%S"=="13" call :run 13 EngineInfluxDbEngine
    if "%%S"=="14" call :run 14 EngineExternalConfig
    if "%%S"=="15" call :run 15 EngineSolr
    if "%%S"=="16" call :run 16 EngineRedisEntity
)

echo.
if "%FAILED%"=="1" (
    echo ACCEPTANCE_RESULT=FAIL
    exit /b 1
)
echo ACCEPTANCE_RESULT=PASS
exit /b 0

:run
echo.
echo === stage %1 : %2 ===
java %JOPT% -cp "classes;lib/*" %2
if errorlevel 1 (
    echo STAGE%1_RESULT=FAIL
    set FAILED=1
) else (
    echo STAGE%1_RESULT=PASS
)
goto :eof
