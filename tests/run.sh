#!/bin/bash
# 桌面联测：用 mock_adbd.py（按 AOSP 语义复刻 adbd）验证手机端自研 ADB 客户端。
# 用法：./tests/run.sh          （全部通过会打印「全部通过 ✅」）
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T="$ROOT/tests"
PORT="${1:-15999}"
KEYS="$T/keys.txt"
cd "$T"
rm -rf out; mkdir -p out
javac -nowarn -d out android/util/Base64.java   "$ROOT/phone/src/com/carhelper/phone/AdbClient.java"   com/carhelper/phone/TestMain.java
rm -f "$KEYS" adbd.log java.log
setsid python3 mock_adbd.py "$PORT" "$KEYS" > adbd.log 2>&1 < /dev/null &
MOCK=$!
sleep 1
set +e
java -cp out com.carhelper.phone.TestMain "$PORT" "$KEYS" > java.log 2>&1
RC=$?
kill -9 $MOCK 2>/dev/null || true
wait $MOCK 2>/dev/null || true
set -e
grep -av "adb tx\|adb rx" java.log
echo "--- mock adbd 日志见 tests/adbd.log ---"
exit $RC
