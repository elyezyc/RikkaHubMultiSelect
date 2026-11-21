#!/usr/bin/env bash
# 一次性跑完三种校验：反射锚点（对照宿主 APK）、JVM 单元测试、产物 APK 检查
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK="${1:-$ROOT/dist/RikkaHubMultiSelect-1.0.0.apk}"
JDK_HOME="${JDK_HOME:-$(ls -d "$ROOT"/tools/jdk-* 2>/dev/null | head -1 || true)}"

echo "############ 1/3 反射锚点（对照宿主 APK） ############"
python3 "$ROOT/tools/verify_anchors.py" "${HOST_APK:-$ROOT/rikkahub-3.3.0.apk}"

echo
echo "############ 2/3 JVM 单元测试 ############"
[ -x "$JDK_HOME/bin/javac" ] || {
    echo "找不到 JDK（$JDK_HOME），先跑 ./tools/fetch_toolchain.sh" >&2
    exit 1
}
OUT="$ROOT/.work/test"
rm -rf "$OUT" && mkdir -p "$OUT"
XPOSED_JAR="${XPOSED_JAR:-$ROOT/tools/xposed-api-82.jar}"
STUB_SOURCES=$(find "$ROOT/stubs" -name '*.java' | sort)
"$JDK_HOME/bin/javac" --release 8 -Xlint:-options -cp "$XPOSED_JAR" -d "$OUT" \
    $STUB_SOURCES \
    "$ROOT/module/src/com/elyezyc/rikkahubmultiselect/Copy.java" \
    "$ROOT/module/src/com/elyezyc/rikkahubmultiselect/Reflect.java" \
    "$ROOT/module/src/com/elyezyc/rikkahubmultiselect/Sql.java" \
    "$ROOT/module/src/com/elyezyc/rikkahubmultiselect/Uuids.java" \
    "$ROOT/module/src/com/elyezyc/rikkahubmultiselect/Suspend.java" \
    "$ROOT/module/src/com/elyezyc/rikkahubmultiselect/XLog.java" \
    "$ROOT/tests/com/elyezyc/rikkahubmultiselect/SelfTest.java"
"$JDK_HOME/bin/java" -cp "$OUT:$XPOSED_JAR" com.elyezyc.rikkahubmultiselect.SelfTest

echo
echo "############ 3/3 产物 APK ############"
if [ -f "$APK" ]; then
    python3 "$ROOT/tools/verify_apk.py" "$APK"
else
    echo "跳过：没有 $APK（先 ./build.sh）"
fi

echo
echo "全部校验通过。"
