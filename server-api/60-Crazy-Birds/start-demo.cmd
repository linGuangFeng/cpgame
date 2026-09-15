@echo off
cd /d %~dp0
java -Dfile.encoding=UTF-8 -jar dist\controller.jar --port 55060 --config dist\controller.properties --publish ..\..\publish\60-Crazy-Birds
