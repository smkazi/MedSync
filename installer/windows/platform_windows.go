//go:build windows

package main

import (
	"os"
	"os/exec"
	"strconv"
	"syscall"
	"unsafe"
)

// The Windows-only process and shell handling. Everything in here has a Unix twin in
// platform_unix.go, which exists so this installer can be built and its whole flow exercised on
// Linux — the alternative is a program whose only test is somebody double-clicking it.

// spawn starts a detached background process with its output in a log file.
//
// Two flags matter and both are the difference between a stack that survives and one that does not:
// CREATE_NEW_PROCESS_GROUP detaches the child from this console, so closing the installer's window
// does not take twelve JVMs down with it, and HideWindow stops each service opening a console
// window of its own — without it, one click produces fourteen black rectangles on the taskbar.
func spawn(logPath, dir string, env []string, name string, args ...string) (int, error) {
	f, err := os.Create(logPath)
	if err != nil {
		return 0, err
	}
	// Deliberately not closed: the child writes to this handle for as long as it runs. Go dups the
	// handle into the child, so the file stays open there after this process lets go of it, and the
	// installer is short-lived anyway.
	cmd := exec.Command(name, args...)
	cmd.Dir = dir
	cmd.Env = env
	cmd.Stdout, cmd.Stderr = f, f
	cmd.SysProcAttr = &syscall.SysProcAttr{
		CreationFlags: syscall.CREATE_NEW_PROCESS_GROUP | 0x08000000, // 0x08000000 = CREATE_NO_WINDOW
		HideWindow:    true,
	}
	if err := cmd.Start(); err != nil {
		f.Close()
		return 0, err
	}
	// Released rather than waited on: this installer exits while the platform keeps running, and a
	// Wait here would block forever.
	pid := cmd.Process.Pid
	go func() { _ = cmd.Wait() }()
	return pid, nil
}

// terminate stops one process by id.
//
// taskkill /T, not Process.Kill(). `npx next start` and `mvn` both launch the real work as a child,
// so killing the recorded pid alone leaves next-server holding port 3000 — the same defect the Unix
// script had, where the fix was to signal the process group. /T is the Windows equivalent: kill the
// tree. /F because a JVM given a polite request through this route does not always take it.
func terminate(pid int) error {
	if pid <= 0 {
		return os.ErrProcessDone
	}
	if err := exec.Command("taskkill", "/PID", strconv.Itoa(pid), "/T", "/F").Run(); err == nil {
		return nil
	}
	p, err := os.FindProcess(pid)
	if err != nil {
		return err
	}
	return p.Kill()
}

// openBrowser opens the default browser without going through a shell.
//
// rundll32 rather than `cmd /c start`, because `start` is a cmd builtin whose first quoted argument
// is taken as a window title, which is the classic way a URL with an ampersand in it opens the
// wrong thing or nothing at all.
func openBrowser(url string) {
	_ = exec.Command("rundll32", "url.dll,FileProtocolHandler", url).Start()
}

// startedFromExplorer reports whether this process owns its console window — that is, whether the
// window will vanish when the program returns.
//
// GetConsoleProcessList returns how many processes are attached to this console. A double-click
// from Explorer creates a console for this process alone, so the answer is 1; running it from an
// existing cmd or PowerShell gives 2 or more. This is what decides whether to wait for Enter, and
// getting it wrong in either direction is annoying: no pause means an error nobody can read, and an
// always-pause means every scripted invocation hangs.
func startedFromExplorer() bool {
	kernel32 := syscall.NewLazyDLL("kernel32.dll")
	proc := kernel32.NewProc("GetConsoleProcessList")
	if proc.Find() != nil {
		return false
	}
	var pids [8]uint32
	n, _, _ := proc.Call(uintptr(unsafe.Pointer(&pids[0])), uintptr(len(pids)))
	return n == 1
}
