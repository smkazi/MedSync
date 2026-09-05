#!/usr/bin/env bash
#
# Make one file out of two: the compiled installer, then the payload archive.
#
# `cat` is the whole mechanism and that is not a shortcut. A zip's central directory lives at the
# end of the file and its offsets are relative to where the archive starts, so an archive with
# arbitrary bytes in front of it is still a valid archive — Go's archive/zip finds the
# end-of-directory record by scanning backwards and adjusts for the prefix. Windows loads the PE
# because the appended bytes sit past the end of the image the loader is told about. No installer
# framework, no SFX stub, nothing added to what a person double-clicking this has to trust.
set -euo pipefail

[ $# -eq 3 ] || { echo "usage: append-payload.sh <installer> <payload.zip> <output>" >&2; exit 2; }
INSTALLER="$1"; PAYLOAD="$2"; OUT="$3"

mkdir -p "$(dirname "$OUT")"
cat "$INSTALLER" "$PAYLOAD" > "$OUT"
chmod +x "$OUT"

printf 'installer %s\npayload   %s\ncombined  %s  (%s)\n' \
  "$(du -h "$INSTALLER" | cut -f1)" \
  "$(du -h "$PAYLOAD" | cut -f1)" \
  "$OUT" "$(du -h "$OUT" | cut -f1)"
