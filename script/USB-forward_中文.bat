@echo off
:: 避免其他繁体中文的电脑乱码
chcp 65001
setlocal enabledelayedexpansion

:: 常量设置
set PORT=5740

:: 获取设备列表（跳过标题行）
set deviceCount=0
for /f "skip=1 tokens=1" %%a in ('adb devices') do (
    if not "%%a"=="" (
        :: 排除空行
        set /a deviceCount+=1
        set "device[!deviceCount!]=%%a"
    )
)

:: 检查是否有两部设备
if %deviceCount% neq 2 (
    echo 检测到的设备数量为 %deviceCount%，请同时连接两部手机！
    echo 已连接设备：
    for /L %%i in (1,1,%deviceCount%) do (
        call :printDeviceName !device[%%i]!
    )
    pause
    exit /b
)

:: 获取两个设备的显示名称（格式：设备名:设备ID）
call :getDeviceDisplay 1 deviceDisplay1
call :getDeviceDisplay 2 deviceDisplay2

echo.
echo 请选择作为A手机（服务端）的设备：
echo 1. %deviceDisplay1%
echo 2. %deviceDisplay2%
set /p choice=请输入数字 (1或2):

if "%choice%"=="1" (
    set "deviceA=%device[1]%"
    set "deviceB=%device[2]%"
) else if "%choice%"=="2" (
    set "deviceA=%device[2]%"
    set "deviceB=%device[1]%"
) else (
    echo 无效选择，退出.
    pause
    exit /b
)

echo.
echo 选择的A手机: %deviceA%
echo 选择的B手机: %deviceB%

echo.
echo 正在设置端口转发...
adb -s %deviceA% forward tcp:%PORT% tcp:%PORT%
adb -s %deviceB% reverse tcp:%PORT% tcp:%PORT%

echo.
echo 端口转发设置完成！
pause
exit /b

:printDeviceName
:: 参数：设备ID，输出格式为：设备名:设备ID
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
:: 参数1：设备序号；参数2：返回变量名称
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
