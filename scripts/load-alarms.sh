#!/usr/bin/env bash
#
# load-alarms.sh — seed the CSW Alarm Store from the repo's alarms.conf.
#
# Registers the 16 per-assembly `hcdFaulted` alarms (metadata) so that
# MotionAssemblyHandlers' setSeverity calls succeed instead of failing with
# `KeyNotFoundException: …-hcdfaulted not found in Alarm Store`. Idempotent:
# `--reset` clears and re-initializes the store from the file, so re-running
# after adding an alarm is the normal workflow.
#
# NOTE: --reset also clears any latched/acknowledged alarm STATE. All our
# alarms are non-latching and auto-acknowledgeable, so this is currently
# harmless — revisit if a latching alarm is ever added.
#
# Prerequisites:
#   1. csw-services running WITH the Alarm Service:  csw-services start -a …
#   2. csw-alarm-cli installed:                      cs install csw-alarm-cli
#
# Usage:
#   ./scripts/load-alarms.sh
#
set -euo pipefail

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
ALARMS_CONF="${ALARMS_CONF:-$REPO_ROOT/ics-assemblies/src/main/resources/alarms.conf}"

if [[ ! -f "$ALARMS_CONF" ]]; then
  echo "ERROR: alarms file not found: $ALARMS_CONF" >&2
  exit 1
fi
if ! command -v csw-alarm-cli >/dev/null 2>&1; then
  echo "ERROR: csw-alarm-cli not on PATH (install: cs install csw-alarm-cli)" >&2
  exit 1
fi

echo "Seeding Alarm Store from $ALARMS_CONF ..."
csw-alarm-cli init "$ALARMS_CONF" --local --reset
echo "Done. Verify with e.g.:"
echo "  csw-alarm-cli severity get --subsystem APS --component ICS.STIM.InsertionStage --name hcdFaulted"