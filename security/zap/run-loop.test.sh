#!/usr/bin/env bash
# Proves security/zap/run.sh attempts every plan and reports every failure, rather than leaving on
# the first plan that cannot run. No ZAP and no Docker involved: ZAP_CMD is stubbed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"; [[ -n "${SRV:-}" ]] && kill "$SRV" 2>/dev/null || true' EXIT

PORT=18099

# A gateway that answers /actuator/health, which run.sh checks before doing anything.
cat > "$WORK/serve.py" <<'PY'
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

class H(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200 if self.path == "/actuator/health" else 404)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"UP"}')
    def log_message(self, *a):
        pass

HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY
python3 "$WORK/serve.py" "$PORT" >/dev/null 2>&1 &
SRV=$!

for _ in $(seq 1 50); do
  curl -fsS --max-time 1 "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1 && break
  sleep 0.1
done

# A stubbed ZAP: fails for the baseline plan, succeeds for the authenticated one and writes a
# clean report. That is the exact shape of the failure this change is about - one plan broken,
# one fine - and the old loop never reached the second.
cat > "$WORK/fakezap.sh" <<'SH'
#!/usr/bin/env bash
plan="${@: -1}"
case "$plan" in
  *baseline*)
    echo "Unrecognised active scan policy name for job x : medsync-public"
    echo "Automation plan failures:"
    exit 1
    ;;
  *authenticated*)
    mkdir -p "$ZAP_REPORT_DIR"
    printf '{"site":[{"alerts":[]}]}' > "$ZAP_REPORT_DIR/zap-authenticated.json"
    echo "fake authenticated scan complete"
    exit 0
    ;;
esac
echo "stub does not know plan: $plan" >&2
exit 1
SH
chmod +x "$WORK/fakezap.sh"

set +e
out="$(
  ZAP_TARGET="http://127.0.0.1:$PORT" \
  ZAP_REPORT_DIR="$WORK/reports" \
  ZAP_CMD="$WORK/fakezap.sh" \
  "$ROOT/run.sh" baseline authenticated 2>&1
)"
rc=$?
set -e

echo "$out"
echo "--- exit status: $rc"

fail=0
check() { # description, then a grep pattern
  if grep -q "$2" <<< "$out"; then
    echo "ok   - $1"
  else
    echo "FAIL - $1"
    fail=1
  fi
}

check "the baseline plan was attempted"                 '== ZAP plan: baseline'
check "the authenticated plan was attempted too"        '== ZAP plan: authenticated'
check "the broken plan is named"                        'could not run: baseline'
check "the working plan still reported its findings"    'High=0'
check "the count of scanned plans is stated"            '1 of 2 plan(s) scanned'

if [[ $rc -eq 2 ]]; then
  echo "ok   - exit status is 2 (could not run), not 1 (findings) and not 0"
else
  echo "FAIL - exit status is $rc, expected 2"
  fail=1
fi

# And the console log the tee writes, so a CI artifact carries the reason.
if [[ -f "$WORK/reports/zap-baseline.console.log" ]]; then
  echo "ok   - the failing plan's console output was captured to the report dir"
else
  echo "FAIL - no zap-baseline.console.log in the report dir"
  fail=1
fi

exit $fail
