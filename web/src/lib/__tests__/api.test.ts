import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";

// accessToken() reads a Next request cookie, which does not exist outside a request. Stubbed to a
// fixed value so these tests are about error translation, which is the part with real logic in it.
vi.mock("@/lib/session", () => ({
  accessToken: async () => "test-token",
}));

// A plain import: vitest hoists vi.mock() above the import graph, so the stub above is in place
// before this module resolves its own dependency on session.
import { api, ApiError, isAuthError } from "@/lib/api";

function jsonResponse(status: number, body: unknown, ok = status < 400): Response {
  return {
    ok,
    status,
    text: async () => (body === undefined ? "" : JSON.stringify(body)),
  } as Response;
}

describe("api", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("attaches the session's bearer token and never caches", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(200, { id: "p1" }));

    await api("/patients/p1");

    const call = vi.mocked(fetch).mock.calls.at(0);
    const init = call?.[1];
    expect((init?.headers as Record<string, string>).Authorization).toBe("Bearer test-token");
    // Platform data is per user. A cached response would serve one clinician another's patient.
    expect(init?.cache).toBe("no-store");
  });

  it("returns undefined for 204 rather than trying to parse an empty body", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(204, undefined));
    await expect(api("/appointments/a1/cancel", { method: "POST" })).resolves.toBeUndefined();
  });

  it("surfaces the problem+json detail, which is what the user is shown", async () => {
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse(409, { title: "Conflict", detail: "That slot is already booked" }, false),
    );

    await expect(api("/appointments", { method: "POST" })).rejects.toMatchObject({
      status: 409,
      detail: "That slot is already booked",
    });
  });

  it("carries field errors through so a form can mark the offending input", async () => {
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse(400, { detail: "Validation failed", errors: { dateOfBirth: "must be in the past" } }, false),
    );

    const error: unknown = await api("/patients", { method: "POST" }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).fieldErrors).toEqual({ dateOfBirth: "must be in the past" });
  });

  it("falls back through title, then a generic message, rather than showing 'undefined'", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(503, { title: "Service Unavailable" }, false));
    await expect(api("/lab/orders")).rejects.toMatchObject({ detail: "Service Unavailable" });

    vi.mocked(fetch).mockResolvedValue(jsonResponse(500, {}, false));
    await expect(api("/lab/orders")).rejects.toMatchObject({ detail: "Request failed (500)" });
  });

  it("does not throw a parser error when a gateway returns HTML instead of JSON", async () => {
    // A proxy timing out mid-stack sends an HTML error page. Blowing up on JSON.parse would hide
    // the real status behind a SyntaxError.
    vi.mocked(fetch).mockResolvedValue({
      ok: false,
      status: 502,
      text: async () => "<html><body>Bad Gateway</body></html>",
    } as Response);

    await expect(api("/patients")).rejects.toMatchObject({
      status: 502,
      detail: "<html><body>Bad Gateway</body></html>",
    });
  });
});

describe("isAuthError", () => {
  it("is true for 401 and 403, which send the user back to sign in", () => {
    expect(isAuthError(new ApiError(401, "no"))).toBe(true);
    expect(isAuthError(new ApiError(403, "no"))).toBe(true);
  });

  it("is false for everything else, including a 429", () => {
    // A rate-limited request is not an expired session. Treating it as one would sign the user
    // out mid-consultation.
    expect(isAuthError(new ApiError(429, "slow down"))).toBe(false);
    expect(isAuthError(new ApiError(500, "boom"))).toBe(false);
    expect(isAuthError(new Error("network"))).toBe(false);
    expect(isAuthError(null)).toBe(false);
  });
});
