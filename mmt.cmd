@echo off
setlocal
rem The older game-control verbs retain their existing Windows implementation.
for %%V in (ui node suite world look mp scenario status stop restart lint bench) do if /I "%~1"=="%%V" goto legacy
set "_MMT_ARG=%~2"
if /I "%~1"=="port" if defined _MMT_ARG if not "%_MMT_ARG:~0,2%"=="--" goto legacy
set "_MMT_JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "_MMT_JAVA=%JAVA_HOME%\bin\java.exe"
if exist "%~dp0mmt.jar" if not defined _MMT_JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "_MMT_JAVA=%JAVA_HOME%\bin\java.exe"
if not defined _MMT_JAVA for /d %%J in ("%~dp0.tools\jdk-*") do if exist "%%~J\bin\javac.exe" set "_MMT_JAVA=%%~J\bin\java.exe"
if not defined _MMT_JAVA set "_MMT_JAVA=java"
rem A source checkout compiles itself, so it needs a JDK (javac); a JRE stops with "Module jdk.compiler not in boot Layer".
set "_MMT_NEED=java"
if not exist "%~dp0mmt.jar" set "_MMT_NEED=javac"
if /I "%_MMT_JAVA%"=="java" (
  where %_MMT_NEED% >nul 2>nul
  if errorlevel 1 call :ensurejava
  if errorlevel 1 exit /b 2
)
rem Java 8 to 16 cannot run mmt and would fail with a class-version stack trace: say so instead.
call :checkjava
if not errorlevel 1 goto run
echo %_MMT_JAVA% is Java %_MMT_MAJOR%, older than the Java 17 mmt needs; looking for a newer one. 1>&2
set "_MMT_JAVA=java"
where %_MMT_NEED% >nul 2>nul
if errorlevel 1 goto nopath
call :checkjava
if not errorlevel 1 goto run
:nopath
call :ensurejava
if errorlevel 1 exit /b 2
:run
rem Arguments go through %* outside any parenthesised block: a ")" in an argument (eval --code "f()") would close the block.
if not exist "%~dp0mmt.jar" goto source
"%_MMT_JAVA%" -Dmmt.home="%~dp0." -jar "%~dp0mmt.jar" %*
exit /b %errorlevel%
:source
"%_MMT_JAVA%" "%~dp0tools\cli\Bootstrap.java" "%~dp0." %*
exit /b %errorlevel%
:checkjava
rem The first line of java -version reads like: openjdk version "21.0.2" ... or java version "1.8.0_392" ...
set "_MMT_JV="
for /f "tokens=3" %%V in ('call "%_MMT_JAVA%" -version 2^>^&1 ^| findstr /i /c:"version"') do if not defined _MMT_JV set "_MMT_JV=%%~V"
if not defined _MMT_JV exit /b 0
set "_MMT_MAJOR="
for /f "delims=.-_+" %%M in ("%_MMT_JV%") do set "_MMT_MAJOR=%%M"
if "%_MMT_MAJOR%"=="1" goto oldjava
echo %_MMT_MAJOR%| findstr /r "^[0-9][0-9]*$" >nul || exit /b 0
if %_MMT_MAJOR% GEQ 17 exit /b 0
:oldjava
if "%_MMT_MAJOR%"=="1" for /f "tokens=2 delims=." %%M in ("%_MMT_JV%") do set "_MMT_MAJOR=%%M"
exit /b 1
:ensurejava
set "_MMT_IMAGE=jre"
if not exist "%~dp0mmt.jar" set "_MMT_IMAGE=jdk"
set "_MMT_JRE=%LOCALAPPDATA%\mmt\%_MMT_IMAGE%"
if exist "%_MMT_JRE%\current\bin\java.exe" goto :usejre
rem A runtime downloaded under the old name (modaccel) is reused rather than downloaded again.
if exist "%LOCALAPPDATA%\modaccel\%_MMT_IMAGE%\current\bin\java.exe" set "_MMT_JRE=%LOCALAPPDATA%\modaccel\%_MMT_IMAGE%"
if exist "%_MMT_JRE%\current\bin\java.exe" goto :usejre
set "_MMT_JRE=%LOCALAPPDATA%\mmt\%_MMT_IMAGE%"
if defined MMT_NO_DOWNLOAD goto :nojava
if defined MODACCEL_NO_DOWNLOAD goto :nojava
set "_MMT_BOOTSTRAP=%~dp0bootstrap-jre.ps1"
if not exist "%_MMT_BOOTSTRAP%" set "_MMT_BOOTSTRAP=%~dp0tools\bootstrap-jre.ps1"
if not exist "%_MMT_BOOTSTRAP%" goto :nojava
powershell -NoProfile -ExecutionPolicy Bypass -File "%_MMT_BOOTSTRAP%" %_MMT_IMAGE%
if not exist "%_MMT_JRE%\current\bin\java.exe" goto :nojava
:usejre
set "_MMT_JAVA=%_MMT_JRE%\current\bin\java.exe"
exit /b 0
:nojava
if "%_MMT_NEED%"=="javac" (
  echo This is a source checkout of mmt, which compiles itself: it needs a JDK 17 or newer ^(one with javac^), not just a Java runtime. Set JAVA_HOME to a JDK 17+, or use the packaged mmt-portable.zip, which runs on a plain Java runtime. 1>&2
  exit /b 2
)
echo mmt requires Java 17 or newer. Install Java 17+, or set JAVA_HOME to its installation directory. 1>&2
exit /b 2
:legacy
if not exist "%~dp0mmt.ps1" (
  echo This older command is available only in a source checkout. Use portable commands such as port --from VERSION --to VERSION --fix, ui-check, world-check, mp-check, or game-status. 1>&2
  exit /b 2
)
pwsh -NoProfile -File "%~dp0mmt.ps1" %*
exit /b %errorlevel%
