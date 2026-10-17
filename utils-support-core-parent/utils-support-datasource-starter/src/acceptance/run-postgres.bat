@echo off
REM ============================================================================
REM  run-postgres.bat  (ASCII only)
REM
REM  Starts / stops a throwaway PostgreSQL instance from the official
REM  "no installer" binaries, so the acceptance harness can run REAL queries
REM  on a machine without Docker / Podman / WSL.
REM
REM  The instance is fully self-contained: it lives under %TEMP%, listens on a
REM  non-default port, uses trust authentication, and does NOT register any
REM  Windows service or write to C:\Program Files.
REM
REM  Layout (override PGROOT / PGPORT in the environment if needed):
REM    %PGROOT%\bin          initdb.exe, pg_ctl.exe, psql.exe, createdb.exe
REM    %PGROOT%\..\pgdata    data directory (created by initdb on first start)
REM
REM  Usage: run-postgres.bat start | stop | status
REM ============================================================================
setlocal
if "%PGROOT%"=="" set PGROOT=%TEMP%\opencode\pg\pgsql
if "%PGPORT%"=="" set PGPORT=55432
set "PGDATA=%PGROOT%\..\pgdata"
set "PGLOG=%PGDATA%\server.log"
set "PGBIN=%PGROOT%\bin"

if /i "%1"=="start" goto :start
if /i "%1"=="stop" goto :stop
if /i "%1"=="status" goto :status
echo Usage: run-postgres.bat start ^| stop ^| status
exit /b 1

:start
if not exist "%PGBIN%\initdb.exe" (
    echo [ERROR] PostgreSQL binaries not found: %PGBIN%\initdb.exe
    echo         Download the official portable build:
    echo           https://www.enterprisedb.com/download-postgresql-binaries
    echo         Extract it so that %PGBIN%\initdb.exe exists, then retry.
    exit /b 2
)

if not exist "%PGDATA%\PG_VERSION" (
    echo [..] initdb %PGDATA%
    "%PGBIN%\initdb.exe" -D "%PGDATA%" -U postgres --auth=trust --encoding=UTF8 --locale=C
    if errorlevel 1 (
        echo [WARN] initdb with --locale=C failed, retrying with default locale
        "%PGBIN%\initdb.exe" -D "%PGDATA%" -U postgres --auth=trust --encoding=UTF8
        if errorlevel 1 exit /b 3
    )
)

"%PGBIN%\pg_ctl.exe" -D "%PGDATA%" status >nul 2>&1
if not errorlevel 1 (
    echo [OK] PostgreSQL already running on port %PGPORT%
    goto :createdb
)

echo [..] pg_ctl start (port %PGPORT%)
"%PGBIN%\pg_ctl.exe" -D "%PGDATA%" -l "%PGLOG%" -o "-p %PGPORT%" start
if errorlevel 1 (
    echo [ERROR] pg_ctl start failed, see %PGLOG%
    exit /b 4
)

:createdb
"%PGBIN%\psql.exe" -h 127.0.0.1 -p %PGPORT% -U postgres -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='engine_accept'" | findstr /r "^1$" >nul
if errorlevel 1 (
    echo [..] createdb engine_accept
    "%PGBIN%\createdb.exe" -h 127.0.0.1 -p %PGPORT% -U postgres engine_accept
    if errorlevel 1 exit /b 5
)

"%PGBIN%\psql.exe" -h 127.0.0.1 -p %PGPORT% -U postgres -d engine_accept -tAc "SELECT 'POSTGRES_OK ' || version()" 
if errorlevel 1 exit /b 6
exit /b 0

:stop
if not exist "%PGBIN%\pg_ctl.exe" exit /b 2
"%PGBIN%\pg_ctl.exe" -D "%PGDATA%" -m fast stop
if errorlevel 1 exit /b 4
echo [OK] PostgreSQL stopped
exit /b 0

:status
if not exist "%PGBIN%\pg_ctl.exe" exit /b 2
"%PGBIN%\pg_ctl.exe" -D "%PGDATA%" status
exit /b %errorlevel%
