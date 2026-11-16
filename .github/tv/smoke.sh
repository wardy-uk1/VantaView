#!/usr/bin/env bash
# Android TV smoke test: drives VantaView with remote keys (and taps only to type on set-up screens),
# saving a screenshot after each step, plus any crash in the log.
set -u
OUT=tv-out; mkdir -p $OUT
n=0
shot() {
  n=$((n+1)); f=$(printf "%s/%02d-%s.png" $OUT $n "$1"); adb exec-out screencap -p > "$f"; echo "shot $f"
  # What's on screen, as text, for reading the log without the pictures.
  dump; python3 - <<'PY'
import re
x=open('tv-out/ui.xml',encoding='utf-8',errors='ignore').read()
t=[m.group(1) for m in re.finditer(r' text="([^"]+)"',x)]
f=[re.search(r' text="([^"]*)"',n.group(0)) for n in re.finditer(r'<node [^>]*focused="true"[^>]*>',x)]
print("   screen:", " | ".join(t[:40])[:900])
print("   focused:", [g.group(1) for g in f if g])
PY
}
key() { for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.6; done; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml $OUT/ui.xml >/dev/null 2>&1; }
# Tap the first element whose text contains $1.
tap_text() {
  dump
  xy=$(python3 - "$1" <<'PY'
import re,sys
t=sys.argv[1].lower(); x=open('tv-out/ui.xml',encoding='utf-8',errors='ignore').read()
for m in re.finditer(r'<node [^>]*>', x):
    n=m.group(0); txt=(re.search(r' text="([^"]*)"',n) or re.search(r'content-desc="([^"]*)"',n))
    if txt and t in txt.group(1).lower():
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n)
        if b: a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
PY
)
  if [ -n "$xy" ]; then adb shell input tap $xy; echo "tapped '$1' at $xy"; else echo "NOT FOUND: '$1'"; fi
  sleep 1.5
}
# Whether the on-screen keyboard is up (it should only open when OK is pressed on a text box).
kb() { echo "   keyboard: $(adb shell dumpsys input_method | grep -o 'mInputShown=[a-z]*' | head -1)"; }
crashes() { adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: |ANR in com.vantaview" > $OUT/crashes.txt || true; }

adb install -r VantaView-test.apk || exit 1
adb logcat -c
echo "---- start-up time ----"; adb shell am start -W -n com.vantaview.app/.MainActivity
sleep 3; shot splash
sleep 10; shot terms
key DPAD_CENTER; sleep 3; shot profile-setup
tap_text "Profile name"; adb shell input text Tester; key BACK; sleep 1; shot profile-typed
tap_text "Continue"; sleep 3; shot sources
tap_text "Add free channels"; sleep 25; shot countries
# Country chooser: it should open with a country focused; pick United Kingdom, then Save.
tap_text "United Kingdom"; sleep 1; shot countries-picked
tap_text "Save"; sleep 8; shot after-countries
# Setup steps 3 and 4 (live TV, then films and series): Continue, then Skip for now, to Home.
tap_text "Continue"; sleep 3; shot setup-films
tap_text "Skip for now"; sleep 4
home() { for i in 1 2 3 4; do dump; grep -q "Good morning\|Good afternoon\|Good evening" $OUT/ui.xml && break; key BACK; sleep 1; done; }
home; shot home
# Every section from the sidebar.
for section in "Search" "Live TV" "TV Guide" "Movies" "Library" "Sources" "Settings"; do
  home; tap_text "$section"; sleep 5; shot "section-$section"
done
# Settings with the remote: the list on the left, the options on the right.
home; tap_text "Settings"; sleep 3
key DPAD_DOWN DPAD_DOWN; sleep 1; shot settings-down-2
key DPAD_DOWN DPAD_DOWN; sleep 1; shot settings-down-4
key DPAD_RIGHT; sleep 1; shot settings-into-pane
# Live TV with the remote: down the list, play, controls, the Audio/Subtitles/Size buttons, number keys.
home; tap_text "Live TV"; sleep 6
key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN; sleep 1; shot live-focus
key DPAD_CENTER; sleep 12; shot player
key DPAD_CENTER; sleep 1; shot player-controls
key DPAD_RIGHT; sleep 1; shot player-buttons
key DPAD_CENTER; sleep 2; shot player-audio-picker
key BACK; sleep 1; key DPAD_UP; sleep 8; shot player-next-channel
key 1 0 2; sleep 1; shot player-typing; sleep 3; shot player-after-number
key BACK BACK; sleep 2; shot after-player
# TV Guide with the remote.
home; tap_text "TV Guide"; sleep 8
key DPAD_DOWN DPAD_DOWN DPAD_RIGHT DPAD_RIGHT; sleep 1; shot guide-remote
key DPAD_CENTER; sleep 6; shot guide-play
key BACK; sleep 2
# Search with the remote keyboard.
home; tap_text "Search"; sleep 2; adb shell input text BBC; sleep 6; shot search-bbc

# ---- Remote-only checks for the newer screens ----
echo "---- remote: Home refresh button ----"
home; key DPAD_UP DPAD_UP DPAD_UP; sleep 1; shot home-top
key DPAD_RIGHT; sleep 1; shot home-right-1
key DPAD_LEFT; sleep 1; shot home-left
echo "---- remote: Live TV preview, then full screen, then channel up/down ----"
home; tap_text "Live TV"; sleep 6
# From the groups on the left: right to the star, right again to the channel, then down two channels.
key DPAD_DOWN DPAD_UP; sleep 1; key DPAD_RIGHT DPAD_RIGHT DPAD_DOWN DPAD_DOWN; sleep 1; shot live2-focus
key DPAD_CENTER; sleep 10; shot live2-preview
key DPAD_CENTER; sleep 10; shot live2-full
key DPAD_UP; sleep 8; shot live2-ch-up
key DPAD_DOWN; sleep 8; shot live2-ch-down
key DPAD_CENTER; sleep 1; key DPAD_DOWN DPAD_DOWN; sleep 1; shot live2-controls
key BACK BACK; sleep 3; shot live2-back
key BACK; sleep 2
echo "---- remote: Settings drop-downs ----"
home; tap_text "Settings"; sleep 3
key DPAD_DOWN DPAD_DOWN DPAD_DOWN; sleep 1; shot settings2-playback
key DPAD_RIGHT; sleep 1; shot settings2-in-pane
key DPAD_CENTER; sleep 1; shot settings2-opened
key DPAD_DOWN DPAD_DOWN DPAD_DOWN; sleep 1; shot settings2-down
key DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_DOWN; sleep 1; key DPAD_CENTER; sleep 1; shot settings2-second-opened
echo "---- remote: text boxes on Sources (down should pass them, no keyboard) ----"
home; tap_text "Sources"; sleep 4
key BACK; sleep 1
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do key DPAD_DOWN; done; sleep 1; shot sources-down; kb
for i in 1 2 3 4 5 6; do key DPAD_DOWN; done; sleep 1; shot sources-down-more; kb
key DPAD_UP DPAD_UP DPAD_UP; sleep 1; shot sources-up; kb
# OK on a text box should start typing and open the keyboard.
key DPAD_CENTER; sleep 2; shot sources-ok-on-box; kb; key BACK; sleep 1
echo "---- remote: Show QR code ----"
tap_text "Show QR code"; sleep 2; shot sources-qr
key BACK; sleep 1
# Back twice on Home leaves the app.
home; key BACK; sleep 1; shot back-once; key BACK; sleep 2; shot back-twice
crashes
adb logcat -d -t 2000 > $OUT/logcat.txt
echo "---- crashes ----"; cat $OUT/crashes.txt
echo "done"
