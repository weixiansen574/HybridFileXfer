@echo off
chcp 65001
set /p IPAddress=Please enter the phone's IP address:  
echo The IP address you entered is: %IPAddress%  

java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "%~dp0HybridFileXfer.jar" -c "%IPAddress%"
pause
