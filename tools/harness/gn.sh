#!/usr/bin/env bash
# Go Nine debug harness (emulator-5564 by default; SERIAL=... to change).
#   gn.sh cmd NAME [ARG]   send a debug broadcast (see DebugCommandReceiver)
#   gn.sh shot FILE        screenshot via pico-cli
#   gn.sh log [N]          last N GoNine log lines
set -e
SERIAL=${SERIAL:-emulator-5564}
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
case "$1" in
  cmd)
    if [ -n "$3" ]; then "$ADB" -s "$SERIAL" shell am broadcast -a io.embodit.gonine.DEBUG -p io.embodit.gonine --es cmd "$2" --es a "'$3'" >/dev/null
    else "$ADB" -s "$SERIAL" shell am broadcast -a io.embodit.gonine.DEBUG -p io.embodit.gonine --es cmd "$2" >/dev/null; fi ;;
  shot) pico-cli capture screenshot -d "$SERIAL" -o "$2" --replace >/dev/null && echo "$2" ;;
  log) "$ADB" -s "$SERIAL" logcat -d -s GoNine:* | tail -n "${2:-40}" ;;
  *) echo "usage: gn.sh cmd NAME [ARG] | shot FILE | log [N]"; exit 2 ;;
esac
