package main

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// The shape of the runtime the payload unpacks into, in one place.
//
// Every path the installer executes something from is derived here rather than spelled out at the
// call site, for the reason the service table exists in state.go: a start path and a stop path that
// each build their own idea of where java lives are two paths that will one day disagree, and the
// disagreement shows up as a platform that starts and cannot be stopped.
//
// The layout, as the payload build writes it:
//
//	PAYLOAD-ID                 the build's sha256 of this archive; what the runtime dir is named for
//	jre/bin/java.exe           Temurin 21, jlinked to the modules the services actually load
//	node/node.exe              Node 22, the runtime only — no npm, because nothing installs anything
//	pgsql/bin/postgres.exe     PostgreSQL 16 server, initdb, pg_ctl and psql
//	python/python.exe          embeddable CPython 3.11 with the AI service's wheels already in it
//	services/<name>.jar        the twelve thin service jars
//	lib/*.jar                  the one shared dependency directory all twelve are launched against
//	web/server.js              Next.js standalone output
//	ai/                        the AI service's source and its trained model
//	licenses/                  every bundled component's own licence text
//	THIRD-PARTY-NOTICES.txt
type runtimeTree struct{ root string }

// exeName is the whole of the platform difference in this file. Keeping it to one function is what
// lets the rest of the installer's logic be exercised on Linux, where these same names resolve to
// real binaries under a Linux payload built by the same job.
func exeName(name string) string {
	if runtime.GOOS == "windows" {
		return name + ".exe"
	}
	return name
}

func (r runtimeTree) present() bool { return r.root != "" }

func (r runtimeTree) java() string { return filepath.Join(r.root, "jre", "bin", exeName("java")) }
func (r runtimeTree) node() string { return filepath.Join(r.root, "node", exeName("node")) }

// python resolves the bundled interpreter, and it is the one path that genuinely differs between
// the two payloads rather than merely differing by an extension.
//
// Windows gets the embeddable distribution, which is a flat directory: python\python.exe. Unix gets
// an ordinary virtualenv, which is python/bin/python. Checking both rather than branching on GOOS,
// because the thing that matters is where the interpreter actually is — and a payload built one way
// and read the other should say "the AI service is not in this payload", not crash.
func (r runtimeTree) python() string {
	flat := filepath.Join(r.root, "python", exeName("python"))
	if fileExists(flat) {
		return flat
	}
	nested := filepath.Join(r.root, "python", "bin", exeName("python"))
	if fileExists(nested) {
		return nested
	}
	return flat
}

// pgBin resolves one of the PostgreSQL programs — initdb, pg_ctl, postgres, psql.
func (r runtimeTree) pgBin(name string) string {
	return filepath.Join(r.root, "pgsql", "bin", exeName(name))
}

func (r runtimeTree) libDir() string   { return filepath.Join(r.root, "lib") }
func (r runtimeTree) webDir() string   { return filepath.Join(r.root, "web") }
func (r runtimeTree) aiDir() string    { return filepath.Join(r.root, "ai") }
func (r runtimeTree) webEntry() string { return filepath.Join(r.webDir(), "server.js") }

func (r runtimeTree) serviceJar(service string) string {
	return filepath.Join(r.root, "services", service+".jar")
}

// javaArgs is the launch line for one service, and the -Dloader.path is the whole point of it.
//
// The twelve jars are packaged with Spring Boot's ZIP layout, which means PropertiesLauncher, which
// means the dependencies are read from the directory named here rather than from inside each jar.
// That is what turns 1,000 MB of twelve fat jars into 5 MB of twelve thin ones plus one 124 MB
// pool — the single measurement this whole bundle is built on.
//
// The directory named is this service's own, not the pool: see linkClasspaths for why a merged
// classpath is not a tidier version of the same thing.
func (r runtimeTree) javaArgs(service string) []string {
	return []string{
		"-Dloader.path=" + r.classpathDir(service),
		// Bounded on purpose. Twelve JVMs on a laptop with no ceiling each take a quarter of
		// physical memory as their default maximum heap, and the failure that produces is the
		// machine swapping rather than anything naming MedSync.
		"-Xmx320m",
		"-XX:+UseSerialGC",
		"-jar", r.serviceJar(service),
		"--spring.profiles.active=dev",
	}
}

// missing lists the parts of the runtime that are not where they should be.
//
// Reported as a list rather than as the first failure, because a truncated download and a payload
// job that forgot one component look identical from a single missing file, and the difference
// between "one thing is absent" and "nothing is here" is the difference between a bug report and a
// re-download.
func (r runtimeTree) missing() []string {
	if !r.present() {
		return []string{"the runtime itself"}
	}
	required := []struct {
		what string
		path string
	}{
		{"the Java runtime", r.java()},
		{"the Node runtime", r.node()},
		{"the PostgreSQL server", r.pgBin("postgres")},
		{"initdb", r.pgBin("initdb")},
		{"psql", r.pgBin("psql")},
		{"the shared library pool", r.libDir()},
		{"the classpath lists", filepath.Join(r.root, "classpath")},
		{"the web app", r.webEntry()},
	}
	var out []string
	for _, item := range required {
		if _, err := os.Stat(item.path); err != nil {
			out = append(out, fmt.Sprintf("%s (%s)", item.what, item.path))
		}
	}
	for _, svc := range services {
		if _, err := os.Stat(r.serviceJar(svc.Name)); err != nil {
			out = append(out, svc.Name+"'s jar")
		}
	}
	// The AI service is checked separately and is not fatal: it is the one component the platform
	// is designed to run without, behind HMS_AI_ENABLED, and a payload built without it should
	// produce a working install that says so rather than a refusal.
	return out
}

// hasAI reports whether the payload carried the Python service.
func (r runtimeTree) hasAI() bool {
	if !r.present() {
		return false
	}
	if _, err := os.Stat(r.python()); err != nil {
		return false
	}
	_, err := os.Stat(filepath.Join(r.aiDir(), "app", "main.py"))
	return err == nil
}

// describe is what `doctor` and `version` print: what is actually inside this exe.
func (r runtimeTree) describe() {
	if !r.present() {
		warn("This build carries no payload — it will build MedSync from source instead.")
		dim("That is the developer build. A released MedSync-Setup.exe carries everything it needs.")
		return
	}
	say("   Runtime: %s", r.root)
	for _, line := range []struct{ what, path string }{
		{"Java", r.java()},
		{"Node", r.node()},
		{"PostgreSQL", r.pgBin("postgres")},
		{"Python (AI service)", r.python()},
	} {
		if _, err := os.Stat(line.path); err == nil {
			ok("%-22s bundled", line.what)
		} else {
			dim("%-22s not in this payload", line.what)
		}
	}
	if jars, err := os.ReadDir(r.libDir()); err == nil {
		ok("%-22s %d jars, shared by every service", "shared libraries", len(jars))
	}
	if entries, err := os.ReadDir(filepath.Join(r.root, "services")); err == nil {
		ok("%-22s %d", "service jars", len(entries))
	}
	if _, err := os.Stat(filepath.Join(r.root, "THIRD-PARTY-NOTICES.txt")); err == nil {
		ok("%-22s %s", "licences", filepath.Join(r.root, "THIRD-PARTY-NOTICES.txt"))
	}
}

// pathWith puts the runtime's own binaries in front of whatever the machine has.
//
// It matters for PostgreSQL rather than for Java: initdb and pg_ctl locate their siblings through
// the directory they were launched from, but a psql found on PATH from an unrelated installation is
// how a version mismatch reaches a cluster this installer created. Prepending rather than replacing,
// because a person's own PATH is theirs and this program has no business emptying it.
func (r runtimeTree) pathWith(env []string) []string {
	if !r.present() {
		return env
	}
	dirs := []string{
		filepath.Dir(r.java()),
		filepath.Dir(r.node()),
		filepath.Dir(r.pgBin("psql")),
	}
	sep := string(os.PathListSeparator)
	prefix := strings.Join(dirs, sep)

	out := make([]string, 0, len(env))
	replaced := false
	for _, kv := range env {
		// Windows environment variable names are case-insensitive and the block a process is given
		// may spell this "Path"; comparing on the literal "PATH=" is how a bundled runtime silently
		// fails to be found there.
		if len(kv) >= 5 && strings.EqualFold(kv[:5], "PATH=") {
			out = append(out, kv[:5]+prefix+sep+kv[5:])
			replaced = true
			continue
		}
		out = append(out, kv)
	}
	if !replaced {
		out = append(out, "PATH="+prefix)
	}
	return out
}

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

// classpathDir is where this service's exact set of jars is assembled.
func (r runtimeTree) classpathDir(service string) string {
	return filepath.Join(r.root, "cp", service)
}

// linkClasspaths rebuilds each service's exact classpath out of the pooled library directory.
//
// The payload stores one copy of each distinct jar in lib/ and, beside it, one text file per
// service listing the jars that service actually depends on. This turns those lists back into
// directories — by hard link, so 1,246 classpath entries occupy the 172 jars' worth of disk they
// really are.
//
// It exists because the obvious shortcut does not work. Pointing every service at the pooled
// directory gives all twelve one merged classpath, and that broke on the first run: the gateway is
// reactive and the other eleven are servlet, so identity-service found Spring Cloud Gateway on its
// classpath and refused to start — "Spring MVC found on classpath, which is incompatible with
// Spring Cloud Gateway". That was the fortunate failure. The unfortunate one is a service quietly
// acquiring an auto-configuration from a dependency it never declared, which nothing reports.
//
// A hard link and not a symbolic one: creating a symlink on Windows needs either developer mode or
// elevation, while a hard link on NTFS needs neither. Copying is the fallback for a filesystem that
// has neither, and it costs disk rather than correctness.
func (r runtimeTree) linkClasspaths() error {
	for _, svc := range services {
		list := filepath.Join(r.root, "classpath", svc.Name+".txt")
		names, err := readLines(list)
		if err != nil {
			return fmt.Errorf("%s has no classpath list in this payload: %w", svc.Name, err)
		}
		dir := r.classpathDir(svc.Name)
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return err
		}
		for _, name := range names {
			source := filepath.Join(r.libDir(), name)
			target := filepath.Join(dir, name)
			if fileExists(target) {
				continue
			}
			if err := os.Link(source, target); err == nil {
				continue
			}
			if err := copyFile(source, target); err != nil {
				return fmt.Errorf("%s: %w", name, err)
			}
		}
	}
	return nil
}

func readLines(path string) ([]string, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var out []string
	for _, line := range strings.Split(string(raw), "\n") {
		if line = strings.TrimSpace(line); line != "" {
			out = append(out, line)
		}
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("%s is empty", path)
	}
	return out, nil
}

func copyFile(source, target string) error {
	in, err := os.Open(source)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}
