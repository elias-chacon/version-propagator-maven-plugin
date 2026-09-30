@echo off
setlocal EnableExtensions DisableDelayedExpansion
echo mvn script version 1.0.3

set "SCRIPT_DIR=%~dp0"
set "ENV_FILE=%SCRIPT_DIR%.env"

if not exist "%ENV_FILE%" (
    echo Error: .env file not found at "%ENV_FILE%"
    exit /b 1
)

REM Clear values that may have come from the system.
set "JAVA_HOME="
set "MAVEN_HOME="
set "MAVEN_SETTINGS="
set "MAVEN_ARGS="
set "MAVEN_OPTS="
set "JAVA_OPTS="

REM Load every variable from .env, not only the Maven variables.
REM Delayed expansion remains disabled so special characters are preserved.
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ENV_FILE%") do (
    set "%%A=%%B"
)

if not defined JAVA_HOME (
    echo Error: JAVA_HOME is not set in "%ENV_FILE%".
    exit /b 1
)

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo Error: invalid JAVA_HOME:
    echo "%JAVA_HOME%"
    exit /b 1
)

if not defined MAVEN_HOME (
    echo Error: MAVEN_HOME is not set in "%ENV_FILE%".
    exit /b 1
)

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
    echo Error: MAVEN_HOME points to an invalid location:
    echo "%MAVEN_HOME%"
    exit /b 1
)

if defined MAVEN_SETTINGS if not exist "%MAVEN_SETTINGS%" (
    echo Error: MAVEN_SETTINGS points to a file that does not exist:
    echo "%MAVEN_SETTINGS%"
    exit /b 1
)

if "%~1"=="" (
    echo Usage: %~nx0 [maven arguments]
    echo Example: %~nx0 test "-Dtest=com.example.MyTest"
    exit /b 1
)

REM Force Maven and all child processes to use the JDK from .env.
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo JAVA_HOME: %JAVA_HOME%
echo Java executable: "%JAVA_HOME%\bin\java.exe"
echo Maven executable: "%MAVEN_HOME%\bin\mvn.cmd"

if defined MAVEN_SETTINGS (
    echo Maven settings: "%MAVEN_SETTINGS%"
) else (
    echo Maven settings: default
)

echo.
"%JAVA_HOME%\bin\java.exe" -version
echo.

if defined MAVEN_SETTINGS (
    echo Running: "%MAVEN_HOME%\bin\mvn.cmd" -s "%MAVEN_SETTINGS%" %MAVEN_ARGS% %*
    echo.
    call "%MAVEN_HOME%\bin\mvn.cmd" -s "%MAVEN_SETTINGS%" %MAVEN_ARGS% %*
) else (
    echo Running: "%MAVEN_HOME%\bin\mvn.cmd" %MAVEN_ARGS% %*
    echo.
    call "%MAVEN_HOME%\bin\mvn.cmd" %MAVEN_ARGS% %*
)

exit /b %ERRORLEVEL%
