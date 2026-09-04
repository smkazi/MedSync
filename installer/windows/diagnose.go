package main

import (
	"bufio"
	"fmt"
	"os"
	"strings"
)

// Turning a failed start into an explanation, in the window the person is already looking at.
//
// This file exists because "Its log is C:\Users\...\identity-service.log" is not a diagnosis. It is
// a homework assignment, handed to somebody who has just waited twenty minutes and who — having
// double-clicked an installer — may reasonably have no idea how to open a log file, let alone which
// of its four hundred lines matters. The installer has the file open in front of it. It can read it.
//
// So on a failed start it prints the tail, and then says in one sentence what the tail means
// whenever it recognises the shape. Every signature below is a failure this platform can actually
// produce on a first install, and each one has a different fix — which is precisely why printing
// them all as "it did not come up" was useless.

/** How much of the tail to show: enough for a stack trace's cause, not enough to scroll the screen away. */
const logTailLines = 30

type signature struct {
	// Matched against the whole tail, lower-cased. Several may match; all that do are printed,
	// because a start can fail for two reasons at once and guessing which is "the" one is how a
	// diagnostic sends somebody down the wrong path.
	needle  string
	meaning string
	fix     string
}

var signatures = []signature{
	{
		needle:  "does not exist",
		meaning: "the database is not there — the service connected to the server and found no `hms` database",
		fix:     "run `MedSync-Setup.exe uninstall`, then start again: the database is created before the build",
	},
	{
		needle:  "connection refused",
		meaning: "nothing is listening where the services were told the database is",
		fix:     "check `MedSync-Setup.exe doctor` — if a private cluster was created, it is not running",
	},
	{
		needle:  "password authentication failed",
		meaning: "the database is there but rejected the login",
		fix:     "set HMS_DB_USER and HMS_DB_PASSWORD to credentials that work, or let the installer make its own cluster",
	},
	{
		needle:  "pg_trgm",
		meaning: "the pg_trgm extension is missing, and the very first migration needs it",
		fix:     "psql -U hms -d hms -c \"create extension pg_trgm; create extension btree_gist;\"",
	},
	{
		needle:  "btree_gist",
		meaning: "the btree_gist extension is missing, and the appointment overlap guard is built on it",
		fix:     "psql -U hms -d hms -c \"create extension pg_trgm; create extension btree_gist;\"",
	},
	{
		needle:  "validate failed",
		meaning: "Flyway found migrations already applied that no longer match — this database was migrated by a different version",
		fix:     "`MedSync-Setup.exe uninstall` and start again, which builds a fresh database",
	},
	{
		needle:  "already in use",
		meaning: "another program is already on that port",
		fix:     "`MedSync-Setup.exe down` if a previous run left it, or close whatever else is using it",
	},
	{
		needle:  "unsupportedclassversion",
		meaning: "the JVM running the service is older than the one it was built with",
		fix:     "check `java -version` is 21 or newer, and that JAVA_HOME points at the same one",
	},
	{
		needle:  "out of memory",
		meaning: "the JVM could not get the memory it asked for",
		fix:     "close other applications, or run fewer services by using the Docker route instead",
	},
	{
		needle:  "unable to obtain jdbc connection",
		meaning: "the service started but could not reach the database at all",
		fix:     "`MedSync-Setup.exe doctor` reports which database it would use and whether it answers",
	},
}

// explainLog prints the end of a log file and whatever it can be read to mean.
func explainLog(path string) {
	lines, err := lastLines(path, logTailLines)
	if err != nil {
		dim("could not read %s: %v", path, err)
		return
	}
	if len(lines) == 0 {
		dim("%s is empty — the process died before it could log anything", path)
		return
	}

	say("")
	say("   The last %d lines of %s:", len(lines), path)
	say("   " + strings.Repeat("-", 74))
	for _, line := range lines {
		// Truncated rather than wrapped: a stack frame that wraps four times pushes the cause off
		// the screen, and the cause is the only line anybody needs.
		if len(line) > 150 {
			line = line[:150] + " ..."
		}
		fmt.Printf("   | %s\n", line)
	}
	say("   " + strings.Repeat("-", 74))

	haystack := strings.ToLower(strings.Join(lines, "\n"))
	matched := false
	for _, s := range signatures {
		if strings.Contains(haystack, s.needle) {
			if !matched {
				say("")
				say("   What that means:")
				matched = true
			}
			warn("%s", s.meaning)
			dim("try: %s", s.fix)
		}
	}
	if !matched {
		say("")
		dim("Nothing in there matches a failure this installer knows about. The lines above are")
		dim("the whole story the service told; the full file has the rest.")
	}
}

// isStackFrame is true for the lines of a Java trace that carry no information for somebody trying
// to start a program.
//
// Dropped rather than shown, and this was measured rather than assumed: the first version of this
// diagnostic printed the last thirty lines verbatim, and a Spring failure produced twenty-eight
// frames — which pushed "FATAL: database does not exist", the one line that says what to do, off
// the top of the window. The messages and every "Caused by:" survive, which is the whole trace
// anybody reads anyway; the frames are still in the file for whoever wants them.
func isStackFrame(line string) bool {
	trimmed := strings.TrimLeft(line, " \t")
	if len(trimmed) == len(line) {
		return false // not indented: a message, not a frame
	}
	return strings.HasPrefix(trimmed, "at ") ||
		(strings.HasPrefix(trimmed, "... ") && strings.Contains(trimmed, "frames omitted"))
}

// lastLines reads the final n lines of a file.
//
// Straightforwardly, by reading the whole thing: a service log that failed to start is kilobytes,
// not gigabytes, and a seek-backwards implementation would be more code and one more thing to get
// wrong in the middle of reporting somebody else's error.
func lastLines(path string, n int) ([]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	var all []string
	scanner := bufio.NewScanner(f)
	// Spring stack traces and Flyway's SQL echoes both produce long lines; the default 64KB token
	// limit would turn one of those into an error instead of a diagnosis.
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for scanner.Scan() {
		line := strings.TrimRight(scanner.Text(), " \t\r")
		if line == "" || isStackFrame(line) {
			continue
		}
		all = append(all, line)
	}
	if err := scanner.Err(); err != nil && len(all) == 0 {
		return nil, err
	}
	if len(all) > n {
		all = all[len(all)-n:]
	}
	return all, nil
}
