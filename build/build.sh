#!/bin/bash
# 车机助手 —— 纯命令行 Android 构建脚本（无 Gradle / 无 AndroidX 依赖）
#
# 用法：
#   ./build.sh car      # 构建车机端「一键全屏」APK
#   ./build.sh phone    # 构建手机端装机 App（需先构建 car，其产物会打进 assets）
#   ./build.sh all
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="$ROOT/build/sdk"
BT="$SDK/build-tools"
AJAR="$SDK/platform-34/android.jar"
OUT="$ROOT/build/out"
KS="$ROOT/build/keystore/debug.keystore"
mkdir -p "$OUT"

for t in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$AJAR"; do
  [ -e "$t" ] || { echo "缺少构建组件: $t"; exit 1; }
done

if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=CarHelper,OU=Dev,O=CarHelper,L=CN,ST=CN,C=CN" >/dev/null 2>&1
  echo "[keystore] 已生成调试签名 $KS"
fi

build_module() {
  local name="$1" dir="$2"
  echo "== 构建 $name =="
  local work="$OUT/$name"
  rm -rf "$work"; mkdir -p "$work/classes" "$work/gen" "$work/dex"

  local resargs=""
  if [ -d "$dir/res" ]; then
    "$BT/aapt2" compile --dir "$dir/res" -o "$work/res.zip" >/dev/null
    resargs="-R $work/res.zip"
  fi

  "$BT/aapt2" link -o "$work/base.apk" -I "$AJAR" \
    --manifest "$dir/AndroidManifest.xml" $resargs \
    --java "$work/gen" --auto-add-overlay >/dev/null

  find "$dir/src" "$work/gen" -name '*.java' > "$work/srcs.txt"
  javac -nowarn -source 8 -target 8 -bootclasspath "$AJAR" -classpath "$AJAR" \
    -encoding UTF-8 -d "$work/classes" @"$work/srcs.txt" 2>&1 \
    | grep -viE "warning|警告|^note|bootstrap" || true
  [ -d "$work/classes/com" ] || { echo "  !! 编译失败"; exit 1; }

  find "$work/classes" -name '*.class' > "$work/classes.txt"
  "$BT/d8" --lib "$AJAR" --min-api 24 --output "$work/dex" @"$work/classes.txt" >/dev/null

  cp "$work/base.apk" "$work/unsigned.apk"
  ( cd "$work/dex" && zip -q -j "$work/unsigned.apk" classes.dex )
  if [ -d "$dir/assets" ]; then
    ( cd "$dir" && zip -q -r "$work/unsigned.apk" assets )
  fi

  "$BT/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
  "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$OUT/$name.apk" "$work/aligned.apk"
  echo "   -> $OUT/$name.apk  ($(stat -c%s "$OUT/$name.apk") bytes)"
}

case "${1:-all}" in
  car)   build_module carhelper-fullscreen "$ROOT/car" ;;
  phone) build_module carhelper-phone      "$ROOT/phone" ;;
  all)
    build_module carhelper-fullscreen "$ROOT/car"
    mkdir -p "$ROOT/phone/assets"
    cp "$OUT/carhelper-fullscreen.apk" "$ROOT/phone/assets/carhelper-fullscreen.apk"
    build_module carhelper-phone "$ROOT/phone"
    ;;
  *) echo "用法: $0 [car|phone|all]"; exit 1 ;;
esac
