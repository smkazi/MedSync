package main

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// MedSync-Setup.exe — install and run the whole platform from one double-click.
//
// A console application rather than a windowed one, and that is a decision rather than laziness.
// This install builds fourteen Java modules and a Next.js app and can take twenty minutes on a
// first run; a progress bar with no detail behind it would tell somebody nothing while they wait,
// and when a build fails the thing they need is the compiler's own words. So the window is a log,
// it stays open at the end when it was double-clicked, and every path out of the program either
// prints why or prints where to look.
//
// Build:
//     GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -o MedSync-Setup.exe ./installer/windows
//
// CGO off, so the result is one static file with no runtime, no Visual C++ redistributable and no
// installer framework behind it.

const version = "1.0.0"

func main() {
	args := os.Args[1:]
	// No arguments means a double-click, which means the console belongs to this process and will
	// vanish with it. That is what makes the pause at the end necessary, and it is also the signal
	// that nobody is scripting this and questions may be asked.
	interactive = len(args) == 0 && startedFromExplorer()

	command := "up"
	if len(args) > 0 {
		command = strings.ToLower(strings.TrimPrefix(args[0], "--"))
	}

	switch command {
	case "up", "install", "start":
		cmdUp()
	case "doctor", "check":
		cmdDoctor()
	case "fetch":
		// Its own command because it is the one step somebody may want to do on its own — on a
		// slow connection, or to look at the source before building it — and because it is the
		// only way to exercise the download and unpack path without a twenty-minute build behind
		// it. A step that can only be tested as part of something else does not get tested.
		src := ensureSource()
		s := loadState()
		s.Source = src
		s.save()
		pause()
	case "smoke":
		cmdSmoke()
	case "db", "database":
		// The database ladder on its own. Two reasons it is a command rather than a private step:
		// somebody running the services from an IDE wants exactly this and nothing else, and it is
		// the only way to exercise the ladder — initdb, pg_ctl, the extensions — without a
		// twenty-minute build in front of it, which is what lets CI check it on every push.
		s := loadState()
		s.ensureKey()
		s.database()
		s.prepare()
		ok("HMS_DB_URL=%s", s.dbURL())
		pause()
	case "status":
		loadState().status()
	case "down", "stop":
		cmdDown()
	case "uninstall":
		cmdUninstall()
	case "version", "v":
		say("MedSync-Setup %s (%s/%s)", version, runtime.GOOS, runtime.GOARCH)
	case "help", "h", "?", "/?":
		usage()
	default:
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Printf(`MedSync-Setup %s — install, run and check MedSync.

  MedSync-Setup.exe              install if needed, start everything, check it, open a browser
                                 (this is what a double-click does)
  MedSync-Setup.exe doctor       report prerequisites, the database and the ports
  MedSync-Setup.exe fetch        download the source only, without building it
  MedSync-Setup.exe db           provision a PostgreSQL and print its URL, nothing else
  MedSync-Setup.exe smoke        sign in and read one screen from every service
  MedSync-Setup.exe status       what is running, and on which port
  MedSync-Setup.exe down         stop everything
  MedSync-Setup.exe uninstall    stop everything and delete what this installed
  MedSync-Setup.exe version

Environment:
  MEDSYNC_HOME         where everything is kept        (default %%LOCALAPPDATA%%\MedSync)
  MEDSYNC_MODE         native or docker                (default: docker when it is running)
  MEDSYNC_NO_BROWSER   set to 1 to not open a browser
  MEDSYNC_SKIP_BUILD   set to 1 to start what is already built
  MEDSYNC_ASSUME_YES   set to 1 to install prerequisites without asking
  MEDSYNC_REPO/_REF    download the source from somewhere else
  MEDSYNC_TOKEN        a GitHub token, if the repository is private
  HMS_DB_URL           use a PostgreSQL of your own instead of the ladder
`, version)
}

func banner() {
	say(`
================================================================================
 MedSync-Setup %s
 A hospital management platform: twelve services, a browser app, one database.
================================================================================`, version)
}

func cmdDoctor() {
	banner()
	step("Prerequisites")
	env := inspect()
	missing := env.report()

	step("Route")
	switch chooseMode(env) {
	case "compose":
		ok("Docker Desktop is running — containers, no JDK or Node needed")
	case "native":
		ok("Native — build and run on this machine")
	default:
		bad("Neither route is available yet")
	}

	step("Database")
	switch {
	case os.Getenv("HMS_DB_URL") != "":
		ok("HMS_DB_URL is set: %s", os.Getenv("HMS_DB_URL"))
	case portBusy(5432):
		ok("something is already listening on 5432 — it will be used")
	case pgTool("initdb") != "" && pgTool("pg_ctl") != "":
		ok("PostgreSQL server binaries at %s", filepath.Dir(pgTool("initdb")))
		dim("a private cluster will be created on port %d and removed by `uninstall`", privateDBPort)
	case env.dockerRunning:
		ok("a PostgreSQL container will be used")
	case env.psql.satisfied():
		// Worth separating from "no PostgreSQL at all", because the fix is different: a client-only
		// install needs the server package, not a first-time install of PostgreSQL.
		warn("only the PostgreSQL client is installed — initdb and pg_ctl were not found")
		dim("install the server package, or start Docker Desktop, or set HMS_DB_URL")
	default:
		warn("no PostgreSQL and no Docker — one of them is needed")
	}

	step("Ports")
	// Ownership matters, not just occupancy. A port held by this installer's own previous run is
	// fine and a port held by something else is a problem, and reporting them the same way sends
	// somebody hunting for a conflict that is their own MedSync still running.
	s := loadState()
	ours := map[int]bool{}
	for _, p := range s.Ports {
		ours[p] = true
	}
	busy, mine := 0, 0
	for _, p := range append([]int{webPort}, portsOfServices()...) {
		switch {
		case !portBusy(p):
			ok("%d free", p)
		case ours[p]:
			ok("%d this MedSync", p)
			mine++
		default:
			warn("%d in use by something this installer did not start", p)
			busy++
		}
	}

	say("")
	if len(missing) > 0 && chooseMode(env) == "" {
		bad("%d prerequisite(s) missing and no Docker. Run this without arguments to install them.", len(missing))
		pause()
		os.Exit(1)
	}
	if busy > 0 {
		warn("%d port(s) are occupied. Free them, or `MedSync-Setup.exe down` if a previous run left them.", busy)
		pause()
		os.Exit(1)
	}
	if mine > 0 {
		ok("MedSync is already running on %d port(s).", mine)
	}
	ok("Ready.")
	pause()
}

func portsOfServices() []int {
	var out []int
	for _, s := range services {
		out = append(out, s.Port)
	}
	return out
}

// chooseMode decides between containers and a native build.
//
// Docker first when its engine is actually running, because that route needs no JDK, no Maven, no
// Node and no PostgreSQL — it is the shortest distance between a double-click and a login page. An
// explicit MEDSYNC_MODE beats the guess in both directions, including forcing native on a machine
// that has Docker, which is what somebody developing against this will want.
func chooseMode(env *environment) string {
	switch strings.ToLower(os.Getenv("MEDSYNC_MODE")) {
	case "native":
		if env.nativePathReady() {
			return "native"
		}
		return ""
	case "docker", "compose":
		if env.dockerRunning {
			return "compose"
		}
		return ""
	}
	if env.dockerRunning {
		return "compose"
	}
	if env.nativePathReady() {
		return "native"
	}
	return ""
}

func cmdUp() {
	banner()
	s := loadState()
	s.ensureKey()

	step("Prerequisites")
	env := inspect()
	missing := env.report()

	mode := chooseMode(env)
	if mode == "" {
		// Nothing usable yet. Offer the install, then look again — refreshPath is what makes the
		// second look able to see what the first one could not.
		if offerInstall(missing) {
			refreshPath()
			step("Checking again")
			env = inspect()
			env.report()
			mode = chooseMode(env)
		}
	}
	if mode == "" {
		fail("MedSync cannot run yet.\n\n" +
			"Either install the prerequisites above, or install Docker Desktop and start it —\n" +
			"with Docker running this installer needs nothing else at all:\n\n" +
			"    winget install --id Docker.DockerDesktop")
	}

	src := ensureSource()
	s.Source = src
	s.Mode = mode
	s.save()

	if mode == "compose" {
		s.composeUp(src)
	} else {
		s.database()
		s.prepare()
		say("")
		dim("database  %s", s.dbURL())
		dim("logs      %s", logDir())
		if os.Getenv("MEDSYNC_SKIP_BUILD") != "1" {
			s.build(src)
		}
		s.startServices(src)
		s.startWeb(src)
	}

	if failures := smoke(); failures > 0 {
		fail("%d check(s) failed. The logs are in %s", failures, logDir())
	}
	say("")
	ok("Everything answered.")

	printAccounts()
	if os.Getenv("MEDSYNC_NO_BROWSER") != "1" {
		openBrowser(fmt.Sprintf("http://localhost:%d", webPort))
	}
	pause()
}

func cmdSmoke() {
	if failures := smoke(); failures > 0 {
		fail("%d check(s) failed. The logs are in %s", failures, logDir())
	}
	say("")
	ok("Everything answered.")
	pause()
}

func cmdDown() {
	s := loadState()
	if s.Mode == "compose" && s.Source != "" {
		s.composeDown(s.Source)
	} else {
		s.stopAll()
	}
	dim("the database is left running; `MedSync-Setup.exe uninstall` removes it")
	pause()
}

func cmdUninstall() {
	banner()
	s := loadState()
	if s.Mode == "compose" && s.Source != "" {
		s.composeDown(s.Source)
	} else {
		s.stopAll()
		s.stopDatabase()
	}
	say("")
	say("   About to delete %s", homeDir())
	say("   That removes the database cluster, the downloaded source, the logs and the key.")
	if !confirm("Delete it?") {
		say("")
		dim("Nothing was deleted. MedSync is stopped.")
		pause()
		return
	}
	if err := os.RemoveAll(homeDir()); err != nil {
		fail("could not delete %s: %v", homeDir(), err)
	}
	ok("Removed.")
	dim("Anything installed with winget is still installed; `winget uninstall` removes those.")
	pause()
}

// The accounts, with what each one is for.
//
// Printed rather than left to the README because the separations are the most interesting thing
// about this platform and a demo where somebody only ever signs in as the administrator shows none
// of them. The last line is the point: these are enforced by the services, so trying it is a 403
// and not a missing menu item.
func printAccounts() {
	say(`
--------------------------------------------------------------------------------
 MedSync is running.

   Web app            http://localhost:%d
   API gateway        http://localhost:%d
   Corridor display   http://localhost:%d/display/GF-GEN   (no sign-in: carries no
                      patient information at all, by design)

 Sign in with any of these. The password for all of them is:

   %s

   admin            everything, and the audit trail
   dr.rao           a doctor - charts, orders, prescribes
   nurse.iqbal      a nurse - triage, the casualty board, the drug round
   reception        the front desk - registers and books, and cannot open a chart
   lab.tech         collects specimens and enters results, cannot verify them
   dr.pathan        a pathologist - verifies and releases
   pharmacist       dispenses and keeps the stock, cannot open a chart
   cashier          invoices and payments, cannot open a chart
   radiographer     runs the scanner worklist, cannot report on it
   dr.mistry        a radiologist - reports and signs, cannot order the scan
   epidemiologist   aggregate rates and notifiable counts, and nothing per-patient
   new.starter      still on its first password, so it can only change it

 Those separations are enforced by the services rather than by the menu: signing
 in as the cashier and typing a chart address gives you a 403, not a hidden link.

   MedSync-Setup.exe status     what is running
   MedSync-Setup.exe smoke      check it again
   MedSync-Setup.exe down       stop it
   MedSync-Setup.exe uninstall  stop it and remove everything

 Logs: %s
--------------------------------------------------------------------------------`,
		webPort, gatewayPort, webPort, seedPassword(), filepath.Clean(logDir()))
}
