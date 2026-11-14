#!/bin/bash
# rish: Shizuku's terminal with adb-level powers, run from Linux. Shipped by the launcher.
#
# In plain terms: Shizuku (an Android app) holds adb-level access, the kind a computer gets over a USB
# cable, and keeps it after Wi-Fi goes off, until the phone reboots. rish is its small client: this
# starts it with Android's own program launcher (app_process), which Linux can reach because the
# launcher shares Android's system folders with Linux (/system, /apex, ...).
#
#   rish                 an interactive shell as Android's "shell" user
#   rish -c "command"    one command, e.g. rish -c "input keyevent HOME"
#
# Setup, once: in Shizuku, "Use Shizuku in terminal apps" → Export files, to Download/Rish.
# The first run asks you in Shizuku to allow omalauncher.
ENV=/run/user/0/android-env          # the settings Android programs need; the launcher writes it at start
DEX="$HOME/.local/share/rish/rish_shizuku.dex"
EXPORTED=/sdcard/Download/Rish/rish_shizuku.dex

if [ ! -x /system/bin/app_process ] || [ ! -f "$ENV" ]; then
  echo "rish: Android's system folders aren't visible in Linux; this needs launcher build 52 or later" >&2; exit 1
fi
# Take the file Shizuku exported, and update it when Shizuku exports a newer one.
if [ -f "$EXPORTED" ] && ! cmp -s "$EXPORTED" "$DEX" 2>/dev/null; then
  mkdir -p "$(dirname "$DEX")"; rm -f "$DEX"; cp "$EXPORTED" "$DEX"
fi
if [ ! -f "$DEX" ]; then
  echo "rish: export it first: Shizuku → Use Shizuku in terminal apps → Export files → Download/Rish" >&2; exit 1
fi
# Android 14 and later refuse to load code from a file the app could still change: make it read-only.
chmod 400 "$DEX"

. "$ENV"
export PATH="$PATH:/system/bin"
# Linux's fast system calls (launcher 1.4.0) put libfastpath.so in every program through LD_PRELOAD; Android's
# app_process can't load it ("CANNOT LINK EXECUTABLE ... not accessible"), so it mustn't inherit it.
unset LD_PRELOAD
exec /system/bin/app_process -Djava.class.path="$DEX" /system/bin \
  --nice-name=rish rikka.shizuku.shell.ShizukuShellLoader "$@"
