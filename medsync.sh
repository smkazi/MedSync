#!/usr/bin/env bash
# MedSync - one file that installs the platform, starts it, checks it and opens it in a browser.
#
#   curl -fsSLO <raw url>/medsync.sh && bash medsync.sh up
#
# or, from inside a checkout:
#
#   ./medsync.sh up
#
# ---------------------------------------------------------------------------------------------
# Why a script and not an installer package
#
# MedSync is twelve JVM services, a Python service, a Next.js app and a PostgreSQL database. A
# single binary or a fat jar could hold the Java half and could not hold the other three, so an
# "installer" for this platform is necessarily a thing that orchestrates other tools rather than a
# thing that contains the product. This script is that orchestration, and it is one file so it can
# be handed to somebody as one file.
#
# What it deliberately does NOT do: install anything system-wide, run a package manager, or ask for
# sudo. `doctor` names what is missing and the exact command to install it on the detected platform,
# and then stops. Software that installs Java on somebody's laptop without being asked is software
# nobody should run, and a script that needs root to be evaluated is a script that will not be
# evaluated. Everything this script creates lives under one directory it owns and `uninstall`
# removes.
# ---------------------------------------------------------------------------------------------

set -euo pipefail

# Bash 3.2 (what macOS ships) is the floor: no associative arrays, no ${var^^}, no mapfile below.
# Worth the constraint - the alternative is telling a Mac user to install a shell before they can
# install the thing they came for.

VERSION="1.0.0"
REPO_URL="${MEDSYNC_REPO:-https://github.com/smkazi/MedSync.git}"
REPO_REF="${MEDSYNC_REF:-claude/hospital-management-repo-cugrud}"

# Everything this script owns. One directory, so `uninstall` is one `rm -rf` and there is no
# question about what was left behind.
HOME_DIR="${MEDSYNC_HOME:-$HOME/.medsync}"

# ---- service map -----------------------------------------------------------------------------
# Held as parallel space-separated lists rather than an associative array, for the bash 3.2 reason
# above. Order matters: identity first because every other service validates its tokens against
# it, and the gateway last because it health-checks what it routes to.
JAVA_SERVICES="identity-service:8081 patient-service:8082 scheduling-service:8083 \
laboratory-service:8084 notification-service:8085 admissions-service:8086 pharmacy-service:8087 \
billing-service:8088 interop-service:8089 imaging-service:8090 immunisation-service:8091 \
gateway:8080"

WEB_PORT="${MEDSYNC_WEB_PORT:-3000}"
GATEWAY_PORT=8080
AI_PORT=8000

# ---- output ----------------------------------------------------------------------------------
# Colour only when a terminal is attached, so the log a CI job or a `tee` captures stays readable.
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  C_DIM=$'\033[2m'; C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YLW=$'\033[33m'
  C_BLU=$'\033[34m'; C_BLD=$'\033[1m'; C_OFF=$'\033[0m'
else
  C_DIM=""; C_RED=""; C_GRN=""; C_YLW=""; C_BLU=""; C_BLD=""; C_OFF=""
fi

say()  { printf '%s\n' "$*"; }
step() { printf '\n%s==>%s %s%s%s\n' "$C_BLU" "$C_OFF" "$C_BLD" "$*" "$C_OFF"; }
ok()   { printf '    %s✓%s %s\n' "$C_GRN" "$C_OFF" "$*"; }
warn() { printf '    %s!%s %s\n' "$C_YLW" "$C_OFF" "$*"; }
bad()  { printf '    %s✗%s %s\n' "$C_RED" "$C_OFF" "$*"; }
dim()  { printf '      %s%s%s\n' "$C_DIM" "$*" "$C_OFF"; }
die()  { printf '\n%serror:%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }

have() { command -v "$1" >/dev/null 2>&1; }

# ---- platform --------------------------------------------------------------------------------
OS="$(uname -s)"
case "$OS" in
  Darwin) PLATFORM=macos ;;
  Linux)  PLATFORM=linux ;;
  *)      PLATFORM=other ;;
esac

# How to install a missing prerequisite on this machine. Printed rather than run.
install_hint() {
  case "$PLATFORM" in
    macos) printf 'brew install %s\n' "$1" ;;
    linux)
      if   have apt-get; then printf 'sudo apt-get install -y %s\n' "$1"
      elif have dnf;     then printf 'sudo dnf install -y %s\n' "$1"
      elif have pacman;  then printf 'sudo pacman -S %s\n' "$1"
      else                    printf 'install %s with your package manager\n' "$1"
      fi ;;
    *) printf 'install %s\n' "$1" ;;
  esac
}

# ---- prerequisites ---------------------------------------------------------------------------
# Each row: the command, the minimum version, the package name to suggest, and whether the platform
# runs without it. "Optional" is a real category here and not a hedge: the AI service backs
# note summarisation and ICD-10 suggestions, and every screen that uses it degrades to a plain text
# box rather than erroring, so a run without Python is a smaller MedSync and not a broken one.

version_of() {
  case "$1" in
    # grep for the version line rather than head -1: a JAVA_TOOL_OPTIONS in the environment makes
    # the JVM print a "Picked up ..." line first, and matching on that reported no version at all.
    java) java -version 2>&1 | grep -m1 'version "' | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' ;;
    mvn)  mvn -v 2>/dev/null | head -1 | sed -n 's/Apache Maven \([0-9.]*\).*/\1/p' ;;
    node) node -v 2>/dev/null | sed 's/^v//' ;;
    psql) psql --version 2>/dev/null | sed -n 's/psql (PostgreSQL) \([0-9.]*\).*/\1/p' ;;
    git)  git --version 2>/dev/null | sed -n 's/git version \([0-9.]*\).*/\1/p' ;;
    uv)   uv --version 2>/dev/null | sed -n 's/uv \([0-9.]*\).*/\1/p' ;;
    *)    echo "" ;;
  esac
}

# Numeric compare of dotted versions: is $1 >= $2? Pure sort, no bc, no python.
version_at_least() {
  [ -n "$1" ] || return 1
  [ "$(printf '%s\n%s\n' "$2" "$1" | sort -t. -k1,1n -k2,2n -k3,3n | head -1)" = "$2" ]
}

MISSING_REQUIRED=0

check_one() {
  local cmd="$1" min="$2" pkg="$3" need="$4" label="$5"
  local got
  if ! have "$cmd"; then
    if [ "$need" = required ]; then
      bad "$label - not found"; dim "$(install_hint "$pkg")"; MISSING_REQUIRED=$((MISSING_REQUIRED + 1))
    else
      warn "$label - not found (optional)"; dim "$(install_hint "$pkg")"
    fi
    return 1
  fi
  got="$(version_of "$cmd")"
  if [ -n "$min" ] && [ -n "$got" ] && ! version_at_least "$got" "$min"; then
    if [ "$need" = required ]; then
      bad "$label $got - need $min or newer"; MISSING_REQUIRED=$((MISSING_REQUIRED + 1))
    else
      warn "$label $got - need $min or newer (optional)"
    fi
    return 1
  fi
  ok "$label ${got:-present}"
}

cmd_doctor() {
  step "Prerequisites"
  check_one java 21    openjdk@21   required "Java"       || true
  check_one mvn  3.9   maven        optional "Maven (the bundled wrapper covers it)" || true
  check_one node 22    node         required "Node"       || true
  check_one git  2.0   git          required "git"        || true
  check_one curl ""    curl         required "curl"       || true
  # Through pg_bin, because Debian and Homebrew both keep the PostgreSQL binaries off PATH and a
  # `have psql` there reports "not installed" on a machine that has a whole server.
  if pg_bin psql >/dev/null; then
    ok "PostgreSQL client $("$(pg_bin psql)" --version | sed -n 's/.*) \([0-9.]*\).*/\1/p')"
  else
    warn "PostgreSQL client - not found (optional; only the database ladder needs it)"
    dim "$(install_hint postgresql@16)"
  fi
  check_one uv   ""    uv           optional "uv (Python, for the AI service)" || true

  step "Database"
  db_report

  step "Ports"
  local p taken=0 ours=0
  for p in $WEB_PORT $AI_PORT 8080 8081 8082 8083 8084 8085 8086 8087 8088 8089 8090 8091; do
    if ! port_busy "$p"; then
      ok "$p free"
    elif pid_file_for_port "$p" >/dev/null; then
      ok "$p this MedSync"
      ours=$((ours + 1))
    else
      warn "$p in use by something this script did not start"
      taken=$((taken + 1))
    fi
  done

  say ""
  if [ "$MISSING_REQUIRED" -gt 0 ]; then
    die "$MISSING_REQUIRED required prerequisite(s) missing - install them and run 'doctor' again."
  fi
  if [ "$taken" -gt 0 ]; then
    # Not fatal and not glossed over: it may be a MedSync somebody started by hand, or it may be
    # another application, and only the person at the keyboard can tell which.
    warn "$taken port(s) already occupied. Free them, or '$0 down' if a previous run left them."
    say ""
    exit 1
  fi
  if [ "$ours" -gt 0 ]; then ok "MedSync is already running on $ours port(s)."; fi
  ok "Ready."
}

# ---- ports -----------------------------------------------------------------------------------
# /dev/tcp rather than lsof or ss: neither is installed everywhere and bash's own redirection is.
# The connection is opened inside a subshell, so it is closed by that subshell exiting and there is
# no descriptor left dangling in this one.
port_busy() {
  (exec 3<>"/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1
}

pid_file_for_port() {
  local f
  for f in "$RUN_DIR"/*.port; do
    [ -f "$f" ] || continue
    if [ "$(cat "$f")" = "$1" ]; then echo "$f"; return 0; fi
  done
  return 1
}

wait_http() {
  local url="$1" label="$2" tries="${3:-90}" i=1
  while [ "$i" -le "$tries" ]; do
    if curl -fsS -o /dev/null "$url" 2>/dev/null; then ok "$label"; return 0; fi
    sleep 2
    i=$((i + 1))
  done
  bad "$label - did not answer $url"
  return 1
}

# ---- source ----------------------------------------------------------------------------------
# The script may be sitting in a checkout (run from the repo) or entirely on its own (curled down
# and run from a downloads folder). Both are supported, and which one happened is stated rather
# than guessed at, because "it built something other than the tree I am editing" is a confusing
# half hour.
#
# `find_source` never clones and never fails, for the commands that must work when the network is
# gone and there is nothing left to fetch - stopping a stack should not depend on GitHub.
find_source() {
  local here
  here="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
  if [ -f "$here/pom.xml" ] && [ -d "$here/services/identity-service" ]; then SRC="$here"; return 0; fi
  if [ -f "$HOME_DIR/src/pom.xml" ]; then SRC="$HOME_DIR/src"; return 0; fi
  SRC=""
  return 1
}

resolve_source() {
  local here
  here="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
  if [ -f "$here/pom.xml" ] && [ -d "$here/services/identity-service" ]; then
    SRC="$here"
    ok "Source: this checkout ($SRC)"
    return
  fi
  SRC="$HOME_DIR/src"
  if [ -d "$SRC/.git" ]; then
    ok "Source: $SRC (updating)"
    git -C "$SRC" fetch --quiet origin "$REPO_REF" || warn "fetch failed - building what is on disk"
    git -C "$SRC" checkout --quiet "$REPO_REF" 2>/dev/null || true
    git -C "$SRC" reset --hard --quiet "origin/$REPO_REF" 2>/dev/null || true
  else
    ok "Source: cloning $REPO_URL#$REPO_REF into $SRC"
    mkdir -p "$(dirname "$SRC")"
    git clone --quiet --branch "$REPO_REF" --depth 1 "$REPO_URL" "$SRC" \
      || die "clone failed. Set MEDSYNC_REPO if this repository is private and you have a URL that works."
  fi
}

# RUN_DIR holds pids, logs, the private database cluster and the generated secrets. Set before
# resolve_source so `doctor` can read it without a checkout.
RUN_DIR="$HOME_DIR/run"
ENV_FILE="$HOME_DIR/medsync.env"
mkdir -p "$RUN_DIR"

# ---- secrets ---------------------------------------------------------------------------------
# Generated once, on this machine, and kept out of the repository. Two of them cannot be
# regenerated freely and that is the whole reason this is a file rather than a fresh value each
# run: HMS_PHI_KEY decrypts the encrypted patient identifiers already in the database, so a new key
# on the second run makes those columns permanently unreadable. The platform starts without it
# using a built-in development key of 32 zero bytes and says so loudly in the log; generating a
# real one here means a local instance is not quietly demonstrating the insecure path.
ensure_env() {
  if [ -f "$ENV_FILE" ]; then
    ok "Secrets: $ENV_FILE (existing)"
    return
  fi
  local phi
  if have openssl; then
    phi="$(openssl rand -base64 32)"
  else
    # /dev/urandom through base64 is the same 32 bytes; openssl is a convenience, not a dependency.
    phi="$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
  fi
  # umask inside a subshell: setting it here directly would tighten every file and directory the
  # rest of the run creates, including the database cluster a non-root PostgreSQL then cannot read.
  ( umask 077
  cat > "$ENV_FILE" <<EOF
# Generated by medsync.sh $(date -u +%Y-%m-%dT%H:%M:%SZ). Machine-local; never commit this file.
#
# HMS_PHI_KEY decrypts the encrypted patient identifier columns. Changing it makes every row
# already written unreadable, which is why it is generated once and then left alone.
HMS_PHI_KEY=$phi
EOF
  )
  ok "Secrets: generated $ENV_FILE"
}

# ---- database --------------------------------------------------------------------------------
# The ladder, in order, and the order is the argument:
#
#   1. HMS_DB_URL already set and reachable   - the operator has told us where the database is, and
#                                               second-guessing that is how a script writes to the
#                                               wrong server.
#   2. a PostgreSQL server already listening  - a developer machine usually has one, and starting a
#      on 5432                                  second cluster beside it wastes a gigabyte and
#                                               confuses every psql they type afterwards.
#   3. a private cluster this script owns     - initdb into RUN_DIR on a port of its own. Needs no
#      (initdb/pg_ctl present)                  root, touches nothing else, and `uninstall` deletes
#                                               it. This is the good path on a clean machine.
#   4. a Docker container                     - last because it needs a daemon and because a
#                                               container's data directory outliving the container
#                                               surprises people.
#
# Anything found is reported with which rung it came from, so "it connected to something" is never
# a mystery.
DB_PORT=""
DB_MODE=""
CLUSTER_DIR="$RUN_DIR/pgdata"

db_url() { echo "jdbc:postgresql://127.0.0.1:$DB_PORT/hms"; }

db_reachable() { port_busy "$1"; }

db_report() {
  if [ -n "${HMS_DB_URL:-}" ]; then
    ok "HMS_DB_URL is set - using it: $HMS_DB_URL"
  elif db_reachable 5432; then
    ok "PostgreSQL listening on 5432 - will use it"
  elif [ -f "$RUN_DIR/db.dir" ] && [ -d "$(cat "$RUN_DIR/db.dir")/base" ]; then
    ok "Private cluster at $(cat "$RUN_DIR/db.dir")"
  elif pg_bin initdb >/dev/null && pg_bin pg_ctl >/dev/null && { [ "$(id -u)" != 0 ] || id postgres >/dev/null 2>&1; }; then
    ok "PostgreSQL server binaries at $(dirname "$(pg_bin initdb)") - a private cluster will be created"
    if [ "$(id -u)" = 0 ]; then dim "running as root, so it will be created and run as the postgres user"; fi
  elif have docker; then
    warn "No PostgreSQL - a Docker container will be used"
  else
    bad "No PostgreSQL and no Docker"
    dim "$(install_hint postgresql@16)"
    MISSING_REQUIRED=$((MISSING_REQUIRED + 1))
  fi
}

# Locate initdb/pg_ctl even when the distribution keeps them off PATH, which Debian and Homebrew
# both do. Without this the script would fall through to Docker on a machine that has PostgreSQL.
pg_bin() {
  local name="$1" d
  if have "$name"; then command -v "$name"; return 0; fi
  for d in /usr/lib/postgresql/*/bin /usr/pgsql-*/bin /opt/homebrew/opt/postgresql@*/bin \
           /usr/local/opt/postgresql@*/bin /Library/PostgreSQL/*/bin; do
    [ -x "$d/$name" ] && { echo "$d/$name"; return 0; }
  done
  return 1
}

#
# PostgreSQL refuses to run as root, and that is not a corner case: a container or a CI job is
# usually root, and this is where the first real run of this script fell over. So when the caller is
# root the cluster is created and started as the `postgres` system user instead, which means the
# data directory has to live somewhere that user can traverse - /root/.medsync is 0700 and it
# cannot. Without a `postgres` user there is nothing to drop to, and the ladder moves on to Docker.
PG_AS=""
pg_as_setup() {
  if [ "$(id -u)" != 0 ]; then PG_AS=""; return 0; fi
  if id postgres >/dev/null 2>&1; then
    PG_AS=postgres
    CLUSTER_DIR="${MEDSYNC_CLUSTER_DIR:-/var/lib/postgresql/medsync-pgdata}"
    # Beside the cluster rather than in RUN_DIR: the postgres user has to be able to write it, and
    # RUN_DIR is under a home directory it cannot enter.
    PG_LOG="$CLUSTER_DIR.log"
    return 0
  fi
  warn "running as root with no 'postgres' user - a private cluster is not possible"
  return 1
}

# Run a PostgreSQL binary, dropping to the postgres user when this script is root.
pg_run() {
  if [ -n "$PG_AS" ]; then
    su "$PG_AS" -c "$*"
  else
    eval "$*"
  fi
}

db_start_private_cluster() {
  local initdb pg_ctl
  initdb="$(pg_bin initdb)" || return 1
  pg_ctl="$(pg_bin pg_ctl)" || return 1
  pg_as_setup || return 1
  PG_LOG="${PG_LOG:-$RUN_DIR/postgres.log}"

  # A port of its own, so this cluster can never be mistaken for the machine's own PostgreSQL and
  # a stray `psql -p 5432` cannot write into it.
  DB_PORT="${MEDSYNC_DB_PORT:-55432}"

  if [ ! -d "$CLUSTER_DIR/base" ]; then
    step "Creating a private PostgreSQL cluster"
    mkdir -p "$CLUSTER_DIR"
    if [ -n "$PG_AS" ]; then chown "$PG_AS" "$CLUSTER_DIR"; chmod 700 "$CLUSTER_DIR"; fi
    # trust auth on loopback only: this cluster listens on 127.0.0.1, holds nothing but demo data,
    # and a password prompt in an installer is a password written into a script somewhere.
    pg_run "'$initdb' -D '$CLUSTER_DIR' -U hms --auth-local=trust --auth-host=trust" >/dev/null \
      || { warn "initdb failed"; return 1; }
    ok "cluster created at $CLUSTER_DIR"
  fi

  if ! db_reachable "$DB_PORT"; then
    : > "$PG_LOG"
    if [ -n "$PG_AS" ]; then chown "$PG_AS" "$PG_LOG"; fi
    pg_run "'$pg_ctl' -D '$CLUSTER_DIR' -l '$PG_LOG' \
      -o '-p $DB_PORT -c listen_addresses=127.0.0.1 -k $CLUSTER_DIR' start" >/dev/null \
      || { warn "could not start the cluster - see $PG_LOG"; return 1; }
    local i=1
    while [ "$i" -le 30 ]; do db_reachable "$DB_PORT" && break; sleep 1; i=$((i + 1)); done
  fi
  db_reachable "$DB_PORT" || { warn "the cluster did not come up - see $PG_LOG"; return 1; }
  DB_MODE=private
  echo "$DB_PORT"     > "$RUN_DIR/db.port"
  echo private        > "$RUN_DIR/db.mode"
  echo "$CLUSTER_DIR" > "$RUN_DIR/db.dir"
  ok "PostgreSQL up on $DB_PORT (private cluster at $CLUSTER_DIR)"
}

db_start_docker() {
  have docker || return 1
  DB_PORT="${MEDSYNC_DB_PORT:-55432}"
  if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^medsync-db$'; then
    if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -q '^medsync-db$'; then
      docker start medsync-db >/dev/null
    else
      step "Starting PostgreSQL in Docker"
      docker run -d --name medsync-db -p "127.0.0.1:$DB_PORT:5432" \
        -e POSTGRES_USER=hms -e POSTGRES_PASSWORD=hms -e POSTGRES_DB=hms \
        postgres:16 >/dev/null || die "docker run failed"
    fi
  fi
  local i=1
  while [ "$i" -le 60 ]; do db_reachable "$DB_PORT" && break; sleep 1; i=$((i + 1)); done
  db_reachable "$DB_PORT" || die "the database container did not accept connections"
  DB_MODE=docker
  echo "$DB_PORT" > "$RUN_DIR/db.port"
  echo docker    > "$RUN_DIR/db.mode"
  ok "PostgreSQL up on $DB_PORT (container medsync-db)"
}

# The database, the role and the two extensions. Superuser is needed once, for pg_trgm and
# btree_gist; Flyway creates and migrates all thirteen schemas itself on first start, which is why
# there is no schema step here and no migration files to copy anywhere.
db_prepare() {
  local psql_bin
  psql_bin="$(pg_bin psql)" || psql_bin=""
  if [ -z "$psql_bin" ] && [ "$DB_MODE" = docker ]; then
    docker exec medsync-db psql -U hms -d hms -c \
      'create extension if not exists pg_trgm; create extension if not exists btree_gist;' >/dev/null \
      && { ok "database hms ready (extensions installed)"; return 0; }
    warn "could not install the extensions - the first service to migrate will fail and say which"
    return 0
  fi
  [ -n "$psql_bin" ] || { warn "no psql on PATH - skipping database preparation"; return 0; }

  local admin="postgresql://hms@127.0.0.1:$DB_PORT/postgres"
  [ "$DB_MODE" = docker ] && admin="postgresql://hms:hms@127.0.0.1:$DB_PORT/postgres"

  "$psql_bin" "$admin" -tAc "select 1 from pg_database where datname='hms'" 2>/dev/null | grep -q 1 \
    || "$psql_bin" "$admin" -q -c 'create database hms' >/dev/null 2>&1 || true

  local target="postgresql://hms@127.0.0.1:$DB_PORT/hms"
  [ "$DB_MODE" = docker ] && target="postgresql://hms:hms@127.0.0.1:$DB_PORT/hms"
  if "$psql_bin" "$target" -q \
       -c 'create extension if not exists pg_trgm' \
       -c 'create extension if not exists btree_gist' >/dev/null 2>&1; then
    ok "database hms ready (extensions installed)"
  else
    warn "could not install pg_trgm/btree_gist as 'hms'"
    dim "run these once as a superuser, then start again:"
    dim "  psql -p $DB_PORT -d hms -c 'create extension pg_trgm; create extension btree_gist;'"
  fi
}

db_up() {
  if [ -n "${HMS_DB_URL:-}" ]; then
    DB_MODE=external
    ok "Database: HMS_DB_URL from the environment"
    return
  fi
  if [ -f "$RUN_DIR/db.mode" ] && [ -f "$RUN_DIR/db.port" ]; then
    DB_MODE="$(cat "$RUN_DIR/db.mode")"; DB_PORT="$(cat "$RUN_DIR/db.port")"
    if [ -f "$RUN_DIR/db.dir" ]; then export MEDSYNC_CLUSTER_DIR="$(cat "$RUN_DIR/db.dir")"; fi
    if [ "$DB_MODE" = private ] && ! db_reachable "$DB_PORT"; then db_start_private_cluster; return; fi
    if [ "$DB_MODE" = docker ]  && ! db_reachable "$DB_PORT"; then db_start_docker;          return; fi
    if db_reachable "$DB_PORT"; then ok "Database: $DB_MODE cluster on $DB_PORT"; return; fi
  fi
  if db_reachable 5432; then
    DB_PORT=5432; DB_MODE=existing
    echo 5432     > "$RUN_DIR/db.port"
    echo existing > "$RUN_DIR/db.mode"
    ok "Database: the PostgreSQL already running on 5432"
    return
  fi
  db_start_private_cluster || db_start_docker \
    || die "no way to get a PostgreSQL. Install one ($(install_hint postgresql@16)), or start Docker."
}

# ---- the environment every service is started with -------------------------------------------
# Exported once, here, so the Java services, the web app and the test suites cannot disagree about
# which database they are talking to - the failure mode being a suite that passes against an empty
# schema nobody is looking at.
export_stack_env() {
  # shellcheck disable=SC1090
  [ -f "$ENV_FILE" ] && . "$ENV_FILE" && export HMS_PHI_KEY

  if [ -z "${HMS_DB_URL:-}" ]; then
    export HMS_DB_URL="$(db_url)"
  fi
  export HMS_DB_USER="${HMS_DB_USER:-hms}"
  export HMS_DB_PASSWORD="${HMS_DB_PASSWORD:-hms}"
  export HMS_SEED_ENABLED=true
  export HMS_EVENTS_TRANSPORT="${HMS_EVENTS_TRANSPORT:-log}"
  export HMS_ZONE="${HMS_ZONE:-Asia/Kolkata}"
  export HMS_PROFILE=dev
  export HMS_RUN_DIR="$RUN_DIR"
}

# ---- build -----------------------------------------------------------------------------------
# Which Maven to run, and it is not necessarily an installed one.
#
# The repository ships a Maven wrapper, so a machine with no Maven builds this perfectly well -
# `./mvnw` fetches the exact version the project pins into the user's own ~/.m2 and runs it. That is
# why Maven moved out of the required prerequisites: a real Windows install stopped for want of a
# Maven the project does not require, which is a refusal that is correct by its own rules and wrong
# about the world. An installed Maven still wins when there is one, because it needs no download.
maven_cmd() {
  if have mvn && version_at_least "$(version_of mvn)" 3.9; then
    echo "mvn"
  elif [ -x "$SRC/mvnw" ]; then
    echo "$SRC/mvnw"
  else
    echo "mvn"
  fi
}

build_java() {
  step "Building the Java modules"
  dim "using $(maven_cmd)"
  dim "first run downloads the Maven dependencies - several minutes"
  ( cd "$SRC" && "$(maven_cmd)" -q -B package -DskipTests ) || die "the Java build failed"
  ok "$(ls "$SRC"/services/*/target/*.jar 2>/dev/null | grep -vc sources || echo 0) service jars built"
}

build_web() {
  step "Building the web app"
  ( cd "$SRC/web" && { [ -d node_modules ] || npm ci --silent 2>/dev/null || npm install --silent; } ) \
    || die "npm install failed"
  ( cd "$SRC/web" && GATEWAY_URL="http://127.0.0.1:$GATEWAY_PORT" \
      IDENTITY_URL="http://127.0.0.1:8081" NEXT_PUBLIC_HMS_ZONE="${HMS_ZONE:-Asia/Kolkata}" \
      npm run build >"$RUN_DIR/web-build.log" 2>&1 ) \
    || { tail -30 "$RUN_DIR/web-build.log"; die "the web build failed - full log in $RUN_DIR/web-build.log"; }
  ok "web app built"
}

build_ai() {
  if ! have uv; then
    warn "uv not installed - skipping the AI service"
    dim "note summarisation and the ICD-10 suggest box fall back to their deterministic paths;"
    dim "every screen still works. $(install_hint uv)"
    return 1
  fi
  step "Preparing the AI service"
  ( cd "$SRC/services/ai-service" && uv sync --quiet ) || { warn "uv sync failed - skipping the AI service"; return 1; }
  ok "AI service ready"
}

# ---- start / stop ----------------------------------------------------------------------------
start_java() {
  step "Starting the Java services"
  # scripts/local.sh is the path that has actually been exercised in this repository - it resolves
  # each service's own database role, waits on /actuator/health and writes a pid file. Reimplementing
  # that here would give the platform two start paths that drift apart, and the one that drifts is
  # always the one nobody runs.
  ( cd "$SRC" && scripts/local.sh start ) || die "one or more services did not start - try '$0 logs identity-service'"
  local entry
  for entry in $JAVA_SERVICES; do
    echo "${entry##*:}" > "$RUN_DIR/${entry%%:*}.port"
  done
}

# setsid where it exists: without it the background process stays in this shell's process group and
# dies with the terminal, which is how a "started" stack is gone by the time somebody opens a
# browser.
spawn() {
  local log="$1"; shift
  if have setsid; then
    setsid nohup "$@" >"$log" 2>&1 < /dev/null &
  else
    nohup "$@" >"$log" 2>&1 < /dev/null &
  fi
  echo $!
}

start_ai() {
  have uv || return 0
  [ -d "$SRC/services/ai-service/.venv" ] || return 0
  if port_busy "$AI_PORT"; then ok "ai-service already on $AI_PORT"; return 0; fi
  step "Starting the AI service"
  local pid
  pid="$( cd "$SRC/services/ai-service" && spawn "$RUN_DIR/ai-service.log" \
            uv run uvicorn app.main:app --host 127.0.0.1 --port "$AI_PORT" )"
  echo "$pid"     > "$RUN_DIR/ai-service.pid"
  echo "$AI_PORT" > "$RUN_DIR/ai-service.port"
  # /actuator/health, not /health: the Python service deliberately mirrors the Spring path so one
  # probe configuration covers every service on the platform.
  wait_http "http://127.0.0.1:$AI_PORT/actuator/health" "ai-service on $AI_PORT" 30 || \
    warn "the AI service did not answer - the platform runs without it (log: $RUN_DIR/ai-service.log)"
}

start_web() {
  if port_busy "$WEB_PORT"; then ok "web already on $WEB_PORT"; return 0; fi
  step "Starting the web app"
  local pid
  # `npx next start --port` rather than `npm run start`: that script pins 3000 on its own command
  # line, so a MEDSYNC_WEB_PORT would have been quietly ignored and the banner would have printed a
  # URL nothing was listening on.
  pid="$( cd "$SRC/web" && GATEWAY_URL="http://127.0.0.1:$GATEWAY_PORT" \
            IDENTITY_URL="http://127.0.0.1:8081" \
            NEXT_PUBLIC_HMS_ZONE="${HMS_ZONE:-Asia/Kolkata}" COOKIE_SECURE=false \
            spawn "$RUN_DIR/web.log" npx next start --port "$WEB_PORT" )"
  echo "$pid"      > "$RUN_DIR/web.pid"
  echo "$WEB_PORT" > "$RUN_DIR/web.port"
  wait_http "http://127.0.0.1:$WEB_PORT/login" "web on $WEB_PORT" 60 \
    || die "the web app did not come up - see $RUN_DIR/web.log"
}

#
# The process *group*, not the pid. `npx next start` and `uv run uvicorn` both fork the real server
# as a child, so killing the recorded pid left next-server holding port 3000 and the next `up`
# reported "web already on 3000" while serving the previous build. `spawn` puts each of them in its
# own session, which is exactly what makes the group safe to signal - the negative pid cannot reach
# anything this script did not start. The pid is still tried afterwards, for the setsid-less case
# where the group is this shell's own and must not be signalled.
stop_pid_file() {
  local f="$1" name pid
  [ -f "$f" ] || return 0
  name="$(basename "$f" .pid)"
  pid="$(cat "$f")"
  if kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null; then
    ok "stopped $name (pid $pid)"
  else
    dim "$name was not running"
  fi
  rm -f "$f" "$RUN_DIR/$name.port"
}

# ---- smoke -----------------------------------------------------------------------------------
# Health endpoints answer while a service is still unable to serve a real request - a wrong
# database URL, a failed migration on one schema, a token the others will not accept. So the
# installer's own check signs in for real and reads one thing from every service through the
# gateway, which is the smallest test that distinguishes "up" from "working".
smoke_one() {
  local label="$1" path="$2" token="$3" code
  code="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" \
          "http://127.0.0.1:$GATEWAY_PORT$path" 2>/dev/null || echo 000)"
  case "$code" in
    2*) ok "$label ($code)" ;;
    *)  bad "$label - HTTP $code on $path"; SMOKE_FAIL=$((SMOKE_FAIL + 1)) ;;
  esac
}

cmd_smoke() {
  # Reads only. No source resolution and no database provisioning: `smoke` is what somebody runs to
  # find out whether the thing they started is working, and a diagnostic that starts a cluster of
  # its own has changed the state it was asked to report on.
  step "Smoke test, through the gateway, as a real signed-in user"
  SMOKE_FAIL=0

  local body token
  body="$(curl -s -X POST "http://127.0.0.1:$GATEWAY_PORT/auth/login" \
            -H 'Content-Type: application/json' \
            -d "{\"username\":\"admin\",\"password\":\"${HMS_SEED_PASSWORD:-ChangeMe!Dev2026}\"}" \
            2>/dev/null || true)"
  token="$(printf '%s' "$body" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
  if [ -z "$token" ]; then
    bad "sign-in failed"
    dim "${body:-no response} "
    die "the platform is not serving requests. '$0 status' and '$0 logs identity-service'."
  fi
  ok "signed in as admin"

  # One read per service, and every path here was checked against a running gateway rather than
  # inferred from a controller - three of the first guesses answered 404 or 405, which a smoke test
  # would have reported as a broken platform.
  smoke_one "identity      users"           "/admin/users?size=1"        "$token"
  smoke_one "patient       register"        "/patients?size=1"           "$token"
  smoke_one "patient       bed directory"   "/beds"                      "$token"
  smoke_one "scheduling    appointments"    "/appointments?size=1"       "$token"
  smoke_one "laboratory    worklist"        "/lab/orders?size=1"         "$token"
  smoke_one "notification  outbox"          "/notifications?size=1"      "$token"
  smoke_one "admissions    casualty board"  "/casualty"                  "$token"
  smoke_one "admissions    bed map"         "/admissions/beds"           "$token"
  smoke_one "pharmacy      formulary"       "/pharmacy/formulary?size=1" "$token"
  smoke_one "billing       invoices"        "/invoices?size=1"           "$token"
  smoke_one "interop       consents"        "/consents?size=1"           "$token"
  smoke_one "imaging       worklist"        "/imaging/worklist"          "$token"
  smoke_one "immunisation  vaccines"        "/vaccines/products"         "$token"
  smoke_one "immunisation  measures"        "/measures"                  "$token"
  smoke_one "public health notifiable"      "/surveillance/notifiable"   "$token"

  # No token, and that is the point: the corridor display carries no PHI and is the one path
  # allowlisted through the gateway unauthenticated. A 401 here means the allowlist broke.
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$GATEWAY_PORT/public/queue/GF-GEN" || echo 000)"
  case "$code" in 2*) ok "public queue display, no token ($code)" ;;
    *) bad "public queue display - HTTP $code"; SMOKE_FAIL=$((SMOKE_FAIL + 1)) ;; esac

  code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$WEB_PORT/login" || echo 000)"
  case "$code" in 2*) ok "web sign-in page ($code)" ;;
    *) bad "web sign-in page - HTTP $code"; SMOKE_FAIL=$((SMOKE_FAIL + 1)) ;; esac

  say ""
  if [ "$SMOKE_FAIL" -eq 0 ]; then ok "everything answered."; else
    die "$SMOKE_FAIL check(s) failed. '$0 logs <service>' for the reason."
  fi
}

# ---- browser ---------------------------------------------------------------------------------
open_browser() {
  local url="http://localhost:$WEB_PORT"
  if [ "${MEDSYNC_NO_BROWSER:-0}" = 1 ]; then return 0; fi
  if   have open;     then open "$url"     >/dev/null 2>&1 || true
  elif have xdg-open; then xdg-open "$url" >/dev/null 2>&1 || true
  fi
}

banner() {
  local pw="${HMS_SEED_PASSWORD:-ChangeMe!Dev2026}"
  cat <<EOF

${C_BLD}MedSync is running.${C_OFF}

  Web app          ${C_BLD}http://localhost:$WEB_PORT${C_OFF}
  API gateway      http://localhost:$GATEWAY_PORT
  Corridor display http://localhost:$WEB_PORT/display/GF-GEN   ${C_DIM}(no sign-in - carries no PHI)${C_OFF}

  Sign in with any of these, password ${C_BLD}$pw${C_OFF}:

    admin           everything, and the audit trail
    dr.rao          a doctor - charts, orders, prescribes
    nurse.iqbal     a nurse - triage, the casualty board, the drug round
    reception       the front desk - registers and books, no chart
    lab.tech        collects specimens and enters results, cannot verify
    dr.pathan       a pathologist - verifies and releases
    pharmacist      dispenses, keeps the formulary, cannot open a chart
    cashier         invoices and payments, cannot open a chart
    radiographer    the modality worklist, cannot report
    dr.mistry       a radiologist - reports and signs, cannot order
    epidemiologist  aggregate rates and notifiable counts only
    new.starter     still on its initial password, so it can only change it

  ${C_DIM}The separations above are enforced by the services, not by the menu: signing in as
  the cashier and typing a chart URL is a 403, not a hidden link.${C_OFF}

  $0 status      what is up
  $0 smoke       sign in and read one screen from every service
  $0 test        run the test suites
  $0 logs <svc>  follow one service's log
  $0 down        stop everything
EOF
}

# ---- commands --------------------------------------------------------------------------------
cmd_up() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --skip-build) MEDSYNC_SKIP_BUILD=1 ;;
      --no-browser) MEDSYNC_NO_BROWSER=1 ;;
      *) die "unknown option '$1' for up. Try --skip-build or --no-browser." ;;
    esac
    shift
  done
  say "${C_BLD}MedSync installer $VERSION${C_OFF}"
  step "Checking prerequisites"
  MISSING_REQUIRED=0
  check_one java 21  openjdk@21 required "Java"  || true
  check_one mvn  3.9 maven      optional "Maven (the bundled wrapper covers it)" || true
  check_one node 22  node       required "Node"  || true
  check_one git  2.0 git        required "git"   || true
  check_one curl ""  curl       required "curl"  || true
  [ "$MISSING_REQUIRED" -eq 0 ] || die "install the above and run '$0 doctor' to re-check."

  resolve_source
  ensure_env
  db_up
  export_stack_env
  db_prepare

  say ""
  dim "database  $HMS_DB_URL"
  dim "run dir   $RUN_DIR"

  [ "${MEDSYNC_SKIP_BUILD:-0}" = 1 ] || { build_java; build_web; build_ai || true; }

  start_java
  start_ai
  start_web

  cmd_smoke
  banner
  open_browser
}

cmd_down() {
  find_source || true
  step "Stopping"

  # The port list is taken before anything is stopped: stopping the web app removes its .port file,
  # so a check afterwards would not have looked at port 3000 at all - which is how a stale
  # next-server survived a `down` and served the previous build to the next `up`.
  local f ports="" name
  for f in "$RUN_DIR"/*.port; do
    [ -f "$f" ] || continue
    name="$(basename "$f" .port)"
    if [ "$name" = db ]; then continue; fi
    ports="$ports $(cat "$f")"
  done

  if [ -n "${SRC:-}" ] && [ -x "$SRC/scripts/local.sh" ]; then
    ( cd "$SRC" && HMS_RUN_DIR="$RUN_DIR" scripts/local.sh stop ) || true
  fi
  stop_pid_file "$RUN_DIR/web.pid"
  stop_pid_file "$RUN_DIR/ai-service.pid"

  # Wait for those ports to actually come free. SIGTERM returns immediately and a JVM takes a second
  # or two to close its listener, so a `down` that reported success straight away was followed by an
  # `up` that failed on "port in use" - the stop looked fine and the start looked broken.
  local i=1 still=0 p
  while [ "$i" -le 30 ]; do
    still=0
    for p in $ports; do
      if port_busy "$p"; then still=$((still + 1)); fi
    done
    if [ "$still" -eq 0 ]; then break; fi
    sleep 1
    i=$((i + 1))
  done
  if [ "$still" -eq 0 ]; then
    ok "every port is free"
  else
    warn "$still port(s) still bound after 30s - '$0 status', then stop them by hand"
  fi

  if [ "${1:-}" = "--all" ]; then
    if [ -f "$RUN_DIR/db.mode" ]; then
      # Where the cluster actually is, recorded when it was created. Fed back through
      # MEDSYNC_CLUSTER_DIR so pg_as_setup's default cannot overwrite it.
      if [ -f "$RUN_DIR/db.dir" ]; then export MEDSYNC_CLUSTER_DIR="$(cat "$RUN_DIR/db.dir")"; fi
      case "$(cat "$RUN_DIR/db.mode")" in
        private)
          pg_as_setup >/dev/null 2>&1 || true
          if pg_bin pg_ctl >/dev/null; then
            pg_run "'$(pg_bin pg_ctl)' -D '$CLUSTER_DIR' stop" >/dev/null 2>&1 \
              && ok "stopped the private cluster" || dim "the private cluster was not running"
          fi ;;
        docker)  docker stop medsync-db >/dev/null 2>&1 && ok "stopped the database container" ;;
        *)       dim "the database was not started by this script - leaving it alone" ;;
      esac
    fi
  else
    dim "the database is left running; '$0 down --all' stops it too"
  fi
}

cmd_status() {
  step "Status"
  local f name port state
  for f in "$RUN_DIR"/*.port; do
    [ -f "$f" ] || continue
    name="$(basename "$f" .port)"
    # db.port is the database's own state file and is reported below with which rung of the ladder
    # it came from, which is more use than a bare port number.
    [ "$name" = db ] && continue
    port="$(cat "$f")"
    if port_busy "$port"; then state="${C_GRN}up${C_OFF}"; else state="${C_RED}down${C_OFF}"; fi
    printf '    %-22s %-6s %b\n' "$name" "$port" "$state"
  done
  if [ -f "$RUN_DIR/db.port" ]; then
    port="$(cat "$RUN_DIR/db.port")"
    if port_busy "$port"; then state="${C_GRN}up${C_OFF}"; else state="${C_RED}down${C_OFF}"; fi
    printf '    %-22s %-6s %b\n' "postgresql ($(cat "$RUN_DIR/db.mode" 2>/dev/null))" "$port" "$state"
  fi
}

cmd_logs() {
  local name="${1:?usage: $0 logs <service>   (e.g. identity-service, web, ai-service)}"
  local f="$RUN_DIR/$name.log"
  [ -f "$f" ] || die "no log at $f. '$0 status' lists what is running."
  tail -f "$f"
}

# The suites, in the order that fails fastest. Two things the README learned the hard way and this
# encodes so nobody rediscovers them: `mvn verify` repackages the jars underneath the running JVMs,
# so the stack is stopped for the Java run and restarted afterwards; and the browser suite needs
# the web server already up, because the Playwright config deliberately has no webServer block.
cmd_test() {
  resolve_source
  db_up; export_stack_env
  local which="${1:-all}"
  export HMS_TEST_DB_URL="${HMS_TEST_DB_URL:-jdbc:postgresql://127.0.0.1:$DB_PORT/hms_test}"

  # The API and browser suites drive the gateway hard enough to trip the rate limiter, which then
  # fails a test for a reason that has nothing to do with what the test is about. The limiter has
  # its own unit coverage in the gateway module, so raising it for a suite run costs nothing.
  export HMS_RATE_LIMIT_RPM="${HMS_RATE_LIMIT_RPM:-100000}"
  export HMS_RATE_LIMIT_AUTH_RPM="${HMS_RATE_LIMIT_AUTH_RPM:-5000}"
  export HMS_RATE_LIMIT_PORTAL_RPM="${HMS_RATE_LIMIT_PORTAL_RPM:-100000}"

  local run_java=0 run_web=0 run_api=0 run_browser=0
  case "$which" in
    java)        run_java=1 ;;
    web)         run_web=1 ;;
    api)         run_api=1 ;;
    browser|e2e) run_browser=1 ;;
    all)         run_java=1; run_web=1; run_api=1; run_browser=1 ;;
    *)           die "unknown suite '$which'. One of: java web api browser all" ;;
  esac

  if [ "$run_java" = 1 ]; then
    step "Java suites"
    dim "the stack is stopped first: 'verify' repackages the jars the running JVMs loaded from,"
    dim "and a service that then lazily loads a class fails minutes later with ClassNotFoundException"
    ( cd "$SRC" && scripts/local.sh stop >/dev/null 2>&1 ) || true
    local psql_bin; psql_bin="$(pg_bin psql)" || psql_bin=""
    if [ -n "$psql_bin" ]; then
      "$psql_bin" "postgresql://hms@127.0.0.1:$DB_PORT/postgres" -q \
        -c 'create database hms_test' >/dev/null 2>&1 || true
    fi
    ( cd "$SRC" && "$(maven_cmd)" -B verify ) || die "the Java suites failed"
    ( cd "$SRC" && scripts/local.sh start ) || die "the stack did not come back up after the rebuild"
  fi

  if [ "$run_web" = 1 ]; then
    step "Web unit tests and static checks"
    ( cd "$SRC/web" && npm run lint && npm run typecheck && npm test ) || die "the web checks failed"
  fi

  if [ "$run_api" = 1 ]; then
    step "Cross-service API journeys and the authorization abuse suite"
    ( cd "$SRC" && "$(maven_cmd)" -B -Pautomation -pl tests/api verify ) || die "the API suites failed"
  fi

  if [ "$run_browser" = 1 ]; then
    step "Browser end-to-end suite"
    # The Playwright config deliberately carries no `webServer` block, so the server has to be up
    # before the suite starts rather than being spawned by it.
    start_web
    ( cd "$SRC/web" && npx playwright install --with-deps chromium >/dev/null 2>&1 ) || true
    ( cd "$SRC/web" && npx playwright test ) || die "the browser suite failed"
  fi

  say ""
  ok "green."
}

cmd_reset() {
  resolve_source; db_up; export_stack_env
  step "Dropping and recreating the database"
  local psql_bin; psql_bin="$(pg_bin psql)" || die "psql is needed to reset the database"
  ( cd "$SRC" && HMS_RUN_DIR="$RUN_DIR" scripts/local.sh stop >/dev/null 2>&1 ) || true
  stop_pid_file "$RUN_DIR/web.pid"
  "$psql_bin" "postgresql://hms@127.0.0.1:$DB_PORT/postgres" -q \
    -c 'drop database if exists hms' -c 'create database hms' \
    || die "could not recreate the database"
  db_prepare
  ok "empty. '$0 up' will migrate and seed it again."
}

cmd_uninstall() {
  cmd_down --all || true
  step "Removing what this script created"
  if [ -f "$RUN_DIR/db.mode" ] && [ "$(cat "$RUN_DIR/db.mode")" = docker ]; then
    docker rm -f medsync-db >/dev/null 2>&1 && ok "removed the database container" || true
  fi
  say "    about to delete $HOME_DIR"
  printf '    type the word remove to confirm: '
  local answer; read -r answer
  [ "$answer" = remove ] || die "not confirmed - nothing deleted."
  rm -rf "$HOME_DIR"
  ok "gone. A checkout you were working in is untouched."
}

usage() {
  cat <<EOF
MedSync $VERSION - install, run and check the platform.

  $0 up            install if needed, start everything, smoke-test it, open a browser
                   --skip-build   start what is already built
                   --no-browser   do not open one
  $0 down [--all]  stop the services (--all stops the database too)
  $0 status        what is up, and on which port
  $0 smoke         sign in and read one screen from every service
  $0 test [suite]  run the suites: java | web | api | browser | all (default)
  $0 logs <svc>    follow a log: identity-service, web, ai-service, ...
  $0 doctor        report prerequisites, database and ports; exits non-zero if short
  $0 reset         drop and recreate the database
  $0 uninstall     stop everything and delete $HOME_DIR

Environment:
  MEDSYNC_HOME        where this script keeps everything   (default ~/.medsync)
  MEDSYNC_DB_PORT     port for a database it starts itself (default 55432)
  MEDSYNC_WEB_PORT    port for the web app                 (default 3000)
  MEDSYNC_NO_BROWSER  set to 1 to not open a browser
  MEDSYNC_SKIP_BUILD  set to 1 to start what is already built
  MEDSYNC_REPO/_REF   clone from somewhere other than the default
  HMS_DB_URL          use a PostgreSQL of your own instead of any of the above
EOF
}

case "${1:-up}" in
  up|install|start) shift || true; cmd_up "$@" ;;
  down|stop)        shift || true; cmd_down "$@" ;;
  status)           cmd_status ;;
  smoke)            cmd_smoke ;;
  test)             shift || true; cmd_test "$@" ;;
  logs)             shift || true; cmd_logs "$@" ;;
  doctor)           cmd_doctor ;;
  reset)            cmd_reset ;;
  uninstall)        cmd_uninstall ;;
  -h|--help|help)   usage ;;
  --version)        say "$VERSION" ;;
  *)                usage >&2; exit 2 ;;
esac
