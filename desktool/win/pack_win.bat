@echo off
rem 打包 Windows 安装包（msi + exe 绿色版），必须在 Windows 上执行
setlocal
cd /d "%~dp0"

if "%JAVA_HOME%"=="" (
  echo 请先设置 JAVA_HOME 指向 JDK 17+（推荐 JDK 21）
  exit /b 1
)

echo === 单元测试 ===
call gradlew.bat test --console=plain
if errorlevel 1 exit /b 1

echo === 打包 msi ===
call gradlew.bat packageMsi --console=plain
if errorlevel 1 exit /b 1

echo === 打包免安装版 ===
call gradlew.bat createDistributable --console=plain
if errorlevel 1 exit /b 1

echo.
echo 产物：
echo   build\compose\binaries\main\msi\
echo   build\compose\binaries\main\app\PacketCaptureDesk\
endlocal
