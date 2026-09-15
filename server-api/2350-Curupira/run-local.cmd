@echo off
setlocal
cd /d "%~dp0"
start "Curupira Java API" cmd /k call "%~dp0run-api.cmd"
jwebserver -b 0.0.0.0 -p 8500 -d "%~dp0..\..\publish\2350-Curupira"
