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

# Clear app data, restore DND permission, and reopen MainActivity
"$ADB" "$@" shell pm clear com.jelena.studytracker
"$ADB" "$@" shell cmd notification allow_dnd com.jelena.studytracker
"$ADB" "$@" shell am start -n com.jelena.studytracker/.MainActivity
