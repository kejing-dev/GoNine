#!/usr/bin/env bash
# Re-shoots every state for QA with the installed APK: tools/harness/shoot_all.sh <outdir>   (SERIAL=emulator-xxxx)
set -u
G="$(dirname "$0")/gn.sh"; M=$1; mkdir -p "$M"
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}; A="$ADB -s ${SERIAL:-emulator-5564}"
shot(){ "$G" shot "$M/$1.png" >/dev/null; }
c(){ "$G" cmd "$@"; }
$A shell am force-stop io.embodit.gonine; $A shell monkey -p io.embodit.gonine -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 0.6; shot S00; sleep 8; shot first-screen
c goto S01; sleep 4; shot S01-zh
for L in zh en; do c lang $L; c new ai,b,1,2; sleep 3; for P in play new rules settings; do c page $P; sleep 3; shot ${P}-$L; done; done
c lang zh; c page play; c first; sleep 4; shot S03-first
c ghost E5; sleep 3; shot S04
c preset atari; sleep 2; c hint on; sleep 3; shot S03-hint; c place E6; sleep 0.3; shot S05-capture; sleep 3; shot S05-after; c hint off
c new ai,b,1,0; sleep 2; c place E5; sleep 0.5; shot S06
c flash hold; c preset suicide; sleep 2; c tap A1; sleep 0.8; shot S08-suicide
c tap A2; sleep 0.8; shot S08-occupied
c new local,b,1,0; sleep 2; c preset ko; sleep 2; c place E4; sleep 3; c tap D4; sleep 0.8; shot S08-ko; c flash off
c new ai,b,1,0; sleep 2; c preset dead; sleep 2; c pass; sleep 4; shot S07-dead
c preset seki; sleep 2; c pass; sleep 4; shot S07-seki
c preset clean; sleep 2; c pass; sleep 4; shot S07-clean; c accept; sleep 3; shot S09-zh; c lang en; sleep 3; shot S09-en; c lang zh
c new ai,b,1,2; sleep 3; c askpass; sleep 2; shot S13-pass; c cancel; c resign; sleep 2; shot S13-resign; c cancel
c tilt 25; sleep 3; shot tilt25; c tilt 0; sleep 3; shot tilt0; c tilt 15
c closepanel; sleep 4; shot S10-closed; c tap E5; sleep 4; shot S10-reopened
c page settings; sleep 2; c recall; sleep 6; shot recall-front; c page play
c new local,b,1,0; sleep 3; shot local-even
c place D4; sleep 3; $A shell am force-stop io.embodit.gonine; $A shell monkey -p io.embodit.gonine -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 9; shot S16-restored
c icons; sleep 2
