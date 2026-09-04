// The Windows installer. Deliberately a module of its own rather than part of any service: it is
// build tooling, it is the only Go in the repository, and a `go.mod` at the root would put a third
// toolchain in front of anybody running `mvn` or `npm`.
//
// Standard library only, and that is a requirement rather than an accident. A one-click installer
// that cannot be built without a network round trip to fetch dependencies is a one-click installer
// that fails in the environment where it matters most, and every dependency it links is one more
// thing shipped inside a binary a stranger is about to double-click.
module medsync/installer

go 1.24
