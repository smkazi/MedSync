#!/usr/bin/env bash
# Start/stop the Java services natively (no Docker), against a local Postgres.
# Usage: scripts/local.sh start [service...] | stop | status | logs <service>
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${HMS_RUN_DIR:-$ROOT/.run}"
mkdir -p "$RUN_DIR"

# service:port pairs, in start order (identity first: everything validates its tokens)
SERVICES=(
  "identity-service:8081"
  "patient-service:8082"
  "scheduling-service:8083"
  "laboratory-service:8084"
  "notification-service:8085"
  "admissions-service:8086"
  "pharmacy-service:8087"
  "gateway:8080"
)

port_of() {
  local name="$1"
  for entry in "${SERVICES[@]}"; do
    [[ "${entry%%:*}" == "$name" ]] && { echo "${entry##*:}"; return; }
  done
  echo ""
}

start_one() {
  local name="$1" port="$2"
  local jar
  jar=$(ls "$ROOT/services/$name/target/$name"-*.jar 2>/dev/null | grep -v sources | head -1 || true)
  if [[ -z "$jar" ]]; then
    echo "!! $name: no jar built - run 'mvn -q package -DskipTests' first" >&2
    return 1
  fi
  if [[ -f "$RUN_DIR/$name.pid" ]] && kill -0 "$(cat "$RUN_DIR/$name.pid")" 2>/dev/null; then
    echo "== $name already running (pid $(cat "$RUN_DIR/$name.pid"))"
    return 0
  fi
  nohup java -jar "$jar" --spring.profiles.active="${HMS_PROFILE:-dev}" \
    > "$RUN_DIR/$name.log" 2>&1 &
  echo $! > "$RUN_DIR/$name.pid"
  printf "== %-22s pid %-7s port %s " "$name" "$(cat "$RUN_DIR/$name.pid")" "$port"
  for _ in $(seq 1 60); do
    if curl -fsS "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1; then
      echo "UP"
      return 0
    fi
    sleep 2
  done
  echo "FAILED - see $RUN_DIR/$name.log"
  return 1
}

case "${1:-start}" in
  start)
    shift || true
    if [[ $# -gt 0 ]]; then
      for name in "$@"; do start_one "$name" "$(port_of "$name")"; done
    else
      for entry in "${SERVICES[@]}"; do start_one "${entry%%:*}" "${entry##*:}"; done
    fi
    ;;
  stop)
    for entry in "${SERVICES[@]}"; do
      name="${entry%%:*}"
      if [[ -f "$RUN_DIR/$name.pid" ]]; then
        pid=$(cat "$RUN_DIR/$name.pid")
        kill "$pid" 2>/dev/null && echo "== stopped $name (pid $pid)" || echo "== $name not running"
        rm -f "$RUN_DIR/$name.pid"
      fi
    done
    ;;
  status)
    for entry in "${SERVICES[@]}"; do
      name="${entry%%:*}"; port="${entry##*:}"
      state=$(curl -fsS "http://127.0.0.1:$port/actuator/health" 2>/dev/null | head -c 60 || echo "down")
      printf "%-22s %-6s %s\n" "$name" "$port" "$state"
    done
    ;;
  logs)
    tail -f "$RUN_DIR/${2:?service name required}.log"
    ;;
  *)
    echo "Usage: scripts/local.sh start [service...] | stop | status | logs <service>" >&2
    exit 2
    ;;
esac
