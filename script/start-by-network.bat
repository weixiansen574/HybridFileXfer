@echo off
chcp 65001
set /p IPAddress=Please enter the phone's IP address:  
echo The IP address you entered is: %IPAddress%  

java -jar HybridFileXfer.jar -c %IPAddress%  
pause
