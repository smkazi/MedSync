import { describe, expect, it } from "vitest";

import { resumePath, seeOther } from "@/lib/redirect";

describe("seeOther", () => {
  it("is a 303, so the browser follows it with a GET", () => {
    // 302 would let the browser repeat the POST, re-registering a patient on a refresh.
    expect(seeOther("/patients").status).toBe(303);
  });

  it("writes Location as a path, not a guessed absolute URL", () => {
    // new URL(path, request.url) resolves against whatever host the request claims, which behind a
    // proxy is not the host the browser used - and a mismatch drops the SameSite=Strict session
    // cookie across the redirect, silently signing the user out after every form post.
    expect(seeOther("/patients/abc").headers.get("Location")).toBe("/patients/abc");
  });

  it("has no body", () => {
    expect(seeOther("/").body).toBeNull();
  });
});

describe("resumePath", () => {
  it("keeps a path on this app, with its query", () => {
    expect(resumePath("/appointments")).toBe("/appointments");
    // Half the screens worth resuming onto are a query: a board on a date, a filtered report.
    expect(resumePath("/appointments?date=2026-09-03")).toBe("/appointments?date=2026-09-03");
    expect(resumePath("/encounters/9f1c2f7e-0000-4000-8000-000000000001"))
      .toBe("/encounters/9f1c2f7e-0000-4000-8000-000000000001");
  });

  it("falls back to the dashboard when there is nothing to resume to", () => {
    expect(resumePath(undefined)).toBe("/");
    expect(resumePath(null)).toBe("/");
    expect(resumePath("")).toBe("/");
  });

  it("refuses anything that leaves this origin", () => {
    // The open redirect this function exists for. A sign-in page that will bounce to any URL is a
    // phishing page hosted on the hospital's own domain: the copy it lands on keeps what is typed.
    expect(resumePath("https://elsewhere.example/login")).toBe("/");
    expect(resumePath("http://elsewhere.example")).toBe("/");
    // Protocol-relative: no scheme, still another host.
    expect(resumePath("//elsewhere.example/login")).toBe("/");
    // A backslash, which some browsers normalise to a slash — making this protocol-relative too.
    expect(resumePath("/\\elsewhere.example/login")).toBe("/");
    expect(resumePath("/patients\\..\\elsewhere")).toBe("/");
    // Not a path at all.
    expect(resumePath("javascript:alert(1)")).toBe("/");
    expect(resumePath("patients")).toBe("/");
  });

  it("refuses a value that would split the Location header", () => {
    // This value is written straight into a response header. A newline in it is header injection,
    // and the whole control range is refused rather than the one pair that does the damage.
    expect(resumePath("/patients\r\nSet-Cookie: medsync_at=stolen")).toBe("/");
    expect(resumePath("/patients\nLocation: https://elsewhere.example")).toBe("/");
    expect(resumePath("/patients\u0000")).toBe("/");
    expect(resumePath("/two words")).toBe("/");
  });

  it("refuses sign-in and the route handlers, which are on this origin and still not destinations", () => {
    // /login would be a loop. /api/auth/logout is the useful one: signing in and immediately being
    // signed out reads as a broken platform rather than as somebody's link.
    expect(resumePath("/login")).toBe("/");
    expect(resumePath("/login?next=/login")).toBe("/");
    expect(resumePath("/api/auth/logout")).toBe("/");
    expect(resumePath("/api/portal/record")).toBe("/");
  });

  it("returns the normalised path rather than the string it was handed", () => {
    // What goes into the header is re-serialised from the parse, so a traversal that resolves
    // inside the app resolves before it is used rather than being passed along to be resolved by
    // something else with different rules.
    expect(resumePath("/patients/../appointments")).toBe("/appointments");
    expect(resumePath("/patients/./new")).toBe("/patients/new");
    // And one that tries to climb out of the app lands at its root, not above it.
    expect(resumePath("/../../etc/passwd")).toBe("/etc/passwd");
  });
});
