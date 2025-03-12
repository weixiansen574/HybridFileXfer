@echo off
chcp 65001
setlocal enabledelayedexpansion

:: Constants
set PORT=5740

:: Get the list of devices (skip the header line)
set deviceCount=0
for /f "skip=1 tokens=1" %%a in ('adb devices') do (
    if not "%%a"=="" (
        :: Exclude empty lines
        set /a deviceCount+=1
        set "device[!deviceCount!]=%%a"
    )
)

:: Check if exactly two devices are connected
if %deviceCount% neq 2 (
    echo Detected devices: %deviceCount%. Please connect exactly two phones!
    echo Connected devices:
    for /L %%i in (1,1,%deviceCount%) do (
        call :printDeviceName !device[%%i]!
    )
    pause
    exit /b
)

:: Get the display names of the two devices (format: DeviceName:DeviceID)
call :getDeviceDisplay 1 deviceDisplay1
call :getDeviceDisplay 2 deviceDisplay2

echo.
echo Please select the device to be Phone A (server):
echo 1. %deviceDisplay1%
echo 2. %deviceDisplay2%
set /p choice=Enter a number (1 or 2):

if "%choice%"=="1" (
    set "deviceA=%device[1]%"
    set "deviceB=%device[2]%"
) else if "%choice%"=="2" (
    set "deviceA=%device[2]%"
    set "deviceB=%device[1]%"
) else (
    echo Invalid selection, exiting.
    pause
    exit /b
)

echo.
echo Selected Phone A: %deviceA%
echo Selected Phone B: %deviceB%

echo.
echo Setting up port forwarding...
adb -s %deviceA% forward tcp:%PORT% tcp:%PORT%
adb -s %deviceB% reverse tcp:%PORT% tcp:%PORT%

echo.
echo Port forwarding setup complete!
pause
exit /b

:printDeviceName
:: Parameter: Device ID, output format: DeviceName:DeviceID
setlocal
set "devID=%1"
for /f "delims=" %%n in ('adb -s %devID% shell settings get global device_name 2^>nul') do (
    set "devName=%%n"
    goto afterLoop2
)
:afterLoop2
if "%devName%"=="" set "devName=Unknown"
echo %devName%:%devID%
endlocal
goto :eof

:getDeviceDisplay
:: Parameter 1: Device index; Parameter 2: Output variable name
setlocal
set "devID="
set "devID=!device[%1]!"
for /f "delims=" %%n in ('adb -s !devID! shell settings get global device_name 2^>nul') do (
    set "devName=%%n"
    goto afterLoop
)
:afterLoop
if "!devName!"=="" set "devName=Unknown"
endlocal & set "%2=%devName%:!device[%1]!"
goto :eof
