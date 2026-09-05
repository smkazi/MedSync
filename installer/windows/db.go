package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// The database, as a ladder with four rungs, and the order is the argument:
//
//  1. HMS_DB_URL already set — the operator has said where the database is, and second-guessing
//     that is how a script writes to the wrong server.
//  2. something already listening on 5432 — a developer machine usually has one, and standing a
//     second cluster up beside it wastes a gigabyte and confuses every psql they type afterwards.
//  3. a private cluster this installer creates and owns, on a port of its own — needs no
//     elevation, touches nothing else, and `uninstall` deletes it. The good path on a clean
//     Windows machine that has PostgreSQL installed.
//  4. a Docker container — last, because it needs the engine running and because a container's
//     volume outliving the container surprises people.
//
// Whichever rung answers is named in the output. "It connected to something" must never be a
// mystery, because the one failure this ladder can produce is a platform that migrates a schema
// into a database nobody meant to use.

const privateDBPort = 55432

func (s *state) database(rt runtimeTree) {
	if v := os.Getenv("HMS_DB_URL"); v != "" {
		s.DBMode, s.DBPort = "external", 0
		ok("Database: HMS_DB_URL from the environment (%s)", v)
		return
	}

	// A cluster this installer made earlier, restarted rather than remade.
	if s.DBMode == "private" && s.DBDir != "" {
		if portBusy(s.DBPort) {
			ok("Database: the private cluster already running on %d", s.DBPort)
			return
		}
		if s.startCluster(rt) {
			return
		}
		warn("the private cluster at %s would not start", s.DBDir)
	}
	if s.DBMode == "docker" && !portBusy(s.DBPort) {
		if s.startDockerDB() {
			return
		}
	}

	// An existing server is used only if we can actually sign in to it, and that condition is not
	// pedantry — it is the next thing that would have broken on a real machine. Somebody who
	// installed PostgreSQL the normal way has a `postgres` superuser with a password they chose,
	// not the `hms`/`hms` this platform defaults to, so taking the rung on "something is listening"
	// alone gets as far as `create database` and then fails on authentication, after the ladder has
	// already committed to it.
	//
	// Failing the probe is also the more polite outcome. Writing eleven schemas into a developer's
	// own PostgreSQL without being asked is not something an installer should do by accident; if it
	// cannot authenticate, it stands up its own cluster instead and says so.
	//
	// The whole rung is skipped on a bundled install, and that is the point of bundling: a payload
	// that carries its own PostgreSQL has no reason to go looking for somebody else's, and every
	// reason not to. Guessing at another server's credentials was only ever worth the risk because
	// there was no database in the box. An existing server is still usable there — deliberately,
	// by setting HMS_DB_URL, which is the rung above this one.
	if !rt.present() && portBusy(5432) {
		if s.canSignIn(5432, rt) {
			s.DBMode, s.DBPort = "existing", 5432
			ok("Database: the PostgreSQL already running on 5432")
			s.save()
			return
		}
		warn("something is listening on 5432 but would not accept the %s login", envOr("HMS_DB_USER", "hms"))
		dim("leaving it alone and creating a private cluster instead")
		dim("to use it, create the role and pass its credentials:")
		dim("  set HMS_DB_URL=jdbc:postgresql://127.0.0.1:5432/hms")
		dim("  set HMS_DB_USER=...   &   set HMS_DB_PASSWORD=...")
	}
	if s.DBDir == "" {
		s.DBDir = filepath.Join(homeDir(), "pgdata")
	}
	if s.DBPort == 0 {
		s.DBPort = privateDBPort
	}
	if s.initCluster(rt) && s.startCluster(rt) {
		return
	}
	if s.startDockerDB() {
		return
	}
	if rt.present() {
		// Unreachable on a complete payload — the server is in the file that is running — so if it
		// is reached, the useful thing to say is that the bundled one would not start, not that a
		// PostgreSQL should be installed. Suggesting an install here would send somebody to fix a
		// machine that is not the problem.
		fail("the bundled PostgreSQL would not start.\n\n"+
			"Its log is %s\n\n"+
			"To use a server you already have instead:\n\n"+
			"    set HMS_DB_URL=jdbc:postgresql://host:5432/hms",
			filepath.Join(logDir(), "postgres.log"))
	}
	fail("no way to get a PostgreSQL.\n\n" +
		"Install PostgreSQL 16 (winget install PostgreSQL.PostgreSQL.16), or start Docker\n" +
		"Desktop, or point this at a server you already have:\n\n" +
		"    set HMS_DB_URL=jdbc:postgresql://host:5432/hms")
}

// canSignIn asks the question the ladder used to assume the answer to: can this platform's
// credentials actually open a session on that port?
//
// Without psql there is no way to find out, and the honest answer to "I cannot check" is no: the
// alternative is committing to a server on a guess and failing several steps later, where the error
// names a database rather than a decision.
func (s *state) canSignIn(port int, rt runtimeTree) bool {
	psql := rt.pgTool("psql")
	if psql == "" {
		return false
	}
	user := envOr("HMS_DB_USER", "hms")
	pass := envOr("HMS_DB_PASSWORD", "hms")
	// Through psqlRun for its argument order, which matters more here than anywhere: this probe
	// fails closed, so getting it wrong does not produce an error - it silently declines a server
	// that would have worked perfectly and stands up a second cluster beside it.
	out, err := psqlRun(psql, fmt.Sprintf("postgresql://%s:%s@127.0.0.1:%d/postgres", user, pass, port),
		"select 1")
	return err == nil && !strings.Contains(strings.ToLower(string(out)), "error")
}

// pgTool resolves one of PostgreSQL's programs, preferring the one inside this installer.
//
// The bundled copy first, and not merely for convenience: initdb, pg_ctl, postgres and psql are a
// matched set, and a psql found on PATH from some other installation is exactly how a version
// mismatch reaches a cluster this installer created. On a payload build there is no second
// candidate at all — the machine is not required to have PostgreSQL — so the PATH search below is
// the developer path's fallback rather than the normal case.
func (r runtimeTree) pgTool(name string) string {
	if r.present() {
		if path := r.pgBin(name); fileExists(path) {
			return path
		}
	}
	t := &tool{command: name}
	t.look()
	if t.found {
		return t.path
	}
	return ""
}

// initCluster runs initdb, unless the cluster is already there.
//
// Unlike on Linux there is no root problem here — Windows has no equivalent refusal — but there is
// a different one, and it took a Windows runner to find it: initdb must be the thing that creates
// the data directory. See the comment on the RemoveAll below.
func (s *state) initCluster(rt runtimeTree) bool {
	initdb := rt.pgTool("initdb")
	if initdb == "" {
		return false
	}
	if _, err := os.Stat(filepath.Join(s.DBDir, "base")); err == nil {
		return true
	}
	step("Creating a private PostgreSQL cluster in %s", s.DBDir)
	// The parent, and deliberately NOT the data directory itself.
	//
	// This is the bug a Windows runner found and a Linux one never could. Handed a directory that
	// already exists, initdb tries to tighten its permissions rather than create it with the right
	// ones — and on Windows that fails outright: "could not change permissions of directory ...:
	// Permission denied", on a directory this process had just made and owned. Letting initdb
	// create it is also what its own documentation describes, so this is the ordinary path rather
	// than a workaround.
	_ = os.RemoveAll(s.DBDir)
	if err := os.MkdirAll(filepath.Dir(s.DBDir), 0o755); err != nil {
		warn("could not create %s: %v", filepath.Dir(s.DBDir), err)
		return false
	}
	// trust authentication, and only because of what this cluster is: it listens on 127.0.0.1
	// alone, it holds demo data, and the alternative is a password that has to be written into a
	// file this installer also ships.
	cmd := exec.Command(initdb, "-D", s.DBDir, "-U", "hms", "-E", "UTF8", "--auth-host=trust", "--auth-local=trust")
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	// On Unix as root this becomes an unprivileged command, because initdb refuses to run as root
	// and is right to. A no-op on Windows and a no-op for an ordinary user anywhere. The parent
	// directory has to change hands too, or initdb cannot create the data directory inside it.
	if uid, gid, dropped := dropPrivileges(cmd); dropped {
		dim("running initdb as an unprivileged user, which is the only way it will run at all")
		if err := ownedBy(filepath.Dir(s.DBDir), uid, gid); err != nil {
			warn("could not hand %s over: %v", filepath.Dir(s.DBDir), err)
			return false
		}
	}
	if err := cmd.Run(); err != nil {
		warn("initdb failed: %v", err)
		return false
	}
	ok("cluster created")
	return true
}

func (s *state) startCluster(rt runtimeTree) bool {
	pgCtl := rt.pgTool("pg_ctl")
	if pgCtl == "" {
		return false
	}
	logFile := filepath.Join(logDir(), "postgres.log")
	_ = os.MkdirAll(logDir(), 0o755)
	cmd := exec.Command(pgCtl, "-D", s.DBDir, "-l", logFile,
		"-o", fmt.Sprintf("-p %d -c listen_addresses=127.0.0.1", s.DBPort), "start")
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	// The same drop as initdb, and for the same refusal: the server behind pg_ctl will not run as
	// root either. The log file is created here rather than left to pg_ctl, because the dropped
	// user has to be able to write to it and cannot create it in a directory root owns.
	if uid, gid, dropped := dropPrivileges(cmd); dropped {
		if f, err := os.OpenFile(logFile, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644); err == nil {
			f.Close()
		}
		_ = os.Chown(logFile, uid, gid)
		if err := ownedBy(s.DBDir, uid, gid); err != nil {
			warn("could not hand %s over: %v", s.DBDir, err)
			return false
		}
	}
	if err := cmd.Run(); err != nil {
		warn("pg_ctl start failed: %v (log: %s)", err, logFile)
		return false
	}
	for i := 0; i < 30 && !portBusy(s.DBPort); i++ {
		time.Sleep(time.Second)
	}
	if !portBusy(s.DBPort) {
		warn("the cluster did not accept connections — see %s", logFile)
		return false
	}
	s.DBMode = "private"
	s.save()
	ok("Database: private cluster on %d (%s)", s.DBPort, s.DBDir)
	return true
}

func (s *state) startDockerDB() bool {
	docker, err := exec.LookPath("docker")
	if err != nil || exec.Command(docker, "info").Run() != nil {
		return false
	}
	if s.DBPort == 0 {
		s.DBPort = privateDBPort
	}
	step("Starting PostgreSQL in a container")
	// Start an existing container before creating a second one, or every run leaks a container and
	// the name collision becomes the error somebody has to debug.
	if exec.Command(docker, "start", "medsync-db").Run() != nil {
		run := exec.Command(docker, "run", "-d", "--name", "medsync-db",
			"-p", fmt.Sprintf("127.0.0.1:%d:5432", s.DBPort),
			"-e", "POSTGRES_USER=hms", "-e", "POSTGRES_PASSWORD=hms", "-e", "POSTGRES_DB=hms",
			"postgres:16")
		out, err := run.CombinedOutput()
		if err != nil {
			os.Stderr.Write(out)
			warn("docker run failed: %v", err)
			// A Docker Desktop switched to Windows containers answers "no matching manifest for
			// windows(...)/amd64", which reads like a broken image rather than a mode the user can
			// change in one click. Naming the actual problem is the difference between a dead end
			// and a fix — and every other image this platform would want is Linux too.
			if strings.Contains(string(out), "no matching manifest") {
				dim("this Docker engine is in Windows-container mode, and postgres:16 is a Linux image.")
				dim("right-click the Docker tray icon and choose \"Switch to Linux containers\",")
				dim("or install PostgreSQL, or set HMS_DB_URL.")
			}
			return false
		}
	}
	for i := 0; i < 60 && !portBusy(s.DBPort); i++ {
		time.Sleep(time.Second)
	}
	if !portBusy(s.DBPort) {
		warn("the database container did not accept connections")
		return false
	}
	s.DBMode = "docker"
	s.save()
	ok("Database: container medsync-db on %d", s.DBPort)
	return true
}

// prepare creates the database and the two extensions. Everything else — all eleven service
// schemas and every table in them — is Flyway's job on first start, which is why there is no schema
// step here and nothing to copy anywhere.
func (s *state) prepare(rt runtimeTree) {
	if s.DBMode == "external" {
		dim("HMS_DB_URL is yours; the database, role and extensions are assumed to exist")
		return
	}
	psql := rt.pgTool("psql")
	if psql == "" {
		if s.DBMode == "docker" {
			docker, _ := exec.LookPath("docker")
			cmd := exec.Command(docker, "exec", "medsync-db", "psql", "-U", "hms", "-d", "hms",
				"-c", "create extension if not exists pg_trgm; create extension if not exists btree_gist;")
			if cmd.Run() == nil {
				ok("database hms ready (extensions installed)")
				return
			}
		}
		warn("no psql on this machine — the first service to migrate will fail and say which extension is missing")
		return
	}

	admin := fmt.Sprintf("postgresql://hms:hms@127.0.0.1:%d/postgres", s.DBPort)
	target := fmt.Sprintf("postgresql://hms:hms@127.0.0.1:%d/hms", s.DBPort)

	// "already exists" is the expected answer on every run after the first, so a non-zero exit is
	// not on its own a problem — but it is no longer discarded, because the one failure that
	// matters here is silent and fatal: if the database is not created, every service that follows
	// dies on a missing database twenty minutes later, and the message names Flyway rather than
	// this step.
	out, err := psqlRun(psql, admin, "create database hms")
	created := err == nil
	if !created && !strings.Contains(strings.ToLower(string(out)), "already exists") {
		warn("could not create the database: %v", err)
		dim("%s", strings.TrimSpace(string(out)))
	}

	if out, err := psqlRun(psql, target,
		"create extension if not exists pg_trgm",
		"create extension if not exists btree_gist"); err != nil {
		warn("could not install pg_trgm/btree_gist: %v", err)
		dim("%s", strings.TrimSpace(string(out)))
		// Fatal on a cluster this installer owns, and only a warning on somebody else's. Without
		// these two extensions the very first migration fails, so continuing means a long build
		// followed by twelve services that cannot start - which is exactly what happened on the
		// machine that found this. Stopping here costs a minute; carrying on cost twenty.
		if s.DBMode == "private" || s.DBMode == "docker" {
			fail("the database this installer created cannot be prepared.\n\n"+
				"Run this once, then start again:\n"+
				"    psql -p %d -U hms -d hms -c \"create extension pg_trgm; create extension btree_gist;\"",
				s.DBPort)
		}
		dim("run this once as a superuser, then start again:")
		dim("  psql -p %d -U hms -d hms -c \"create extension pg_trgm; create extension btree_gist;\"", s.DBPort)
		return
	}
	// And then prove it, rather than inferring it from three commands that did not complain.
	//
	// This is the check whose absence cost a real install twenty minutes: the database steps all
	// reported success, the build ran, and only then did twelve services fail one after another
	// against a database that could not serve them. What a service needs is a connection to `hms`
	// with both extensions present, so that is exactly what is asked for here — one query, before
	// anything expensive happens.
	if out, err := psqlRun(psql, target,
		"select 1 from pg_extension where extname = 'pg_trgm'",
		"select 1 from pg_extension where extname = 'btree_gist'"); err != nil {
		warn("the database does not answer the way a service will need it to: %v", err)
		dim("%s", strings.TrimSpace(string(out)))
		if s.DBMode == "private" || s.DBMode == "docker" {
			fail("refusing to build against a database that is not ready.\n\n"+
				"    psql -p %d -U hms -d hms -c \"create extension pg_trgm; create extension btree_gist;\"\n\n"+
				"Or start clean: MedSync-Setup.exe uninstall", s.DBPort)
		}
		return
	}
	ok("database hms ready (extensions installed, and checked)")
}

// psqlRun runs one or more statements, with every flag before the connection string and the
// connection string itself passed through -d.
//
// That shape is not stylistic. psql's option parsing stops at the first positional argument on at
// least some builds - the one on the Windows machine that found this among them - so
// `psql "<uri>" -c "..."` there took the URI as DBNAME, the next flag as USERNAME, and discarded
// every -c with a warning. The database was never created, and the failure surfaced twenty minutes
// later as twelve services that would not start. With -d there is no positional argument at all, so
// there is nothing to stop parsing at.
func psqlRun(psql, conn string, statements ...string) ([]byte, error) {
	args := []string{"-q", "-v", "ON_ERROR_STOP=1"}
	for _, statement := range statements {
		args = append(args, "-c", statement)
	}
	args = append(args, "-d", conn)
	cmd := exec.Command(psql, args...)
	cmd.Env = append(os.Environ(), "PGCONNECT_TIMEOUT=10")
	return cmd.CombinedOutput()
}

func (s *state) stopDatabase(rt runtimeTree) {
	switch s.DBMode {
	case "private":
		if pgCtl := rt.pgTool("pg_ctl"); pgCtl != "" && s.DBDir != "" {
			stop := exec.Command(pgCtl, "-D", s.DBDir, "-m", "fast", "stop")
			// The same drop as the start: pg_ctl refuses as root, so a cluster started by an
			// unprivileged user could otherwise be started and never stopped.
			dropPrivileges(stop)
			if stop.Run() == nil {
				ok("stopped the private cluster")
				return
			}
		}
		dim("the private cluster was not running")
	case "docker":
		if docker, err := exec.LookPath("docker"); err == nil {
			if exec.Command(docker, "stop", "medsync-db").Run() == nil {
				ok("stopped the database container")
				return
			}
		}
		dim("the database container was not running")
	default:
		dim("the database was not started by this installer — leaving it alone")
	}
}
