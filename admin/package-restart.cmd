@echo off
setlocal
cd /d "%~dp0"

where mvn >nul 2>&1
if errorlevel 1 (
  echo [ERROR] mvn was not found in PATH.
  exit /b 1
)

echo [1/2] Packaging CPGame admin...
call mvn -q -DskipTests package
if errorlevel 1 exit /b 1

if not exist "target\cpgame-admin-0.1.0-SNAPSHOT.jar" (
  echo [ERROR] Packaged jar was not found.
  exit /b 1
)

if not exist "var" mkdir "var"
copy /y "target\cpgame-admin-0.1.0-SNAPSHOT.jar" "var\cpgame-admin.jar.next" >nul
if errorlevel 1 exit /b 1

echo [2/2] Restarting CPGame admin...
call "%~dp0restart.cmd" --deploy
exit /b %errorlevel%
