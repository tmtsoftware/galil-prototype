#!/usr/bin/env bash
#
# apsIcsHcds.sh — start / status / stop the four GalilMotion HCD containers via
# the CSW host-config app. Pairs with apsIcsHcdSims.sh.
#
#   start   runs galil-host-config-app, which spawns one JVM per container
#           (prefixes .1..4) and then EXITS; the container JVMs run independently.
#   status  lists those JVMs (matched by main class GalilContainerCmdApp).
#   stop    TERMs them (then KILLs after a grace period).
#   restart stop + start.
#
# Order of operations:
#   apsIcsHcdSims.sh start   ->   apsIcsHcds.sh start         (bring up)
#   apsIcsHcds.sh stop       ->   apsIcsHcdSims.sh stop       (tear down)
#
# Env overrides:
#   REPO_ROOT    repo root                     (default: parent of this script's dir)
#   STAGE_BIN    staged bin dir                (default: $REPO_ROOT/target/universal/stage/bin)
#   HOST_CONFIG  host config (file or CS path) (default: galil-deploy/.../GalilApsHostConfig.conf)
#   LOCAL        1 = read HOST_CONFIG from disk (--local); 0 = from Config Service (default: 1)
#
# Requires: `sbt stage` done; csw-services (Location Service) up. Start the
# simulators first so each HCD finds its endpoint on connect.
#
# Portable to macOS' bash 3.2.

set -uo pipefail

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
STAGE_BIN="${STAGE_BIN:-$REPO_ROOT/target/universal/stage/bin}"
HOST_CONFIG="${HOST_CONFIG:-$REPO_ROOT/galil-deploy/src/main/resources/GalilApsHostConfig.conf}"
LOCAL="${LOCAL:-1}"

HC_APP="$STAGE_BIN/galil-host-config-app"
# Container launcher for -s: use the HCD's OWN app (GalilHcdApp, a ContainerCmd)
# so each container runs under galil-hcd's application.conf — FileAppender (log
# files in TMT_LOG_HOME) + HmiLogAppender (HMI log streaming) — identical to a
# single-HCD run. The generic galil-container-cmd-app (galil-deploy) uses
# StdOut-only logging, so it produces NO log files and no HMI log pane.
CC_APP="${CONTAINER_SCRIPT:-$STAGE_BIN/galil-hcd}"
# Container JVMs are GalilHcdApp's ContainerCmd; match its class for status/stop.
SIG='csw\.proto\.galil\.hcd\.GalilHcdApp'

list() { pgrep -f "$SIG" 2>/dev/null || true; }

status() {
  local pids; pids="$(list)"
  if [ -z "$pids" ]; then echo "No GalilMotion HCD containers running."; return 0; fi
  echo "Running HCD container JVMs:"
  for p in $pids; do
    conf="$(ps -o args= -p "$p" 2>/dev/null | tr ' ' '\n' | grep -E 'GalilHcd[0-9]*\.conf' | head -1)"
    printf "  pid %-7s %s\n" "$p" "${conf:-?}"
  done
}

start() {
  local existing; existing="$(list)"
  if [ -n "$existing" ]; then
    echo "HCD containers already running (pids: $(echo "$existing" | tr '\n' ' ')) — run 'stop' first."
    return 1
  fi
  if [ ! -x "$HC_APP" ] || [ ! -x "$CC_APP" ]; then
    echo "ERROR: staged launchers not found under $STAGE_BIN" >&2
    echo "       expected galil-host-config-app and galil-hcd (set CONTAINER_SCRIPT to override); run 'sbt stage'." >&2
    return 1
  fi
  # Run from the repo root so the host config's root-relative configFilePath
  # entries resolve in the spawned containers, which inherit this directory.
  cd "$REPO_ROOT"
  echo "Starting HCD containers via host config: $HOST_CONFIG  (LOCAL=$LOCAL)"
  if [ "$LOCAL" = "1" ]; then
    "$HC_APP" --local "$HOST_CONFIG" -s "$CC_APP"
  else
    "$HC_APP" "$HOST_CONFIG" -s "$CC_APP"
  fi
  local rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "host-config app exited with status $rc (is csw-services up? was 'sbt stage' run?)" >&2
    return "$rc"
  fi
  sleep 1
  status
}

stop() {
  local pids; pids="$(list)"
  if [ -z "$pids" ]; then echo "No GalilMotion HCD containers running."; return 0; fi
  echo "Stopping: $(echo "$pids" | tr '\n' ' ')"
  kill $pids 2>/dev/null || true
  local n=0
  while [ "$n" -lt 20 ]; do [ -z "$(list)" ] && break; sleep 0.3; n=$((n + 1)); done
  local still; still="$(list)"
  if [ -n "$still" ]; then echo "Force-killing: $(echo "$still" | tr '\n' ' ')"; kill -9 $still 2>/dev/null || true; fi
  echo "Done."
}

case "${1:-status}" in
  start)   start ;;
  status)  status ;;
  stop)    stop ;;
  restart) stop; sleep 1; start ;;
  *) echo "usage: $0 {start|status|stop|restart}" >&2; exit 2 ;;
esac