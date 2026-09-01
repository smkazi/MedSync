import { describe, expect, it, vi } from "vitest";

// cookies() only exists inside a Next request. The functions that touch it are covered by the
// Playwright suite against a real browser and a real cookie jar; what is unit-testable here is the
// pure role check and the parsing that has to survive a corrupt cookie.
vi.mock("next/headers", () => ({
  cookies: async () => {
    throw new Error("cookies() is not available outside a request");
  },
}));

import { hasRole, SESSION_COOKIES } from "@/lib/session";

const doctor = {
  id: "u1",
  username: "dr.rao",
  fullName: "Dr Anika Rao",
  roles: ["DOCTOR"],
  mustChangePassword: false,
};

describe("hasRole", () => {
  it("is true when the user holds any one of the roles asked for", () => {
    expect(hasRole(doctor, "DOCTOR")).toBe(true);
    expect(hasRole(doctor, "ADMIN", "DOCTOR", "NURSE")).toBe(true);
  });

  it("is false for a role the user does not hold", () => {
    expect(hasRole(doctor, "ADMIN")).toBe(false);
    expect(hasRole(doctor, "LAB_TECH", "PATHOLOGIST")).toBe(false);
  });

  it("is false for no session at all", () => {
    // This is the one that matters. Navigation is built from these checks, so a null session that
    // returned true would render admin links to a signed-out visitor.
    expect(hasRole(null, "DOCTOR")).toBe(false);
    expect(hasRole(null, "ADMIN", "DOCTOR")).toBe(false);
  });

  it("is false when asked for no roles at all", () => {
    expect(hasRole(doctor)).toBe(false);
  });

  it("does not match on a prefix or a differently-cased role", () => {
    // Role codes are exact. "DOCTOR_ASSISTANT" is not a doctor.
    expect(hasRole({ ...doctor, roles: ["DOCTOR_ASSISTANT"] }, "DOCTOR")).toBe(false);
    expect(hasRole({ ...doctor, roles: ["doctor"] }, "DOCTOR")).toBe(false);
  });
});

describe("session cookie names", () => {
  it("keeps the credential cookies distinct from the display cookie", () => {
    const { ACCESS_COOKIE, REFRESH_COOKIE, USER_COOKIE } = SESSION_COOKIES;
    expect(new Set([ACCESS_COOKIE, REFRESH_COOKIE, USER_COOKIE]).size).toBe(3);
  });
});
