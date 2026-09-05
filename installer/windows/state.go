package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"time"
)

// Everything the installer remembers between runs, in one JSON file under one directory it owns.
//
// One directory means `uninstall` is unambiguous and there is never a question about what was left
// on the machine. On Windows that is %LOCALAPPDATA%\MedSync — LocalAppData rather than AppData
// because none of this should follow a user onto another machine through a roaming profile, and
// a database cluster least of all.

type state struct {
	DBMode  string         `json:"dbMode"`  // external | existing | private | docker
	DBPort  int            `json:"dbPort"`  //
	DBDir   string         `json:"dbDir"`   // the private cluster, when there is one
	Mode    string         `json:"mode"`    // native | compose
	PHIKey  string         `json:"phiKey"`  // generated once; see the comment on ensureKey
	Pids    map[string]int `json:"pids"`    // service name -> process id
	Ports   map[string]int `json:"ports"`   // service name -> port
	Source  string         `json:"source"`  //
	AI      bool           `json:"ai"`      // whether the AI service is part of this install
	Written string         `json:"written"` //
}

func homeDir() string {
	if v := os.Getenv("MEDSYNC_HOME"); v != "" {
		return v
	}
	if runtime.GOOS == "windows" {
		if base := os.Getenv("LOCALAPPDATA"); base != "" {
			return filepath.Join(base, "MedSync")
		}
	}
	home, err := os.UserHomeDir()
	if err != nil {
		home = "."
	}
	return filepath.Join(home, ".medsync")
}

func statePath() string { return filepath.Join(homeDir(), "state.json") }
func logDir() string    { return filepath.Join(homeDir(), "logs") }

func loadState() *state {
	s := &state{Pids: map[string]int{}, Ports: map[string]int{}}
	raw, err := os.ReadFile(statePath())
	if err != nil {
		return s
	}
	_ = json.Unmarshal(raw, s)
	if s.Pids == nil {
		s.Pids = map[string]int{}
	}
	if s.Ports == nil {
		s.Ports = map[string]int{}
	}
	return s
}

func (s *state) save() {
	s.Written = time.Now().UTC().Format(time.RFC3339)
	_ = os.MkdirAll(homeDir(), 0o755)
	raw, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return
	}
	// 0600: the file holds HMS_PHI_KEY. On Windows the mode is largely advisory and the real
	// protection is that LocalAppData is per-user, but a copy of this tree onto a share should not
	// be world-readable either.
	if err := os.WriteFile(statePath(), raw, 0o600); err != nil {
		warn("could not write %s: %v", statePath(), err)
	}
}

// ensureKey generates the PHI key once and then never again.
//
// It decrypts the encrypted patient identifier columns — national id, insurance policy number — so
// a fresh key on the second run would leave every row already written permanently unreadable. The
// platform starts without one using a built-in development key of 32 zero bytes and says so loudly
// in its log; generating a real one here means a local install is not quietly demonstrating the
// insecure path.
func (s *state) ensureKey() {
	if s.PHIKey != "" {
		return
	}
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		fail("could not generate a key: %v", err)
	}
	s.PHIKey = base64.StdEncoding.EncodeToString(buf)
	s.save()
	ok("Generated the PHI encryption key (kept in %s)", statePath())
}

// ---- ports -------------------------------------------------------------------------------------

// The service map, in start order. identity first because every other service validates its tokens
// against it; the gateway last because it health-checks what it routes to.
var services = []struct {
	Name string
	Port int
}{
	{"identity-service", 8081},
	{"patient-service", 8082},
	{"scheduling-service", 8083},
	{"laboratory-service", 8084},
	{"notification-service", 8085},
	{"admissions-service", 8086},
	{"pharmacy-service", 8087},
	{"billing-service", 8088},
	{"interop-service", 8089},
	{"imaging-service", 8090},
	{"immunisation-service", 8091},
	{"gateway", 8080},
}

const (
	gatewayPort = 8080
	webPort     = 3000
	// The AI service. Kept out of the table above because it is a Python process rather than a
	// service jar, and in the table's own terms — a name and a port to health-check — it is
	// identical, which is why aiService below is a row and not a special case.
	aiPort = 8000
)

// aiService is the clinical decision-support service: no-show risk, triage acuity, ICD-10 coding
// suggestions and note summarisation.
//
// It has a row here because until this bundle it had none anywhere, and that was a real defect
// rather than an omission of documentation. `hms.ai.enabled` defaults to **true**
// (scheduling-service's application.yml, `enabled: ${HMS_AI_ENABLED:true}`) and no installer ever
// set it or started anything on 8000 — so every appointment booked on a Windows install called a
// port with nothing behind it and paid the circuit breaker's timeout before falling open. The
// symptom was a booking that took a few seconds and no error anywhere, which is the hardest shape
// of defect to notice and the easiest to fix once seen.
var aiService = struct {
	Name string
	Port int
}{"ai-service", aiPort}

func portBusy(port int) bool {
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 700*time.Millisecond)
	if err != nil {
		return false
	}
	conn.Close()
	return true
}

func waitPortFree(ports []int, timeout time.Duration) int {
	deadline := time.Now().Add(timeout)
	for {
		still := 0
		for _, p := range ports {
			if portBusy(p) {
				still++
			}
		}
		if still == 0 || time.Now().After(deadline) {
			return still
		}
		time.Sleep(time.Second)
	}
}

func (s *state) allPorts() []int {
	var out []int
	for _, p := range s.Ports {
		out = append(out, p)
	}
	return out
}

// ---- the environment every service is started with ---------------------------------------------

// Assembled in one place so the services, the web app and anything run afterwards cannot disagree
// about which database they are talking to. Disagreeing looks like a platform that starts and
// serves nothing, or worse, a suite that passes against a schema nobody is looking at.
func (s *state) serviceEnv() []string {
	env := os.Environ()
	set := func(k, v string) {
		if os.Getenv(k) == "" {
			env = append(env, k+"="+v)
		}
	}
	env = append(env, "HMS_DB_URL="+s.dbURL())
	set("HMS_DB_USER", "hms")
	set("HMS_DB_PASSWORD", "hms")
	env = append(env, "HMS_SEED_ENABLED=true")
	set("HMS_EVENTS_TRANSPORT", "log")
	set("HMS_ZONE", "Asia/Kolkata")
	env = append(env, "HMS_PHI_KEY="+s.PHIKey)
	// Stated rather than inherited. Both of these have defaults baked into scheduling-service that
	// happen to be right when the AI service is running and are silently wrong when it is not, and
	// "the default happens to match" is not a property anybody can check from here.
	env = append(env, fmt.Sprintf("HMS_AI_ENABLED=%t", s.AI))
	env = append(env, fmt.Sprintf("HMS_AI_URI=http://127.0.0.1:%d", aiPort))
	return env
}

func (s *state) dbURL() string {
	if v := os.Getenv("HMS_DB_URL"); v != "" {
		return v
	}
	return fmt.Sprintf("jdbc:postgresql://127.0.0.1:%d/hms", s.DBPort)
}

func seedPassword() string { return envOr("HMS_SEED_PASSWORD", "ChangeMe!Dev2026") }
