#!/bin/bash
# desktop-display: the Linux desktop's resolution and scale, picked from a list (the Display button).
#
# In plain terms: "resolution" is how many pixels the desktop's picture has, "scale" is how big things
# are drawn in it. The desktop can be on the phone's screen or, with a monitor plugged in, drawn
# straight onto the monitor; each place remembers its own resolution and scale.
#
#   desktop-display           show the list (fuzzel) and apply what's picked
#   desktop-display status    print where the desktop is and its resolution and scale
#   desktop-display apply     set the saved scale (and, on the phone, resolution) for where the
#                             desktop is now; the launcher runs this when it moves to or from a monitor
#
# On a monitor, a resolution marked "monitor signal" is one the monitor itself accepts: the monitor is
# switched to it and enlarges the picture itself, as with a PC. Any other size is drawn at that size
# and stretched over the whole screen (what a 4:3 CRT behind a plain adapter needs).
# A new resolution on a monitor asks "Keep this resolution?" and goes back by itself after 15 seconds
# without an answer, in case the screen went black.
# Scale 1 is the sharpest: every app draws its own pixels. Below 1, apps draw bigger and the picture
# is shrunk, which fits more on screen but softens text.
#
# Files: ~/.cache/omalauncher/monitor is written by the launcher ("on WxH", the monitor's own
# resolution and its signals, or "off"); ~/.config/omalauncher/monitor is what's wanted on a monitor
# ("auto" or WxH), which the launcher follows as it changes; ~/.config/labwc/scale and resolution are
# the phone screen's, ~/.config/labwc/monitor-scale the monitor's.
state=~/.cache/omalauncher/monitor
want=~/.config/omalauncher/monitor
cfg=~/.config/labwc
export XDG_RUNTIME_DIR=/run/user/0/desktop WAYLAND_DISPLAY=wayland-0

on_monitor() { [ "$(head -c 2 "$state" 2>/dev/null)" = on ]; }
signals() { sed -n 's/^modes: //p' "$state" 2>/dev/null; }
scale_file() { if on_monitor; then echo $cfg/monitor-scale; else echo $cfg/scale; fi; }
cur_scale() { s=$(cat "$(scale_file)" 2>/dev/null); case "$s" in ''|*[!0-9.]*) s=1;; esac; echo "$s"; }
cur_res() {
  if on_monitor; then r=$(cat "$want" 2>/dev/null); echo "${r:-auto}"
  else r=$(cat $cfg/resolution 2>/dev/null); echo "${r:-auto}"; fi
}
running() { pgrep -x labwc >/dev/null; }

set_scale() {
  mkdir -p $cfg; echo "$1" > "$(scale_file)"
  running && wlr-randr --output WL-1 --scale "$1" >/dev/null 2>&1
}
set_res() {
  if on_monitor; then
    # The launcher watches this file and redraws the monitor at the new size.
    mkdir -p "$(dirname "$want")"; echo "$1" > "$want.tmp" && mv "$want.tmp" "$want"
  elif [ "$1" = auto ]; then
    : > $cfg/resolution
  else
    echo "$1" > $cfg/resolution
    running && wlr-randr --output WL-1 --custom-mode "$1" >/dev/null 2>&1
  fi
}

case "$1" in
  status)
    if on_monitor; then echo "on the monitor: $(sed -n '1s/^on //p' "$state") (wanted: $(cur_res)), scale $(cur_scale)"; sed -n '2,3p' "$state"
    else echo "on the phone: $(cur_res), scale $(cur_scale)"; fi
    exit 0 ;;
  apply)
    running || exit 0
    if on_monitor; then wlr-randr --output WL-1 --scale "$(cur_scale)" >/dev/null 2>&1
    else r=$(cat $cfg/resolution 2>/dev/null); wlr-randr --output WL-1 ${r:+--custom-mode "$r"} --scale "$(cur_scale)" >/dev/null 2>&1; fi
    exit 0 ;;
esac

res=$(cur_res); scale=$(cur_scale); sig=" $(signals) "
mark() { [ "$1" = "$2" ] && echo "   <- now"; }
row() { # size, shape
  note=""; case "$sig" in *" $1 "*) note="   monitor signal";; esac
  echo "Resolution $1   $2$note$(mark "$1" "$res")"
}
if on_monitor; then where="monitor"; auto="best for this monitor, up to 1440p"; else where="phone"; auto="the size of the launcher's window (restart the desktop)"; fi
choice=$( {
  echo "Resolution auto   $auto$(mark auto "$res")"
  for r in 2560x1440 1920x1080 1600x900 1280x720; do row $r "16:9"; done
  for r in 1600x1200 1440x1080 1280x960 1024x768 800x600 640x480; do row $r "4:3"; done
  for s in 2 1.5 1.25 1 0.9 0.8 0.75 0.67 0.6 0.5; do
    case $s in 1) n="sharpest";; 2) n="twice as big, sharp";; 0.*) n="fits more, softer";; *) n="bigger";; esac
    echo "Scale $s   $n$(mark "$s" "$scale")"
  done
} | fuzzel --dmenu --prompt "Display ($where): " --lines 21 --width 62) || exit 0
set -- $choice
case "$1" in
  Resolution)
    set_res "$2"
    # On a monitor a new resolution can leave the screen black (a signal it lists but can't show). So it has to be
    # confirmed: without a "Keep" within 15 seconds, the resolution from before comes back by itself.
    if on_monitor && [ "$2" != "$res" ]; then
      sleep 2
      keep=$(printf 'Keep %s\nGo back to %s\n' "$2" "$res" | timeout 15 fuzzel --dmenu --prompt "Keep this resolution? Goes back in 15 s: " --lines 2 --width 50)
      case "$keep" in Keep*) ;; *) set_res "$res" ;; esac
    fi ;;
  Scale) set_scale "$2" ;;
esac
