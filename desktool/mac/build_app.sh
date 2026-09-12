#!/usr/bin/env bash
# 构建 macOS 桌面面板并组装为可双击的 .app
set -euo pipefail

cd "$(dirname "$0")"
SCRIPT_DIR="$(pwd)"

APP_NAME="Packet Capture Panel"
BIN_NAME="StreamDesk"
DIST_DIR="dist/${APP_NAME}.app"

echo "==> swift build (release)"
swift build -c release --disable-sandbox

BIN_PATH=".build/release/${BIN_NAME}"
if [[ ! -f "$BIN_PATH" ]]; then
  echo "构建产物缺失：$BIN_PATH" >&2
  exit 1
fi

echo "==> 组装 ${DIST_DIR}"
rm -rf "$DIST_DIR"
mkdir -p "${DIST_DIR}/Contents/MacOS"
mkdir -p "${DIST_DIR}/Contents/Resources"
cp "$BIN_PATH" "${DIST_DIR}/Contents/MacOS/${BIN_NAME}"

cat > "${DIST_DIR}/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key>
    <string>Packet Capture</string>
    <key>CFBundleDisplayName</key>
    <string>Packet Capture</string>
    <key>CFBundleIconFile</key>
    <string>AppIcon</string>
    <key>CFBundleIconName</key>
    <string>AppIcon</string>
    <key>CFBundleIdentifier</key>
    <string>com.ht.stream.desktool</string>
    <key>CFBundleExecutable</key>
    <string>StreamDesk</string>
    <key>CFBundleVersion</key>
    <string>1.0</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>LSMinimumSystemVersion</key>
    <string>13.0</string>
    <key>NSHighResolutionCapable</key>
    <true/>
    <!-- 局域网明文 HTTP 同步地址，需放开 ATS -->
    <key>NSAppTransportSecurity</key>
    <dict>
        <key>NSAllowsArbitraryLoads</key>
        <true/>
    </dict>
</dict>
</plist>
PLIST

# 渲染并嵌入 AppIcon.icns（深蓝渐变 + 白色波形 SF Symbol）
# 用纯 Swift 渲染 PNG + CGImageDestination 直接写 icns，比 sips+iconutil 链路更稳
echo "==> 生成 AppIcon.icns"
PNG_TMP="/tmp/appicon-1024.png"
ICNS_OUT="${DIST_DIR}/Contents/Resources/AppIcon.icns"
swift "${SCRIPT_DIR}/scripts/gen_icon.swift" "$PNG_TMP"
swift "${SCRIPT_DIR}/scripts/png_to_icns.swift" "$PNG_TMP" "$ICNS_OUT"

echo "==> 完成：${DIST_DIR}"
echo "运行：open \"${DIST_DIR}\""
