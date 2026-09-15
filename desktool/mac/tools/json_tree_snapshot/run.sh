#!/usr/bin/env bash
# 离屏渲染两棵 JSON 树并导出 PNG，用于校验层级缩进等视觉问题（本机无屏幕录制权限，screencapture 不可用）。
# 用法：
#   ./run.sh                          # 620x760 → /tmp/json_tree_readonly.png / _editable.png
#   ./run.sh 700 900 /tmp/tree        # 自定义尺寸与输出前缀
set -euo pipefail
cd "$(dirname "$0")/../.."   # desktool/mac

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

FILES=$(ls Sources/StreamDesk/*.swift | grep -v 'App.swift$' | tr '\n' ' ')
# 指定部署目标，与 Package.swift 的 .macOS(.v13) 对齐
TARGET="$(uname -m)-apple-macosx13.0"
# shellcheck disable=SC2086
swiftc -O -parse-as-library -target "$TARGET" -o "$OUT/json_tree_snapshot" tools/json_tree_snapshot/Snapshot.swift $FILES
"$OUT/json_tree_snapshot" "$@"
