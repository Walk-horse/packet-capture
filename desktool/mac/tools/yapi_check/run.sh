#!/usr/bin/env bash
# 离线校验「从 YAPI 获取」的解析与 JSON Schema → 响应示例 转换（不联网、不依赖真实 Token）。
# 用法：./run.sh
# 原理：把 Sources/StreamDesk 下除 App.swift(@main) 以外的源文件 + 本目录 main.swift 编译成命令行程序后运行。
set -euo pipefail
cd "$(dirname "$0")/../.."   # desktool/mac

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

FILES=$(ls Sources/StreamDesk/*.swift | grep -v 'App.swift$' | tr '\n' ' ')
# shellcheck disable=SC2086
swiftc -o "$OUT/yapi_check" tools/yapi_check/main.swift $FILES
"$OUT/yapi_check"
