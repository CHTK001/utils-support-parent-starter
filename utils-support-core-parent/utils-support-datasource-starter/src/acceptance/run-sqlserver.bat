@echo off
REM ============================================================================
REM  run-sqlserver.bat  (ASCII only)
REM
REM  Starts / stops / probes the local SQL Server Express instance used by the
REM  acceptance harness, so stage 23 can run REAL queries on a machine without
REM  Docker / Podman / WSL.
REM
REM  The instance is the official Microsoft SQL Server 2022 Express default
REM  instance "SQLEXPRESS" (installed with /SECURITYMODE=SQL and /TCPENABLED=1).
REM  This script only controls the Windows service and reports the port; it does
REM  not install anything and does not change server configuration.
REM
REM  Layout (override in the environment if needed):
REM    MSSQL_INSTANCE  service instance name       (default SQLEXPRESS)
REM    MSSQL_PORT      TCP port to probe            (default 1433)
REM
REM  Usage: run-sqlserver.bat start | stop | status
REM ============================================================================
setlocal
if "%MSSQL_INSTANCE%"=="" set MSSQL_INSTANCE=SQLEXPRESS
if "%MSSQL_PORT%"=="" set MSSQL_PORT=1433
set "SVC=MSSQL$%MSSQL_INSTANCE%"

if /i "%1"=="start" goto :start
if /i "%1"=="stop" goto :stop
if /i "%1"=="status" goto :status
echo Usage: run-sqlserver.bat start ^| stop ^| status
exit /b 1

:start
sc query "%SVC%" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] SQL Server service not found: %SVC%
    echo         Install SQL Server 2022 Express with:
    echo           SQL2022-SSEI-Expr.exe /ACTION=Install /QUIET ^
    echo             /IACCEPTSQLSERVERLICENSETERMS /FEATURES=SQLEngine ^
    echo             /INSTANCENAME=%MSSQL_INSTANCE% /SECURITYMODE=SQL ^
    echo             /SAPWD=^<sa-password^> /TCPENABLED=1 /NPENABLED=0
    exit /b 2
)
net start "%SVC%" >nul 2>&1
sc query "%SVC%" | findstr /i "RUNNING" >nul
if errorlevel 1 (
    echo [ERROR] service %SVC% is not running
    exit /b 4
)
echo [OK] %SVC% running
powershell -NoProfile -Command "$c = New-Object Net.Sockets.TcpClient; try { $c.Connect('127.0.0.1', %MSSQL_PORT%); Write-Output '[OK] TCP 127.0.0.1:%MSSQL_PORT% reachable' } catch { Write-Output '[WARN] TCP 127.0.0.1:%MSSQL_PORT% NOT reachable'; exit 6 } finally { $c.Dispose() }"
if errorlevel 1 exit /b 6
exit /b 0

:stop
net stop "%SVC%"
if errorlevel 1 (
    echo [ERROR] failed to stop %SVC%
    exit /b 4
)
echo [OK] %SVC% stopped
exit /b 0

:status
sc query "%SVC%" | findstr /i "STATE"
powershell -NoProfile -Command "$c = New-Object Net.Sockets.TcpClient; try { $c.Connect('127.0.0.1', %MSSQL_PORT%); Write-Output 'TCP=UP' } catch { Write-Output 'TCP=DOWN' } finally { $c.Dispose() }"
exit /b 0
