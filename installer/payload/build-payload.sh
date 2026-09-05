#!/usr/bin/env bash
#
# Assemble everything MedSync needs to run into one archive, so the installer can carry it.
#
# This is the script that turns "build it on the user's machine" into "ship it already built". What
# it produces is a zip that gets appended to the compiled Go binary (see installer/windows/payload.go
# for the reading half): a Java runtime, a Node runtime, a PostgreSQL server, an embeddable Python,
# the twelve service jars against one shared library directory, the built web app, the AI service
# with its trained model, and the licences for all of it.
#
# One script for both targets rather than a PowerShell twin, and that is deliberate: the Windows
# runner has bash, and a payload build whose Linux and Windows halves are separate programs is a
# payload build where only one half is ever exercised. Running it here on Linux is what makes the
# Windows run in CI a re-run rather than a first run.
#
#   ./build-payload.sh --os linux              # exercised locally; a Linux MedSync-Setup
#   ./build-payload.sh --os windows            # what ships
#
set -euo pipefail

TARGET_OS="linux"
OUT=""
SKIP_WEB=0
SKIP_AI=0
SKIP_JAVA=0

while [ $# -gt 0 ]; do
  case "$1" in
    --os) TARGET_OS="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --skip-web) SKIP_WEB=1; shift ;;
    --skip-ai) SKIP_AI=1; shift ;;
    --skip-java) SKIP_JAVA=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD="$SRC/installer/payload/build"
STAGE="$BUILD/$TARGET_OS/payload"
OUT="${OUT:-$BUILD/$TARGET_OS/payload.zip}"
# Absolute, because pack() zips from inside the staging directory and a relative output path would
# land inside the payload it is building.
mkdir -p "$(dirname "$OUT")"
OUT="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"

# Versions pinned rather than "latest", for the reason every other version in this repository is
# pinned: a payload that changes under you turns a reproducible installer into a lottery.
NODE_VERSION="22.22.2"
PG_VERSION="16.9-1"
PYTHON_VERSION="3.11.9"

EXE=""
[ "$TARGET_OS" = "windows" ] && EXE=".exe"

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
note() { printf '    %s\n' "$*"; }

# Archive helpers, because the two machines this runs on do not agree on what is installed.
#
# Git for Windows ships a minimal MSYS2 with no `zip` and no `unzip`; what a GitHub windows-latest
# runner does have is 7-Zip on PATH. Rather than write a PowerShell twin of this script — the whole
# point of one script is that both targets exercise the same code — the two archive operations go
# through these, which prefer the Unix tools and fall back to 7z.
unpack() {  # unpack <archive> <dest>
  local archive="$1" dest="$2"
  mkdir -p "$dest"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$archive" -d "$dest"
  elif command -v 7z >/dev/null 2>&1; then
    7z x -y -o"$dest" "$archive" >/dev/null
  else
    echo "neither unzip nor 7z is available" >&2; exit 1
  fi
}

pack() {  # pack <output.zip> <directory>
  local out="$1" dir="$2"
  rm -f "$out"
  if command -v zip >/dev/null 2>&1; then
    # -X drops extra attributes that differ run to run; the payload should be reproducible.
    ( cd "$dir" && zip -q -r -X -9 "$out" . -x '.*' )
  elif command -v 7z >/dev/null 2>&1; then
    ( cd "$dir" && 7z a -tzip -mx=9 -bso0 -bsp0 "$out" ./* >/dev/null )
  else
    echo "neither zip nor 7z is available" >&2; exit 1
  fi
}

rm -rf "$STAGE"
mkdir -p "$STAGE"

# ---------------------------------------------------------------------------------------------
# 1. The services: twelve thin jars and one shared library directory.
#
# The measurement this whole bundle rests on: the twelve fat jars weigh 1,000 MB and carry 1,246
# dependency entries between them, but only 172 of those are distinct — 123 MB. Packaged thin
# (root pom's `thin` profile: ZIP layout, PropertiesLauncher, no BOOT-INF/lib) the same twelve
# weigh 4.6 MB, and one shared lib/ weighs 124 MB. 1,000 MB becomes 129 MB.
# ---------------------------------------------------------------------------------------------
if [ "$SKIP_JAVA" != "1" ]; then
  say "Building the service jars, thin"
  cd "$SRC"
  # Removed rather than `clean`ed: Maven's jar plugin treats an up-to-date jar as nothing to do, so
  # a fat jar left from an ordinary build is silently kept and the payload ships 1,000 MB. Found by
  # measuring the output and disbelieving it.
  rm -f services/*/target/*.jar services/*/target/*.jar.original
  # `install`, not `package`, and this is not a preference — it is a correctness fix found by
  # running the payload.
  #
  # `dependency:copy-dependencies -pl services/<x>` resolves that service's dependencies from the
  # local repository, not from the reactor. hms-common is a dependency of eleven services and a
  # module of this same build, so with `package` the pool gets whatever copy of hms-common happens
  # to be sitting in ~/.m2 — which on the machine this was written on was three days old and did
  # not contain ServiceUnavailableException. scheduling-service unpacked, started, and died on
  # NoClassDefFoundError for a class that is in the source tree and was in the jar built ten
  # minutes earlier. Nothing before the service tried to load it could have noticed.
  mvn -B -ntp -q -Pthin install -DskipTests

  mkdir -p "$STAGE/services" "$STAGE/lib" "$STAGE/classpath"
  for dir in "$SRC"/services/*/; do
    svc="$(basename "$dir")"
    jar="$(ls "$dir"target/"$svc"-*.jar 2>/dev/null | grep -v sources | head -1 || true)"
    [ -n "$jar" ] || continue
    cp "$jar" "$STAGE/services/$svc.jar"

    # Each service's dependencies are resolved into a directory of its own first, and then the
    # jars are pooled. Both halves matter:
    #
    #   the pool     one copy of each distinct jar, which is the 1,000 MB -> 124 MB saving
    #   the list     each service's EXACT classpath, which is what keeps it correct
    #
    # Resolving straight into a shared directory - which is what this did first - produces one
    # merged classpath for everybody, and that is not a tidier version of the same thing. It broke
    # immediately and loudly: the gateway is reactive (Spring Cloud Gateway) and the other eleven
    # are servlet (Spring MVC), so a merged directory put Spring Cloud Gateway in front of
    # identity-service, which refused to start with "Spring MVC found on classpath, which is
    # incompatible with Spring Cloud Gateway". The loud failure was the lucky case. The quiet one
    # is a service acquiring an auto-configuration it never declared a dependency on, and on a
    # clinical platform a silent behaviour change is the worse outcome by a distance.
    #
    # The installer reassembles the per-service directories at extraction time by hard-linking out
    # of the pool, so exact classpaths cost no extra bytes on disk or in this archive.
    rm -rf "$BUILD/deps"
    mvn -B -ntp -q dependency:copy-dependencies -pl "services/$svc" \
        -DoutputDirectory="$BUILD/deps" -DincludeScope=runtime
    : > "$STAGE/classpath/$svc.txt"
    for dep in "$BUILD/deps"/*.jar; do
      name="$(basename "$dep")"
      [ -f "$STAGE/lib/$name" ] || cp "$dep" "$STAGE/lib/$name"
      echo "$name" >> "$STAGE/classpath/$svc.txt"
    done
  done
  entries=$(cat "$STAGE"/classpath/*.txt | wc -l)
  note "$(ls "$STAGE/services" | wc -l) service jars"
  note "$entries classpath entries across them, $(ls "$STAGE/lib" | wc -l) distinct jars pooled"

  # The natives for platforms this payload is not for. Netty ships one jar per operating system and
  # architecture and Maven resolves all of them, so a Windows bundle otherwise carries Linux and
  # macOS shared objects it can never load.
  say "Pruning natives that are not $TARGET_OS"
  case "$TARGET_OS" in
    windows) KEEP="windows" ;;
    linux)   KEEP="linux-x86_64" ;;
  esac
  pruned=0
  for jar in "$STAGE"/lib/*.jar; do
    base="$(basename "$jar")"
    case "$base" in
      *linux*|*osx*|*aarch_64*|*aarch64*|*macos*)
        case "$base" in *"$KEEP"*) continue ;; esac
        rm -f "$jar"; pruned=$((pruned + 1)) ;;
    esac
  done
  note "removed $pruned native jar(s) for other platforms"

  # And out of the classpath lists, or every service would be launched against a jar that is no
  # longer there. PropertiesLauncher tolerates a missing entry silently, which is exactly why this
  # has to be done here rather than discovered later as a ClassNotFoundException.
  for list in "$STAGE"/classpath/*.txt; do
    while read -r name; do
      [ -f "$STAGE/lib/$name" ] && echo "$name"
    done < "$list" > "$list.kept"
    mv "$list.kept" "$list"
  done
fi

# ---------------------------------------------------------------------------------------------
# 2. The Java runtime, jlinked.
#
# A full JDK is ~330 MB and a full JRE ~180 MB; what the services need is neither. jlink builds a
# runtime image containing only the named modules, which for a Spring Boot service is java.se plus
# a handful of jdk.* the JVM loads reflectively and jdeps therefore cannot see.
#
# The module list is a decision, not a guess, and each entry is here because leaving it out breaks
# something specific:
#   java.se              the whole standard API; the services use JDBC, XML, management and more
#   jdk.crypto.ec        elliptic-curve TLS — without it HTTPS to any modern host fails at handshake
#   jdk.crypto.cryptoki  PKCS#11, for a deployment whose keys live in a token
#   jdk.unsupported      sun.misc.Unsafe, which Netty and Hibernate both reach for
#   jdk.management*      what /actuator/health and the JVM metrics are built on
#   jdk.zipfs            the ZipFileSystem the jar tooling and some resource loaders use
#   jdk.localedata       every locale; a hospital platform that renders only en-US dates is wrong
#   jdk.jdwp.agent       remote debugging, so a deployment can be attached to rather than replaced
# ---------------------------------------------------------------------------------------------
say "Building the Java runtime with jlink"
JAVA_MODULES="java.se,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.management,jdk.management.agent,jdk.zipfs,jdk.localedata,jdk.jdwp.agent,jdk.httpserver,jdk.naming.dns"
rm -rf "$STAGE/jre"
jlink --add-modules "$JAVA_MODULES" \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --include-locales=en,hi,bn,ta,te,mr,gu,kn,ml,pa \
      --output "$STAGE/jre"
note "jre: $(du -sh "$STAGE/jre" | cut -f1)"
"$STAGE/jre/bin/java$EXE" -version 2>&1 | head -1 | sed 's/^/    /' || true

# ---------------------------------------------------------------------------------------------
# 3. Node — the runtime only.
#
# One binary. Not npm, not npx, not a node_modules tree: nothing in a bundled install installs a
# package, and the web app is shipped as Next.js standalone output, which is a server.js plus the
# handful of modules it actually imports.
# ---------------------------------------------------------------------------------------------
say "Staging the Node runtime"
mkdir -p "$STAGE/node"
if [ "$TARGET_OS" = "windows" ]; then
  NODE_ZIP="$BUILD/node-v$NODE_VERSION-win-x64.zip"
  [ -f "$NODE_ZIP" ] || curl -fsSL -o "$NODE_ZIP" \
      "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-win-x64.zip"
  rm -rf "$BUILD/node-unzip"
  unpack "$NODE_ZIP" "$BUILD/node-unzip"
  cp "$BUILD/node-unzip/node-v$NODE_VERSION-win-x64/node.exe" "$STAGE/node/node.exe"
else
  cp "$(command -v node)" "$STAGE/node/node"
fi
note "node: $(du -sh "$STAGE/node" | cut -f1)"

# ---------------------------------------------------------------------------------------------
# 4. PostgreSQL — the server, pruned.
#
# bin, lib and share only. The full distribution carries headers, documentation, pgAdmin, the
# StackBuilder and a symbols directory, none of which a running cluster reads.
# ---------------------------------------------------------------------------------------------
say "Staging the PostgreSQL server"
mkdir -p "$STAGE/pgsql"
if [ "$TARGET_OS" = "windows" ]; then
  PG_ZIP="$BUILD/postgresql-$PG_VERSION-windows-x64-binaries.zip"
  [ -f "$PG_ZIP" ] || curl -fsSL -o "$PG_ZIP" \
      "https://get.enterprisedb.com/postgresql/postgresql-$PG_VERSION-windows-x64-binaries.zip"
  rm -rf "$BUILD/pg-unzip"
  unpack "$PG_ZIP" "$BUILD/pg-unzip"
  for part in bin lib share; do cp -r "$BUILD/pg-unzip/pgsql/$part" "$STAGE/pgsql/"; done
else
  PGHOME="$(ls -d /usr/lib/postgresql/*/ 2>/dev/null | sort -V | tail -1)"
  [ -n "$PGHOME" ] || { echo "no PostgreSQL server installed to stage" >&2; exit 1; }
  cp -r "$PGHOME/bin" "$STAGE/pgsql/"
  cp -r "$PGHOME/lib" "$STAGE/pgsql/" 2>/dev/null || true
  mkdir -p "$STAGE/pgsql/share"
  cp -r /usr/share/postgresql/*/. "$STAGE/pgsql/share/" 2>/dev/null || true
fi
note "pgsql: $(du -sh "$STAGE/pgsql" | cut -f1)"

# ---------------------------------------------------------------------------------------------
# 5. The web app: Next.js standalone output.
#
# `output: "standalone"` has been set in web/next.config.ts since the app was written and nothing
# used it — the installer ran `npx next start`, which needs the whole 614 MB node_modules tree at
# run time. The standalone build is 66 MB and is what `node server.js` reads.
# ---------------------------------------------------------------------------------------------
if [ "$SKIP_WEB" != "1" ]; then
  say "Building the web app (standalone)"
  cd "$SRC/web"
  [ -d node_modules ] || npm ci
  npm run build >/dev/null
  mkdir -p "$STAGE/web"
  cp -r .next/standalone/. "$STAGE/web/"
  # The two directories the standalone server does not copy itself, and both are load-bearing:
  # without .next/static every page renders unstyled and scriptless, and without public/ the
  # favicon and any static asset 404s.
  mkdir -p "$STAGE/web/.next"
  cp -r .next/static "$STAGE/web/.next/static"
  [ -d public ] && cp -r public "$STAGE/web/public"
  note "web: $(du -sh "$STAGE/web" | cut -f1)"
fi

# ---------------------------------------------------------------------------------------------
# 6. The AI service.
#
# Its own runtime, because the platform has never shipped one: ai-service was in no service table
# and nothing started it, while `hms.ai.enabled` defaulted to true. On Windows this is the
# embeddable CPython with wheels installed into it; on Linux a plain venv, which is the same shape.
# ---------------------------------------------------------------------------------------------
if [ "$SKIP_AI" != "1" ]; then
  say "Staging the AI service"
  mkdir -p "$STAGE/ai"
  # app, models AND data. The last one was missed first time round and the service died on
  # startup with FileNotFoundError for 'data/icd10_subset.json' — a relative path, resolved
  # against the working directory, which is this directory. Copying the source tree's three
  # content directories rather than guessing which ones are load-bearing.
  for part in app models data; do
    cp -r "$SRC/services/ai-service/$part" "$STAGE/ai/" 2>/dev/null || true
  done
  find "$STAGE/ai" -name __pycache__ -type d -prune -exec rm -rf {} + 2>/dev/null || true

  if [ "$TARGET_OS" = "windows" ]; then
    PY_ZIP="$BUILD/python-$PYTHON_VERSION-embed-amd64.zip"
    [ -f "$PY_ZIP" ] || curl -fsSL -o "$PY_ZIP" \
        "https://www.python.org/ftp/python/$PYTHON_VERSION/python-$PYTHON_VERSION-embed-amd64.zip"
    rm -rf "$STAGE/python"
    unpack "$PY_ZIP" "$STAGE/python"
    # The embeddable distribution ships with site-packages disabled: python311._pth lists the
    # search path and comments out `import site`, which is exactly what stops pip-installed
    # packages being importable. Enabling it is the documented way to use the embeddable build as
    # an application runtime.
    #
    # `..\ai` is on that path for a reason found on the first Windows run of this script, and it
    # is not a detail of the check that found it — the service could not have started either.
    # When a `._pth` file is present, CPython stops adding the working directory to sys.path and
    # ignores PYTHONPATH: the file IS the path. So `python -m uvicorn app.main:app` launched with
    # its working directory set to the AI service's own folder still answered
    # "ModuleNotFoundError: No module named 'app'". Entries here resolve against the directory
    # holding python.exe, so `..\ai` is the service's folder wherever the runtime is unpacked.
    #
    # A Linux run of this script cannot catch it: a virtualenv has no `._pth` and puts the working
    # directory on sys.path, so the identical code imports fine there.
    PTH="$(ls "$STAGE"/python/python*._pth | head -1)"
    printf 'python311.zip\n.\nLib\\site-packages\n..\\ai\nimport site\n' > "$PTH"
    mkdir -p "$STAGE/python/Lib/site-packages"
    python -m pip download --quiet --dest "$BUILD/wheels" --only-binary=:all: \
      --platform win_amd64 --python-version 3.11 --implementation cp \
      fastapi 'uvicorn[standard]' pydantic pydantic-settings anthropic scikit-learn numpy joblib 'pyjwt[crypto]'
    python -m pip install --quiet --no-deps --target "$STAGE/python/Lib/site-packages" "$BUILD"/wheels/*.whl
  else
    # A virtualenv, and it is worth being exact about what that is and is not: a venv references
    # the system interpreter's standard library through pyvenv.cfg, so the LINUX payload's Python
    # is not standalone the way the rest of it is. That is acceptable because the Linux payload
    # exists to exercise this script and the installer's logic, not to be shipped — the Windows
    # payload uses the embeddable distribution, which carries its own stdlib in python311.zip and
    # needs nothing from the machine. Said out loud rather than left for somebody to discover.
    rm -rf "$STAGE/python"
    python3 -m venv "$STAGE/python"
    "$STAGE/python/bin/pip" install --quiet \
      fastapi 'uvicorn[standard]' pydantic pydantic-settings anthropic scikit-learn numpy joblib 'pyjwt[crypto]'
  fi
  # Pruning, and this is the biggest single lever in the whole payload. scikit-learn pulls scipy,
  # which pulls numpy, and between them they are more than half the uncompressed bundle — most of
  # it test suites, bytecode caches and debug symbols that a running service never opens.
  #
  # Measured before writing this: the unpruned Linux virtualenv is 379 MB against a 803 MB payload.
  # What is removed and why:
  #   __pycache__     regenerated on first import; shipping it caches nothing across machines
  #   tests/          scipy and numpy ship their own suites, tens of megabytes, run by nobody here
  #   *.pyi, docs     type stubs and documentation, read by editors rather than by an interpreter
  #   debug symbols   stripped from the shared objects, which is most of what is left
  say "Pruning the Python runtime"
  before=$(du -sm "$STAGE/python" | cut -f1)
  find "$STAGE/python" -name '__pycache__' -type d -prune -exec rm -rf {} + 2>/dev/null || true
  # `tests` only, and NOT `testing` or `test`. Found by running it: numpy.testing is a public API
  # module, not a test suite, and scipy imports it on the way up — removing it produced
  # "ModuleNotFoundError: No module named 'numpy.testing'" from a service that had started fine
  # five minutes earlier. A pruning rule that guesses from a directory's name is exactly the kind
  # of optimisation that has to be run rather than reasoned about.
  find "$STAGE/python" -type d -name tests -prune -exec rm -rf {} + 2>/dev/null || true
  find "$STAGE/python" -type f \( -name '*.pyi' -o -name '*.c' -o -name '*.h' -o -name '*.pyx' \) -delete 2>/dev/null || true
  if [ "$TARGET_OS" != "windows" ] && command -v strip >/dev/null 2>&1; then
    find "$STAGE/python" -name '*.so' -exec strip --strip-unneeded {} + 2>/dev/null || true
  fi
  after=$(du -sm "$STAGE/python" | cut -f1)
  note "python: ${before} MB -> ${after} MB"
  note "ai: $(du -sh "$STAGE/ai" | cut -f1)"

  # Prove the pruned runtime still works, here, rather than finding out when a service fails to
  # start on somebody's laptop. This exists because the first version of the pruning above removed
  # numpy.testing — a public API module that scipy imports — and the payload was sealed, shipped and
  # unpacked before anything said so.
  #
  # Skipped when cross-building the Windows payload from Linux, where these wheels cannot be
  # executed at all; the Windows runner runs the same line against the real interpreter.
  if [ "$TARGET_OS" = "$(uname -s | tr 'A-Z' 'a-z' | sed 's/mingw.*/windows/;s/msys.*/windows/')" ] || [ "$TARGET_OS" = "linux" ]; then
    say "Checking the bundled Python still imports what the service needs"
    PY="$STAGE/python/bin/python"
    [ -x "$PY" ] || PY="$STAGE/python/python$([ "$TARGET_OS" = windows ] && echo .exe)"
    ( cd "$STAGE/ai" && "$PY" -c "
import numpy, scipy, sklearn, joblib, fastapi, uvicorn, numpy.testing
import app.main
print('    numpy', numpy.__version__, 'scipy', scipy.__version__, 'sklearn', sklearn.__version__)
print('    the AI service imports')
" ) || { echo "the bundled Python cannot import what the AI service needs" >&2; exit 1; }
  fi
fi

# ---------------------------------------------------------------------------------------------
# 7. Licences.
#
# An obligation this project did not have until today. Building on the user's machine
# redistributed nothing; a payload redistributes a JDK (GPLv2 with the Classpath Exception), Node
# (MIT), PostgreSQL (the PostgreSQL licence), CPython, roughly 172 Java libraries and a set of
# Python wheels, and every one of those carries an attribution requirement. The repository had no
# LICENSE file and no licenses/ directory at all before this, so these are generated rather than
# collected.
# ---------------------------------------------------------------------------------------------
say "Collecting licences"
mkdir -p "$STAGE/licenses"
[ -f "$STAGE/jre/legal/java.base/LICENSE" ] && cp -r "$STAGE/jre/legal" "$STAGE/licenses/java" 2>/dev/null || true
cp "$SRC/NOTICE" "$STAGE/licenses/MedSync-NOTICE.txt" 2>/dev/null || true
{
  echo "MedSync — third-party notices"
  echo "Generated by installer/payload/build-payload.sh for the $TARGET_OS payload."
  echo
  echo "This installer redistributes the following. Each component remains under its own licence;"
  echo "nothing here changes those terms, and the full texts are in the licenses/ directory beside"
  echo "this file."
  echo
  echo "  OpenJDK (Eclipse Temurin) 21   GPLv2 with the Classpath Exception"
  echo "  Node.js $NODE_VERSION                MIT"
  echo "  PostgreSQL $PG_VERSION            PostgreSQL Licence"
  echo "  CPython $PYTHON_VERSION                Python Software Foundation Licence"
  echo
  echo "Java libraries bundled in lib/:"
  ls "$STAGE/lib" 2>/dev/null | sed 's/^/  /'
  echo
  echo "Python packages bundled for the AI service:"
  ls "$STAGE/python/Lib/site-packages" 2>/dev/null | grep -E 'dist-info$' | sed 's/\.dist-info$//' | sed 's/^/  /' || \
    ls "$STAGE"/python/lib/python*/site-packages 2>/dev/null | grep -E 'dist-info$' | sed 's/\.dist-info$//' | sed 's/^/  /' || true
} > "$STAGE/THIRD-PARTY-NOTICES.txt"
note "$(wc -l < "$STAGE/THIRD-PARTY-NOTICES.txt") lines of notices"

# ---------------------------------------------------------------------------------------------
# 8. The payload's identity, and the archive.
#
# PAYLOAD-ID is a sha256 over a manifest of every file's own sha256, which makes it a content hash
# of the payload that can be computed before the archive exists — the archive cannot contain its
# own hash. The installer reads it to name the directory it unpacks into, so two different payloads
# never share one and a re-run of the same installer skips unpacking entirely.
# ---------------------------------------------------------------------------------------------
say "Sealing the payload"
cd "$STAGE"
find . -type f ! -name PAYLOAD-ID -print0 | sort -z | xargs -0 sha256sum > "$BUILD/manifest.txt"
sha256sum < "$BUILD/manifest.txt" | cut -d' ' -f1 > "$STAGE/PAYLOAD-ID"
note "payload id $(cut -c1-16 "$STAGE/PAYLOAD-ID")"

pack "$OUT" "$STAGE"
say "Payload: $OUT ($(du -sh "$OUT" | cut -f1) compressed, $(du -sh "$STAGE" | cut -f1) unpacked)"
