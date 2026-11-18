#!/usr/bin/env bash
# Tests + APK. Usage : scripts/build-apk.sh [debug|release]
set -euo pipefail
cd "$(dirname "$0")/.."
TYPE=${1:-debug}
./gradlew :core:test :app:testDebugUnitTest
if [ "$TYPE" = release ]; then
  [ -f keystore.properties ] || { echo "keystore.properties manquant : lancer scripts/create-release-keystore.sh" >&2; exit 1; }
  ./gradlew :app:assembleRelease
  APK=app/build/outputs/apk/release/app-release.apk
  "${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties)}"/build-tools/35.0.0/apksigner verify --verbose "$APK"
else
  ./gradlew :app:assembleDebug
  APK=app/build/outputs/apk/debug/app-debug.apk
fi
sha256sum "$APK"
echo "APK : $APK"
