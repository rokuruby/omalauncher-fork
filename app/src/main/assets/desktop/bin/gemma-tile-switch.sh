#!/bin/sh
# Used by the Gemma server home tile: gemma-tile-switch <on|off|none> <tap-id>
# Switches once per tap id, waits until the server follows, and prints {"id":..,"status":..}.
want="$1"; id="${2:-0}"
last="$HOME/.cache/gemma-tile-last"; log="$HOME/.cache/gemma-tap.log"
mkdir -p "$HOME/.cache"
if { [ "$want" = on ] || [ "$want" = off ]; } && [ "$id" != 0 ] && [ "$(cat "$last" 2>/dev/null)" != "$id" ]; then
  echo "$id" > "$last"
  echo "$(date) tap $id: $want" >> "$log"
  /usr/local/bin/gemma-server "$want" >/dev/null 2>&1
  i=0
  while [ $i -lt 12 ]; do
    s=$(/usr/local/bin/gemma-server status); [ "$s" = "$want" ] && break
    sleep 2; i=$((i+1))
  done
  echo "$(date) tap $id: now $s" >> "$log"
fi
s=$(/usr/local/bin/gemma-server status)
printf '{"id":%s,"status":"%s"}\n' "$(cat "$last" 2>/dev/null || echo 0)" "$s"
