#!/bin/bash
# adb-phone: connect this Linux to the phone's own adb.
#   adb-phone status      JSON {state, serial, wifi, data}
#   adb-phone connect     connect (data mode port 5555 first, else Wireless debugging found by mDNS;
#                         if Wireless debugging is off and Shizuku runs, rish turns it on first)
#   adb-phone ensure [WHO] connect if needed, telling the user by notification (WHO = its title);
#                         prints the serial, exits 1 with the reason when it can't connect
#   adb-phone pair CODE   pair, with the pairing screen open on the phone (port found by mDNS)
#   adb-phone keep        turn on port 5555 so adb stays on after Wi-Fi goes off, until reboot
#   adb-phone off         disconnect and turn port 5555 back off
DATA=127.0.0.1:5555

mdns_port() { # $1 = connect | pairing
  timeout 6 adb mdns services 2>/dev/null | awk -v t="_adb-tls-$1._tcp" '$2==t {split($3,a,":"); print a[2]; exit}'
}
online() { [ "$(timeout 5 adb -s "$1" get-state 2>/dev/null)" = device ]; }
serial() { # the connected device, data mode preferred
  online $DATA && { echo $DATA; return; }
  adb devices | awk 'NR>1 && $2=="device" {print $1; exit}'
}
shizuku() { command -v rish >/dev/null && [ "$(timeout 15 rish -c 'id -u' 2>/dev/null | tr -d '\r')" = 2000 ]; }
wifi_on() { [ "$(timeout 10 rish -c 'settings get global wifi_on' 2>/dev/null | tr -d '\r')" != 0 ]; }
note() { command -v omalauncher >/dev/null && omalauncher notify "$1" "$2" >/dev/null 2>&1; }

case "$1" in
  status)
    s=$(serial); w=$( [ -n "$(mdns_port connect)" ] && echo true || echo false)
    d=$(online $DATA && echo true || echo false)
    printf '{"state":"%s","serial":"%s","wifi":%s,"data":%s}\n' "$( [ -n "$s" ] && echo connected || echo off)" "$s" "$w" "$d" ;;
  connect)
    adb start-server >/dev/null 2>&1
    s=$(serial); [ -n "$s" ] && { echo "connected: $s"; exit 0; }
    timeout 8 adb connect $DATA >/dev/null 2>&1; online $DATA && { echo "connected: $DATA"; exit 0; }
    p=$(mdns_port connect)
    [ -n "$p" ] && timeout 10 adb connect 127.0.0.1:$p >/dev/null 2>&1
    s=$(serial); [ -n "$s" ] && { echo "connected: $s"; exit 0; }
    # Wireless debugging is off (it goes off by itself now and then; mDNS may still list its old port).
    # Shizuku can turn it back on.
    shizuku || { echo "Wireless debugging is off and Shizuku isn't running: open Shizuku and start it, or turn on Wireless debugging in Developer options"; exit 1; }
    wifi_on || { echo "Wi-Fi is off: Wireless debugging needs Wi-Fi (or turn on Keep on without Wi-Fi next time)"; exit 1; }
    timeout 10 rish -c 'settings put global adb_wifi_enabled 1' >/dev/null 2>&1
    for i in 1 2 3 4 5 6; do
      sleep 2; p=$(mdns_port connect)
      [ -n "$p" ] && timeout 10 adb connect 127.0.0.1:$p >/dev/null 2>&1
      s=$(serial); [ -n "$s" ] && { echo "connected: $s (turned on Wireless debugging through Shizuku)"; exit 0; }
    done
    echo "Turned on Wireless debugging through Shizuku but couldn't connect: if this keeps happening, pair again on the ADB tile"; exit 1 ;;
  ensure)
    who=${2:-ADB}
    adb start-server >/dev/null 2>&1
    s=$(serial); [ -n "$s" ] && { echo "$s"; exit 0; }
    note "$who" "adb isn't connected. Reconnecting..."
    msg=$("$0" connect 2>&1 | tail -1); s=$(serial)
    if [ -n "$s" ]; then
      command -v omalauncher >/dev/null && omalauncher unnotify "$who" >/dev/null 2>&1
      note "$who" "adb was off; reconnected."
      echo "$s"; exit 0
    fi
    note "$who" "adb isn't connected. $msg"
    echo "$msg" >&2; exit 1 ;;
  pair)
    [ -z "$2" ] && { echo "usage: adb-phone pair CODE"; exit 1; }
    p=$(mdns_port pairing)
    [ -z "$p" ] && { echo "Open Wireless debugging → Pair device with pairing code first"; exit 1; }
    timeout 15 adb pair 127.0.0.1:$p "$2" | tail -1 && "$0" connect ;;
  keep|data)
    "$0" connect >/dev/null; s=$(serial)
    [ -z "$s" ] && { echo "Not connected"; exit 1; }
    online $DATA && { echo "already kept on without Wi-Fi"; exit 0; }
    timeout 10 adb -s "$s" tcpip 5555 >/dev/null; sleep 3; timeout 8 adb connect $DATA | tail -1 ;;
  off)
    online $DATA && timeout 8 adb -s $DATA usb >/dev/null 2>&1
    adb disconnect >/dev/null 2>&1; echo "disconnected" ;;
  *) sed -n '2,10p' "$0" | sed 's/^# //' ;;
esac
