chcp 65001
@echo off
adb devices >nul 2>&1
if %errorlevel% neq 0 (
    echo ADB 未安装或未找到设备。
    pause
    exit /b
)

:CHECK_APP
for /f "tokens=*" %%i in ('adb shell dumpsys activity activities ^| findstr "top.weixiansen574.hybridfilexfer"') do set APP_RUNNING=%%i

if not defined APP_RUNNING (
    echo 应用未运行，正在启动...
    adb shell am start -n top.weixiansen574.hybridfilexfer/.MainActivity
) else (
    echo 应用正在运行。
)

timeout /t 5 >nul
goto CHECK_APP
java -jar HybridFileXfer.jar -c adb
pause