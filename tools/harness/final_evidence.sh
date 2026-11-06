#!/usr/bin/env bash
# After shoot_all: English TabBar query, real-click new-game confirm, real-click exit + recents.
#   SERIAL=emulator-xxxx tools/harness/final_evidence.sh <outdir> <factory app build dir>
set -u
G="$(dirname "$0")/gn.sh"; M=$1; B=$2; S=${SERIAL:-emulator-5564}
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}; A="$ADB -s $S"
act(){ pico-cli interact act --device "$S" --package io.embodit.gonine --text "$1" --action click --format json 2>&1 | grep -o '"execution":"[a-z]*"' | head -1; }
"$G" cmd lang en; sleep 1; $A shell am force-stop io.embodit.gonine; $A shell monkey -p io.embodit.gonine -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 10
pico-cli interact query --device "$S" --package io.embodit.gonine --format json > "$B/tabbar-en-query.json" 2>&1
"$G" cmd lang zh; "$G" cmd new ai,b,1,2; sleep 3; "$G" cmd place E5; sleep 3
act "新局"; sleep 2; act "开始"; sleep 3; "$G" shot "$M/newgame-confirm.png" >/dev/null
act "再想想"; act "设置"; sleep 2; act "退出应用"; sleep 2; "$G" shot "$M/exit-confirm.png" >/dev/null; act "退出"; sleep 4
echo "pid=[$($A shell pidof io.embodit.gonine)]"
$A shell dumpsys activity recents > "$B/exit-recents.txt"
grep -E "Recent #" "$B/exit-recents.txt" | grep -c gonine
