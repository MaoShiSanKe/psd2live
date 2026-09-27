@echo off
setlocal
chcp 65001 >nul
pushd "%~dp0"
rem Keep startup builds disposable even if a user-level Gradle setting enables persistent daemons.
call gradlew.bat --no-daemon writeRunArgs || goto :fail
rem Start java directly, not under Gradle, so closing or Ctrl+C reaches the app itself.
set /p JAVA_EXE=<build\run\java
"%JAVA_EXE%" @build\run\jvm.args %*
:fail
set EXIT_CODE=%ERRORLEVEL%
popd
endlocal & exit /b %EXIT_CODE%
