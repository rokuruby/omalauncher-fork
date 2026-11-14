#!/bin/bash
# desktop-shortcut: put an app's icon on the Linux desktop (the ~/Desktop folder, drawn by
# "pcmanfm-qt --desktop", which labwc's autostart runs).
#   desktop-shortcut          pick a Linux or Android app from a list (fuzzel) and add its icon
#   desktop-shortcut list     print what the list would show
# A Linux app's icon is a copy of its launcher (.desktop) file; an Android app's icon is a small
# launcher file that runs "phone-window app PACKAGE NAME". To remove an icon, right-click it on the
# desktop and choose Delete (or delete the file in ~/Desktop).
D=~/Desktop; mkdir -p "$D"
note() { command -v notify-send >/dev/null && notify-send "Desktop shortcut" "$1" 2>/dev/null; echo "$1"; }

# Linux apps: "Name <tab> file", user launchers win over system ones, hidden ones left out.
linux=$(for f in ~/.local/share/applications/*.desktop /usr/share/applications/*.desktop; do
  [ -f "$f" ] || continue
  grep -qiE '^(NoDisplay|Hidden)=true' "$f" && continue
  n=$(grep -m1 '^Name=' "$f" | cut -d= -f2-); [ -n "$n" ] && printf '%s\t%s\t%s\n' "$n" "$f" "$(basename "$f")"
done | awk -F'\t' '!seen[$3]++ {print $1 "\t" $2}' | sort -f)
# Android apps: only if adb is connected (phone-apps list prints "Name <tab> package").
android=$(phone-apps list 2>/dev/null)

menu=$( { echo "$linux" | awk -F'\t' 'NF {print "Linux: " $1}'; echo "$android" | awk -F'\t' 'NF {print "Android: " $1}'; } )
[ "$1" = list ] && { echo "$menu"; exit 0; }
choice=$(echo "$menu" | fuzzel --dmenu --prompt "Add to desktop: " --lines 14 --width 44) || exit 0
name=${choice#*: }
case "$choice" in
  "Linux: "*)
    src=$(echo "$linux" | awk -F'\t' -v c="$name" '$1==c {print $2; exit}'); [ -f "$src" ] || exit 1
    cp "$src" "$D/$(basename "$src")"; chmod +x "$D/$(basename "$src")" ;;
  "Android: "*)
    pkg=$(echo "$android" | awk -F'\t' -v c="$name" '$1==c {print $2; exit}'); [ -n "$pkg" ] || exit 1
    title=${name% ($pkg)}
    printf '[Desktop Entry]\nType=Application\nName=%s\nComment=Android app in a Phone window\nExec=phone-window app %s "%s"\nIcon=android-app\n' \
      "$title" "$pkg" "${title//\"/}" > "$D/android-$pkg.desktop"; chmod +x "$D/android-$pkg.desktop" ;;
  *) exit 0 ;;
esac
note "Added $name to the desktop"
