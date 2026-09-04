package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// The check that runs at the end of an install, and the reason it is not a health check.
//
// Every service on this platform answers /actuator/health while still being unable to serve a
// request: a database URL pointing at the wrong server, one schema whose migration failed, a token
// the other eleven will not accept. So this signs in as a real seeded user and reads one real
// endpoint from every service through the gateway, plus the one path that is deliberately
// unauthenticated. That is the smallest thing that tells "up" apart from "working", and it is what
// an installer owes somebody before it tells them the install succeeded.

type check struct {
	label string
	path  string
}

// Every path here was verified against a running gateway rather than read off a controller. Three
// of the first guesses answered 404 or 405 — the immunisation reads are under /vaccines/products
// rather than /vaccines, interop's consent register is /consents and not /interop/consents, and
// /imaging/orders is a POST — and a smoke test with those in it would have reported a working
// platform as broken.
var checks = []check{
	{"identity      users", "/admin/users?size=1"},
	{"patient       register", "/patients?size=1"},
	{"patient       bed directory", "/beds"},
	{"scheduling    appointments", "/appointments?size=1"},
	{"laboratory    worklist", "/lab/orders?size=1"},
	{"notification  outbox", "/notifications?size=1"},
	{"admissions    casualty board", "/casualty"},
	{"admissions    bed map", "/admissions/beds"},
	{"pharmacy      formulary", "/pharmacy/formulary?size=1"},
	{"billing       invoices", "/invoices?size=1"},
	{"interop       consents", "/consents?size=1"},
	{"imaging       worklist", "/imaging/worklist"},
	{"immunisation  vaccines", "/vaccines/products"},
	{"immunisation  measures", "/measures"},
	{"public health notifiable", "/surveillance/notifiable"},
}

func gateway(path string) string { return fmt.Sprintf("http://127.0.0.1:%d%s", gatewayPort, path) }

func signIn() (string, error) {
	body, _ := json.Marshal(map[string]string{"username": "admin", "password": seedPassword()})
	client := &http.Client{Timeout: 20 * time.Second}
	resp, err := client.Post(gateway("/auth/login"), "application/json", bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("the gateway answered %s: %s", resp.Status, strings.TrimSpace(string(raw)))
	}
	var payload struct {
		AccessToken string `json:"accessToken"`
	}
	if err := json.Unmarshal(raw, &payload); err != nil || payload.AccessToken == "" {
		return "", fmt.Errorf("no access token in the response")
	}
	return payload.AccessToken, nil
}

func get(url, token string) int {
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return 0
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	client := &http.Client{Timeout: 20 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return 0
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<20))
	return resp.StatusCode
}

// smoke returns the number of failures, so the caller decides whether to stop. It never exits on
// its own: somebody who has just waited ten minutes for a build deserves the whole list of what
// answered and what did not, not the first failure.
func smoke() int {
	step("Checking the platform, signed in as a real user")
	token, err := signIn()
	if err != nil {
		bad("sign-in failed: %v", err)
		return 1
	}
	ok("signed in as admin")

	failures := 0
	for _, c := range checks {
		code := get(gateway(c.path), token)
		if code >= 200 && code < 300 {
			ok("%s (%d)", c.label, code)
		} else {
			bad("%s — HTTP %d on %s", c.label, code, c.path)
			failures++
		}
	}

	// No token on purpose. The corridor display carries no patient information and is the one path
	// allowlisted through the gateway unauthenticated; a 401 here means that allowlist broke, which
	// is a different and more interesting failure than any of the above.
	if code := get(gateway("/public/queue/GF-GEN"), ""); code >= 200 && code < 300 {
		ok("public queue display, no token (%d)", code)
	} else {
		bad("public queue display — HTTP %d", code)
		failures++
	}

	if code := get(fmt.Sprintf("http://127.0.0.1:%d/login", webPort), ""); code >= 200 && code < 300 {
		ok("web sign-in page (%d)", code)
	} else {
		bad("web sign-in page — HTTP %d", code)
		failures++
	}
	return failures
}
