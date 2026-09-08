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

# The other stub, and the one that matters most: ZAP scans completely, writes its reports, and
# STILL exits non-zero - which is what it does whenever a plan records "Automation plan warnings",
# `failOnWarning: false` notwithstanding. A real nightly did exactly this on both plans and the
# gate reported "0 of 2 plan(s) scanned" while the artifact held six reports. The findings, not
# the exit code, must decide.
cat > "$WORK/fakezap-warn.sh" <<'SH'
#!/usr/bin/env bash
plan="${@: -1}"
name="$(basename "$plan" .yaml)"
mkdir -p "$ZAP_REPORT_DIR"
# One Medium alert, so the gate has something real to find at the default threshold.
printf '{"site":[{"alerts":[{"riskcode":"2","alert":"Stub Medium finding","instances":[{}]}]}]}' \
  > "$ZAP_REPORT_DIR/zap-$name.json"
echo "Job report generated report /zap/reports/zap-$name.json"
echo "Automation plan warnings:"
echo "	Job spider error accessing URL http://localhost:8080 status code returned : 404 expected 200"
exit 1
SH
chmod +x "$WORK/fakezap-warn.sh"

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

# ---------------------------------------------------------------------------------------------
echo
echo "== a plan that warns, exits non-zero, and still wrote its reports"

set +e
out2="$(
  ZAP_TARGET="http://127.0.0.1:$PORT" \
  ZAP_REPORT_DIR="$WORK/reports2" \
  ZAP_CMD="$WORK/fakezap-warn.sh" \
  "$ROOT/run.sh" baseline authenticated 2>&1
)"
rc2=$?
set -e

echo "$out2"
echo "--- exit status: $rc2"

check2() {
  if grep -q "$2" <<< "$out2"; then
    echo "ok   - $1"
  else
    echo "FAIL - $1"
    fail=1
  fi
}

check2 "the non-zero exit is reported as a warning, not as could-not-run"  'but it produced a report, so it did scan'
check2 "the findings were summarised instead of discarded"                'Stub Medium finding'
check2 "both plans counted as scanned"                                     'Medium=1'
check2 "the reader is pointed at the console log for the warnings"         'console.log in the artifact'

if grep -q 'could not run:' <<< "$out2"; then
  echo "FAIL - a plan that wrote a report was still filed as could-not-run"
  fail=1
else
  echo "ok   - no plan was filed as could-not-run"
fi

if [[ $rc2 -eq 1 ]]; then
  echo "ok   - exit status is 1 (findings at or above the gate), not 2 (could not run)"
else
  echo "FAIL - exit status is $rc2, expected 1"
  fail=1
fi

# ---------------------------------------------------------------------------------------------
echo
echo "== every non-GET requestor row sets a Content-Type"
# By hand this is 31 rows across 600 lines, which is how they all came to be missing it. A row
# without the header is answered 415 before method security runs, so its 403 assertion proves
# nothing - see the long note above the requestor job in authenticated.yaml.
missing="$(
  awk '
    /^        method: (POST|PATCH|PUT)[ \t]*$/ { m = NR; meth = $2; next }
    m && /^        headers:/ { m = 0; next }
    m && /^      - url:/ { print "row ending line " NR ": " meth " without Content-Type"; m = 0 }
    m && /^  - type:/ { print "row at line " m ": " meth " without Content-Type"; m = 0 }
  ' "$ROOT/authenticated.yaml" "$ROOT/baseline.yaml"
)"
if [[ -z "$missing" ]]; then
  n=$(grep -cE '^        method: (POST|PATCH|PUT)[ \t]*$' "$ROOT/authenticated.yaml" "$ROOT/baseline.yaml" \
      | awk -F: '{s += $2} END {print s}')
  echo "ok   - all $n non-GET rows carry a Content-Type header"
else
  echo "FAIL - non-GET rows with no Content-Type:"
  echo "$missing" | sed 's/^/       /'
  fail=1
fi

exit $fail
