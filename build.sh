#!/bin/bash
# Build + sign XTrail. Output: build/XTrail-debug.apk
set -e
cd "$(dirname "$0")"
SDK="${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk"
BT="$SDK/build-tools/35.0.0"
AJ="$SDK/platforms/android-36/android.jar"
XP=libs/xposed102.jar
rm -rf build && mkdir -p build/classes build/dex build/res build/apk
javac --release 17 -encoding UTF-8 -cp "$AJ;$XP" -d build/classes src/com/catsuki/xtrail/*.java
cmd //c "$(cygpath -w "$BT/d8.bat")" --lib "$(cygpath -w "$AJ")" --min-api 26 --output build/dex $(cd build/classes && find . -name '*.class' | sed 's|^\./|build/classes/|' | tr '\n' ' ') 2>&1 | grep -v '^$' || true
"$BT/aapt2.exe" compile --dir res -o build/res/res.zip
"$BT/aapt2.exe" link -o build/apk/unsigned.apk -I "$AJ" --manifest AndroidManifest.xml --min-sdk-version 26 --target-sdk-version 35 build/res/res.zip
jar --update --file build/apk/unsigned.apk -C build/dex classes.dex -C meta META-INF
"$BT/zipalign.exe" -f -p 4 build/apk/unsigned.apk build/apk/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias xtrail -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=XTrail" >/dev/null 2>&1
cmd //c "$(cygpath -w "$BT/apksigner.bat")" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out build/XTrail-debug.apk build/apk/aligned.apk
echo BUILD OK: $(pwd)/build/XTrail-debug.apk
