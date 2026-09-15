@echo off
setlocal EnableExtensions
set "GAME_ROOT=%~dp0..\.."
set "GENERATOR=%GAME_ROOT%\generator\16-Jungle-Fruit"

pushd "%GENERATOR%"
call mvn -q test-compile package
if errorlevel 1 goto :failed
java -cp "target\test-classes;target\classes" com.cpgame.batcha.g16.GeneratorSelfTest
if errorlevel 1 goto :failed
java -cp "target\test-classes;target\classes" com.cpgame.batcha.g16.RedisPoolAuditMain "dist\loader.properties"
if errorlevel 1 goto :failed
popd
echo Jungle Fruit self-test PASS.
exit /b 0

:failed
set "EXIT_CODE=%ERRORLEVEL%"
popd
echo Jungle Fruit self-test FAILED with exit code %EXIT_CODE%.
exit /b %EXIT_CODE%
