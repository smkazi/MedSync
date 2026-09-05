package main

import (
	"bufio"
	"fmt"
	"os"
	"strings"
)

// Plain ASCII markers and no colour anywhere.
//
// Not an aesthetic choice. ANSI escapes need virtual-terminal processing enabled on the console
// handle, which the old conhost that still opens when somebody double-clicks a file does not do by
// default — so a coloured installer prints "←[32m✓←[0m" to exactly the user least able to work out
// why. `[ok]` reads correctly in every Windows console there has ever been, and in a log file
// somebody pastes into an email.

func say(format string, a ...any)  { fmt.Printf(format+"\n", a...) }
func step(format string, a ...any) { fmt.Printf("\n== "+format+"\n", a...) }
func ok(format string, a ...any)   { fmt.Printf("   [ok] "+format+"\n", a...) }
func warn(format string, a ...any) { fmt.Printf("   [! ] "+format+"\n", a...) }
func bad(format string, a ...any)  { fmt.Printf("   [xx] "+format+"\n", a...) }
func dim(format string, a ...any)  { fmt.Printf("        "+format+"\n", a...) }

// fail prints the reason, then holds the window open if there is nobody to see it otherwise.
//
// A console application started from Explorer loses its window the instant it returns, so an
// installer that exits on an error shows the user a black rectangle that vanishes. That is the
// single most common way a Windows installer fails to communicate, and it is why every exit path
// here goes through this or through pause().
func fail(format string, a ...any) {
	fmt.Fprintf(os.Stderr, "\nSTOPPED: "+format+"\n", a...)
	pause()
	os.Exit(1)
}

// pause waits for Enter, but only when the window is about to disappear — that is, when the program
// was started with no arguments, which is what a double-click does. Somebody typing commands into
// their own terminal does not want to press Enter after each one.
var interactive bool

func pause() {
	if !interactive {
		return
	}
	fmt.Print("\nPress Enter to close this window... ")
	bufio.NewReader(os.Stdin).ReadString('\n')
}

func confirm(question string) bool {
	// An unattended yes, for CI. Not a general "assume yes for everything" flag: the only question
	// this program asks is the one before `uninstall` deletes its own directory, and a verification
	// job that cannot answer it cannot check that uninstall leaves nothing behind.
	if os.Getenv("MEDSYNC_ASSUME_YES") == "1" {
		fmt.Printf("\n%s [y/N] y   (MEDSYNC_ASSUME_YES)\n", question)
		return true
	}
	fmt.Printf("\n%s [y/N] ", question)
	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil {
		return false
	}
	answer := strings.ToLower(strings.TrimSpace(line))
	return answer == "y" || answer == "yes"
}
