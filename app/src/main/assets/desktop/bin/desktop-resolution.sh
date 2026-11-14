#!/bin/bash
# desktop-resolution [WIDTHxHEIGHT|auto]: show or set the Linux desktop's resolution; saved for next start.
#   desktop-resolution 1920x1200   1080p-class 16:10 (the launcher shrinks it to fit its window)
#   desktop-resolution auto        follow the launcher window's own size again
f=~/.config/labwc/resolution
[ -z "$1" ] && { r=$(cat $f 2>/dev/null); echo "${r:-auto (window size)}"; exit 0; }
if [ "$1" = auto ]; then : > $f; else echo "$1" > $f; fi
pgrep -x labwc >/dev/null || { echo "saved; applies when the desktop starts"; exit 0; }
export XDG_RUNTIME_DIR=/run/user/0/desktop WAYLAND_DISPLAY=wayland-0
if [ "$1" = auto ]; then echo "saved; restart the desktop to go back to the window size"
else wlr-randr --output WL-1 --custom-mode "$1" && echo "resolution set to $1"; fi
