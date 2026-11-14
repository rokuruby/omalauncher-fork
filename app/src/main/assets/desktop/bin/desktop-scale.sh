#!/bin/bash
# desktop-scale [value]: show or set the Linux desktop's scale (e.g. 0.6 smaller, 1 bigger); saved for next start.
# The phone's screen and a monitor each keep their own: on a monitor (the launcher writes "on ..." to
# ~/.cache/omalauncher/monitor while it draws the desktop there) it is ~/.config/labwc/monitor-scale, default 1.
f=~/.config/labwc/scale; d=0.7
[ "$(head -c 2 ~/.cache/omalauncher/monitor 2>/dev/null)" = on ] && { f=~/.config/labwc/monitor-scale; d=1; }
[ -z "$1" ] && { echo "scale: $(cat $f 2>/dev/null || echo $d)"; exit 0; }
echo "$1" > $f
pgrep -x labwc >/dev/null && XDG_RUNTIME_DIR=/run/user/0/desktop WAYLAND_DISPLAY=wayland-0 wlr-randr --output WL-1 --scale "$1" && echo "scale set to $1"
