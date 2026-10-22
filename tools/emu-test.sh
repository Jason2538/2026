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
tapText "1" doc && sleep 8; shot 5-edit
tapText "보정" edit && sleep 10; shot 6-look
tapText "저장" look && sleep 10; shot 7-back-doc
tapText "글자 인식" doc2 && sleep 20; shot 8-ocr
adb shell input keyevent KEYCODE_BACK; sleep 3
tapText "PDF 저장" doc3 && sleep 3; shot 9-pdf-dialog
tapText "저장" pdfdlg && sleep 10; shot 10-pdf-saved
tapText "이미지 저장" doc4 && sleep 8; shot 11-images
adb shell ls -R /sdcard/Documents /sdcard/Pictures > $OUT/files.txt 2>&1
adb shell am force-stop com.bookscan.app
adb shell am start -W -n com.bookscan.app/.MainActivity; sleep 6; shot 12-main-again
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" > $OUT/activity.txt
adb logcat -d > $OUT/logcat.txt
adb logcat -d -b crash > $OUT/crash.txt
adb shell run-as com.bookscan.app cat files/crash.txt > $OUT/app-crash.txt 2>/dev/null || true
grep -E "FATAL|AndroidRuntime|bookscan|libc|DEBUG" $OUT/logcat.txt | head -300 > $OUT/summary.txt
exit 0
