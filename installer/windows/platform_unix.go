//go:build !windows

package main

import (
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"strconv"
	"syscall"
)

// The Unix twin of platform_windows.go, and its whole purpose is verification.
//
// The installer targets Windows, and nothing about a Windows binary can be executed on the machine
// this was written on. Keeping the platform-specific calls behind four small functions means the
// other seven files — detection, the download, the database ladder, the build, the start order, the
// smoke test — compile and run natively here, so the logic is exercised rather than reasoned about.
// What remains unverified is then exactly this file's Windows counterpart, which is small enough to
// read, and the CI job on a real windows-latest runner covers even that.

func spawn(logPath, dir string, env []string, name string, args ...string) (int, error) {
	// The directory, before the file in it. Found by testing: on the HMS_DB_URL path nothing else
	// had created it — the private-cluster path happened to, which is why every run that made its
	// own database worked and a run against somebody else's failed on the very first service with
	// "no such file or directory", naming a log rather than a cause.
	if err := os.MkdirAll(filepath.Dir(logPath), 0o755); err != nil {
		return 0, err
	}
	f, err := os.Create(logPath)
	if err != nil {
		return 0, err
	}
	cmd := exec.Command(name, args...)
	cmd.Dir = dir
	cmd.Env = env
	cmd.Stdout, cmd.Stderr = f, f
	// Setsid, the counterpart of CREATE_NEW_PROCESS_GROUP: the child gets its own session, so it
	// survives this process and its group can be signalled without reaching anything else.
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := cmd.Start(); err != nil {
		f.Close()
		return 0, err
	}
	pid := cmd.Process.Pid
	go func() { _ = cmd.Wait() }()
	return pid, nil
}

func terminate(pid int) error {
	if pid <= 0 {
		return os.ErrProcessDone
	}
	// The negative pid is the process group, which is what Setsid above made safe to signal.
	if err := syscall.Kill(-pid, syscall.SIGTERM); err == nil {
		return nil
	}
	p, err := os.FindProcess(pid)
	if err != nil {
		return err
	}
	return p.Signal(syscall.SIGTERM)
}

func openBrowser(url string) {
	for _, candidate := range []string{"xdg-open", "open"} {
		if path, err := exec.LookPath(candidate); err == nil {
			_ = exec.Command(path, url).Start()
			return
		}
	}
}

// Always false off Windows: there is no console window to lose, so nothing should wait for Enter.
func startedFromExplorer() bool { return false }

// dropPrivileges makes a command run as an unprivileged user when this process is root.
//
// It exists for one program: initdb refuses outright to run as root, and so does the postgres
// server behind pg_ctl. That refusal is correct — a database server running as root is a
// vulnerability rather than a convenience — and it is not a Windows concern at all, which is why
// this has no counterpart in platform_windows.go beyond a no-op.
//
// It matters here rather than being someone else's problem because a container is very often root,
// and this program's whole verification story is that its platform-independent half is exercised on
// Linux. Without this, the bundled PostgreSQL cannot be started here at all, and the one rung of
// the database ladder that a bundled install actually uses would be the one rung never run outside
// a Windows runner. scripts/local.sh already does the same thing by invoking `su postgres`.
//
// Reported rather than silent: the caller has to know, because the data directory and the log file
// have to be owned by whoever is about to write to them.
func dropPrivileges(cmd *exec.Cmd) (uid, gid int, dropped bool) {
	if os.Geteuid() != 0 {
		return 0, 0, false
	}
	for _, name := range []string{"postgres", "nobody"} {
		u, err := user.Lookup(name)
		if err != nil {
			continue
		}
		uidN, err1 := strconv.Atoi(u.Uid)
		gidN, err2 := strconv.Atoi(u.Gid)
		if err1 != nil || err2 != nil {
			continue
		}
		attr := cmd.SysProcAttr
		if attr == nil {
			attr = &syscall.SysProcAttr{}
		}
		attr.Credential = &syscall.Credential{Uid: uint32(uidN), Gid: uint32(gidN)}
		cmd.SysProcAttr = attr
		return uidN, gidN, true
	}
	return 0, 0, false
}

// ownedBy hands a directory tree to the user a dropped-privilege command will run as.
func ownedBy(path string, uid, gid int) error {
	return filepath.Walk(path, func(p string, _ os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		return os.Chown(p, uid, gid)
	})
}
