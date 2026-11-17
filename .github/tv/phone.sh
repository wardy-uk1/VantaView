#!/usr/bin/env bash
# Phone smoke test (portrait, then on its side): taps through every page and Settings section,
# saving a screenshot and the on-screen text after each step, plus any crash in the log.
set -u
OUT=tv-out; mkdir -p $OUT
n=0
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml $OUT/ui.xml >/dev/null 2>&1; }
shot() {
  n=$((n+1)); f=$(printf "%s/%02d-%s.png" $OUT $n "$1"); adb exec-out screencap -p > "$f"; echo "shot $f"
  dump; python3 - <<'PY'
import re
x=open('tv-out/ui.xml',encoding='utf-8',errors='ignore').read()
t=[m.group(1) for m in re.finditer(r' text="([^"]+)"',x)]
print("   screen:", " | ".join(t[:45])[:1000])
PY
}
key() { for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.6; done; }
# The emulator's own apps sometimes freeze; their "isn't responding" box would block every tap.
clear_anr() {
  if grep -q "isn&apos;t responding\|isn't responding" tv-out/ui.xml 2>/dev/null; then
    echo "dismissing a system 'not responding' box"
    xy=$(python3 -c "import re;x=open('tv-out/ui.xml',encoding='utf-8',errors='ignore').read();m=re.search(r'text=\"Wait\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',x);print('' if not m else f'{(int(m[1])+int(m[3]))//2} {(int(m[2])+int(m[4]))//2}')")
    if [ -n "$xy" ]; then adb shell input tap $xy; else adb shell input keyevent KEYCODE_BACK; fi; sleep 1; dump
  fi
}
tap_text() {
  dump; clear_anr
  xy=$(python3 - "$1" <<'PY'
import re,sys
t=sys.argv[1].lower(); x=open('tv-out/ui.xml',encoding='utf-8',errors='ignore').read()
# An exact match first, so "Agree" never taps "Disagree"; then text that starts with it; then anywhere.
nodes=[]
for m in re.finditer(r'<node [^>]*>', x):
    n=m.group(0); txt=(re.search(r' text="([^"]+)"',n) or re.search(r'content-desc="([^"]+)"',n))
    b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n)
    if txt and b: nodes.append((txt.group(1).lower().strip(), list(map(int,b.groups()))))
for test in (lambda s: s==t, lambda s: s.startswith(t), lambda s: t in s):
    hit=next((a for s,a in nodes if test(s)), None)
    if hit: print((hit[0]+hit[2])//2,(hit[1]+hit[3])//2); break
PY
)
  if [ -n "$xy" ]; then adb shell input tap $xy; echo "tapped '$1' at $xy"; else echo "NOT FOUND: '$1'"; fi
  sleep 1.5
}
tap_re() {
  dump; clear_anr
  xy=$(python3 - "$1" <<'PY'
import re,sys
pat=re.compile(sys.argv[1]); x=open('tv-out/ui.xml',encoding='utf-8',errors='ignore').read()
for m in re.finditer(r'<node [^>]*>', x):
    n=m.group(0); t=re.search(r' text="([^"]+)"',n); b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n)
    if t and b and pat.search(t.group(1)):
        a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
PY
)
  if [ -z "$xy" ]; then echo "NOT FOUND: /$1/"; return 1; fi
  # Optional third argument: move up by that many pixels (e.g. from the year under a poster onto the poster).
  if [ -n "${3:-}" ]; then set -- "$1" "${2:-}" "$3"; x=${xy% *}; y=${xy#* }; xy="$x $((y - $3))"; fi
  if [ "${2:-}" = hold ]; then adb shell input swipe $xy $xy 1200; echo "held /$1/ at $xy"; else adb shell input tap $xy; echo "tapped /$1/ at $xy"; fi
  sleep 1.5
}
rotate() { adb shell settings put system user_rotation $1; adb shell wm user-rotation lock $1 >/dev/null 2>&1; sleep 4; }
size() { adb shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*'; }
scroll() { s=$(size); w=${s%x*}; h=${s#*x}; adb shell input swipe $((w/2)) $((h*3/4)) $((w/2)) $((h/4)) 400; sleep 1; }
# The on-screen keyboard (Search opens it) covers the menu button, so it's closed first.
hidekb() { if adb shell dumpsys input_method | grep -q 'mInputShown=true'; then adb shell input keyevent KEYCODE_BACK; sleep 1; fi; }
menu() { hidekb; tap_text "Menu"; sleep 1; tap_text "$1"; sleep 4; }
# Player controls on screen (a tap in the middle brings them back).
controls() { dump; grep -q 'text="Size:' $OUT/ui.xml || { s=$(size); w=${s%x*}; h=${s#*x}; adb shell input tap $((w/2)) $((h/2)); sleep 1; }; }
crashes() { adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: |ANR in com.vantaview" > $OUT/crashes.txt || true; }

adb install -r VantaView-test.apk || exit 1
adb shell settings put global hide_error_dialogs 1
# The emulator's home screen app keeps freezing and its "not responding" box blocks every tap; VantaView
# doesn't need it, so it's switched off for the test.
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1 || true
adb shell settings put global anr_show_background 0
sleep 20  # let the emulator's own apps settle first
adb shell settings put system accelerometer_rotation 0
rotate 0
adb logcat -c
adb shell am start -W -n com.vantaview.app/.MainActivity
sleep 12; shot terms
tap_text "Agree"; sleep 3; shot profile-setup
tap_text "Profile name"; adb shell input text Tester; key BACK; sleep 1
tap_text "Continue"; sleep 4; shot after-profile
# A location prompt may show on first start.
tap_text "Only this time"; tap_text "While using"; tap_text "Don't allow"; sleep 2
tap_text "Add free channels"; sleep 25; shot countries
tap_text "United Kingdom"; sleep 1; tap_text "Save"; sleep 10; shot after-countries
# Setup steps 3 and 4: Continue from live TV, add Cinemeta, then Continue to Home.
scroll; scroll; shot setup-tv-end; tap_text "Continue"; sleep 35; shot setup-connection
scroll; tap_text "Continue"; sleep 3; shot setup-films
tap_text "Add Cinemeta"; sleep 8; shot setup-films-added; scroll; tap_text "Continue"; sleep 4; shot home
scroll; shot home-scrolled
# Add Cinemeta so Movies and TV Series have rows.
menu "Sources"; shot sources; tap_text "Add-ons"; sleep 2; shot sources-addons; tap_text "Add Cinemeta"; sleep 8; shot sources-cinemeta
for page in "Search" "Live TV" "TV Guide" "Movies" "TV Series" "Library" "Sources"; do
  menu "$page"; shot "page-$page"; scroll; shot "page-$page-scrolled"
done
# Live TV: Radio tab, order, a channel playing in portrait (video above, list below), then controls.
menu "Live TV"; tap_text "Order"; sleep 1; shot live-order; key BACK
tap_text "Radio"; sleep 2; shot live-radio
tap_text "All"; sleep 2
dump; xy=$(python3 - <<'PY'
import re
x=open('tv-out/ui.xml',encoding='utf-8',errors='ignore').read()
for m in re.finditer(r'<node [^>]*text="Now: [^"]*"[^>]*>', x):
    b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',m.group(0)); a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
PY
)
[ -n "$xy" ] && adb shell input tap $xy || tap_re '\(\d+p\)' 
sleep 12; shot player-portrait
s=$(size); w=${s%x*}; h=${s#*x}; adb shell input tap $((w/2)) $((h/6)); sleep 1; shot player-portrait-controls
rotate 1; shot player-landscape
adb shell input tap 400 300; sleep 1; shot player-landscape-controls
# Open a channel straight into the full player (as a reminder notification does), sideways.
rotate 1; hidekb
for ch in 20 40 60; do
  adb shell am start --activity-single-top -n com.vantaview.app/.MainActivity --el play_channel $ch; sleep 14; controls; dump
  grep -q 'text="Size:' $OUT/ui.xml && break
  adb shell input keyevent KEYCODE_BACK; sleep 2
done
shot player-for-sizes
# Picture sizes: each press of Size, then Zoom and Hold (the controls are brought back each time).
for i in 1 2 3 4 5 6 7; do
  controls; tap_text "Size:"; sleep 2; shot "size-$i"
done
controls; tap_text "Zoom"; controls; tap_text "Zoom"; controls; tap_text "Zoom"; sleep 2; shot zoom-3-steps
controls; tap_text "Hold"; sleep 2; shot zoom-hold-top
# Back to 100% (seven presses of Size went round to Fit again) for the rest of the run.
controls; for i in 1 2 3 4; do tap_text "Zoom"; controls; done
rotate 0; key BACK; key BACK; sleep 2
# Movies: tiles, a tile's page, a film's details.
menu "Movies"; shot movies
# Press and hold a poster: the Watchlist / Watched pop-up.
scroll; scroll; tap_re '★' hold 250; sleep 2; shot poster-actions; key BACK; sleep 1
# A film's details, scrolled to the end (More like this goes last).
tap_re '★' tap 250; sleep 8; shot details; scroll; shot details-scrolled; scroll; scroll; shot details-end; key BACK; key BACK
# Settings: the list, then every section.
menu "Settings"; shot settings
for sec in "Playback" "Appearance" "Subtitles and audio" "Sources and storage" "Weather" "Backup and restore" "Parental control" "Updates" "Help and about"; do
  menu "Settings"; tap_text "$sec"; sleep 2; shot "settings-$sec"; scroll; shot "settings-$sec-2"; scroll; shot "settings-$sec-3"
done
# Appearance looks: Tiles (light) and Spotlight, then back to VantaView.
menu "Settings"; tap_text "Appearance"; sleep 1; tap_text "Tiles"; sleep 2; menu "Home"; shot home-tiles; menu "Live TV"; shot live-tiles
menu "Settings"; tap_text "Appearance"; sleep 1; tap_text "Spotlight"; sleep 2; menu "Movies"; shot movies-spotlight
menu "Settings"; tap_text "Appearance"; sleep 1; tap_text "VantaView"; sleep 2
# On its side: Home, Live TV, Movies, Settings.
rotate 1
for page in "Home" "Live TV" "TV Guide" "Movies" "Settings"; do menu "$page"; shot "landscape-$page"; done
rotate 0
# Language: switch this profile to German and look round, so untranslated text stands out.
menu "Settings"; tap_text "Appearance"; sleep 1; for i in 1 2 3 4 5 6; do scroll; done; shot appearance-language; tap_text "Deutsch"; sleep 3; shot appearance-de
for page in "Start" "Live-TV" "TV-Programm" "Filme" "Mediathek" "Quellen" "Einstellungen"; do
  tap_text "Menü"; sleep 1; tap_text "$page"; sleep 4; shot "de-$page"; scroll; shot "de-$page-2"
done
tap_text "Menü"; sleep 1; tap_text "Einstellungen"; sleep 2; tap_text "Wiedergabe"; sleep 2; shot de-settings-playback
crashes; echo "---- crashes ----"; cat $OUT/crashes.txt
