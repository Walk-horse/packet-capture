@echo off
rem 开发运行（Windows）
setlocal
cd /d "%~dp0"

if "%JAVA_HOME%"=="" (
  echo 请先设置 JAVA_HOME 指向 JDK 17+（推荐 JDK 21），例如：
  echo   set JAVA_HOME=C:\Program Files\Java\jdk-21
  exit /b 1
)

call gradlew.bat run --console=plain
endlocal
