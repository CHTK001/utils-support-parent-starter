@echo off
REM ============================================================================
REM  run-oracle.bat  (ASCII only)
REM
REM  Starts / stops / probes the local Oracle Database 21c Express Edition
REM  instance used by the acceptance harness, so stage 24 can run REAL queries
REM  on a machine without Docker / Podman / WSL.
REM
REM  The instance is the official Windows XE install (silent install from
REM  OracleXE213_Win64.zip with XEInstall.rsp). Services:
REM    OracleServiceXE                        database instance
REM    OracleOraDB21Home1TNSListener          listener (port 1521)
REM  This script only controls those services and probes the port; it does not
REM  install anything and does not change server configuration.
REM
REM  Layout (override in the environment if needed):
REM    ORACLE_PORT        TCP port to probe        (default 1521)
REM    ORACLE_SVC         database service name    (default OracleServiceXE)
REM
REM  Usage: run-oracle.bat start | stop | status
REM ============================================================================
setlocal
if "%ORACLE_PORT%"=="" set ORACLE_PORT=1521
if "%ORACLE_SVC%"=="" set ORACLE_SVC=OracleServiceXE

if /i "%1"=="start" goto :start
if /i "%1"=="stop" goto :stop
if /i "%1"=="status" goto :status
echo Usage: run-oracle.bat start ^| stop ^| status
exit /b 1

:start
sc query "%ORACLE_SVC%" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Oracle service not found: %ORACLE_SVC%
    echo         Install Oracle Database 21c Express Edition for Windows, then retry.
    exit /b 2
)
net start "OracleOraDB21Home1TNSListener" >nul 2>&1
net start "%ORACLE_SVC%" >nul 2>&1
sc query "%ORACLE_SVC%" | findstr /i "RUNNING" >nul
if errorlevel 1 (
    echo [ERROR] service %ORACLE_SVC% is not running
    exit /b 4
)
echo [OK] %ORACLE_SVC% running
powershell -NoProfile -Command "$c = New-Object Net.Sockets.TcpClient; try { $c.Connect('127.0.0.1', %ORACLE_PORT%); Write-Output '[OK] TCP 127.0.0.1:%ORACLE_PORT% reachable' } catch { Write-Output '[WARN] TCP 127.0.0.1:%ORACLE_PORT% NOT reachable'; exit 6 } finally { $c.Dispose() }"
if errorlevel 1 exit /b 6
exit /b 0

:stop
net stop "%ORACLE_SVC%"
if errorlevel 1 (
    echo [ERROR] failed to stop %ORACLE_SVC%
    exit /b 4
)
echo [OK] %ORACLE_SVC% stopped
exit /b 0

:status
sc query "%ORACLE_SVC%" | findstr /i "STATE"
powershell -NoProfile -Command "$c = New-Object Net.Sockets.TcpClient; try { $c.Connect('127.0.0.1', %ORACLE_PORT%); Write-Output 'TCP=UP' } catch { Write-Output 'TCP=DOWN' } finally { $c.Dispose() }"
exit /b 0
