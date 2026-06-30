#!/usr/bin/env bash
#
# load-config.sh — seed the CSW Configuration Service from the repo's .conf files.
#
# The .conf files in the repo are SEEDS. Once they are in the Config Service, the
# components read the active version from the service (ConfigServiceLoader), not
# the bundled copy, and the UI can read/edit them too.
#
# Paths here MUST match what the code resolves:
#   HCD      ->  APS/ICS/HCD/GalilMotion/<N>.conf     (GalilHcd loadConfiguration)
#   Assembly ->  APS/ICS/STIM/InsertionStage.conf     (StageAssemblyHandlers initialize)
#
# Prerequisites:
#   1. csw-services running with the Config Service + AAS (Location Service too).
#   2. csw-config-cli installed:   cs install csw-config-cli
#   3. Logged in as a user holding the config-admin role:
#        csw-config-cli login        # interactive AAS login, caches the token
#      (In the csw-services test realm, grant config-admin to your user if needed.)
#
# Usage:
#   ./scripts/load-config.sh                 # create (fresh service)
#   MODE=update ./scripts/load-config.sh     # update + activate existing paths
#
set -euo pipefail

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
MODE="${MODE:-create}"
COMMENT="${COMMENT:-seed from repo $(date -u +%FT%TZ)}"

HCD_RES="$REPO_ROOT/galil-hcd/src/main/resources"
ASM_RES="$REPO_ROOT/ics-assemblies/src/main/resources"

# localFile : configServicePath
MAP=(
  "$HCD_RES/GalilHcdConfig-APS-1.conf:APS/ICS/HCD/GalilMotion/1.conf"
  "$HCD_RES/GalilHcdConfig-APS-2.conf:APS/ICS/HCD/GalilMotion/2.conf"
  "$HCD_RES/GalilHcdConfig-APS-3.conf:APS/ICS/HCD/GalilMotion/3.conf"
  "$HCD_RES/GalilHcdConfig-APS-4.conf:APS/ICS/HCD/GalilMotion/4.conf"
  "$ASM_RES/InsertionStage.conf:APS/ICS/STIM/InsertionStage.conf"
  "$ASM_RES/SteeringBeamSplitterStage.conf:APS/ICS/FOC/SteeringBeamSplitterStage.conf"
  "$ASM_RES/CollimatorUnit.conf:APS/ICS/FOC/CollimatorUnit.conf"
  "$ASM_RES/CalibrationSourceStage.conf:APS/ICS/FOC/CalibrationSourceStage.conf"
  "$ASM_RES/PshFocusStage.conf:APS/ICS/PSH/FocusStage.conf"
  "$ASM_RES/PitFocusStage.conf:APS/ICS/PIT/FocusStage.conf"
  "$ASM_RES/AptFocusStage.conf:APS/ICS/APT/FocusStage.conf"
  "$ASM_RES/TiltPlate.conf:APS/ICS/FOC/TiltPlate.conf"
  "$ASM_RES/FiberSourceStage.conf:APS/ICS/STIM/FiberSourceStage.conf"
  "$ASM_RES/PupilMaskStage.conf:APS/ICS/STIM/PupilMaskStage.conf"
  "$ASM_RES/PshFilterWheel.conf:APS/ICS/PSH/FilterWheel.conf"
  "$ASM_RES/PitFilterWheel.conf:APS/ICS/PIT/FilterWheel.conf"
  "$ASM_RES/AptFilterWheel.conf:APS/ICS/APT/FilterWheel.conf"
  "$ASM_RES/PshPupilMaskWheel.conf:APS/ICS/PSH/PupilMaskWheel.conf"
  "$ASM_RES/PitPupilMaskWheel.conf:APS/ICS/PIT/PupilMaskWheel.conf"
  "$ASM_RES/FocKMirror.conf:APS/ICS/FOC/KMirror.conf"
  # Detector assembly MOCKS (S79). No HCD; synthetic in-memory frames.
  "$ASM_RES/AptDetector.conf:APS/ICS/APT/Detector.conf"
  "$ASM_RES/PitDetector.conf:APS/ICS/PIT/Detector.conf"
  "$ASM_RES/PshDetector.conf:APS/ICS/PSH/Detector.conf"
)

put() {
  local file="$1" path="$2"
  [[ -f "$file" ]] || { echo "  SKIP (missing): $file"; return; }
  # NOTE: `exists` exits 0 whether or not the file is present and reports the
  # answer on stdout as true/false — so parse stdout, do NOT test the exit code.
  if csw-config-cli exists "$path" 2>/dev/null | grep -iqw true; then
    if [[ "$MODE" == "update" ]]; then
      echo "  update + activate: $path"
      csw-config-cli update "$path" -i "$file" -c "$COMMENT"
      # update does not auto-activate; reset active to the just-uploaded latest version
      csw-config-cli resetActiveVersion "$path" -c "activate $COMMENT"
    else
      echo "  exists (skip):     $path   [MODE=update to refresh]"
    fi
  else
    echo "  create:            $path"
    csw-config-cli create "$path" -i "$file" -c "$COMMENT"   # create => version 1 is active
  fi
}

echo "Seeding Config Service (MODE=$MODE) from $REPO_ROOT ..."
for entry in "${MAP[@]}"; do
  put "${entry%%:*}" "${entry##*:}"
done
echo "Done. Verify with:  csw-config-cli list"
