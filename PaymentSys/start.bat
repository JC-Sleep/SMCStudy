@echo off
echo ========================================
echo 支付系统启动脚本
echo ========================================

REM 设置Java环境
set JAVA_HOME=C:\Program Files\Java\jdk1.8.0_xxx
set PATH=%JAVA_HOME%\bin;%PATH%

REM 设置环境变量（生产环境使用）
set DB_USERNAME=payment_user
set DB_PASSWORD=payment_pass
set SCB_API_KEY=your_api_key
set SCB_API_SECRET=your_api_secret

REM 启动应用
echo 正在启动支付系统...
java -jar target\PaymentSys-1.0.0.jar

pause

