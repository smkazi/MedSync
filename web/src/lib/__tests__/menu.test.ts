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
    "Billing",
    "Messaging",
  ],
  // No Billing for either: neither raises an invoice nor takes money — the laboratory's charges
  // reach billing as an event, with no token and no screen — and a bench technician who could read
  // what every patient has been billed would be reading a financial record for no reason anybody
  // can state.
  LAB_TECH: [
    "Dashboard",
    "Patients",
    "Scheduling",
    "Laboratory",
    "Facility",
  ],
  PATHOLOGIST: [
    "Dashboard",
    "Patients",
    "Scheduling",
    "Laboratory",
    "Facility",
  ],
  // The pharmacy sees its own module and nothing clinical. That is the point of the role: a
  // pharmacist reads a prescription and an allergy list, and never a chart — so there is no
  // Patients menu, no Scheduling and no Clinical, and no Billing either: a dispense is charged
  // through an event rather than by anybody at the counter pressing a button.
  PHARMACIST: ["Dashboard", "Pharmacy"],
  // The billing desk sees the money and nothing else. The mirror image of the pharmacist: this
  // account can raise an invoice and take a payment and cannot open a chart, which is what makes
  // the separation demonstrable rather than asserted.
  CASHIER: ["Dashboard", "Billing"],
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
    // A receptionist triages but must not read the casualty board or the in-patient census - both
    // are a chart in table form. The Clinical menu survives on Triage, and the two items they
    // cannot act on are absent, not present and greyed out, which would disclose what exists and
    // that somebody else can reach it.
    const clinical = menusFor(userWith("RECEPTIONIST")).find((menu) => menu.label === "Clinical");
    expect(clinical?.items?.map((item) => item.label)).toEqual(["Triage"]);

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
    // The casualty board and the census are clinical reading, and the bench and the front desk
    // are both outside it.
    expect(reachableHrefs(userWith("LAB_TECH"))).not.toContain("/casualty");
    expect(reachableHrefs(userWith("RECEPTIONIST"))).not.toContain("/admissions");
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

  it("points nothing at a placeholder route", () => {
    // The menu used to carry items marked "not built" that led to a page naming what a module
    // needed. Every one of those modules exists now, so the flag and the page are gone — and this
    // is what stops either coming back by accident, as a link to a route with nothing behind it.
    for (const menu of MENUS) {
      for (const item of menu.items ?? []) {
        expect(item.href).not.toMatch(/^\/not-built\//);
        expect(item.href.startsWith("/")).toBe(true);
      }
    }
  });
});
