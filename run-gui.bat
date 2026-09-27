@echo off
setlocal
chcp 65001 >nul
pushd "%~dp0"
call gradlew.bat writeRunArgs || goto :fail
rem Start java directly, not under Gradle, so closing or Ctrl+C reaches the app itself.
set /p JAVA_EXE=<build\run\java
"%JAVA_EXE%" @build\run\jvm.args %*
:fail
set EXIT_CODE=%ERRORLEVEL%
popd
endlocal & exit /b %EXIT_CODE%
