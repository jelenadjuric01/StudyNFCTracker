#!/bin/bash
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
if ! [ -f "$ADB" ]; then
    ADB="adb"
fi

if ! "$ADB" "$@" shell pm list packages | grep -q "package:com.jelena.studytracker$"; then
    echo "App not installed on device. Installing via Gradle..."
    (cd "$DIR" && ./gradlew installDebug)
fi

# Send AUTO_CLOSE broadcast directly to AutoCloseReceiver
"$ADB" "$@" shell am broadcast -a com.jelena.studytracker.AUTO_CLOSE -n com.jelena.studytracker/.AutoCloseReceiver
