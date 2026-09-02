import { describe, expect, it, vi } from "vitest";

// The menu imports `hasRole` from the session module, which pulls in `next/headers` at module
// scope. Same mock as session.test.ts: the filtering itself is pure and worth testing here, and
// the rendered result is covered against a real browser in e2e/navigation.spec.ts.
vi.mock("next/headers", () => ({
  cookies: async () => {
    throw new Error("cookies() is not available outside a request");
  },
}));

import { MENUS, menusFor, reachableHrefs, type RoleName } from "@/lib/menu";
import type { SessionUser } from "@/lib/session";

function userWith(...roles: RoleName[]): SessionUser {
  return { id: "u1", username: "u", fullName: "U", roles, mustChangePassword: false };
}

/** What each seeded role should find in the top bar. Kept as literals, not derived from MENUS. */
const EXPECTED_TOP_LEVEL: Record<RoleName, string[]> = {
  ADMIN: [
    "Dashboard",
    "Patients",
    "Scheduling",
    "Clinical",
    "Laboratory",
    "Facility",
    "Pharmacy",
    "Billing",
    "Messaging",
    "Administration",
  ],
  DOCTOR: [
    "Dashboard",
    "Patients",
    "Scheduling",
    "Clinical",
    "Laboratory",
    "Facility",
    "Pharmacy",
    "Billing",
    "Messaging",
  ],
  NURSE: [
    "Dashboard",
    "Patients",
    "Scheduling",
    "Clinical",
    "Laboratory",
    "Facility",
    "Pharmacy",
    "Billing",
    "Messaging",
  ],
  RECEPTIONIST: [
    "Dashboard",
    "Patients",
    "Scheduling",
    "Clinical",
    "Facility",
    "Pharmacy",
    "Billing",
    "Messaging",
  ],
  LAB_TECH: [
    "Dashboard",
    "Patients",
    "Scheduling",
    "Clinical",
    "Laboratory",
    "Facility",
    "Pharmacy",
    "Billing",
  ],
  PATHOLOGIST: [
    "Dashboard",
    "Patients",
    "Scheduling",
    "Clinical",
    "Laboratory",
    "Facility",
    "Pharmacy",
    "Billing",
  ],
};

describe("menusFor", () => {
  it("shows nothing at all to a visitor with no session", () => {
    expect(menusFor(null)).toEqual([]);
  });

  it.each(Object.entries(EXPECTED_TOP_LEVEL))(
    "gives %s exactly the expected top-level menus",
    (role, expected) => {
      const labels = menusFor(userWith(role as RoleName)).map((menu) => menu.label);
      expect(labels).toEqual(expected);
    },
  );

  it("hides the laboratory from a receptionist and administration from everyone but an admin", () => {
    expect(menusFor(userWith("RECEPTIONIST")).map((m) => m.label)).not.toContain("Laboratory");
    for (const role of ["DOCTOR", "NURSE", "RECEPTIONIST", "LAB_TECH", "PATHOLOGIST"] as const) {
      expect(menusFor(userWith(role)).map((m) => m.label)).not.toContain("Administration");
    }
  });

  it("filters items rather than disabling them, so an unreachable route is never serialised", () => {
    // A lab technician has no triage rights. The Clinical menu survives on its not-built items,
    // but the item they cannot act on must be absent - not present and greyed out, which would
    // disclose what exists and that somebody else can reach it.
    const clinical = menusFor(userWith("LAB_TECH")).find((menu) => menu.label === "Clinical");
    expect(clinical?.items?.map((item) => item.label)).not.toContain("Triage");

    const patients = menusFor(userWith("LAB_TECH")).find((menu) => menu.label === "Patients");
    expect(patients?.items?.map((item) => item.label)).toEqual(["Patient register"]);
  });

  it("drops a menu whose every child was filtered away", () => {
    // Administration is gated at the menu, but the rule has to hold for a menu that is not:
    // an empty dropdown is worse than an absent one.
    for (const menu of menusFor(userWith("RECEPTIONIST"))) {
      if (menu.items) expect(menu.items.length).toBeGreaterThan(0);
    }
  });

  it("does not mutate the shared MENUS constant while filtering", () => {
    const before = JSON.stringify(MENUS);
    menusFor(userWith("RECEPTIONIST"));
    menusFor(userWith("ADMIN"));
    expect(JSON.stringify(MENUS)).toBe(before);
  });
});

describe("reachableHrefs", () => {
  it("returns every href a user can open, and no duplicates", () => {
    const hrefs = reachableHrefs(userWith("ADMIN"));
    expect(hrefs).toContain("/");
    expect(hrefs).toContain("/admin/audit");
    expect(hrefs).toContain("/laboratory/device-messages");
    expect(new Set(hrefs).size).toBe(hrefs.length);
  });

  it("excludes what the role cannot reach", () => {
    expect(reachableHrefs(userWith("RECEPTIONIST"))).not.toContain("/admin/users");
    expect(reachableHrefs(userWith("RECEPTIONIST"))).not.toContain("/laboratory");
    expect(reachableHrefs(userWith("LAB_TECH"))).not.toContain("/triage");
  });

  it("is empty with no session", () => {
    expect(reachableHrefs(null)).toEqual([]);
  });
});

describe("MENUS as data", () => {
  it("gives every item a unique href", () => {
    const hrefs = MENUS.flatMap((menu) => (menu.href ? [menu.href] : []))
      .concat(MENUS.flatMap((menu) => (menu.items ?? []).map((item) => item.href)));
    expect(new Set(hrefs).size).toBe(hrefs.length);
  });

  it("points every not-built item at the not-built route, and nothing else at it", () => {
    // The two have to agree in both directions: a notBuilt item that links to a real route would
    // hide a working screen behind a "not built" label, and a real item pointing at /not-built
    // would claim a working feature is missing.
    for (const menu of MENUS) {
      for (const item of menu.items ?? []) {
        expect(item.href.startsWith("/not-built/")).toBe(Boolean(item.notBuilt));
      }
    }
  });

  it("never marks a not-built item as role-restricted", () => {
    // Roles describe who may use a capability. A module with no backend has no capability to
    // restrict, and a role gate there would be a guess at an authorisation nobody has designed.
    for (const menu of MENUS) {
      for (const item of menu.items ?? []) {
        if (item.notBuilt) expect(item.roles).toBeUndefined();
      }
    }
  });
});
