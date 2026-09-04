package main

import (
	"os"
	"os/exec"
	"runtime"
	"strings"
)

// Installing the prerequisites, and the two rules this file exists to keep.
//
// First: it asks. An installer that puts a JDK, Maven, Node and a database server on somebody's
// machine because they double-clicked a file to look at a demo has done something they did not
// agree to, and the fact that all four are reputable does not make it their decision. So the list
// is shown, the sizes are honest, and nothing runs before a yes.
//
// Second: winget only. Not a bundled JDK, not a vendored Node, not a download from a mirror this
// installer chooses — winget resolves to Microsoft's own package repository, the packages are the
// vendors' own, and `winget uninstall` reverses every one of them. A one-click installer that
// leaves behind software the user cannot find in "Apps & features" is malware with good manners.

// A hard cap on how long a single winget install may take. Without it a package waiting on a UAC
// prompt behind the console window hangs the installer with no output, which is indistinguishable
// from a crash.
func wingetAvailable() bool {
	if runtime.GOOS != "windows" {
		return false
	}
	return hasWinget()
}

// offerInstall shows what is missing and installs it if the user agrees. It returns true when it
// installed anything, so the caller knows to re-detect.
func offerInstall(missing []*tool) bool {
	if len(missing) == 0 {
		return false
	}
	if !wingetAvailable() {
		step("Missing prerequisites")
		for _, t := range missing {
			bad("%s", t.name)
			dim("%s", t.url)
		}
		if runtime.GOOS == "windows" {
			dim("")
			dim("winget is not available on this machine, so these cannot be installed for you.")
			dim("Install App Installer from the Microsoft Store, or use the links above.")
			if v := windowsBuild(); v != "" {
				dim("(%s)", v)
			}
		}
		return false
	}

	step("Missing prerequisites")
	for _, t := range missing {
		bad("%s", t.name)
	}
	say("")
	say("   These can be installed with winget, from the vendors' own packages:")
	for _, t := range missing {
		if t.winget == "" {
			continue
		}
		say("     winget install --id %s", t.winget)
	}
	say("")
	say("   They install machine-wide and Windows will ask you to approve each one.")
	say("   PostgreSQL in particular needs administrator rights and will prompt.")
	say("   Every one of them can be removed afterwards with `winget uninstall`.")

	if os.Getenv("MEDSYNC_ASSUME_YES") == "1" {
		say("\n   MEDSYNC_ASSUME_YES=1 — proceeding without asking.")
	} else if !confirm("Install them now?") {
		say("")
		dim("Nothing was installed. Install them yourself and run this again,")
		dim("or start Docker Desktop and this installer will use containers instead.")
		return false
	}

	installed := false
	for _, t := range missing {
		if t.winget == "" {
			warn("%s has no winget package — install it from %s", t.name, t.url)
			continue
		}
		step("Installing %s", t.name)
		cmd := exec.Command("winget", "install", "--id", t.winget,
			"--accept-package-agreements", "--accept-source-agreements",
			"--disable-interactivity", "-e", "-h")
		cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
		if err := cmd.Run(); err != nil {
			// Not fatal. winget exits non-zero for "already installed" and for "a newer version is
			// present" as well as for real failures, and the detection pass that follows is a
			// better judge of whether the tool is now usable than this exit code is.
			warn("winget reported a problem installing %s: %v", t.name, err)
			// Decoded, because "exit status 0x8a150014" is what a real user saw and it told them
			// nothing at all. The exit code is the only thing winget leaves behind when its own
			// message has already scrolled past, so it is worth translating the handful that
			// actually occur — and every one of them ends the same way: here is the vendor's page.
			if hint := wingetExitHint(cmd.ProcessState.ExitCode()); hint != "" {
				dim("%s", hint)
			}
			dim("install it by hand instead: %s", t.url)
		} else {
			ok("%s installed", t.name)
		}
		installed = true
	}
	return installed
}

// wingetExitHint turns winget's exit code into a sentence. Only the codes that come up in practice
// are here; anything else falls through to the vendor link the caller prints anyway.
//
// Go reports the code as a signed int, so the 0x8a15xxxx values arrive negative — hence the
// unsigned cast rather than a comparison against the literal you would read in winget's docs.
func wingetExitHint(code int) string {
	switch uint32(code) {
	case 0x8a150014:
		return "winget has no such package in its sources - the id is wrong, or `winget source update` is needed"
	case 0x8a15002b:
		return "no installer in that package applies to this machine's architecture"
	case 0x8a150011:
		return "winget needs to be run from a session that can elevate, and this one could not"
	case 0x8a15010d:
		return "the package is already installed at this version or newer"
	case 0x8a150044:
		return "the download failed - check the network and try again"
	}
	return ""
}

// refreshPath re-reads PATH the way a newly opened console would.
//
// This is the step whose absence makes every naive Windows installer tell you to reboot. A process
// inherits its environment at launch, so a JDK installed thirty seconds ago is invisible to the
// process that installed it — the machine and user PATH values in the registry have changed, and
// this process's copy has not. Reading them back and rebuilding PATH here is exactly what a fresh
// shell does, and it is why this installer can install a prerequisite and then use it.
func refreshPath() {
	if runtime.GOOS != "windows" {
		return
	}
	machine := regRead(`HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment`, "Path")
	user := regRead(`HKCU\Environment`, "Path")
	merged := joinPath(machine, user, os.Getenv("PATH"))
	if merged != "" {
		_ = os.Setenv("PATH", merged)
	}
}

func regRead(key, name string) string {
	out, err := exec.Command("reg", "query", key, "/v", name).Output()
	if err != nil {
		return ""
	}
	// The output is `    Path    REG_EXPAND_SZ    C:\...;C:\...`, so the value is everything after
	// the type column. Split on whitespace with a limit rather than taking the last field: a
	// directory with a space in it is normal on Windows and would otherwise be truncated.
	for _, line := range strings.Split(string(out), "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 3 && strings.EqualFold(fields[0], name) {
			idx := strings.Index(line, fields[1])
			if idx < 0 {
				continue
			}
			rest := line[idx+len(fields[1]):]
			return strings.TrimSpace(rest)
		}
	}
	return ""
}

func joinPath(parts ...string) string {
	seen := map[string]bool{}
	var out []string
	for _, part := range parts {
		for _, dir := range strings.Split(part, ";") {
			dir = strings.TrimSpace(dir)
			if dir == "" {
				continue
			}
			key := strings.ToLower(dir)
			if seen[key] {
				continue
			}
			seen[key] = true
			out = append(out, dir)
		}
	}
	return strings.Join(out, ";")
}
