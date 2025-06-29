@echo off
chcp 65001 >nul

:: 检测已连接设备
echo 正在检测已连接的 adb 设备...
setlocal enabledelayedexpansion

:: 先确认 adb 可用
adb version >nul 2>&1
if errorlevel 1 (
    echo 未找到 adb，请确认 adb 已配置在环境变量中。
    pause
    exit /b 1
)

:: 获取设备列表，过滤掉“List of devices attached”和空行
set devices_count=0
for /f "skip=1 tokens=1,2" %%a in ('adb devices') do (
    if "%%b"=="device" (
        set /a devices_count+=1
        set "dev_!devices_count!=%%a"
    )
)

if %devices_count%==0 (
    echo 未检测到任何已连接的设备。
    pause
    exit /b 1
)

:: 显示设备及其名称
echo 已连接设备如下：
for /l %%i in (1,1,%devices_count%) do (
    set "devID=!dev_%%i!"
    for /f "usebackq delims=" %%n in (`adb -s !devID! shell settings get global device_name 2^>nul`) do (
        set "devName=%%n"
    )
    if "!devName!"=="" (
        set "devName=未知设备名"
    )
    echo   [%%i] !devID! - !devName!
)

:: 让用户选择设备编号
:choose_device
set /p choice=请输入要连接的设备编号（1-%devices_count%）:
rem 验证输入是否为数字且在范围内
set /a num_choice=%choice% 2>nul
if "%num_choice%"=="" (
    echo 输入无效，请输入数字。
    goto choose_device
)
if %num_choice% lss 1 (
    echo 输入数字小于1，请重新输入。
    goto choose_device
)
if %num_choice% gtr %devices_count% (
    echo 输入数字超出范围，请重新输入。
    goto choose_device
)

:: 取出设备ID
set "selectedDevID=!dev_%num_choice%!"

echo 您选择的设备ID是：%selectedDevID%

:: 运行java命令，带上 -s 选项指定设备
java -jar -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 HybridFileXfer.jar -c adb -s %selectedDevID%

pause
