@echo off
setlocal

rem Builds the phone and watch apps and copies them to dist\ as
rem HeartLink_Phone.apk and HeartLink_Watch.apk (debug builds, ready to install).

cd /d "%~dp0"

echo Building HeartLink phone and watch APKs...
call "%~dp0gradlew.bat" :phone:assembleDebug :watch:assembleDebug
if errorlevel 1 (
    echo.
    echo BUILD FAILED
    exit /b 1
)

if not exist dist mkdir dist
copy /y "phone\build\outputs\apk\debug\phone-debug.apk" "dist\HeartLink_Phone.apk" >nul || goto :copyfail
copy /y "watch\build\outputs\apk\debug\watch-debug.apk" "dist\HeartLink_Watch.apk" >nul || goto :copyfail

echo.
echo Done:
echo   %cd%\dist\HeartLink_Phone.apk
echo   %cd%\dist\HeartLink_Watch.apk
exit /b 0

:copyfail
echo.
echo Build succeeded but copying the APKs failed.
exit /b 1
