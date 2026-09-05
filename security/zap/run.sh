#!/usr/bin/env bash
# Run the OWASP ZAP Automation Framework plans against a running MedSync stack and gate on the
# findings. Two plans: an unauthenticated baseline and an authenticated full scan.
#
#   security/zap/run.sh                  # both plans
#   security/zap/run.sh baseline         # just the anonymous pass
#   security/zap/run.sh authenticated    # just the logged-in pass
#
# Environment:
#   ZAP_TARGET      gateway base URL              (default http://localhost:8080)
#   ZAP_USERNAME    scan account                  (default dr.rao)
#   ZAP_PASSWORD    scan account password         (default the dev seed password)
#   ZAP_REPORT_DIR  where reports land            (default build/zap)
#   ZAP_FAIL_ON     highest tolerated risk        (default medium -> fail on High and Medium)
#   ZAP_CMD         override the ZAP invocation   (default: docker, else zap.sh on PATH)
#
# Exit status is the gate: 0 clean, 1 findings at or above ZAP_FAIL_ON, 2 could not run.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export ZAP_TARGET="${ZAP_TARGET:-http://localhost:8080}"
export ZAP_USERNAME="${ZAP_USERNAME:-dr.rao}"
export ZAP_PASSWORD="${ZAP_PASSWORD:-ChangeMe!Dev2026}"
ZAP_FAIL_ON="${ZAP_FAIL_ON:-medium}"

HOST_REPORT_DIR="${ZAP_REPORT_DIR:-$ROOT/build/zap}"
mkdir -p "$HOST_REPORT_DIR"

# ZAP needs a writable home directory, and under `-u $(id -u)` it does not get one.
#
# The container image has no /etc/passwd entry for an arbitrary host uid, so `getpwuid()` fails
# inside the JVM and it sets the `user.home` property to a literal question mark. ZAP then tries to
# create its configuration under that, resolved against its working directory:
#
#   Unable to create home directory: /zap/?/.ZAP/
#
# Setting HOME does NOT fix this, which this script previously claimed it did. On Linux the JDK
# reads `user.home` from the password database and never consults $HOME, so the variable is
# ignored and the question mark survives - which is exactly what the CI runner (uid 1001) kept
# reporting after that "fix" was in place. The comment was wrong for as long as the failure was.
#
# There are two independent fixes below and both are here on purpose, because neither can be tried
# on the machine this was written on - it has no Docker daemon and no ZAP - and a second wasted
# nightly run costs more than a belt and braces:
#
#   zap.sh -dir /zaphome    ZAP's own option for choosing its home directory. It needs no help
#                           from the JVM to find one, so the question mark never arises.
#   JAVA_TOOL_OPTIONS       read by every JVM before anything else runs, so `-Duser.home=/zaphome`
#                           makes even ZAP's default path resolve somewhere writable. It costs one
#                           "Picked up JAVA_TOOL_OPTIONS" line on stderr, which is a fair price.
#
# HOME is still exported because the container is a whole environment and other things in it
# reasonably expect one; it is simply not what fixes ZAP.
#
# Running the container as root would also work and is rejected: it leaves root-owned reports on
# the host that the next unprivileged run cannot overwrite.
HOST_ZAP_HOME="${ZAP_HOME_DIR:-$HOST_REPORT_DIR/.zaphome}"
mkdir -p "$HOST_ZAP_HOME"

PLANS=("${@:-baseline authenticated}")
read -r -a PLANS <<< "${PLANS[*]}"

# ---- reachability -------------------------------------------------------------------------
if ! curl -fsS --max-time 5 "$ZAP_TARGET/actuator/health" >/dev/null 2>&1; then
  echo "!! $ZAP_TARGET is not answering /actuator/health - start the stack first (make up)" >&2
  exit 2
fi

# ---- how do we run ZAP -------------------------------------------------------------------
# The container needs the plan mounted and needs to reach the host's gateway. On Linux
# --network host is the simplest way to make localhost mean the same thing in both places.
run_plan() {
  local plan="$1" plan_path="$ROOT/security/zap/$plan.yaml"
  [[ -f "$plan_path" ]] || { echo "!! no such plan: $plan_path" >&2; exit 2; }

  if [[ -n "${ZAP_CMD:-}" ]]; then
    ZAP_REPORT_DIR="$HOST_REPORT_DIR" $ZAP_CMD -cmd -autorun "$plan_path"
  elif command -v zap.sh >/dev/null 2>&1; then
    ZAP_REPORT_DIR="$HOST_REPORT_DIR" zap.sh -cmd -autorun "$plan_path"
  elif docker info >/dev/null 2>&1; then
    docker run --rm --network host \
      -e "ZAP_TARGET=$ZAP_TARGET" \
      -e "ZAP_USERNAME=$ZAP_USERNAME" \
      -e "ZAP_PASSWORD=$ZAP_PASSWORD" \
      -e "ZAP_REPORT_DIR=/zap/reports" \
      -e "HOME=/zaphome" \
      -e "JAVA_TOOL_OPTIONS=-Duser.home=/zaphome" \
      -v "$ROOT/security/zap:/zap/plans:ro" \
      -v "$HOST_REPORT_DIR:/zap/reports" \
      -v "$HOST_ZAP_HOME:/zaphome" \
      -u "$(id -u):$(id -g)" \
      ghcr.io/zaproxy/zaproxy:stable \
      zap.sh -cmd -dir /zaphome -autorun "/zap/plans/$plan.yaml"
  else
    echo "!! neither zap.sh nor a working Docker daemon found." >&2
    echo "   Install ZAP (https://www.zaproxy.org/download/) or set ZAP_CMD." >&2
    exit 2
  fi
}

# ---- the gate ----------------------------------------------------------------------------
# ZAP's own failOnWarning is all-or-nothing; we want "fail on High and Medium, report Low".
# The traditional-json report carries riskcode per alert: 3 High, 2 Medium, 1 Low, 0 Info.
threshold_code() {
  case "${1,,}" in
    high) echo 3 ;;
    medium) echo 2 ;;
    low) echo 1 ;;
    info|informational) echo 0 ;;
    never|none) echo 99 ;;
    *) echo "!! ZAP_FAIL_ON must be high|medium|low|info|never" >&2; exit 2 ;;
  esac
}
MIN_CODE="$(threshold_code "$ZAP_FAIL_ON")"

summarise() {
  local report="$1"
  # Not `return 0`. A missing report used to read as "clean at or above high", which is the same
  # silent pass as a scan that never ran reporting success.
  [[ -f "$report" ]] || { echo "!! no JSON report at $report - nothing was scanned." >&2; exit 2; }
  python3 - "$report" "$MIN_CODE" <<'PY'
import json, sys
report, min_code = sys.argv[1], int(sys.argv[2])
with open(report) as fh:
    data = json.load(fh)
names = {3: "High", 2: "Medium", 1: "Low", 0: "Info"}
counts, blocking = {}, []
for site in data.get("site", []):
    for alert in site.get("alerts", []):
        code = int(alert.get("riskcode", 0))
        counts[code] = counts.get(code, 0) + 1
        if code >= min_code:
            blocking.append((code, alert.get("alert", "?"), len(alert.get("instances", []))))
print("   " + "  ".join(f"{names[c]}={counts.get(c, 0)}" for c in (3, 2, 1, 0)))
for code, name, n in sorted(blocking, reverse=True):
    print(f"   [{names[code]}] {name} ({n} instance(s))")
sys.exit(1 if blocking else 0)
PY
}

status=0
for plan in "${PLANS[@]}"; do
  echo "== ZAP plan: $plan  ->  $ZAP_TARGET"
  # `set -e` would abort here on any non-zero exit from ZAP itself, and the script would then leave
  # with status 1 - which this script documents as "findings at or above ZAP_FAIL_ON". It is not:
  # ZAP failing to start is "could not run", which is 2. Conflating them is how a container that
  # never scanned anything reported itself as a High-severity finding.
  if ! run_plan "$plan"; then
    echo "!! ZAP could not complete the '$plan' plan - see the output above." >&2
    echo "   This is a run failure, not a finding. Nothing was scanned." >&2
    exit 2
  fi
  echo "== findings ($plan), gate at $ZAP_FAIL_ON and above:"
  # A plan that produced no JSON report also did not scan, whatever its exit status said.
  if [[ ! -f "$HOST_REPORT_DIR/zap-$plan.json" ]]; then
    echo "!! no JSON report at $HOST_REPORT_DIR/zap-$plan.json - the plan did not produce one." >&2
    echo "   Treating as could-not-run rather than clean." >&2
    exit 2
  fi
  summarise "$HOST_REPORT_DIR/zap-$plan.json" || status=1
done

echo
echo "== reports in $HOST_REPORT_DIR"
if [[ $status -ne 0 ]]; then
  echo "!! ZAP found issues at or above '$ZAP_FAIL_ON'. Read the HTML report before dismissing any."
else
  echo "== clean at or above '$ZAP_FAIL_ON'."
fi
exit $status
