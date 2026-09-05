package main

import (
	"archive/zip"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// The runtime, carried inside the installer's own file.
//
// This is what makes MedSync-Setup.exe self-contained. Appended to the compiled binary is an
// ordinary zip archive holding a Java runtime, a Node runtime, a PostgreSQL server, an embeddable
// Python, the twelve service jars with one shared library directory, the built web app and the
// licences for all of it. At run time the program opens *itself* and reads that archive.
//
// It works because a zip's central directory lives at the END of the file and every offset in it
// is relative to where the archive begins, so an archive with arbitrary leading bytes is still a
// valid archive — Go's archive/zip finds the end-of-directory record by scanning backwards and
// adjusts for the prefix. Windows loads the PE regardless, because the appended bytes sit past the
// end of the image the loader is told about. This is how self-extracting archives have always
// worked, and it needs no installer framework, no NSIS, no SFX stub, and nothing added to the set
// of things a person double-clicking this has to trust.
//
// Nothing is ever held in memory: entries stream out through io.Copy.

// payloadIDEntry names the one metadata file the archive must contain. It holds the sha256 of the
// archive computed at build time, which is what the extracted runtime is keyed on.
//
// Read from inside the archive rather than computed here, and that is a deliberate trade: hashing
// three hundred megabytes on every single run — including `status`, which should be instant — to
// re-derive a number the build already knew would be a strange way to spend a person's time.
const payloadIDEntry = "PAYLOAD-ID"

// extractedMarker is written last, after every entry is on disk, and its absence is what makes a
// half-finished extraction re-run rather than be trusted.
//
// The failure it exists for is ordinary: extraction takes the better part of a minute, and a person
// who closes the window during it would otherwise leave a directory that looks complete because it
// has the right name. The next run would launch a JVM out of a truncated jar and report something
// about a corrupt archive, which names nothing anybody can act on.
const extractedMarker = ".complete"

// errNoPayload is returned when this binary carries no appended archive.
//
// Not a failure: it is the ordinary state of the Linux build used to exercise this program's logic
// during development, and of a bare cross-compiled exe before the payload job has run. The caller
// decides what it means — see ensureRuntime, which falls back to building from source and says so.
var errNoPayload = errors.New("this build carries no payload")

type payload struct {
	file   *os.File
	reader *zip.Reader
	id     string
	bytes  int64
}

// openPayload opens the running executable and reads the archive appended to it.
func openPayload() (*payload, error) {
	exe, err := os.Executable()
	if err != nil {
		return nil, err
	}
	// Resolved, because on Unix this program is often reached through a symlink and the archive is
	// attached to the real file rather than to the name it was called by.
	if resolved, err := filepath.EvalSymlinks(exe); err == nil {
		exe = resolved
	}
	f, err := os.Open(exe)
	if err != nil {
		return nil, err
	}
	info, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, err
	}
	r, err := zip.NewReader(f, info.Size())
	if err != nil {
		f.Close()
		if errors.Is(err, zip.ErrFormat) {
			return nil, errNoPayload
		}
		return nil, err
	}
	p := &payload{file: f, reader: r, bytes: info.Size()}
	id, err := p.readEntry(payloadIDEntry)
	if err != nil {
		f.Close()
		// A zip is appended but it is not one of ours. Refused rather than extracted: an archive
		// this program did not build has no reason to be unpacked into a directory this program
		// then executes binaries out of.
		return nil, fmt.Errorf("the appended archive carries no %s and is not a MedSync payload", payloadIDEntry)
	}
	p.id = strings.TrimSpace(id)
	if p.id == "" {
		f.Close()
		return nil, fmt.Errorf("the payload's %s is empty", payloadIDEntry)
	}
	return p, nil
}

func (p *payload) Close() error { return p.file.Close() }

func (p *payload) readEntry(name string) (string, error) {
	for _, entry := range p.reader.File {
		if entry.Name != name {
			continue
		}
		rc, err := entry.Open()
		if err != nil {
			return "", err
		}
		defer rc.Close()
		// Bounded: this reads a metadata file, and an entry claiming to be one should not be able
		// to make the installer allocate whatever it likes.
		raw, err := io.ReadAll(io.LimitReader(rc, 4096))
		if err != nil {
			return "", err
		}
		return string(raw), nil
	}
	return "", fmt.Errorf("no %s in the payload", name)
}

// uncompressed reports the total size of the archive's contents, for the one line printed before a
// person waits for it.
func (p *payload) uncompressed() int64 {
	var total int64
	for _, entry := range p.reader.File {
		total += int64(entry.UncompressedSize64)
	}
	return total
}

// runtimeRoot is where extracted runtimes live, one directory per payload.
func runtimeRoot() string { return filepath.Join(homeDir(), "runtime") }

// runtimeDir is this payload's own directory, keyed on the build's own hash of it.
//
// Keyed rather than fixed, and the reason is a specific failure: a person who downloads a newer
// installer and runs it while the previous one's services are still up would, with a fixed
// directory, have the extraction overwrite jars out from under twelve running JVMs. Under a
// hash-named directory the new runtime lands beside the old one, the old processes keep the files
// they are using, and `uninstall` removes both.
func (p *payload) runtimeDir() string {
	id := p.id
	if len(id) > 16 {
		id = id[:16]
	}
	return filepath.Join(runtimeRoot(), id)
}

// extract unpacks the payload into dir, and only claims to have done so once every entry is written.
func (p *payload) extract(dir string) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	clean := filepath.Clean(dir)
	total := p.uncompressed()
	var done int64
	files := 0
	lastPrint := time.Now()

	for _, entry := range p.reader.File {
		name := filepath.FromSlash(entry.Name)
		target := filepath.Join(dir, name)
		// Path traversal, checked rather than trusted — the same guard the source download has, and
		// it belongs here for a stronger reason: this archive is unpacked with the user's own
		// privileges into a directory whose contents are then executed.
		if target != clean && !strings.HasPrefix(target, clean+string(os.PathSeparator)) {
			return fmt.Errorf("refusing a payload entry that escapes the runtime directory: %q", entry.Name)
		}
		if entry.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}
		// The mode from the archive, floored at owner-write so the file can be replaced, and with
		// the executable bit preserved. That bit is not cosmetic off Windows: java, node, initdb and
		// postgres all come out of here and are executed directly.
		mode := entry.Mode().Perm()
		if mode == 0 {
			mode = 0o644
		}
		if err := writeEntry(entry, target, mode|0o200); err != nil {
			return fmt.Errorf("%s: %w", entry.Name, err)
		}
		done += int64(entry.UncompressedSize64)
		files++
		if time.Since(lastPrint) > 700*time.Millisecond {
			lastPrint = time.Now()
			fmt.Printf("\r        %d files, %d of %d MB...", files, done>>20, total>>20)
		}
	}
	fmt.Printf("\r        %d files, %d MB extracted%s\n", files, done>>20, strings.Repeat(" ", 20))

	return nil
}

// markComplete writes the sentinel that says this runtime may be trusted on the next run.
func markComplete(dir, id string) error {
	return os.WriteFile(filepath.Join(dir, extractedMarker), []byte(id+"\n"), 0o644)
}

func writeEntry(entry *zip.File, target string, mode os.FileMode) error {
	rc, err := entry.Open()
	if err != nil {
		return err
	}
	defer rc.Close()
	f, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, rc); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}

// ensureRuntime returns the directory holding this installer's runtime, extracting it on the first
// run and finding it already there on every run after.
//
// The empty string with a nil error is the deliberate answer for a build with no payload: the
// caller falls back to the from-source path, which is how this program is exercised on a machine
// that cannot run a Windows binary at all.
func ensureRuntime() (string, error) {
	p, err := openPayload()
	if errors.Is(err, errNoPayload) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	defer p.Close()

	dir := p.runtimeDir()
	if _, err := os.Stat(filepath.Join(dir, extractedMarker)); err == nil {
		dim("Runtime already unpacked at %s", dir)
		return dir, nil
	}
	// Whatever is there is incomplete by definition — the marker is written last — so it goes
	// rather than being extracted over. Extracting over a half-tree leaves whichever files the
	// interrupted run had already written and never re-checks them.
	if _, err := os.Stat(dir); err == nil {
		warn("An unfinished runtime was left at %s; unpacking it again", dir)
		if err := os.RemoveAll(dir); err != nil {
			return "", err
		}
	}
	say("   Unpacking the runtime (%d MB) — this happens once", p.uncompressed()>>20)
	if err := p.extract(dir); err != nil {
		// Removed on failure for the same reason: a directory left behind after a disk filled up
		// must not be mistaken for a runtime on the next run.
		_ = os.RemoveAll(dir)
		return "", err
	}
	// Each service's exact classpath, rebuilt out of the pooled jars by hard link. Before the
	// marker is written, so an interruption here is an incomplete runtime rather than a runtime
	// whose services have no dependencies.
	if err := (runtimeTree{root: dir}).linkClasspaths(); err != nil {
		_ = os.RemoveAll(dir)
		return "", fmt.Errorf("could not assemble the service classpaths: %w", err)
	}
	if err := markComplete(dir, p.id); err != nil {
		return "", err
	}
	ok("Runtime unpacked to %s", dir)
	return dir, nil
}

// payloadSummary describes what is inside the exe, for `doctor` and `version`.
func payloadSummary() (id string, files int, megabytes int64, err error) {
	p, err := openPayload()
	if err != nil {
		return "", 0, 0, err
	}
	defer p.Close()
	return p.id, len(p.reader.File), p.uncompressed() >> 20, nil
}

// existingRuntime reports this payload's runtime directory only if it has already been unpacked.
//
// Separate from ensureRuntime because `doctor` and `uninstall` must not unpack three hundred
// megabytes as a side effect of being asked a question. Doctor's whole job is to answer quickly.
func existingRuntime() (string, bool) {
	p, err := openPayload()
	if err != nil {
		return "", false
	}
	defer p.Close()
	dir := p.runtimeDir()
	if _, err := os.Stat(filepath.Join(dir, extractedMarker)); err != nil {
		return "", false
	}
	return dir, true
}
