#!/usr/bin/env bash
# Cross-compile the Windows installer. Run from anywhere.
#
#   installer/windows/build.sh            -> dist/MedSync-Setup.exe
#   installer/windows/build.sh --linux    -> dist/medsync-setup  (the same code, for testing here)
#
# CGO off, so the result is one static PE with no runtime, no Visual C++ redistributable and no
# installer framework behind it — which is the whole point of writing it in Go rather than wrapping
# a script in a self-extracting archive.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$ROOT/dist"
mkdir -p "$OUT"

# -s -w strips the symbol table and DWARF: a 12MB installer downloads slower than a 9MB one and
# nobody is attaching a debugger to this.
LDFLAGS="-s -w"

if [ "${1:-}" = "--linux" ]; then
  ( cd "$HERE" && CGO_ENABLED=0 go build -ldflags "$LDFLAGS" -o "$OUT/medsync-setup" . )
  echo "built $OUT/medsync-setup"
  exit 0
fi

( cd "$HERE" && GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -ldflags "$LDFLAGS" -o "$OUT/MedSync-Setup.exe" . )
# arm64 as well: Windows on ARM runs x64 through emulation, but a native binary starts faster and
# the cross-compile is free.
( cd "$HERE" && GOOS=windows GOARCH=arm64 CGO_ENABLED=0 go build -ldflags "$LDFLAGS" -o "$OUT/MedSync-Setup-arm64.exe" . )

ls -la "$OUT"/MedSync-Setup*.exe
command -v file >/dev/null && file "$OUT"/MedSync-Setup*.exe
