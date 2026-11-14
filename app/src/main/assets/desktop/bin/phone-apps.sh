#!/bin/bash
# phone-apps: pick an Android app from a list (fuzzel) and open it in its own Phone window.
# The list is every app with a launcher icon, read from the phone each time, plus "Home screen"
# first for an empty phone window. Picking the same app twice moves it to the new window.
#   phone-apps         show the list
#   phone-apps list    print it (name <tab> package)
for p in adb scrcpy fuzzel; do
  command -v $p >/dev/null || { omalauncher notify "Phone apps" "$p isn't installed. In a terminal run: pacman -S scrcpy android-tools fuzzel" 2>/dev/null; echo "$p isn't installed" >&2; exit 1; }
done
on() { [ "$(timeout 5 adb -s "$1" get-state 2>/dev/null)" = device ]; }
pick() { on 127.0.0.1:5555 && { echo 127.0.0.1:5555; return; }; adb devices | awk 'NR>1 && $2=="device" {print $1; exit}'; }
# Not connected: adb-phone ensure reconnects (through Shizuku if needed) and tells the user.
SER=$(pick); [ -z "$SER" ] && { SER=$(adb-phone ensure "Phone apps") || exit 1; }

# scrcpy prints " * Name   package" (system) and " - Name   package" (installed); names can have spaces.
# Two apps with the same name get the package added so they can be told apart.
list=$(timeout 30 scrcpy -s "$SER" --list-apps 2>/dev/null | awk '
  /^ [*-] / { pkg=$NF; sub(/^ [*-] /,""); sub(/[ \t]+[^ \t]+$/,""); n[$0]++; name[pkg]=$0 }
  END { for (p in name) print (n[name[p]]>1 ? name[p] " (" p ")" : name[p]) "\t" p }' | sort -f)
[ -z "$list" ] && { omalauncher notify "Phone apps" "Couldn't read the app list from the phone." 2>/dev/null; exit 1; }
[ "$1" = list ] && { echo "$list"; exit 0; }

choice=$( { echo "Home screen"; echo "$list" | cut -f1; } | fuzzel --dmenu --prompt "Android app: " --lines 14 --width 40) || exit 0
[ -z "$choice" ] && exit 0
[ "$choice" = "Home screen" ] && exec phone-window
pkg=$(echo "$list" | awk -F'\t' -v c="$choice" '$1==c {print $2; exit}')
[ -n "$pkg" ] && exec phone-window app "$pkg" "$choice"
