@echo off
setlocal
set "GLACIER_DIR=%~dp0"
set "GLACIER_ROOT=%~dp0..\.."
if not defined JAVA_HOME (
  echo Set JAVA_HOME to a JDK 21 installation.
  set "GLACIER_EXIT=2"
  goto finish
)
if not exist "%GLACIER_DIR%target\classes" mkdir "%GLACIER_DIR%target\classes"
"%JAVA_HOME%\bin\javac.exe" --release 21 -d "%GLACIER_DIR%target\classes" "%GLACIER_DIR%src\main\java\com\cpgame\glacier\Json.java" "%GLACIER_DIR%src\main\java\com\cpgame\glacier\GameRuleCore.java" "%GLACIER_DIR%src\main\java\com\cpgame\glacier\ResultUtil.java" "%GLACIER_DIR%src\test\java\com\cpgame\glacier\HistoryRegression.java"
if errorlevel 1 goto failed
"%JAVA_HOME%\bin\jar.exe" --create --file "%GLACIER_DIR%target\glacier-validation.jar" --main-class com.cpgame.glacier.HistoryRegression -C "%GLACIER_DIR%target\classes" .
if errorlevel 1 goto failed
"%JAVA_HOME%\bin\java.exe" -jar "%GLACIER_DIR%target\glacier-validation.jar" "%GLACIER_ROOT%" "%GLACIER_ROOT%\reports\1780-Glacier-Treasure\java-history-validation.json"
if errorlevel 1 goto failed
set "GLACIER_EXIT=0"
goto finish
:failed
set "GLACIER_EXIT=%ERRORLEVEL%"
:finish
if /I not "%~1"=="--no-pause" pause
exit /b %GLACIER_EXIT%
