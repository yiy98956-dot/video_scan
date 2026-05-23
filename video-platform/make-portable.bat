@echo off
setlocal
echo [1/2] Building Java project...
call mvn clean package -DskipTests

set JAR_NAME=video-platform-1.0.0.jar
set DIST_DIR=portable-server

echo [2/2] Preparing portable directory: %DIST_DIR%
if exist %DIST_DIR% rmdir /s /q %DIST_DIR%
mkdir %DIST_DIR%
mkdir %DIST_DIR%\bin

copy target\%JAR_NAME% %DIST_DIR%\bin\

:: Create Windows Launcher
echo @echo off > %DIST_DIR%\start-windows.bat
echo java -Djava.awt.headless=false -jar bin\%JAR_NAME% >> %DIST_DIR%\start-windows.bat

:: Create Linux Launcher
echo #!/bin/bash > %DIST_DIR%\start-linux.sh
echo java -jar bin/%JAR_NAME% >> %DIST_DIR%\start-linux.sh

echo.
echo ======================================================
echo SUCCESS! Portable server created in '%DIST_DIR%'
echo.
echo Windows: Run 'start-windows.bat'
echo Linux:   Run 'sh start-linux.sh'
echo ======================================================
pause
