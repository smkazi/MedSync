package main

import (
	"archive/tar"
	"compress/gzip"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Getting the source onto the machine.
//
// By HTTPS tarball rather than by `git clone`, which removes git from the prerequisite list
// entirely — and git is the one prerequisite a Windows user is least likely to have and most likely
// to be confused by. Go's standard library reads tar.gz, so this needs nothing installed and
// nothing shelled out to.

const (
	defaultRepo   = "smkazi/MedSync"
	defaultBranch = "claude/hospital-management-repo-cugrud"
)

func repoSlug() string   { return envOr("MEDSYNC_REPO", defaultRepo) }
func repoBranch() string { return envOr("MEDSYNC_REF", defaultBranch) }

// findSource returns a checkout without fetching anything: beside the executable, the working
// directory, or where a previous run put one. A developer who has already cloned the repository and
// drops the installer into it must get their own tree built rather than a second copy downloaded
// into AppData — the alternative is an installer that appears to ignore the edits somebody just
// made.
func findSource() string {
	var candidates []string
	if exe, err := os.Executable(); err == nil {
		dir := filepath.Dir(exe)
		candidates = append(candidates, dir, filepath.Join(dir, ".."), filepath.Join(dir, "..", ".."))
	}
	if wd, err := os.Getwd(); err == nil {
		candidates = append(candidates, wd, filepath.Join(wd, ".."))
	}
	candidates = append(candidates, filepath.Join(homeDir(), "src"))

	for _, c := range candidates {
		if isCheckout(c) {
			abs, err := filepath.Abs(c)
			if err != nil {
				return c
			}
			return abs
		}
	}
	return ""
}

// Two markers, not one. A directory holding only pom.xml could be any Maven project, and pointing
// the build at somebody's unrelated repository is worse than not finding one.
func isCheckout(dir string) bool {
	if _, err := os.Stat(filepath.Join(dir, "pom.xml")); err != nil {
		return false
	}
	if _, err := os.Stat(filepath.Join(dir, "services", "identity-service")); err != nil {
		return false
	}
	return true
}

func ensureSource() string {
	if src := findSource(); src != "" {
		ok("Source: %s", src)
		return src
	}
	dest := filepath.Join(homeDir(), "src")
	step("Downloading MedSync (%s, branch %s)", repoSlug(), repoBranch())
	if err := download(dest); err != nil {
		fail("could not download the source: %v\n\n"+
			"If this repository is private, create a GitHub token with read access and set it first:\n"+
			"    set MEDSYNC_TOKEN=ghp_...\n"+
			"then run this again.", err)
	}
	ok("Source: %s", dest)
	return dest
}

func download(dest string) error {
	url := fmt.Sprintf("https://codeload.github.com/%s/tar.gz/refs/heads/%s", repoSlug(), repoBranch())
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return err
	}
	// A token only when one is offered. Sending an empty Authorization header is worse than sending
	// none: GitHub answers 401 rather than serving the public tarball it would otherwise have
	// served, so a public repository would fail for somebody with the variable set and blank.
	if token := os.Getenv("MEDSYNC_TOKEN"); token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	client := &http.Client{Timeout: 15 * time.Minute}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("%s said %s", url, resp.Status)
	}

	tmp := dest + ".partial"
	if err := os.RemoveAll(tmp); err != nil {
		return err
	}
	if err := os.MkdirAll(tmp, 0o755); err != nil {
		return err
	}
	if err := extract(resp.Body, tmp); err != nil {
		return err
	}
	// Extracted into dest.partial and then renamed, so an interrupted download cannot leave a
	// half-tree that looks like a checkout and builds into a confusing compile error.
	_ = os.RemoveAll(dest)
	return os.Rename(tmp, dest)
}

// extract writes the tarball, dropping GitHub's single top-level "<repo>-<ref>/" wrapper directory
// so the tree lands where callers expect it.
func extract(r io.Reader, dest string) error {
	gz, err := gzip.NewReader(r)
	if err != nil {
		return err
	}
	defer gz.Close()

	files := 0
	tr := tar.NewReader(gz)
	for {
		header, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		rel := strip(header.Name)
		if rel == "" {
			continue
		}
		// Path traversal, checked rather than trusted. This archive comes off the network and is
		// unpacked with the user's own privileges, so an entry named ../../windows/system32/... has
		// to be refused here — the fact that the source is expected to be GitHub is not a reason to
		// hand it arbitrary write access.
		target := filepath.Join(dest, rel)
		if !strings.HasPrefix(target, filepath.Clean(dest)+string(os.PathSeparator)) {
			return fmt.Errorf("refusing an archive entry that escapes the target directory: %q", header.Name)
		}
		switch header.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}
			f, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, os.FileMode(header.Mode)&0o777|0o600)
			if err != nil {
				return err
			}
			if _, err := io.Copy(f, tr); err != nil {
				f.Close()
				return err
			}
			f.Close()
			files++
			if files%500 == 0 {
				fmt.Printf("\r        %d files...", files)
			}
		}
		// Symlinks and everything else are skipped on purpose: this repository contains none, and
		// creating one on Windows needs either developer mode or elevation.
	}
	fmt.Printf("\r        %d files extracted\n", files)
	if files == 0 {
		return fmt.Errorf("the archive contained no files")
	}
	return nil
}

func strip(name string) string {
	name = filepath.ToSlash(name)
	if i := strings.Index(name, "/"); i >= 0 {
		return filepath.FromSlash(name[i+1:])
	}
	return ""
}
