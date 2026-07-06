#!/usr/bin/env bash
#
# sims.sh — start/stop/status the four APS Galil simulators, one process each.
#
# Each simulator emulates one Galil controller on its own TCP port. The HCDs
# connect per controller using the port scheme in GalilHcdConfig-APS-N.conf:
#       controller.port = 8888 + id   (id 1..4  ->  8889..8892)
# Start these BEFORE the HCD host config so each HCD finds its endpoint on connect.
#
# Usage:
#   ./scripts/sims.sh start     # launch any not-yet-running sims
#   ./scripts/sims.sh stop      # stop all sims this script started
#   ./scripts/sims.sh status    # show id / port / pid / alive
#   ./scripts/sims.sh restart   # stop then start
#
# Config (env overrides):
#   IDS       controller ids to run            (default: "1 2 3 4")
#   PORT_BASE base added to id for the port     (default: 8888)
#   SIM_HOST  bind host                         (default: 127.0.0.1)
#   SIM_BIN   staged simulator launcher         (default: target/universal/stage/bin/galil-simulator)
#   DEBUG     if set to 1, pass --debug to sims
#
# Run `sbt stage` first so SIM_BIN exists.
#
# Logs and pid files live under $TMT_LOG_HOME/sims (same log home as the HCD
# containers' FileAppender and the assemblies), falling back to target/sims if
# TMT_LOG_HOME is unset. Keeping the pid files OUT of target/ matters: `sbt
# clean` used to delete them while the sim processes kept running, orphaning
# the sims. The port-aware checks below (start refuses an occupied port;
# stop/status consult the actual port listener via lsof) recover even if the
# pid files are lost.

set -euo pipefail

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
IDS="${IDS:-1 2 3 4}"
PORT_BASE="${PORT_BASE:-8888}"
SIM_HOST="${SIM_HOST:-127.0.0.1}"
SIM_BIN="${SIM_BIN:-$REPO_ROOT/target/universal/stage/bin/galil-simulator}"
SIM_MAIN="csw.proto.galil.simulator.GalilSimulatorApp"
RUN_DIR="${RUN_DIR:-${TMT_LOG_HOME:-$REPO_ROOT/target}/sims}"
DEBUG="${DEBUG:-0}"

mkdir -p "$RUN_DIR"

port_for() { echo $(( PORT_BASE + $1 )); }
pidfile_for() { echo "$RUN_DIR/sim-$1.pid"; }
logfile_for() { echo "$RUN_DIR/sim-$1.log"; }

# Pid of whatever is actually LISTENING on the port (orphan-proof; empty if none).
port_pid() { # $1 = port
  lsof -nP -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null | head -1 || true
}

alive() { # $1 = pid
  [[ -n "${1:-}" ]] && kill -0 "$1" 2>/dev/null
}

running_pid() { # $1 = id; echoes pid if alive, else nothing
  local pf; pf="$(pidfile_for "$1")"
  [[ -f "$pf" ]] || return 0
  local pid; pid="$(cat "$pf" 2>/dev/null || true)"
  if alive "$pid"; then echo "$pid"; fi
}

start_one() { # $1 = id
  local id="$1" port; port="$(port_for "$id")"
  local existing; existing="$(running_pid "$id")"
  if [[ -n "$existing" ]]; then
    echo "  sim $id  port $port  already running (pid $existing)"
    return 0
  fi
  # Orphan guard: the pid file may be gone (e.g. sbt clean wiped target/sims)
  # while a previous sim still holds the port. Launching onto an occupied port
  # produces a process that binds-fails and exits (leaving a frozen log and a
  # stale pid file), while the untracked orphan keeps serving. Refuse instead.
  local occupant; occupant="$(port_pid "$port")"
  if [[ -n "$occupant" ]]; then
    echo "  sim $id  port $port  BLOCKED: port held by untracked pid $occupant" >&2
    echo "           (orphan from a previous run? '$0 stop' now kills it, or: kill $occupant)" >&2
    return 1
  fi
  if [[ ! -x "$SIM_BIN" ]]; then
    echo "ERROR: simulator launcher not found/executable: $SIM_BIN" >&2
    echo "       run 'sbt stage' first (or set SIM_BIN)." >&2
    exit 1
  fi
  local args=(-main "$SIM_MAIN" --host "$SIM_HOST" --port "$port")
  [[ "$DEBUG" == "1" ]] && args+=(--debug)
  nohup "$SIM_BIN" "${args[@]}" > "$(logfile_for "$id")" 2>&1 &
  local pid=$!
  # Health check: the JVM prints its startup banner quickly but a bind failure
  # arrives asynchronously (stream materialization) and exits the process.
  # Confirm the process is alive AND actually owns the port before declaring
  # success; otherwise surface the log tail instead of a false "started".
  local ok=0
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    sleep 0.3
    if ! alive "$pid"; then break; fi
    if [[ "$(port_pid "$port")" == "$pid" ]]; then ok=1; break; fi
  done
  if [[ "$ok" == "1" ]]; then
    echo "$pid" > "$(pidfile_for "$id")"
    echo "  sim $id  port $port  started (pid $pid)  log $(logfile_for "$id")"
  else
    echo "  sim $id  port $port  FAILED to start (pid $pid) — last log lines:" >&2
    tail -5 "$(logfile_for "$id")" | sed 's/^/           /' >&2
    rm -f "$(pidfile_for "$id")"
    return 1
  fi
}

stop_one() { # $1 = id
  local id="$1" port; port="$(port_for "$id")"
  local pf; pf="$(pidfile_for "$id")"
  local pid; pid="$(cat "$pf" 2>/dev/null || true)"
  if alive "$pid"; then
    kill "$pid" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do alive "$pid" || break; sleep 0.3; done
    if alive "$pid"; then kill -9 "$pid" 2>/dev/null || true; fi
    echo "  sim $id  stopped (pid $pid)"
  else
    echo "  sim $id  not running (no tracked pid)"
  fi
  rm -f "$pf"
  # Orphan sweep: whatever still listens on this sim's port is ours by port
  # ownership (the port scheme is exclusive to these sims); kill it even if
  # the pid file never knew about it.
  local occupant; occupant="$(port_pid "$port")"
  if [[ -n "$occupant" ]]; then
    kill "$occupant" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do alive "$occupant" || break; sleep 0.3; done
    if alive "$occupant"; then kill -9 "$occupant" 2>/dev/null || true; fi
    echo "  sim $id  killed ORPHAN on port $port (pid $occupant, untracked)"
  fi
}

status_one() { # $1 = id
  local id="$1" port; port="$(port_for "$id")"
  local pid; pid="$(running_pid "$id")"
  local occupant; occupant="$(port_pid "$port")"
  if [[ -n "$pid" && "$occupant" == "$pid" ]]; then
    printf "  sim %s  port %s  HMI %s  RUNNING  pid %s\n" "$id" "$port" $((9090 + id)) "$pid"
  elif [[ -n "$occupant" ]]; then
    printf "  sim %s  port %s  HMI %s  ORPHAN   port held by untracked pid %s%s\n" \
      "$id" "$port" $((9090 + id)) "$occupant" \
      "$( [[ -n "$pid" ]] && echo " (pid file says $pid)" )"
  elif [[ -n "$pid" ]]; then
    printf "  sim %s  port %s  HMI %s  ZOMBIE   pid %s alive but not listening\n" \
      "$id" "$port" $((9090 + id)) "$pid"
  else
    printf "  sim %s  port %s  HMI %s  stopped\n" "$id" "$port" $((9090 + id))
  fi
}

cmd="${1:-status}"
case "$cmd" in
  start)   echo "Starting simulators [$IDS] ..."
           rc=0; for id in $IDS; do start_one "$id" || rc=1; done; exit "$rc" ;;
  stop)    echo "Stopping simulators [$IDS] ...";  for id in $IDS; do stop_one  "$id"; done ;;
  status)  echo "Simulator status [$IDS]:";        for id in $IDS; do status_one "$id"; done ;;
  restart) "$0" stop; "$0" start ;;
  *) echo "usage: $0 {start|stop|status|restart}" >&2; exit 2 ;;
esac