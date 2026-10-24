@echo off
setlocal
rem The older game-control verbs retain their existing Windows implementation.
for %%V in (ui node suite world look mp scenario status stop restart lint bench) do if /I "%~1"=="%%V" goto legacy
set "_MODACCEL_ARG=%~2"
if /I "%~1"=="port" if defined _MODACCEL_ARG if not "%_MODACCEL_ARG:~0,2%"=="--" goto legacy
set "_MODACCEL_JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "_MODACCEL_JAVA=%JAVA_HOME%\bin\java.exe"
if exist "%~dp0modaccel.jar" if not defined _MODACCEL_JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "_MODACCEL_JAVA=%JAVA_HOME%\bin\java.exe"
if not defined _MODACCEL_JAVA for /d %%J in ("%~dp0.tools\jdk-*") do if exist "%%~J\bin\javac.exe" set "_MODACCEL_JAVA=%%~J\bin\java.exe"
if not defined _MODACCEL_JAVA set "_MODACCEL_JAVA=java"
if /I "%_MODACCEL_JAVA%"=="java" (
  where java >nul 2>nul
  if errorlevel 1 call :ensurejava
  if errorlevel 1 exit /b 2
)
if exist "%~dp0modaccel.jar" (
  "%_MODACCEL_JAVA%" -Dmodaccel.home="%~dp0." -jar "%~dp0modaccel.jar" %*
) else (
  "%_MODACCEL_JAVA%" "%~dp0tools\cli\Bootstrap.java" "%~dp0." %*
)
exit /b %errorlevel%
:ensurejava
set "_MODACCEL_IMAGE=jre"
if not exist "%~dp0modaccel.jar" set "_MODACCEL_IMAGE=jdk"
set "_MODACCEL_JRE=%LOCALAPPDATA%\modaccel\%_MODACCEL_IMAGE%"
if exist "%_MODACCEL_JRE%\current\bin\java.exe" goto :usejre
if defined MODACCEL_NO_DOWNLOAD goto :nojava
set "_MODACCEL_BOOTSTRAP=%~dp0bootstrap-jre.ps1"
if not exist "%_MODACCEL_BOOTSTRAP%" set "_MODACCEL_BOOTSTRAP=%~dp0tools\bootstrap-jre.ps1"
if not exist "%_MODACCEL_BOOTSTRAP%" goto :nojava
powershell -NoProfile -ExecutionPolicy Bypass -File "%_MODACCEL_BOOTSTRAP%" %_MODACCEL_IMAGE%
if not exist "%_MODACCEL_JRE%\current\bin\java.exe" goto :nojava
:usejre
set "_MODACCEL_JAVA=%_MODACCEL_JRE%\current\bin\java.exe"
exit /b 0
:nojava
echo modaccel requires Java 17 or newer. Install Java, or set JAVA_HOME to its installation directory. 1>&2
exit /b 2
:legacy
if not exist "%~dp0modaccel.ps1" (
  echo This older command is available only in a source checkout. Use portable commands such as port --from VERSION --to VERSION --fix, ui-check, world-check, mp-check, or game-status. 1>&2
  exit /b 2
)
pwsh -NoProfile -File "%~dp0modaccel.ps1" %*
exit /b %errorlevel%
