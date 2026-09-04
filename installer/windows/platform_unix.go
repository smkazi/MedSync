//go:build !windows

package main

import (
	"os"
	"os/exec"
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
