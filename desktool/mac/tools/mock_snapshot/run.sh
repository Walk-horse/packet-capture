#!/usr/bin/env bash
# 离屏渲染「接口模拟 - 编辑页」并导出 PNG，用于校验布局（面板滚动条位置、按钮排布等）。
# 本机没有屏幕录制/辅助功能权限时，screencapture 与 System Events 都不可用，用自绘快照代替。
# 用法：
#   ./run.sh                       # 760x720 → /tmp/mock_editor.png
#   ./run.sh 900 900 /tmp/a.png    # 自定义尺寸与输出
set -euo pipefail
cd "$(dirname "$0")/../.."   # desktool/mac

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

FILES=$(ls Sources/StreamDesk/*.swift | grep -v 'App.swift$' | tr '\n' ' ')
# 指定部署目标，与 Package.swift 的 .macOS(.v13) 对齐（否则按宿主版本编译，会刷 macOS14 弃用告警）
TARGET="$(uname -m)-apple-macosx13.0"
# shellcheck disable=SC2086
swiftc -O -parse-as-library -target "$TARGET" -o "$OUT/mock_snapshot" tools/mock_snapshot/Snapshot.swift $FILES
"$OUT/mock_snapshot" "$@"
