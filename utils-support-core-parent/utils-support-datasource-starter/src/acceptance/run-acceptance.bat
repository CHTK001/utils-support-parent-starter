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
javac -encoding UTF-8 -d "classes" -cp "lib/*" ^
    "harness\EngineFunctional.java" ^
    "harness\EngineAcceptance.java" ^
    "harness\EngineSeriesKv.java" ^
    "harness\EngineEmbedded.java" ^
    "harness\EngineGaps.java" ^
    "harness\EngineRemaining.java" ^
    "harness\EngineQueryBuilder.java" ^
    "harness\EnginePrometheus.java"
if errorlevel 1 (
    echo [ERROR] harness compilation failed
    exit /b 3
)

set JOPT=-Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED ^
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
if "%STAGES%"=="" set STAGES=1 2 3 4 5 6 7 8
set FAILED=0

echo %STAGES% | findstr /C:"1" > nul && call :run 1 EngineAcceptance
echo %STAGES% | findstr /C:"2" > nul && call :run 2 EngineFunctional
echo %STAGES% | findstr /C:"3" > nul && call :run 3 EngineSeriesKv
echo %STAGES% | findstr /C:"4" > nul && call :run 4 EngineEmbedded
echo %STAGES% | findstr /C:"5" > nul && call :run 5 EngineGaps
echo %STAGES% | findstr /C:"6" > nul && call :run 6 EngineRemaining
echo %STAGES% | findstr /C:"7" > nul && call :run 7 EngineQueryBuilder
echo %STAGES% | findstr /C:"8" > nul && call :run 8 EnginePrometheus

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
