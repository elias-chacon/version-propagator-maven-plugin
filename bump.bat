@echo off
setlocal EnableExtensions DisableDelayedExpansion
REM Bumps this project with the plugin itself, as published on GitHub Packages (profile "bump").
REM Usage:  bump.bat -Dbump.part=patch [-Dbump.dryRun=true]
REM Needs GITHUB_ACTOR and GITHUB_TOKEN (read:packages) in the environment. They are never printed.

set "SCRIPT_DIR=%~dp0"
set "ENV_FILE=%SCRIPT_DIR%.env"

REM Only JAVA_HOME and MAVEN_HOME are taken from .env; its Maven settings (corporate mirror) are not used.
if exist "%ENV_FILE%" (
    for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ENV_FILE%") do (
        if /i "%%A"=="JAVA_HOME" set "JAVA_HOME=%%B"
        if /i "%%A"=="MAVEN_HOME" set "MAVEN_HOME=%%B"
    )
)

if not defined GITHUB_ACTOR (
    echo Error: set GITHUB_ACTOR to your GitHub user name.
    exit /b 1
)
if not defined GITHUB_TOKEN (
    echo Error: set GITHUB_TOKEN to a GitHub token with the read:packages scope.
    exit /b 1
)
if "%~1"=="" (
    echo Usage: %~nx0 -Dbump.part=^<major^|minor^|patch^|build^> [-Dbump.dryRun=true]
    exit /b 1
)

set "MVN=mvn"
if defined MAVEN_HOME set "MVN=%MAVEN_HOME%\bin\mvn.cmd"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"

echo Running: "%MVN%" -s .mvn\bump-settings.xml -Pbump propagate:bump %*
call "%MVN%" -f "%SCRIPT_DIR%pom.xml" -s "%SCRIPT_DIR%.mvn\bump-settings.xml" -Pbump propagate:bump %*
exit /b %ERRORLEVEL%
