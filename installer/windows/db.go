package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
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

func (s *state) database() {
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
		if s.startCluster() {
			return
		}
		warn("the private cluster at %s would not start", s.DBDir)
	}
	if s.DBMode == "docker" && !portBusy(s.DBPort) {
		if s.startDockerDB() {
			return
		}
	}

	if portBusy(5432) {
		s.DBMode, s.DBPort = "existing", 5432
		ok("Database: the PostgreSQL already running on 5432")
		s.save()
		return
	}
	if s.DBDir == "" {
		s.DBDir = filepath.Join(homeDir(), "pgdata")
	}
	if s.DBPort == 0 {
		s.DBPort = privateDBPort
	}
	if s.initCluster() && s.startCluster() {
		return
	}
	if s.startDockerDB() {
		return
	}
	fail("no way to get a PostgreSQL.\n\n" +
		"Install PostgreSQL 16 (winget install PostgreSQL.PostgreSQL.16), or start Docker\n" +
		"Desktop, or point this at a server you already have:\n\n" +
		"    set HMS_DB_URL=jdbc:postgresql://host:5432/hms")
}

func pgTool(name string) string {
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
// a different one: initdb will not write into a directory that already exists and is not empty, so
// a half-created cluster from an interrupted run has to be cleared rather than reused.
func (s *state) initCluster() bool {
	initdb := pgTool("initdb")
	if initdb == "" {
		return false
	}
	if _, err := os.Stat(filepath.Join(s.DBDir, "base")); err == nil {
		return true
	}
	step("Creating a private PostgreSQL cluster in %s", s.DBDir)
	_ = os.RemoveAll(s.DBDir)
	if err := os.MkdirAll(s.DBDir, 0o755); err != nil {
		warn("could not create %s: %v", s.DBDir, err)
		return false
	}
	// trust authentication, and only because of what this cluster is: it listens on 127.0.0.1
	// alone, it holds demo data, and the alternative is a password that has to be written into a
	// file this installer also ships.
	cmd := exec.Command(initdb, "-D", s.DBDir, "-U", "hms", "-E", "UTF8", "--auth-host=trust", "--auth-local=trust")
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	if err := cmd.Run(); err != nil {
		warn("initdb failed: %v", err)
		return false
	}
	ok("cluster created")
	return true
}

func (s *state) startCluster() bool {
	pgCtl := pgTool("pg_ctl")
	if pgCtl == "" {
		return false
	}
	logFile := filepath.Join(logDir(), "postgres.log")
	_ = os.MkdirAll(logDir(), 0o755)
	cmd := exec.Command(pgCtl, "-D", s.DBDir, "-l", logFile,
		"-o", fmt.Sprintf("-p %d -c listen_addresses=127.0.0.1", s.DBPort), "start")
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
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
		run.Stdout, run.Stderr = os.Stdout, os.Stderr
		if err := run.Run(); err != nil {
			warn("docker run failed: %v", err)
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
func (s *state) prepare() {
	if s.DBMode == "external" {
		dim("HMS_DB_URL is yours; the database, role and extensions are assumed to exist")
		return
	}
	psql := pgTool("psql")
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
	// Ignored on purpose: "already exists" is the expected answer on every run after the first, and
	// a check-then-create would race with nothing useful gained.
	_ = exec.Command(psql, admin, "-q", "-c", "create database hms").Run()

	cmd := exec.Command(psql, target, "-q",
		"-c", "create extension if not exists pg_trgm",
		"-c", "create extension if not exists btree_gist")
	if out, err := cmd.CombinedOutput(); err != nil {
		warn("could not install pg_trgm/btree_gist: %v", err)
		dim("%s", string(out))
		dim("run this once as a superuser, then start again:")
		dim("  psql -p %d -d hms -c \"create extension pg_trgm; create extension btree_gist;\"", s.DBPort)
		return
	}
	ok("database hms ready (extensions installed)")
}

func (s *state) stopDatabase() {
	switch s.DBMode {
	case "private":
		if pgCtl := pgTool("pg_ctl"); pgCtl != "" && s.DBDir != "" {
			if exec.Command(pgCtl, "-D", s.DBDir, "-m", "fast", "stop").Run() == nil {
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
