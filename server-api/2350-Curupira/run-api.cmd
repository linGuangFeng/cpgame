@echo off
setlocal
cd /d "%~dp0"
if not exist "target\curupira-server-api-1.0.0.jar" (
  echo Missing target\curupira-server-api-1.0.0.jar. Run mvn package first.
  exit /b 1
)
java -jar "target\curupira-server-api-1.0.0.jar"
