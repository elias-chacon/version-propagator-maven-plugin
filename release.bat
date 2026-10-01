@echo off
setlocal EnableExtensions DisableDelayedExpansion
REM Releases this project (release version, commit, tag, atomic push, next SNAPSHOT). Run with --help for details.

set "SCRIPT_DIR=%~dp0"
set "SELF=%~nx0"
cd /d "%SCRIPT_DIR%"
set "PLUGIN_GA=io.github.eliaschacon:version-propagator-maven-plugin"
set "VERSION="
set "NEXT_VERSION="
set "DRY_RUN=false"
set "ASSUME_YES=false"

:parse
if "%~1"=="" goto parsed
if /i "%~1"=="--dry-run" (set "DRY_RUN=true" & shift & goto parse)
if /i "%~1"=="--yes" (set "ASSUME_YES=true" & shift & goto parse)
if /i "%~1"=="-y" (set "ASSUME_YES=true" & shift & goto parse)
if /i "%~1"=="--help" goto help
if /i "%~1"=="-h" goto help
if "%~1"=="/?" goto help
set "ARG=%~1"
if "%ARG:~0,1%"=="-" goto usage
if not defined VERSION (set "VERSION=%~1" & shift & goto parse)
if not defined NEXT_VERSION (set "NEXT_VERSION=%~1" & shift & goto parse)
goto usage

:parsed
if not defined VERSION goto usage
if /i "%VERSION:~0,1%"=="v" set "VERSION=%VERSION:~1%"
set "TAG=v%VERSION%"
echo %VERSION% | findstr /i "SNAPSHOT" >nul && (call :fail "the release version must not be a SNAPSHOT: %VERSION%" & exit /b 1)

REM Maven: the project wrapper when .env exists (JDK, Maven and settings of this machine), plain mvn otherwise.
if exist "%SCRIPT_DIR%.env" (set "MVN=%SCRIPT_DIR%mvn.bat") else (set "MVN=mvn")

REM --- Checks (nothing is changed before they all pass) ----------------------------------------------------------
set "BRANCH="
for /f "delims=" %%b in ('git symbolic-ref --short -q HEAD') do set "BRANCH=%%b"
if not defined BRANCH (call :fail "not on a branch (detached HEAD)." & exit /b 1)
git diff --quiet || (call :fail "the working tree has uncommitted changes." & exit /b 1)
git diff --cached --quiet || (call :fail "the working tree has uncommitted changes." & exit /b 1)
git rev-parse -q --verify "refs/tags/%TAG%" >nul && (call :fail "tag %TAG% already exists locally." & exit /b 1)
git ls-remote --exit-code --tags origin "refs/tags/%TAG%" >nul 2>&1 && (call :fail "tag %TAG% already exists on origin." & exit /b 1)
git fetch -q origin "%BRANCH%" || (call :fail "cannot fetch origin/%BRANCH%." & exit /b 1)
set "BEHIND="
for /f %%n in ('git rev-list --count "HEAD..origin/%BRANCH%"') do set "BEHIND=%%n"
if not "%BEHIND%"=="0" (call :fail "%BRANCH% is behind origin/%BRANCH%: pull first." & exit /b 1)

REM --- Build the plugin used for the version changes -----------------------------------------------------------
echo Building the plugin (tests skipped; they run in the release workflow)...
call "%MVN%" -B -q install -DskipTests || (call :fail "the build failed." & exit /b 1)
call :read_version CURRENT || exit /b 1
set "PLUGIN=%PLUGIN_GA%:%CURRENT%:bump"

set "NEXT_ARGS="-Dbump.part=patch" "-Dbump.snapshot=true""
if defined NEXT_VERSION set "NEXT_ARGS="-Dbump.newVersion=%NEXT_VERSION%""
set "NEXT_LABEL=%NEXT_VERSION%"
if not defined NEXT_LABEL set "NEXT_LABEL=the next patch SNAPSHOT"
echo Release %CURRENT% -^> %VERSION% (tag %TAG%) on %BRANCH%, then %NEXT_LABEL%.

if "%DRY_RUN%"=="true" (
    if not "%CURRENT%"=="%VERSION%" (call :bump "-Dbump.newVersion=%VERSION%" -Dbump.dryRun=true || exit /b 1)
    echo DRY RUN: nothing was changed, committed or pushed.
    exit /b 0
)
if not "%ASSUME_YES%"=="true" (
    set "ANSWER="
    set /p "ANSWER=Commit, tag %TAG% and push to origin? [y/N] "
    call :confirmed || (echo Aborted: nothing was changed. & exit /b 1)
)

REM --- Release ---------------------------------------------------------------------------------------------------
set "COMMITTED=false"
if not "%CURRENT%"=="%VERSION%" (
    call :bump "-Dbump.newVersion=%VERSION%" || exit /b 1
    git commit -q -am "Release %VERSION%" || (call :fail "the release commit failed." & exit /b 1)
    set "COMMITTED=true"
)
git tag -a "%TAG%" -m "Release %VERSION%" || (call :fail "cannot create the tag %TAG%." & exit /b 1)
git push --atomic origin "%BRANCH%" "refs/tags/%TAG%"
if errorlevel 1 (
    echo Error: the push failed. To undo locally: git tag -d %TAG% 1>&2
    if "%COMMITTED%"=="true" echo                                      git reset --hard HEAD~1 1>&2
    exit /b 1
)
echo Released %VERSION%: tag %TAG% pushed (release workflow started).

REM --- Next development version ----------------------------------------------------------------------------------
call :bump %NEXT_ARGS% || exit /b 1
call :read_version NEXT || exit /b 1
git commit -q -am "Prepare next development version %NEXT%" || (call :fail "the commit of %NEXT% failed." & exit /b 1)
git push origin "%BRANCH%" || (call :fail "the push of %NEXT% failed: run 'git push origin %BRANCH%'." & exit /b 1)
echo Next development version %NEXT% pushed.
exit /b 0

REM --- Subroutines -----------------------------------------------------------------------------------------------
:bump
REM Runs the bump goal of the plugin just built, with the README rules of the "bump" profile.
call "%MVN%" -B -o -Pbump "%PLUGIN%" %*
if errorlevel 1 (call :fail "the version change failed (nothing was committed)." & exit /b 1)
exit /b 0

:read_version
REM Sets the variable named %1 to the project version of the parent POM.
call "%MVN%" -B -q -N help:evaluate -Dexpression=project.version -Doutput=target\release-version.txt >nul
if errorlevel 1 (call :fail "cannot read the project version." & exit /b 1)
set /p "%~1=" < target\release-version.txt
exit /b 0

:confirmed
if /i "%ANSWER%"=="y" exit /b 0
if /i "%ANSWER%"=="yes" exit /b 0
exit /b 1

:fail
echo Error: %~1 1>&2
exit /b 1

:usage
echo Usage: %SELF% ^<version^> [^<next-version^>] [--dry-run] [--yes]   (--help for details) 1>&2
exit /b 1

:help
echo Usage: %SELF% ^<version^> [^<next-version^>] [--dry-run] [--yes]
echo.
echo Releases this project: sets ^<version^> with the plugin built from this working tree (offline, profile "bump"),
echo commits 'Release ^<version^>', tags v^<version^>, pushes the branch and the tag atomically (the tag starts
echo .github/workflows/release.yml), then sets the next SNAPSHOT, commits and pushes it.
echo.
echo Arguments:
echo   ^<version^>       release version, e.g. 1.2.0 or v1.2.0 (not a SNAPSHOT)
echo   ^<next-version^>  next development version (default: patch + 1 with -SNAPSHOT, e.g. 1.2.1-SNAPSHOT)
echo   --dry-run       run the checks and show the planned changes; nothing is changed, committed or pushed
echo   -y, --yes       do not ask for confirmation before pushing
echo   -h, --help      show this help
echo.
echo Before any change: on a branch, clean working tree, tag absent locally and on origin, branch not behind origin.
echo The five POMs and README.md change together; no GitHub token is needed. Tests run in the release workflow.
exit /b 0
