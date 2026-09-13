@echo off
rem 打包 Windows 安装包（msi + exe + 免安装绿色版），必须在 Windows 上执行
rem
rem 环境要求（本机已配置好，脚本会自动套用）：
rem   1. 完整 JDK 17+（Android Studio 的 JBR 缺 jpackage.exe，不能用）
rem      本机完整 JDK：C:\Users\sdkdl\.jdks\jdk-21.0.12.1+1（Temurin 21，清华镜像下载）
rem   2. WiX 工具集（Compose 默认从 GitHub 下载，国内常被重置）
rem      本机离线 WiX：C:\Users\sdkdl\.jdks\wix311（wix311-binaries.zip 解压）
rem      通过环境变量 WIX_PATH 指向 + -Pcompose.desktop.application.downloadWix=false 禁用在线下载
rem   3. 360 主动防御可能间歇性拦截 Gradle 启动 jlink/jpackage（报「拒绝访问」或
rem      A problem occurred starting process）。已把 C:\Users\sdkdl\.jdks 加入 360 信任区；
rem      仍偶发拦截时直接重新跑一次脚本即可，通常第二次就过。
setlocal
cd /d "%~dp0"

rem ---- 定位完整 JDK 17+（优先级：已设 JAVA_HOME > 本机 Temurin 21 > Android Studio JBR）----
if "%JAVA_HOME%"=="" (
  if exist "C:\Users\sdkdl\.jdks\jdk-21.0.12.1+1\bin\jpackage.exe" (
    set "JAVA_HOME=C:\Users\sdkdl\.jdks\jdk-21.0.12.1+1"
  ) else if exist "D:\Program Files\Android\Android Studio\jbr\bin\java.exe" (
    echo [警告] 只找到 Android Studio JBR，其缺少 jpackage.exe，MSI/EXE 打包会失败
    set "JAVA_HOME=D:\Program Files\Android\Android Studio\jbr"
  )
)
if "%JAVA_HOME%"=="" (
  echo 请先设置 JAVA_HOME 指向完整 JDK 17+（推荐 JDK 21）
  exit /b 1
)
echo 使用 JAVA_HOME=%JAVA_HOME%

rem ---- 定位离线 WiX（存在则用，避免在线下载被墙）----
set "WIX_ARGS="
if exist "C:\Users\sdkdl\.jdks\wix311\light.exe" (
  set "WIX_PATH=C:\Users\sdkdl\.jdks\wix311"
  set "WIX_ARGS=-Pcompose.desktop.application.downloadWix=false"
  echo 使用离线 WiX：%WIX_PATH%
)

echo === 单元测试 ===
call gradlew.bat test --console=plain %WIX_ARGS%
if errorlevel 1 exit /b 1

echo === 打包 msi ===
call gradlew.bat packageMsi --console=plain %WIX_ARGS%
if errorlevel 1 (
  echo [提示] 若报 jpackage/jlink 启动失败，多半是 360 拦截，重新运行本脚本一次即可
  exit /b 1
)

echo === 打包 exe 安装器 ===
call gradlew.bat packageExe --console=plain %WIX_ARGS%
if errorlevel 1 exit /b 1

echo === 打包免安装版 ===
call gradlew.bat createDistributable --console=plain %WIX_ARGS%
if errorlevel 1 exit /b 1

echo.
echo 产物：
echo   build\compose\binaries\main\msi\*.msi   安装包
echo   build\compose\binaries\main\exe\*.exe   安装器
echo   build\compose\binaries\main\app\PacketCaptureDesk\   免安装绿色版
endlocal
