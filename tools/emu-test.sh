#!/bin/bash
# Runs inside the CI emulator: installs the app, opens it, starts a scan, saves logs + screenshots.
set -x
OUT=emu-out; mkdir -p $OUT
APK=app/build/outputs/apk/release/app-release.apk
adb logcat -c
adb install -r -g $APK
adb shell pm grant com.bookscan.app android.permission.CAMERA
shot() { adb exec-out screencap -p > $OUT/$1.png; }
tapText() {  # tap the centre of the first view whose text is $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml $OUT/ui-$2.xml >/dev/null
  b=$(grep -o "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" $OUT/ui-$2.xml | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')
  [ -z "$b" ] && { echo "NOT FOUND: $1"; return 1; }
  read x1 y1 x2 y2 <<<$(echo $b | tr '[],' '   ')
  adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))
}
adb shell am start -W -n com.bookscan.app/.MainActivity
sleep 8; shot 1-main
tapText "스캔하기" main; sleep 12; shot 2-camera
adb shell input keyevent KEYCODE_VOLUME_DOWN; sleep 10; shot 3-after-capture
tapText "완료" cam; sleep 10; shot 4-doc
adb shell am start -W -n com.bookscan.app/.MainActivity; sleep 5; shot 5-main-again
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" > $OUT/activity.txt
adb logcat -d > $OUT/logcat.txt
adb logcat -d -b crash > $OUT/crash.txt
adb shell run-as com.bookscan.app cat files/crash.txt > $OUT/app-crash.txt 2>/dev/null || true
grep -E "FATAL|AndroidRuntime|bookscan|libc|DEBUG" $OUT/logcat.txt | head -300 > $OUT/summary.txt
exit 0
