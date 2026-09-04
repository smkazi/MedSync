package main

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"time"
)

// What is on this machine, and what to tell somebody who is missing it.
//
// The whole file is detection and reporting. Nothing here installs anything — see winget.go for
// that, and note that it asks first.

type tool struct {
	name    string // what a human calls it
	command string // what to look for on PATH
	minMaj  int    // major version floor, 0 for "any"
	winget  string // winget package id, empty when there is no sensible one
	url     string // where to get it by hand
	found   bool
	version string
	path    string
}

// The version flag differs per tool and two of them print it to stderr, so each one is asked in its
// own way rather than through a shared "run --version" that silently reports nothing.
var versionArgs = map[string][]string{
	"java":   {"-version"},
	"mvn":    {"-v"},
	"node":   {"-v"},
	"npm":    {"-v"},
	"psql":   {"--version"},
	"docker": {"version", "--format", "{{.Client.Version}}"},
}

var firstNumber = regexp.MustCompile(`(\d+)(?:\.(\d+))?(?:\.(\d+))?`)

// look fills in what is known about one tool.
func (t *tool) look() {
	// Explicit extra directories before PATH is consulted, because the two prerequisites most
	// likely to be present and invisible on Windows are exactly these: the JDK and PostgreSQL both
	// install under Program Files without touching PATH unless the user ticks a box.
	if p, err := exec.LookPath(t.command); err == nil {
		t.path = p
	} else if p := t.searchKnownDirs(); p != "" {
		t.path = p
	} else {
		return
	}
	t.found = true

	out, _ := exec.Command(t.path, versionArgs[t.command]...).CombinedOutput()
	// CombinedOutput, not Output: `java -version` writes to stderr, and a JAVA_TOOL_OPTIONS in the
	// environment makes it print a "Picked up ..." line first — which is why the version is found
	// by searching every line for something version-shaped rather than by reading the first one.
	for _, line := range strings.Split(string(out), "\n") {
		if m := firstNumber.FindStringSubmatch(line); m != nil && looksLikeVersionLine(line) {
			t.version = m[0]
			return
		}
	}
	if m := firstNumber.FindStringSubmatch(string(out)); m != nil {
		t.version = m[0]
	}
}

func looksLikeVersionLine(line string) bool {
	l := strings.ToLower(line)
	if strings.Contains(l, "picked up") {
		return false
	}
	return strings.ContainsAny(l, "0123456789")
}

func (t *tool) major() int {
	m := firstNumber.FindStringSubmatch(t.version)
	if m == nil {
		return 0
	}
	n, _ := strconv.Atoi(m[1])
	return n
}

func (t *tool) satisfied() bool {
	return t.found && (t.minMaj == 0 || t.major() >= t.minMaj)
}

// searchKnownDirs looks where Windows installers actually put things. Newest version first, so a
// machine with JDK 17 and JDK 21 side by side gets 21.
func (t *tool) searchKnownDirs() string {
	var globs []string
	exe := t.command + ".exe"
	if runtime.GOOS != "windows" {
		// The Unix build exists to exercise this program's logic on the machine it was written on,
		// so it needs the same "installed but not on PATH" handling — Debian and Homebrew both keep
		// initdb and pg_ctl out of PATH, which is the identical problem Program Files causes on
		// Windows. Without these the Linux run reports no PostgreSQL on a box that has a server.
		switch t.command {
		case "psql", "initdb", "pg_ctl":
			globs = []string{
				"/usr/lib/postgresql/*/bin/" + t.command,
				"/usr/pgsql-*/bin/" + t.command,
				"/opt/homebrew/opt/postgresql@*/bin/" + t.command,
				"/usr/local/opt/postgresql@*/bin/" + t.command,
			}
		default:
			return ""
		}
		return newest(globs)
	}
	switch t.command {
	case "java":
		globs = []string{
			`C:\Program Files\Eclipse Adoptium\jdk-*\bin\java.exe`,
			`C:\Program Files\Microsoft\jdk-*\bin\java.exe`,
			`C:\Program Files\Java\jdk-*\bin\java.exe`,
			`C:\Program Files\Zulu\zulu-*\bin\java.exe`,
		}
	case "psql", "initdb", "pg_ctl":
		globs = []string{`C:\Program Files\PostgreSQL\*\bin\` + exe}
	case "mvn":
		globs = []string{`C:\Program Files\Apache\maven*\bin\mvn.cmd`, `C:\ProgramData\chocolatey\bin\mvn.exe`}
	case "node", "npm", "npx":
		globs = []string{`C:\Program Files\nodejs\` + t.command + `.*`}
	default:
		return ""
	}
	return newest(globs)
}

// newest picks the highest-sorting match, which for these directory layouts means the newest
// version: a machine with JDK 17 and JDK 21 side by side must get 21, or the platform refuses to
// start on a box that can perfectly well run it.
func newest(globs []string) string {
	var hits []string
	for _, g := range globs {
		found, _ := filepath.Glob(g)
		hits = append(hits, found...)
	}
	if len(hits) == 0 {
		return ""
	}
	sort.Sort(sort.Reverse(sort.StringSlice(hits)))
	return hits[0]
}

type environment struct {
	java, maven, node, psql, docker *tool
	dockerRunning                   bool
}

func inspect() *environment {
	e := &environment{
		java: &tool{name: "Java (JDK 21+)", command: "java", minMaj: 21, winget: "EclipseAdoptium.Temurin.21.JDK", url: "https://adoptium.net/temurin/releases/?version=21"},
		// No winget id, deliberately. `winget install --id Apache.Maven` answered "No package found
		// matching input criteria" on a real machine, which stopped an install that had otherwise
		// succeeded — and shipping a package id that may or may not exist in somebody's winget
		// source is worse than shipping none. Maven is not needed anyway: the repository carries a
		// Maven wrapper, so `mvnw.cmd` builds the project with no Maven installed at all.
		maven: &tool{name: "Maven 3.9+", command: "mvn", minMaj: 3, winget: "", url: "https://maven.apache.org/download.cgi"},
		node:  &tool{name: "Node 22+", command: "node", minMaj: 22, winget: "OpenJS.NodeJS.LTS", url: "https://nodejs.org/en/download"},
		psql:  &tool{name: "PostgreSQL 14+", command: "psql", minMaj: 14, winget: "PostgreSQL.PostgreSQL.16", url: "https://www.postgresql.org/download/windows/"},
		docker: &tool{name: "Docker Desktop", command: "docker", minMaj: 0,
			winget: "Docker.DockerDesktop", url: "https://www.docker.com/products/docker-desktop/"},
	}
	for _, t := range []*tool{e.java, e.maven, e.node, e.psql, e.docker} {
		t.look()
	}
	// Installed and running are different questions, and only the second one matters. Docker
	// Desktop leaves `docker.exe` on PATH whether or not the engine behind it is up, so a plain
	// `docker version` check would send the installer down the container path and then fail on
	// "cannot connect to the Docker daemon" with everything already committed to that route.
	if e.docker.found {
		// Bounded, because this is the one probe here that can hang rather than fail. `docker info`
		// on a machine where Docker Desktop is installed but its engine is starting, stopped, or
		// wedged blocks on the named pipe — it took 44 seconds on a CI runner where the engine was
		// healthy — and an installer whose very first screen sits silent for a minute reads as
		// broken. Ten seconds is far longer than a running engine ever needs, and a timeout means
		// exactly what a failure means: not usable, take the other route.
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		e.dockerRunning = exec.CommandContext(ctx, e.docker.path, "info").Run() == nil
	}
	return e
}

// report prints the table and returns the tools that are missing or too old.
func (e *environment) report() []*tool {
	var short []*tool
	// Maven is not in this loop, and that is the whole point of it not being here: the repository
	// ships a Maven wrapper, so a machine with no Maven builds the project perfectly well. It is
	// reported below as information rather than as something to go and install.
	for _, t := range []*tool{e.java, e.node, e.psql} {
		switch {
		case !t.found:
			warn("%-16s not found", t.name)
			short = append(short, t)
		case !t.satisfied():
			warn("%-16s %s — too old", t.name, t.version)
			short = append(short, t)
		default:
			ok("%-16s %s", t.name, t.version)
		}
	}
	switch {
	case e.maven.satisfied():
		ok("%-16s %s", e.maven.name, e.maven.version)
	case e.maven.found:
		dim("%-16s %s — the bundled Maven wrapper will be used instead", e.maven.name, e.maven.version)
	default:
		dim("%-16s not installed — the bundled Maven wrapper will be used", e.maven.name)
	}
	switch {
	case e.dockerRunning:
		ok("%-16s %s, engine running", e.docker.name, e.docker.version)
	case e.docker.found:
		warn("%-16s installed, but the engine is not running", e.docker.name)
	default:
		dim("%-16s not installed (optional)", e.docker.name)
	}
	return short
}

// nativePathReady is true when the platform can be built and run without Docker.
//
// Two things are deliberately absent. PostgreSQL, because the ladder in db.go can reach one four
// different ways and only one of them needs psql on this machine. And Maven, because the repository
// ships a wrapper — a real install stopped here for want of a Maven that the project does not
// actually require, which is the worst kind of refusal: correct by its own rules and wrong about
// the world.
func (e *environment) nativePathReady() bool {
	return e.java.satisfied() && e.node.satisfied()
}

func hasWinget() bool {
	_, err := exec.LookPath("winget")
	return err == nil
}

// windowsBuild reports the Windows release, used only to explain a missing winget: it ships with
// App Installer on Windows 10 1809 and later, and its absence on anything newer usually means the
// machine is a stripped image or a Server SKU rather than that the OS is old.
func windowsBuild() string {
	if runtime.GOOS != "windows" {
		return ""
	}
	out, err := exec.Command("cmd", "/c", "ver").Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
