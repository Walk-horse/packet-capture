#!/usr/bin/env bash
# 开发运行（Windows 用 run.bat）
set -euo pipefail
cd "$(dirname "$0")"

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "请先设置 JAVA_HOME 指向 JDK 17+（推荐 JDK 21）" >&2
  exit 1
fi

./gradlew run --console=plain
