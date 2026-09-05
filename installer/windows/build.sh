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

# -trimpath, for two reasons and the second is the real one.
#
# It makes the build reproducible: the same source, the same Go version and the same GOOS/GOARCH
# produce the same bytes, so somebody can check the .exe they downloaded against one they built
# themselves rather than trusting whoever handed it to them. That matters more for an executable
# than for anything else in this repository.
#
# And without it the binary carries the absolute paths of the machine it was built on, baked into
# every panic message and readable by anyone with a hex editor. A file built here and handed to
# somebody else should not tell them the directory layout of the box that produced it.
BUILDFLAGS="-trimpath"

# --payload <zip> appends a runtime to the executables, which is what makes a released
# MedSync-Setup.exe self-contained. Without it the build produces the developer installer, which
# carries no payload and falls back to building MedSync from source.
PAYLOAD=""
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --payload) PAYLOAD="$2"; shift 2 ;;
    *) ARGS+=("$1"); shift ;;
  esac
done
set -- "${ARGS[@]:-}"

attach() {
  [ -n "$PAYLOAD" ] || return 0
  local exe="$1"
  bash "$(dirname "$HERE")/payload/append-payload.sh" "$exe" "$PAYLOAD" "$exe.tmp"
  mv "$exe.tmp" "$exe"
}

if [ "${1:-}" = "--linux" ]; then
  ( cd "$HERE" && CGO_ENABLED=0 go build $BUILDFLAGS -ldflags "$LDFLAGS" -o "$OUT/medsync-setup" . )
  attach "$OUT/medsync-setup"
  echo "built $OUT/medsync-setup"
  exit 0
fi

( cd "$HERE" && GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build $BUILDFLAGS -ldflags "$LDFLAGS" -o "$OUT/MedSync-Setup.exe" . )
# arm64 as well: Windows on ARM runs x64 through emulation, but a native binary starts faster and
# the cross-compile is free.
( cd "$HERE" && GOOS=windows GOARCH=arm64 CGO_ENABLED=0 go build $BUILDFLAGS -ldflags "$LDFLAGS" -o "$OUT/MedSync-Setup-arm64.exe" . )

# The README travels with the executables, because "in the same folder as the .exe" is where
# somebody who has just unzipped this will look for it, and a README in the repository is a README
# they never see.
attach "$OUT/MedSync-Setup.exe"
attach "$OUT/MedSync-Setup-arm64.exe"

cp "$HERE/README.txt" "$OUT/README.txt"

ls -la "$OUT"/MedSync-Setup*.exe "$OUT/README.txt"
command -v file >/dev/null && file "$OUT"/MedSync-Setup*.exe
