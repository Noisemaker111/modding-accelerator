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
rem A source checkout compiles itself, so it needs a JDK (javac); a JRE stops with "Module jdk.compiler not in boot Layer".
set "_MODACCEL_NEED=java"
if not exist "%~dp0modaccel.jar" set "_MODACCEL_NEED=javac"
if /I "%_MODACCEL_JAVA%"=="java" (
  where %_MODACCEL_NEED% >nul 2>nul
  if errorlevel 1 call :ensurejava
  if errorlevel 1 exit /b 2
)
rem Java 8 to 16 cannot run modaccel and would fail with a class-version stack trace: say so instead.
call :checkjava
if not errorlevel 1 goto run
echo %_MODACCEL_JAVA% is Java %_MODACCEL_MAJOR%, older than the Java 17 modaccel needs; looking for a newer one. 1>&2
set "_MODACCEL_JAVA=java"
where %_MODACCEL_NEED% >nul 2>nul
if errorlevel 1 goto nopath
call :checkjava
if not errorlevel 1 goto run
:nopath
call :ensurejava
if errorlevel 1 exit /b 2
:run
rem Arguments go through %* outside any parenthesised block: a ")" in an argument (eval --code "f()") would close the block.
if not exist "%~dp0modaccel.jar" goto source
"%_MODACCEL_JAVA%" -Dmodaccel.home="%~dp0." -jar "%~dp0modaccel.jar" %*
exit /b %errorlevel%
:source
"%_MODACCEL_JAVA%" "%~dp0tools\cli\Bootstrap.java" "%~dp0." %*
exit /b %errorlevel%
:checkjava
rem The first line of java -version reads like: openjdk version "21.0.2" ... or java version "1.8.0_392" ...
set "_MODACCEL_JV="
for /f "tokens=3" %%V in ('call "%_MODACCEL_JAVA%" -version 2^>^&1 ^| findstr /i /c:"version"') do if not defined _MODACCEL_JV set "_MODACCEL_JV=%%~V"
if not defined _MODACCEL_JV exit /b 0
set "_MODACCEL_MAJOR="
for /f "delims=.-_+" %%M in ("%_MODACCEL_JV%") do set "_MODACCEL_MAJOR=%%M"
if "%_MODACCEL_MAJOR%"=="1" goto oldjava
echo %_MODACCEL_MAJOR%| findstr /r "^[0-9][0-9]*$" >nul || exit /b 0
if %_MODACCEL_MAJOR% GEQ 17 exit /b 0
:oldjava
if "%_MODACCEL_MAJOR%"=="1" for /f "tokens=2 delims=." %%M in ("%_MODACCEL_JV%") do set "_MODACCEL_MAJOR=%%M"
exit /b 1
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
if "%_MODACCEL_NEED%"=="javac" (
  echo This is a source checkout of modaccel, which compiles itself: it needs a JDK 17 or newer ^(one with javac^), not just a Java runtime. Set JAVA_HOME to a JDK 17+, or use the packaged modaccel-portable.zip, which runs on a plain Java runtime. 1>&2
  exit /b 2
)
echo modaccel requires Java 17 or newer. Install Java 17+, or set JAVA_HOME to its installation directory. 1>&2
exit /b 2
:legacy
if not exist "%~dp0modaccel.ps1" (
  echo This older command is available only in a source checkout. Use portable commands such as port --from VERSION --to VERSION --fix, ui-check, world-check, mp-check, or game-status. 1>&2
  exit /b 2
)
pwsh -NoProfile -File "%~dp0modaccel.ps1" %*
exit /b %errorlevel%
