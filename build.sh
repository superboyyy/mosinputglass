#!/bin/bash
# 构建模块 APK -> build/mosinputglass.apk
#
# 需要：JDK 17+，Android SDK（build-tools 和 platforms/android-36）。
#   ANDROID_HOME / ANDROID_SDK_ROOT   SDK 路径，不设时依次找 ~/Library/Android/sdk、~/Android/Sdk
#   BUILD_TOOLS                       build-tools 版本，不设时用已安装的最新版本
#   JAVA_HOME                         不设时用 PATH 里的 javac；macOS 上装了 Android Studio 会自动用它自带的 JDK
# 签名：
#   KEYSTORE / KEYSTORE_PASS / KEY_ALIAS / KEY_PASS   正式发布用你自己的签名
#   不设时用 ~/.android/mosinputglass-debug.keystore（第一次构建时自动生成，不放在项目里）
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
for d in "$SDK" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
  [ -n "$d" ] && [ -d "$d/build-tools" ] && { SDK="$d"; break; }
done
[ -d "$SDK/build-tools" ] || { echo "找不到 Android SDK，请设置 ANDROID_HOME" >&2; exit 1; }
BT="$SDK/build-tools/${BUILD_TOOLS:-$(ls "$SDK/build-tools" | sort -V | tail -1)}"
PLATFORM="$SDK/platforms/android-36"
JAR="$PLATFORM/android.jar"
[ -f "$JAR" ] || { echo "缺少 $JAR（在 SDK Manager 里装 Android 16 / API 36 平台）" >&2; exit 1; }

rm -rf build && mkdir -p build/{gen,lib,stubs,classes,res,dex}

# libxposed service 的 AIDL -> Java
for f in $(find lib/aidl -name '*.aidl'); do
  "$BT/aidl" --lang=java -Ilib/aidl -p"$PLATFORM/framework.aidl" -o build/gen "$f"
done
# 打进 APK 的库：libxposed service + 注解桩
javac -nowarn -source 17 -target 17 -cp "$JAR" -d build/lib $(find lib/java build/gen -name '*.java')
# 只用于编译的 libxposed API（运行时由 LSPosed 提供，不能打进 APK）
javac -nowarn -source 17 -target 17 -cp "$JAR:build/lib" -d build/stubs $(find stubs -name '*.java')
javac -nowarn -source 17 -target 17 -cp "$JAR:build/stubs:build/lib" -d build/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 31 --lib "$JAR" --classpath build/stubs --output build/dex \
  $(find build/classes build/lib -name '*.class')
"$BT/aapt2" compile --dir res -o build/res/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o build/unsigned.apk build/res/res.zip
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)
(cd resources && zip -q -r ../build/unsigned.apk META-INF)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

if [ -z "${KEYSTORE:-}" ]; then
  KEYSTORE="$HOME/.android/mosinputglass-debug.keystore"; KEYSTORE_PASS=android; KEY_ALIAS=debug; KEY_PASS=android
  if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias debug \
      -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=MOS Input Glass Debug" >/dev/null 2>&1
  fi
fi
ALIAS_ARG=(); [ -n "${KEY_ALIAS:-}" ] && ALIAS_ARG=(--ks-key-alias "$KEY_ALIAS")
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" ${ALIAS_ARG[@]+"${ALIAS_ARG[@]}"} \
  --key-pass "pass:${KEY_PASS:-$KEYSTORE_PASS}" --out build/mosinputglass.apk build/aligned.apk
echo "built: build/mosinputglass.apk ($(wc -c < build/mosinputglass.apk | tr -d ' ') bytes)"
