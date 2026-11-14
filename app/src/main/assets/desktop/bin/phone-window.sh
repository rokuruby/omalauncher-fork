#!/bin/bash
# phone-window: the phone as one window on the Linux desktop (scrcpy).
#
# In plain terms: this opens a second, invisible Android screen, shaped like a phone, and shows it
# in a window. Apps you open in it run there, next to your Linux windows, while the phone's real
# screen keeps showing the desktop. The Android screen is a full phone screen; the window shows it
# shrunk, and resizing or maximizing the window zooms the picture. Closing the window closes that
# screen and its apps.
#
#   phone-window                    open a window on the Android home screen
#   phone-window app PACKAGE [NAME] open a window with that app in it (phone-apps is the app list)
#   phone-window stop               close all of them
# Each call opens one more window with its own Android screen, so several apps can sit side by side.
#   phone-window zoom N   make everything in it N times as big as on the phone (saved; 1 = same)
#
# It talks to the phone through adb (the ADB home tile: Connect; "Keep on without Wi-Fi" keeps it
# working when Wi-Fi goes off). Sound is not sent through the window: apps play on the phone as usual.
# Mouse: right-click = Back, middle-click = Home. Keys: Alt+H Home, Alt+B Back, Alt+S app switcher.
TITLE=Phone
ZF=~/.config/phone-window-zoom
say() { echo "phone-window: $1" >&2; command -v omalauncher >/dev/null && omalauncher notify "Phone window" "$1" 2>/dev/null; }

if [ "$1" = stop ]; then pkill -f "scrcpy .*--new-display="; exit 0; fi
if [ "$1" = zoom ]; then
  case "$2" in ''|*[!0-9.]*) echo "zoom: $(cat $ZF 2>/dev/null || echo 1)"; exit 0;; esac
  echo "$2" > $ZF; echo "zoom set to $2; close and reopen the Phone window"; exit 0
fi
APP=()
if [ "$1" = app ]; then
  [ -z "$2" ] && { echo "usage: phone-window app PACKAGE [NAME]"; exit 1; }
  APP=(--start-app="$2"); TITLE=${3:-$2}; shift; shift; [ $# -gt 0 ] && shift
fi

for p in adb scrcpy; do
  command -v $p >/dev/null || { say "$p isn't installed. In a terminal run: pacman -S scrcpy android-tools"; exit 1; }
done

# adb must be connected: use port 5555 if it's on, else whatever adb-phone finds (Wireless debugging).
on() { [ "$(timeout 5 adb -s "$1" get-state 2>/dev/null)" = device ]; }
pick() { on 127.0.0.1:5555 && { echo 127.0.0.1:5555; return; }; adb devices | awk 'NR>1 && $2=="device" {print $1; exit}'; }
# If it isn't, adb-phone ensure reconnects (turning Wireless debugging on through Shizuku if needed)
# and says so in a notification, or says why it couldn't.
SER=$(pick)
[ -z "$SER" ] && { SER=$(adb-phone ensure "Phone window") || exit 1; }

# A full phone screen: the Android screen gets the phone's own size and density (so apps look exactly
# as they do on the phone), and the window shows that picture shrunk to 432 real pixels wide.
# Resizing or maximizing the window only makes the picture bigger; the layout stays the phone's.
# The desktop's scale (desktop-scale) shrinks windows, so the window is made 1/scale as big to match.
# "phone-window zoom 1.2" makes everything in it 20% bigger from the next open (1 = as on the phone).
sf=~/.config/labwc/scale; [ "$(head -c 2 ~/.cache/omalauncher/monitor 2>/dev/null)" = on ] && sf=~/.config/labwc/monitor-scale   # a monitor has its own scale
scale=$(cat $sf 2>/dev/null); case "$scale" in ''|*[!0-9.]*) scale=1;; esac
zoom=$(cat $ZF 2>/dev/null); case "$zoom" in ''|*[!0-9.]*) zoom=1;; esac
size=$(timeout 5 adb -s "$SER" shell wm size | tail -1 | grep -oE '[0-9]+x[0-9]+')
dens=$(timeout 5 adb -s "$SER" shell wm density | tail -1 | grep -oE '[0-9]+$')
PW=${size%x*}; PH=${size#*x}
# A phone that doesn't answer gets a common phone size.
[ -n "$PW" ] && [ -n "$PH" ] && [ "$PW" != "$size" ] || { PW=1080; PH=2400; }
[ "$PW" -gt "$PH" ] && { t=$PW; PW=$PH; PH=$t; }   # always portrait
DPI=$(awk -v d="${dens:-420}" -v z="$zoom" 'BEGIN{printf "%d", d*z}')
W=$(awk -v s="$scale" 'BEGIN{printf "%d", 432/s}')
H=$(awk -v w="$W" -v pw="$PW" -v ph="$PH" 'BEGIN{printf "%d", w*ph/pw}')

exec scrcpy -s "$SER" \
  --new-display=${PW}x${PH}/$DPI \
  --window-width=$W --window-height=$H \
  --window-title="$TITLE" "${APP[@]}" \
  --no-audio --no-clipboard-autosync \
  --max-fps=30 --video-bit-rate=8M --hwdec=disabled \
  "$@" >/tmp/phone-window.$$.log 2>&1
