import { expect, test } from "@playwright/test";
import { openMenu, signIn } from "./sign-in";

/**
 * The navigation shell, exercised against a real browser.
 *
 * <p>Three things here are worth more than the rest. **Role filtering is asserted per role**, for
 * all eleven seeded users, against a hand-written list of what each should see — not against the menu
 * constant, which would just be the implementation agreeing with itself. **Keyboard operation is
 * asserted**, because dropdowns were the chosen pattern and a dropdown that cannot be driven from a
 * keyboard is the failure mode this app cannot afford on a wall-mounted terminal. And **every link
 * in the menu is opened**, which is the check that stops the menu drifting behind the routes the
 * way the five-link bar it replaced did.
 */

/**
 * What each seeded user must find in the top bar. Deliberately literal.
 *
 * A receptionist sees no Laboratory; only the administrator sees Administration. Pharmacy and
 * Billing were both in every role's list while they had no backend, and neither is now: a module
 * with a service and a role behind it is gated like every other one. So the laboratory accounts
 * see neither, the pharmacy sees no money, and the billing desk sees nothing clinical. The
 * laboratory roles also see no Clinical menu at all: triage is the front desk and the clinicians,
 * the casualty board and the census are clinical reading, and a menu whose every child was
 * filtered away is dropped rather than shown empty.
 */
const EXPECTED: Record<string, string[]> = {
  admin: [
    "Dashboard",
    "Patients",
    "Scheduling",
    "Clinical",
    "Laboratory",
    "Radiology",
    "Facility",
    "Pharmacy",
    "Billing",
    "Immunisation",
    "Public health",
    "Sharing",
    "Messaging",
    "Administration",
  ],
  "dr.rao": [
    "Dashboard",
    "Patients",
    "Scheduling",
    "Clinical",
    "Laboratory",
    "Radiology",
    "Facility",
    "Pharmacy",
    "Billing",
    "Immunisation",
    "Public health",
    "Sharing",
    "Messaging",
  ],
  "nurse.iqbal": [
    "Dashboard",
    "Patients",
    "Scheduling",
    "Clinical",
    "Laboratory",
    "Radiology",
    "Facility",
    "Pharmacy",
    "Billing",
    "Immunisation",
    "Public health",
    "Sharing",
    "Messaging",
  ],
  reception: [
    "Dashboard",
    "Patients",
    "Scheduling",
    "Clinical",
    "Facility",
    "Billing",
    "Sharing",
    "Messaging",
  ],
  "lab.tech": [
    "Dashboard",
    "Patients",
    "Scheduling",
    "Laboratory",
    "Facility",
  ],
  "dr.pathan": [
    "Dashboard",
    "Patients",
    "Scheduling",
    "Laboratory",
    "Facility",
  ],
  // The pharmacy account. Its menu is the shape of the role: the module it works in, and nothing
  // clinical — a pharmacist reads a prescription and an allergy list and never a chart.
  pharmacist: ["Dashboard", "Pharmacy", "Immunisation"],
  // The billing desk. The mirror image of the pharmacist: the module it works in and nothing
  // clinical, which is what makes the separation of duties demonstrable rather than asserted.
  cashier: ["Dashboard", "Billing"],
  // The radiography room and the reporting room. Two accounts, one menu each, and the same shape
  // as the pharmacy and the billing desk: the department they work in and nothing clinical. The
  // *items* inside that one menu are where they differ, and radiology.spec.ts is where that is
  // asserted — a radiographer is offered the worklist and not the reporting queue, and a
  // radiologist the reverse.
  radiographer: ["Dashboard", "Radiology"],
  "dr.mistry": ["Dashboard", "Radiology"],
  // Public health and nothing else. The narrowest menu on the platform, and the one whose shape is
  // an argument rather than a convenience: the care-relationship narrowing is "is a clinician and
  // is not an administrator", so this role falls outside it by a check nobody edited — which is
  // safe exactly as long as it is offered no per-patient screen. The Line list item is inside this
  // menu for an administrator and absent here, which public-health.spec.ts asserts both ways.
  epidemiologist: ["Dashboard", "Public health"],
};

test.describe("the menu is role-aware", () => {
  for (const [username, expected] of Object.entries(EXPECTED)) {
    test(`${username} sees exactly the expected top-level menus`, async ({ page }) => {
      await signIn(page, username);
      const nav = page.getByRole("navigation", { name: "Main" });

      // Triggers are buttons, the one plain link is Dashboard; read them in document order.
      const labels = await nav
        .locator(":scope > a, :scope > div > button")
        .evaluateAll((nodes) => nodes.map((node) => node.textContent?.replace("▾", "").trim()));

      expect(labels).toEqual(expected);
    });
  }

  test("a lab technician gets no Clinical menu at all", async ({ page }) => {
    await signIn(page, "lab.tech");
    // Every child is gated away — triage to the front desk and the clinicians, both boards to
    // BED_MANAGE — and an empty dropdown is worse than an absent one.
    await expect(page.getByRole("button", { name: "Clinical", exact: true })).toHaveCount(0);
  });

  test("a receptionist is offered triage and neither board", async ({ page }) => {
    // The other half of the pair, in its own test: a second signIn() in one test lands on the
    // dashboard rather than the sign-in form, because the session is still valid. This is the
    // filtering case — the menu survives on Triage while the two items reception cannot act on
    // are absent, not greyed out.
    await signIn(page, "reception");
    const clinical = await openMenu(page, "Clinical");
    await expect(clinical.getByRole("link", { name: "Triage" })).toBeVisible();
    await expect(clinical.getByRole("link", { name: "Casualty board" })).toHaveCount(0);
    await expect(clinical.getByRole("link", { name: "Admissions & beds" })).toHaveCount(0);
  });

  test("a nurse is offered the casualty board and the census", async ({ page }) => {
    await signIn(page, "nurse.iqbal");
    const clinical = await openMenu(page, "Clinical");
    await expect(clinical.getByRole("link", { name: "Casualty board" })).toBeVisible();
    await expect(clinical.getByRole("link", { name: "Admissions & beds" })).toBeVisible();
  });

  test("an item the role cannot reach is absent, not disabled", async ({ page }) => {
    await signIn(page, "reception");
    // Nothing anywhere in the shell may render as disabled or aria-disabled: that would disclose
    // both what exists and that somebody else can reach it.
    await expect(page.getByRole("banner").locator("[aria-disabled='true'], button[disabled]")).toHaveCount(
      0,
    );
    // And the route itself still refuses, which is the actual control.
    await page.goto("/admin/users");
    await expect(page.getByRole("main")).toContainText(/do not have permission to perform this action/i);
  });
});

test.describe("the menu works from a keyboard alone", () => {
  test("Enter opens, arrows move and wrap, Escape closes and restores focus", async ({ page }) => {
    await signIn(page, "admin");
    const trigger = page.getByRole("button", { name: "Facility", exact: true });

    await trigger.focus();
    await expect(trigger).toHaveAttribute("aria-expanded", "false");

    await page.keyboard.press("Enter");
    await expect(trigger).toHaveAttribute("aria-expanded", "true");
    // Opening lands on the first item, so the next key press is already useful.
    await expect(page.locator("a:focus")).toHaveText(/Room directory/);

    await page.keyboard.press("ArrowDown");
    await expect(page.locator("a:focus")).toHaveText(/^Rooms/);

    await page.keyboard.press("ArrowUp");
    await expect(page.locator("a:focus")).toHaveText(/Room directory/);

    // Wraps backwards off the top rather than trapping the cursor there.
    await page.keyboard.press("ArrowUp");
    await expect(page.locator("a:focus")).toHaveText(/Departments/);

    await page.keyboard.press("Escape");
    await expect(trigger).toHaveAttribute("aria-expanded", "false");
    // Focus returns to where the user was, not to the top of the document.
    await expect(page.locator("button:focus")).toHaveText(/^Facility/);
  });

  test("Space opens a menu and ArrowDown on a closed trigger opens it too", async ({ page }) => {
    await signIn(page, "admin");
    const facility = page.getByRole("button", { name: "Facility", exact: true });

    await facility.focus();
    await page.keyboard.press(" ");
    await expect(facility).toHaveAttribute("aria-expanded", "true");
    await page.keyboard.press("Escape");

    await facility.focus();
    await page.keyboard.press("ArrowDown");
    await expect(facility).toHaveAttribute("aria-expanded", "true");
    await expect(page.locator("a:focus")).toHaveText(/Room directory/);
  });

  test("a keyboard user can reach a screen without ever using a pointer", async ({ page }) => {
    await signIn(page, "admin");
    await page.getByRole("button", { name: "Administration", exact: true }).focus();
    await page.keyboard.press("Enter");

    // Asserted before the arrows, because the failure this test found was a lost keystroke:
    // opening deferred the focus to the next paint, so an ArrowDown arriving inside that frame
    // landed on the trigger instead of the list and did nothing. Three downs then reached the
    // third item rather than the fourth. Opening now commits synchronously, and this is the line
    // that says so.
    await expect(page.locator("a:focus")).toHaveText(/Staff directory/);

    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");

    await expect(page.getByRole("heading", { level: 1 })).toHaveText(/Audit trail/);
  });

  test("hovering alone does not open a menu", async ({ page }) => {
    // The whole reason this is a disclosure widget: a hover-only path is unusable on the tablets
    // and wall terminals this runs on, and opens by accident when a pointer crosses the bar.
    await signIn(page, "admin");
    const trigger = page.getByRole("button", { name: "Laboratory", exact: true });
    await trigger.hover();
    await expect(trigger).toHaveAttribute("aria-expanded", "false");
  });

  test("clicking outside closes an open menu", async ({ page }) => {
    await signIn(page, "admin");
    const trigger = page.getByRole("button", { name: "Laboratory", exact: true });
    await trigger.click();
    await expect(trigger).toHaveAttribute("aria-expanded", "true");

    await page.getByRole("main").click({ position: { x: 5, y: 5 } });
    await expect(trigger).toHaveAttribute("aria-expanded", "false");
  });
});

test.describe("every menu link resolves", () => {
  test("an administrator can open all of them without a 404 or a server error", async ({ page }) => {
    // Thirty-odd server-rendered pages, each hitting the gateway. The default per-test budget is
    // for one interaction, not a sweep of the whole application.
    test.setTimeout(240_000);
    await signIn(page, "admin");
    const nav = page.getByRole("navigation", { name: "Main" });

    // Collect the hrefs from the rendered menu, not from the source constant. A test that reads
    // menu.ts would agree with a typo; this one opens what a person would actually click.
    const hrefs: string[] = [];
    for (const link of await nav.locator(":scope > a[href]").all()) {
      hrefs.push((await link.getAttribute("href")) as string);
    }
    for (const trigger of await nav.locator("button[aria-controls]").all()) {
      await trigger.click();
      const panel = page.locator(`#${await trigger.getAttribute("aria-controls")}`);
      for (const link of await panel.locator("a[href]").all()) {
        hrefs.push((await link.getAttribute("href")) as string);
      }
      await page.keyboard.press("Escape");
    }

    expect(hrefs.length).toBeGreaterThan(25);

    const broken: string[] = [];
    for (const href of hrefs) {
      const response = await page.goto(href);
      const status = response?.status() ?? 0;
      // A page whose server render threw has no <main> at all - it is replaced wholesale by the
      // browser's own error screen. That is the check that caught /patients/new returning
      // `undefined` for a constant exported from a "use server" module: status 200, no page.
      const rendered = await page.getByRole("main").count();
      const body = rendered ? ((await page.getByRole("main").textContent()) ?? "") : "";
      if (
        status >= 400 ||
        rendered === 0 ||
        // Matched against the framework's own wording only. A bare /404/ was tried first and
        // flagged /admin/users, because "404" appears inside a correlation id on that page.
        /This page could not be found|Application error|Internal Server Error/i.test(body)
      ) {
        broken.push(`${href} (${status}${rendered === 0 ? ", did not render" : ""})`);
      }
    }
    expect(broken, "menu links that do not resolve").toEqual([]);
  });
});

test.describe("every menu item leads to a real screen", () => {
  test("no item claims to be unbuilt, and none points at a placeholder route", async ({
    page,
  }) => {
    // This test used to prove the opposite: that Billing said "not built" and offered nothing that
    // looked functional. Billing is built, and so is every other module that was ever in that
    // list, so the honest assertion is now that the label appears nowhere.
    await signIn(page, "admin");
    const nav = page.getByRole("navigation", { name: "Main" });

    for (const trigger of await nav.locator("button[aria-controls]").all()) {
      await trigger.click();
      const panel = page.locator(`#${await trigger.getAttribute("aria-controls")}`);
      await expect(panel).toBeVisible();
      await expect(panel.getByText(/not built/i)).toHaveCount(0);
      for (const link of await panel.locator("a[href]").all()) {
        expect(await link.getAttribute("href")).not.toMatch(/^\/not-built\//);
      }
      await page.keyboard.press("Escape");
    }
  });

  test("the placeholder route is gone rather than answering for anything", async ({ page }) => {
    await signIn(page, "admin");
    // It served pages naming what a module still needed, and every one of those modules is built.
    // A route that stayed would answer a "not built yet" page for a screen that exists, which is
    // the one failure mode the whole idea was meant to avoid.
    const response = await page.goto("/not-built/invoices");
    expect(response?.status()).toBe(404);
  });
});
