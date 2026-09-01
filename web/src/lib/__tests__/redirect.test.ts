import { describe, expect, it } from "vitest";

import { seeOther } from "@/lib/redirect";

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
