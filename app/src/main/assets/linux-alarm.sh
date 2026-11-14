#!/bin/bash
# linux-alarm HH:MM [label]   schedule an alarm today (or tomorrow if that time has passed), in a free
#                             task slot of the home tile; asks which to replace when all 5 are in use
# linux-alarm --slot N HH:MM [label]   put it in task slot N (1-5), replacing what's there
# linux-alarm stop            stop a ringing alarm
# linux-alarm list            show scheduled alarms
# linux-alarm cancel TIME     cancel scheduled alarms at TIME (HH:MM)
# linux-alarm sync JSON       (used by the home tile) schedule the task slots:
#                             [{text,t,task,alarm}, ...]; slot i rings at t if task and alarm are on
D="$HOME/.local/share/alarm"
BEEP=/usr/local/share/linux-alarm/beep.wav   # the sound the launcher ships
[ -e "$D/beep.wav" ] && BEEP=$D/beep.wav     # or one of your own
P=$D/pending          # one file per alarm: name <unix time>-<id>, content = label
mkdir -p "$P"

regen() {  # rebuild alarms.json for the home tile
  for f in "$P"/*; do [ -e "$f" ] || continue
    b=$(basename "$f"); jq -n --argjson t "${b%%-*}" --arg l "$(cat "$f")" --arg id "$b" '{time:$t,label:$l,id:$id}'
  done | jq -s 'sort_by(.time)' > "$D/alarms.json.tmp" && mv "$D/alarms.json.tmp" "$D/alarms.json"
}

ring() {  # ring <file>: wait, then beep until stopped
  f=$1; t=$(basename "$f"); t=${t%%-*}
  while [ "$(date +%s)" -lt "$t" ]; do [ -e "$f" ] || exit 0; sleep 15; done
  [ -e "$f" ] || exit 0
  label=$(cat "$f"); rm -f "$f" "$D/stop"; regen
  printf '%s' "$label" > "$D/ringing"
  pactl set-sink-mute @DEFAULT_SINK@ 0; pactl set-sink-volume @DEFAULT_SINK@ 100%
  while [ ! -e "$D/stop" ]; do paplay "$BEEP"; done
}

case "$1" in
  stop) touch "$D/stop"; rm -f "$D/ringing"
    # clear the tile's "tap the alarm tile to stop" notification, which would stay after the alarm stopped; detached,
    # and a moment later for one posted as the tap came, so the tile's run() doesn't wait on it
    setsid -f sh -c 'sleep 2; omalauncher unnotify "⏰"' </dev/null >/dev/null 2>&1
    # switch off the task slots whose alarm time has come (the one that just rang), on the home tile too
    now=$(date +%s)
    new=$(jq -c --argjson now "$now" 'map(if (.task and .alarm) and (.t // 0) > 0 and .t <= $now
      then .task = false | .alarm = false else . end)' "$D/tasks.json" 2>/dev/null)
    if [ -n "$new" ] && [ "$new" != "$(jq -c . "$D/tasks.json")" ]; then
      printf '%s' "$new" | owl-send "$HOME/.config/omalauncher/widgets/30-alarm.owl" set slots - >/dev/null 2>&1 ||
        printf '%s' "$new" > "$D/tasks.json"
    fi
    exit;;
  list) jq -r '.[] | "\(.time|strflocaltime("%a %H:%M"))  \(.label)"' "$D/alarms.json" 2>/dev/null; exit;;
  cancel) t=$(date -d "$2" +%H:%M)
    for f in "$P"/*; do [ -e "$f" ] || continue; b=$(basename "$f")
      [ "$(date -d @"${b%%-*}" +%H:%M)" = "$t" ] && rm -f "$f"; done; regen; exit;;
  __ring) ring "$2"; exit;;
  sync) printf '%s' "$2" | jq . > "$D/tasks.json.tmp" && mv "$D/tasks.json.tmp" "$D/tasks.json" || exit 1
    now=$(date +%s)
    jq -r 'to_entries[] | [.key, (.value.t // 0), ((.value.task and .value.alarm) | tostring), (.value.text // "")] | @tsv' "$D/tasks.json" |
    while IFS=$'\t' read -r i t on text; do
      [ -n "$text" ] || text="Task $((i+1))"
      keep=""
      for f in "$P"/*-s"${i}"_*; do [ -e "$f" ] || continue
        if [ "$on" = true ] && [ "$t" -gt "$now" ] && [ "${f#"$P/$t-s${i}_"}" != "$f" ] && [ -z "$keep" ]; then keep=$f
        else rm -f "$f"; fi
      done
      if [ "$on" = true ] && [ "$t" -gt "$now" ]; then
        [ -n "$keep" ] || keep="$P/$t-s${i}_$$"
        printf '%s' "$text" > "$keep"
      fi
    done
    for f in "$P"/*; do [ -e "$f" ] || continue   # re-arm any alarm whose ring process died
      # setsid -f, fully detached: a plain `... &` here left a subshell holding the launcher's output pipe
      # until the alarm rang and stopped, so the tile's run("linux-alarm stop") waited behind it and taps did nothing
      pgrep -f -- "__ring $f\$" >/dev/null || setsid -f "$0" __ring "$f" </dev/null >/dev/null 2>&1
    done
    regen; exit;;
esac

slot=""
if [ "$1" = --slot ]; then slot=$2; shift 2; fi
T="$1"; L="${2:-Alarm}"
target=$(date -d "today $T" +%s) || exit 1
[ "$target" -le "$(date +%s)" ] && target=$(date -d "tomorrow $T" +%s)
when=$(date -d @$target '+%a %H:%M')
# put it in a task slot of the home tile: the one given with --slot, else the first free one;
# when all 5 are in use, ask which to replace (or cancel)
W="$HOME/.config/omalauncher/widgets/30-alarm.owl"
if [ -n "$slot" ]; then
  [[ "$slot" =~ ^[1-5]$ ]] || { echo "--slot takes 1-5" >&2; exit 1; }
  i=$((slot-1))
else
  i=$(jq '[.[] | (.task and .alarm)] | index(false) // empty' "$D/tasks.json" 2>/dev/null)
fi
if [ -z "$i" ] && [ -s "$D/tasks.json" ]; then
  echo "All 5 task slots are in use:"
  jq -r 'to_entries[] | "  \(.key+1)) \(.value.t|strflocaltime("%a %d %b %H:%M"))  \(if .value.alarm then "[alarm] " else "" end)\(if .value.text == "" then "Task \(.key+1)" else .value.text end)"' "$D/tasks.json"
  if [ -t 0 ]; then
    read -rp "Replace which task with '$L' ($when)? [1-5, c to cancel] " ans
    [[ "$ans" =~ ^[1-5]$ ]] || { echo "Cancelled, nothing added."; exit 1; }
    i=$((ans-1))
  else
    echo "Cancelled, nothing added. To replace one: linux-alarm --slot N $T \"$L\"" >&2; exit 1
  fi
fi
if [ -n "$i" ] && jq -c --argjson i "$i" --argjson t "$target" --arg l "$L" \
     '.[$i] = {text:$l, t:$t, task:true, alarm:true}' "$D/tasks.json" | owl-send "$W" set slots - >/dev/null 2>&1; then
  echo "Alarm '$L' set for $when (task slot $((i+1)))"; exit
fi
# the home tile isn't running: a plain alarm
f="$P/$target-$$"; printf '%s' "$L" > "$f"; regen
setsid -f "$0" __ring "$f" </dev/null >/dev/null 2>&1
echo "Alarm '$L' set for $when"
