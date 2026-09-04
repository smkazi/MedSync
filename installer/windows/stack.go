package main

import (
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"
)

// Building and running the platform without Docker: fourteen Maven modules, a Next.js app, twelve
// JVMs and, when Python is present, one more service.
//
// This is a reimplementation of what scripts/local.sh does on Unix, and that duplication is the
// cost of Windows having no bash. It is kept honest by the ports and the start order living in one
// table (state.go) that both the start and the stop path read, and by the CI job that runs this
// binary on a real Windows runner — a second start path nobody exercises is a start path that has
// already drifted.

// onWindows is used wherever a command's name differs, which for Node and Maven it does: npm and
// mvn are batch scripts there, and `exec.LookPath` finds them only with the extension because
// Windows resolves executables through PATHEXT rather than through a shebang.
var onWindows = runtime.GOOS == "windows"

func mavenCmd() string {
	if onWindows {
		if p, err := exec.LookPath("mvn.cmd"); err == nil {
			return p
		}
	}
	t := &tool{command: "mvn"}
	t.look()
	if t.found {
		return t.path
	}
	return "mvn"
}

func nodeTool(name string) string {
	if onWindows {
		for _, ext := range []string{".cmd", ".exe", ".bat"} {
			if p, err := exec.LookPath(name + ext); err == nil {
				return p
			}
		}
	}
	if p, err := exec.LookPath(name); err == nil {
		return p
	}
	return name
}

func run(dir string, env []string, name string, args ...string) error {
	cmd := exec.Command(name, args...)
	cmd.Dir = dir
	cmd.Env = env
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	return cmd.Run()
}

func runQuiet(dir string, env []string, logName string, name string, args ...string) error {
	_ = os.MkdirAll(logDir(), 0o755)
	path := filepath.Join(logDir(), logName)
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	cmd := exec.Command(name, args...)
	cmd.Dir = dir
	cmd.Env = env
	cmd.Stdout, cmd.Stderr = f, f
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("%w (full output in %s)", err, path)
	}
	return nil
}

// ---- build -------------------------------------------------------------------------------------

func (s *state) build(src string) {
	step("Building the Java modules")
	dim("the first run downloads the Maven dependencies — expect several minutes")
	if err := run(src, s.serviceEnv(), mavenCmd(), "-B", "-ntp", "-q", "package", "-DskipTests"); err != nil {
		fail("the Java build failed: %v", err)
	}
	jars, _ := filepath.Glob(filepath.Join(src, "services", "*", "target", "*.jar"))
	ok("%d service jars built", len(jars))

	step("Building the web app")
	web := filepath.Join(src, "web")
	if _, err := os.Stat(filepath.Join(web, "node_modules")); err != nil {
		dim("installing npm packages")
		// `npm ci` when there is a lockfile and `npm install` otherwise, rather than one or the
		// other: ci is reproducible and refuses to run without package-lock.json, and a checkout
		// downloaded as a tarball always has one.
		if err := runQuiet(web, s.webEnv(), "npm-install.log", nodeTool("npm"), "ci"); err != nil {
			if err := runQuiet(web, s.webEnv(), "npm-install.log", nodeTool("npm"), "install"); err != nil {
				fail("npm install failed: %v", err)
			}
		}
	}
	if err := runQuiet(web, s.webEnv(), "web-build.log", nodeTool("npm"), "run", "build"); err != nil {
		fail("the web build failed: %v", err)
	}
	ok("web app built")
}

func (s *state) webEnv() []string {
	env := s.serviceEnv()
	env = append(env,
		fmt.Sprintf("GATEWAY_URL=http://127.0.0.1:%d", gatewayPort),
		"IDENTITY_URL=http://127.0.0.1:8081",
		"NEXT_PUBLIC_HMS_ZONE="+envOr("HMS_ZONE", "Asia/Kolkata"),
		"COOKIE_SECURE=false",
	)
	return env
}

// ---- start -------------------------------------------------------------------------------------

func jarFor(src, service string) string {
	pattern := filepath.Join(src, "services", service, "target", service+"-*.jar")
	hits, _ := filepath.Glob(pattern)
	var real []string
	for _, h := range hits {
		// The reactor also writes <name>-<version>-sources.jar when the sources plugin runs; that
		// one has no manifest and `java -jar` on it fails with a message about a missing main class
		// rather than anything that points at the real problem.
		if !strings.Contains(filepath.Base(h), "sources") {
			real = append(real, h)
		}
	}
	if len(real) == 0 {
		return ""
	}
	sort.Strings(real)
	return real[0]
}

func (s *state) startServices(src string) {
	step("Starting the services")
	env := s.serviceEnv()
	for _, svc := range services {
		if portBusy(svc.Port) {
			ok("%-22s already on %d", svc.Name, svc.Port)
			s.Ports[svc.Name] = svc.Port
			continue
		}
		jar := jarFor(src, svc.Name)
		if jar == "" {
			fail("no jar for %s — the build did not produce one", svc.Name)
		}
		pid, err := spawn(filepath.Join(logDir(), svc.Name+".log"), src, env,
			"java", "-jar", jar, "--spring.profiles.active=dev")
		if err != nil {
			fail("could not start %s: %v", svc.Name, err)
		}
		s.Pids[svc.Name] = pid
		s.Ports[svc.Name] = svc.Port
		s.save()

		if !waitHTTP(fmt.Sprintf("http://127.0.0.1:%d/actuator/health", svc.Port), 120*time.Second) {
			bad("%-22s did not come up", svc.Name)
			fail("%s never answered on %d.\n\nIts log is %s", svc.Name, svc.Port,
				filepath.Join(logDir(), svc.Name+".log"))
		}
		ok("%-22s pid %-7d port %d", svc.Name, pid, svc.Port)
	}
}

func (s *state) startWeb(src string) {
	if portBusy(webPort) {
		ok("web already on %d", webPort)
		s.Ports["web"] = webPort
		return
	}
	step("Starting the web app")
	// `next start` through npx rather than `npm run start`, because that script pins its own
	// --port on the command line and would ignore a different one asked for here.
	pid, err := spawn(filepath.Join(logDir(), "web.log"), filepath.Join(src, "web"), s.webEnv(),
		nodeTool("npx"), "next", "start", "--port", fmt.Sprint(webPort))
	if err != nil {
		fail("could not start the web app: %v", err)
	}
	s.Pids["web"] = pid
	s.Ports["web"] = webPort
	s.save()
	if !waitHTTP(fmt.Sprintf("http://127.0.0.1:%d/login", webPort), 120*time.Second) {
		fail("the web app never answered on %d.\n\nIts log is %s", webPort, filepath.Join(logDir(), "web.log"))
	}
	ok("web on %d", webPort)
}

func waitHTTP(url string, timeout time.Duration) bool {
	client := &http.Client{Timeout: 4 * time.Second}
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		resp, err := client.Get(url)
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode < 400 {
				return true
			}
		}
		time.Sleep(2 * time.Second)
	}
	return false
}

// ---- stop --------------------------------------------------------------------------------------

func (s *state) stopAll() {
	step("Stopping")
	// The port list is taken before anything is stopped, because the check afterwards has to know
	// which ports to watch and stopping clears the record of them.
	ports := s.allPorts()

	names := make([]string, 0, len(s.Pids))
	for name := range s.Pids {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		pid := s.Pids[name]
		if err := terminate(pid); err != nil {
			dim("%s (pid %d) was not running", name, pid)
		} else {
			ok("stopped %s (pid %d)", name, pid)
		}
		delete(s.Pids, name)
		delete(s.Ports, name)
	}
	s.save()

	// SIGTERM's Windows equivalent returns at once and a JVM takes a second or two to close its
	// listener, so reporting success immediately is how a clean-looking stop is followed by a start
	// that fails on "port in use".
	if still := waitPortFree(ports, 30*time.Second); still == 0 {
		ok("every port is free")
	} else {
		warn("%d port(s) still bound after 30s — check Task Manager for stray java.exe", still)
	}
}

func (s *state) status() {
	step("Status")
	if len(s.Ports) == 0 {
		dim("nothing recorded — this installer has not started anything yet")
	}
	names := make([]string, 0, len(s.Ports))
	for name := range s.Ports {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		state := "down"
		if portBusy(s.Ports[name]) {
			state = "up"
		}
		say("   %-22s %-6d %s", name, s.Ports[name], state)
	}
	if s.DBPort > 0 {
		state := "down"
		if portBusy(s.DBPort) {
			state = "up"
		}
		say("   %-22s %-6d %s", "postgresql ("+s.DBMode+")", s.DBPort, state)
	}
}

// ---- the container path ------------------------------------------------------------------------

// Docker Desktop is the shortest route on Windows when it is there: the repository already carries
// a compose file that brings up PostgreSQL, Kafka, every service and the web app, so this path
// installs no JDK, no Maven and no Node.
//
// It writes the .env that compose refuses to start without. That refusal is deliberate in the
// repository — an earlier version of the compose file carried a literal development PHI key, which
// the secret scanner flagged, correctly — so generating the values here rather than committing them
// is the same decision one layer out.
func (s *state) composeUp(src string) {
	docker, err := exec.LookPath("docker")
	if err != nil {
		fail("docker is not on PATH")
	}
	envFile := filepath.Join(src, ".env")
	if _, err := os.Stat(envFile); err != nil {
		content := fmt.Sprintf("HMS_PHI_KEY=%s\nHMS_DB_PASSWORD=hms\nHMS_SEED_ENABLED=true\nHMS_SEED_PASSWORD=%s\nHMS_EVENTS_TRANSPORT=kafka\nHMS_ZONE=%s\nNEXT_PUBLIC_HMS_ZONE=%s\nCOOKIE_SECURE=false\n",
			s.PHIKey, seedPassword(), envOr("HMS_ZONE", "Asia/Kolkata"), envOr("HMS_ZONE", "Asia/Kolkata"))
		if err := os.WriteFile(envFile, []byte(content), 0o600); err != nil {
			fail("could not write %s: %v", envFile, err)
		}
		ok("wrote %s", envFile)
	} else {
		ok("using the existing %s", envFile)
	}

	step("Starting the containers (this builds them the first time)")
	if err := run(src, os.Environ(), docker, "compose", "up", "--build", "-d"); err != nil {
		fail("docker compose failed: %v", err)
	}
	s.Mode = "compose"
	s.Ports = map[string]int{"web": webPort, "gateway": gatewayPort}
	s.save()

	step("Waiting for the platform to answer")
	if !waitHTTP(fmt.Sprintf("http://127.0.0.1:%d/actuator/health", gatewayPort), 6*time.Minute) {
		fail("the gateway never answered.\n\nTry: docker compose logs gateway")
	}
	ok("gateway up")
	if !waitHTTP(fmt.Sprintf("http://127.0.0.1:%d/login", webPort), 3*time.Minute) {
		fail("the web app never answered.\n\nTry: docker compose logs web")
	}
	ok("web up")
}

func (s *state) composeDown(src string) {
	docker, err := exec.LookPath("docker")
	if err != nil {
		return
	}
	step("Stopping the containers")
	if err := run(src, os.Environ(), docker, "compose", "down"); err != nil {
		warn("docker compose down: %v", err)
		return
	}
	s.Ports = map[string]int{}
	s.save()
	ok("stopped")
}
