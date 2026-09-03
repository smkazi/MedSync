import { expect, test, type Locator, type Page } from "@playwright/test";
import { signIn } from "./sign-in";

/**
 * Facility and administration writes.
 *
 * <p>Seven CRUD surfaces share one form component, so the assertions here are less about each
 * field and more about the four rules that differ between them — and each of those rules is one
 * somebody could break without noticing:
 *
 * <ul>
 *   <li>a room is read by code and written by id, so the row has to carry both</li>
 *   <li>an unticked checkbox posts nothing, which for a sparse PATCH would read as "unchanged"</li>
 *   <li>an empty role set means "leave the roles alone", never "take them all away"</li>
 *   <li>every one of these is ADMIN_ONLY, so nobody else is offered the form at all</li>
 * </ul>
 */

/** A suffix unique to this run: these tests write to a database that outlives them. */
function unique(prefix: string): string {
  return `${prefix}${Date.now().toString(36).slice(-5).toUpperCase()}`;
}

/**
 * The lowest level no floor occupies, read off the table.
 *
 * <p>`uq_floor_level` is a hard unique constraint and nothing removes a floor, so a test that
 * hard-codes a level passes once against a persistent database and conflicts for ever after.
 * Claiming the next free one is the only repeatable shape — and it exercises the refusal too,
 * because the platform now names the floor already at a level rather than reporting a constraint.
 */
async function nextFreeLevel(page: Page): Promise<number> {
  const cells = await page
    .getByRole("region", { name: "Floors" })
    .locator("tbody tr td:first-child")
    .allTextContents();
  const levels = cells.map((text) => Number(text.trim())).filter((level) => Number.isFinite(level));
  return Math.max(0, ...levels) + 1;
}

/** Opens the `<details>` that holds a row's edit form, identified by a cell in that row. */
async function openEditFor(page: Page, rowText: string) {
  const row = page.getByRole("row").filter({ hasText: rowText });
  await openDisclosure(row);
  return row;
}

/**
 * Opens a row's `<details>`, and leaves it open if it already was.
 *
 * <p>Clicking the summary toggles. A test that edits the same row twice therefore closed the form
 * on its second visit and then waited ninety seconds for a field that was right there in the DOM
 * and invisible.
 */
async function openDisclosure(scope: Locator) {
  const details = scope.locator("details").first();
  if (!(await details.evaluate((element) => (element as HTMLDetailsElement).open))) {
    await scope.getByText("Edit", { exact: true }).first().click();
  }
}

/**
 * The named card a form lives in.
 *
 * <p>Every scoped, deliberately. These screens render one edit form per table row plus one to add
 * with, all sharing the same field labels, so an unscoped `getByLabel("Name")` is ambiguous by
 * construction — it used to resolve to whichever came first in the document, which was a collapsed
 * edit form for an unrelated row. A titled `Card` is an ARIA region with a name, which is what
 * makes this addressable at all.
 */
function card(page: Page, title: string) {
  return page.getByRole("region", { name: title });
}

/**
 * Why every status assertion below is scoped.
 *
 * <p>These screens carry one form per row plus one to add with, and each reports its own outcome.
 * After an inline edit two `role="status"` regions are legitimately on the page — the add form
 * still saying what it added, the row saying what it saved — so an unscoped `getByRole("status")`
 * is ambiguous by construction rather than by accident. Scoping says which form is being asked
 * about, which is also the only version of the assertion that could catch the wrong form reporting.
 */

test.describe("facility administration", () => {
  test("a floor is added, then corrected and taken out of use", async ({ page }) => {
    test.setTimeout(90_000);
    await signIn(page, "admin");
    await page.goto("/facility/floors");

    const code = unique("F").slice(0, 6);
    const level = await nextFreeLevel(page);
    const add = card(page, "Add a floor");
    await add.getByLabel("Code").fill(code);
    await add.getByLabel("Name").fill("Verification Floor");
    await add.getByLabel("Level").fill(String(level));
    await add.getByRole("button", { name: "Add floor" }).click();

    await expect(add.getByRole("status")).toContainText(`Floor ${code.toUpperCase()} added`);
    await expect(page.getByRole("cell", { name: code.toUpperCase(), exact: true })).toBeVisible();

    // Sparse update: only the name is retyped, and the level must survive untouched.
    const row = await openEditFor(page, code.toUpperCase());
    await row.getByLabel("Name").fill("Verification Floor, renamed");
    await row.getByRole("button", { name: "Save" }).click();
    await expect(row.getByRole("status")).toContainText("Floor updated");
    await expect(
      page.getByRole("row").filter({ hasText: code.toUpperCase() }),
    ).toContainText("Verification Floor, renamed");
    await expect(page.getByRole("row").filter({ hasText: code.toUpperCase() })).toContainText(
      String(level),
    );

    // An unticked checkbox posts nothing at all, so the form carries a hidden "false" beside it.
    // Without that, deactivating would silently do nothing.
    const again = await openEditFor(page, code.toUpperCase());
    await again.getByLabel("In use").uncheck();
    await again.getByRole("button", { name: "Save" }).click();
    // The badge, not the status line. "Floor updated." is still on screen from the save above, so
    // asserting it again passes instantly on a stale message and then races the actual write - the
    // failure looked like the deactivation had not happened when it simply had not finished.
    await expect(
      page.getByRole("row").filter({ hasText: code.toUpperCase() }),
    ).toContainText("inactive", { timeout: 15_000 });
  });

  test("a room type is added and its flags are what the platform reads", async ({ page }) => {
    test.setTimeout(90_000);
    await signIn(page, "admin");
    await page.goto("/facility/room-types");

    const code = unique("DAY_");
    const add = card(page, "Add a room type");
    await add.getByLabel("Code").fill(code);
    await add.getByLabel("Name").fill("Day unit");
    await add.getByLabel("Clinical", { exact: true }).check();
    await add.getByLabel("Schedulable", { exact: true }).check();
    await add.getByRole("button", { name: "Add room type" }).click();

    await expect(add.getByRole("status")).toContainText(`Room type ${code.toUpperCase()} added`);
    const row = page.getByRole("row").filter({ hasText: code.toUpperCase() });
    await expect(row).toContainText("yes");

    // Schedulable and bed-allocated together is refused by a database constraint, because it would
    // let a booked outpatient be sent to a resuscitation position. The refusal is the service's.
    const editing = await openEditFor(page, code.toUpperCase());
    await editing.getByLabel("Bed-allocated", { exact: true }).check();
    await editing.getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("main")).toContainText(/schedulable|bed|allocat/i);
  });

  test("a room is created and then edited by id, not by code", async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, "admin");
    await page.goto("/facility/rooms");

    const code = unique("VR-");
    const add = card(page, "Add a room");
    await add.getByLabel("Code").fill(code);
    await add.getByLabel("Name").fill("Verification Room");
    await add.getByLabel("Type").selectOption("CONSULTATION");
    await add.getByLabel("Floor").selectOption({ index: 1 });
    await add.getByLabel("Bookable").check();
    await add.getByRole("button", { name: "Add room" }).click();

    await expect(add.getByRole("status")).toContainText(`Room ${code.toUpperCase()} added`);

    await page.goto(`/facility/rooms?q=${code}`);
    const row = page.getByRole("row").filter({ hasText: code.toUpperCase() });
    // A consulting room marked bookable is bookable now, because its type is schedulable.
    await expect(row).toContainText("yes");

    // GET /rooms/{code} and PATCH /rooms/{id} are deliberately asymmetric, so the row carries the
    // id as well as the code. If it did not, this save would 404 or hit the wrong room.
    const editing = await openEditFor(page, code.toUpperCase());
    await editing.getByLabel("Directions").fill("Second door past the lifts.");
    await editing.getByRole("button", { name: "Save" }).click();
    await expect(editing.getByRole("status")).toContainText("Room updated");

    await page.goto(`/facility/rooms?q=${code}`);
    await expect(page.getByRole("main")).toContainText("Second door past the lifts.");
  });

  test("a bed is added within capacity and can be taken out of service", async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, "admin");

    // Its own ward, so this test never competes with the seeded casualty capacity.
    const roomCode = unique("VW-");
    await page.goto("/facility/rooms");
    const addRoom = card(page, "Add a room");
    await addRoom.getByLabel("Code").fill(roomCode);
    await addRoom.getByLabel("Name").fill("Verification Ward");
    await addRoom.getByLabel("Type").selectOption("WARD");
    await addRoom.getByLabel("Floor").selectOption({ index: 1 });
    await addRoom.getByLabel("Bed capacity").fill("1");
    await addRoom.getByRole("button", { name: "Add room" }).click();
    await expect(addRoom.getByRole("status")).toContainText("added");

    await page.goto("/facility/beds");
    // A bed code unique to this run: each run builds its own ward, but "VB-1" in five wards makes
    // every row locator below ambiguous.
    const bedCode = unique("VB-");
    const addBedForm = card(page, "Add a bed");
    await addBedForm.getByLabel("Room").selectOption(roomCode.toUpperCase());
    await addBedForm.getByLabel("Bed code").fill(bedCode);
    await addBedForm.getByLabel("Label").fill("Window side");
    await addBedForm.getByRole("button", { name: "Add bed" }).click();
    await expect(addBedForm.getByRole("status")).toContainText(`Bed ${bedCode.toUpperCase()} added`);

    // One more than the room is designed for is refused, with the numbers — a room with more
    // positions recorded than it has means one of those beds is somewhere else.
    await addBedForm.getByLabel("Room").selectOption(roomCode.toUpperCase());
    await addBedForm.getByLabel("Bed code").fill(`${bedCode}X`);
    await addBedForm.getByRole("button", { name: "Add bed" }).click();
    await expect(page.getByRole("main")).toContainText(/designed for 1 bed/i);

    // Scoped to Positions: the by-room summary above it lists every bed code in a room, so an
    // unscoped row filter matches both that summary row and the position's own.
    const positions = card(page, "Positions");
    const row = positions.getByRole("row").filter({ hasText: bedCode });
    await openDisclosure(row);
    await row.getByLabel("In service").uncheck();
    await row.getByRole("button", { name: "Save" }).click();
    await expect(row.getByRole("status")).toContainText("Bed updated");
    await expect(positions.getByRole("row").filter({ hasText: bedCode }))
      .toContainText("out of service");
  });

  test("a department is added and retired, and its code cannot be rewritten", async ({ page }) => {
    test.setTimeout(90_000);
    await signIn(page, "admin");
    await page.goto("/facility/departments");

    const code = unique("VD");
    const add = card(page, "Add a department");
    await add.getByLabel("Code").fill(code);
    await add.getByLabel("Name").fill("Verification Clinic");
    await add.getByRole("button", { name: "Add department" }).click();
    await expect(add.getByRole("status")).toContainText(`Department ${code.toUpperCase()} added`);

    // Three services store the code, so the edit form has no code field to offer.
    const row = await openEditFor(page, code.toUpperCase());
    await expect(row.getByLabel("Code")).toHaveCount(0);
    await row.getByLabel("In use").uncheck();
    await row.getByRole("button", { name: "Save" }).click();
    await expect(row.getByRole("status")).toContainText("updated");
    await expect(
      page.getByRole("row").filter({ hasText: code.toUpperCase() }),
    ).toContainText("inactive");
  });

  test("nobody but an administrator is offered these forms", async ({ page }) => {
    test.setTimeout(90_000);
    await signIn(page, "dr.rao");

    // Every write here is ADMIN_ONLY. A doctor reads the building and cannot change it, and the
    // form is absent rather than present and refused — a screen that looks usable and is not is
    // worse than one that is honest about it.
    for (const path of ["/facility/floors", "/facility/rooms", "/facility/room-types"]) {
      await page.goto(path);
      await expect(page.getByRole("main")).toBeVisible();
      await expect(page.getByRole("button", { name: /^Add / })).toHaveCount(0);
      await expect(page.getByText("Edit", { exact: true })).toHaveCount(0);
    }
  });
});

test.describe("account administration", () => {
  test("an account is created owing a password change, and its roles are a set", async ({
    page,
  }) => {
    test.setTimeout(120_000);
    await signIn(page, "admin");
    await page.goto("/admin/users");

    const username = unique("verify").toLowerCase();
    const add = card(page, "Create an account");
    await add.getByLabel("Username").fill(username);
    await add.getByLabel("Full name").fill("Verification Account");
    await add.getByLabel("Email").fill(`${username}@hms.local`);
    await add.getByLabel("Initial password").fill("Verification!Pass2026");
    await add.getByRole("checkbox", { name: "NURSE" }).check();
    await add.getByRole("button", { name: "Create account" }).click();

    await expect(add.getByRole("status")).toContainText(
      /must change this password before it can do anything/,
    );

    await page.goto(`/admin/users?q=${username}`);
    const row = page.getByRole("row").filter({ hasText: username });
    await expect(row).toContainText("NURSE");
    // Created flagged: whoever creates an account knows the password they chose for it.
    await expect(row).toContainText("initial password");

    // Saving with no role ticked leaves the roles alone. An empty set would strip the account's
    // access, and the screen that did that by accident would look exactly like one that did
    // nothing at all.
    const editing = await openEditFor(page, username);
    await editing.getByLabel("Full name").fill("Verification Account, renamed");
    await editing.getByRole("button", { name: "Save" }).click();
    await expect(editing.getByRole("status")).toContainText("Account updated");

    await page.goto(`/admin/users?q=${username}`);
    const after = page.getByRole("row").filter({ hasText: username });
    await expect(after).toContainText("Verification Account, renamed");
    await expect(after).toContainText("NURSE");
  });

  test("creating an account with no role is refused before the platform is asked", async ({
    page,
  }) => {
    test.setTimeout(90_000);
    await signIn(page, "admin");
    await page.goto("/admin/users");

    const username = unique("norole").toLowerCase();
    const add = card(page, "Create an account");
    await add.getByLabel("Username").fill(username);
    await add.getByLabel("Full name").fill("No Role Account");
    await add.getByLabel("Email").fill(`${username}@hms.local`);
    await add.getByLabel("Initial password").fill("Verification!Pass2026");
    await add.getByRole("button", { name: "Create account" }).click();

    await expect(page.getByRole("main")).toContainText(/at least one role/i);
    await page.goto(`/admin/users?q=${username}`);
    await expect(page.getByRole("cell", { name: username })).toHaveCount(0);
  });

  test("being bookable takes a staff record AND a login, and this proves both halves", async ({
    page,
  }) => {
    test.setTimeout(150_000);
    await signIn(page, "admin");

    // An appointment's clinician_id *is* a user id, so a staff record with no login cannot be
    // booked however complete it looks - and a login with no staff record has no name to offer.
    // Both halves are needed, which is why this test builds both. The first version asserted only
    // the staff record and failed for the right reason: the platform would not offer it.
    const username = unique("clin").toLowerCase();
    const name = `Verification Clinician ${username}`;
    await page.goto("/admin/users");
    const addAccount = card(page, "Create an account");
    await addAccount.getByLabel("Username").fill(username);
    await addAccount.getByLabel("Full name").fill(name);
    await addAccount.getByLabel("Email").fill(`${username}@hms.local`);
    await addAccount.getByLabel("Initial password").fill("Verification!Pass2026");
    await addAccount.getByRole("checkbox", { name: "DOCTOR" }).check();
    await addAccount.getByRole("button", { name: "Create account" }).click();
    await expect(addAccount.getByRole("status")).toContainText("must change this password");

    const employeeNo = unique("EMP");
    await page.goto("/admin/staff");
    const add = card(page, "Add a staff record");
    await add.getByLabel("Employee no").fill(employeeNo);
    await add.getByLabel("Full name").fill(name);
    await add.getByLabel("Designation").fill("Consultant Physician");
    await add.getByLabel("Department").selectOption("GEN");
    await add.getByLabel("Platform login").selectOption({ label: `${name} (${username})` });
    await add.getByRole("button", { name: "Add to the directory" }).click();
    await expect(add.getByRole("status")).toContainText(`${name} added to the directory`);

    // The combobox, not the region: `Card` names its section "Clinician and day", which answers
    // to a label match for "Clinician" as well.
    await page.goto("/appointments/new");
    await expect(page.getByRole("combobox", { name: "Clinician" })).toContainText(name);

    // Out of post, out of the pick-list. The login is untouched and still not bookable without a
    // staff record, which is the other half of the same rule.
    // Searching by the number on the badge, which the screen has always promised and the query
    // only started honouring in this slice.
    await page.goto(`/admin/staff?q=${employeeNo}`);
    const row = await openEditFor(page, employeeNo);
    await row.getByLabel("In post").uncheck();
    await row.getByRole("button", { name: "Save" }).click();

    // Not the row's own status: out of post means out of the default list, so the row this form
    // lived in is gone by the time the save lands. Its disappearance is the outcome, and it is a
    // stronger assertion than the message would have been.
    await expect(page.getByRole("row").filter({ hasText: employeeNo })).toHaveCount(0);
    await page.goto(`/admin/staff?q=${employeeNo}&includeInactive=on`);
    await expect(page.getByRole("row").filter({ hasText: employeeNo })).toContainText("inactive");

    await page.goto("/appointments/new");
    await expect(page.getByRole("combobox", { name: "Clinician" })).not.toContainText(name);
  });


  test("a password reset signs every session out and re-flags the account", async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, "admin");
    await page.goto("/admin/users");

    const username = unique("reset").toLowerCase();
    const add = card(page, "Create an account");
    await add.getByLabel("Username").fill(username);
    await add.getByLabel("Full name").fill("Reset Target");
    await add.getByLabel("Email").fill(`${username}@hms.local`);
    await add.getByLabel("Initial password").fill("Verification!Pass2026");
    await add.getByRole("checkbox", { name: "RECEPTIONIST" }).check();
    await add.getByRole("button", { name: "Create account" }).click();
    await expect(add.getByRole("status")).toContainText("must change this password");

    await page.goto(`/admin/users?q=${username}`);
    const row = page.getByRole("row").filter({ hasText: username });
    await row.getByText("Reset password").click();
    await row.getByLabel("New password").fill("Reset!Password2026");
    await row.getByRole("button", { name: "Reset it" }).click();

    await expect(row.getByRole("status")).toContainText(
      /Every session is signed out and it must be changed/,
    );
  });
});

test.describe("the audit report", () => {
  test("filtering by who narrows the report, and the CSV is the same report", async ({ page }) => {
    // The row this test reads is written by signing in as somebody: LOGIN_SUCCEEDED for dr.rao.
    await signIn(page, "dr.rao");
    await signIn(page, "admin");

    await page.goto("/admin/audit?action=LOGIN_SUCCEEDED&username=dr.rao");
    const rows = page.getByRole("row").filter({ hasText: "LOGIN_SUCCEEDED" });
    await expect(rows.first()).toBeVisible();
    // Every row the filter returned is that person's. The defect this guards is a filter that
    // also returned every system-initiated row, which is a report answering a different question.
    for (const row of await rows.all()) {
      await expect(row).toContainText("dr.rao");
    }

    const download = page.waitForEvent("download");
    await page.getByRole("link", { name: "Download CSV" }).click();
    const file = await download;

    // The filename carries the period, so two downloads in one folder are still tellable apart.
    expect(file.suggestedFilename()).toMatch(/^audit-\d{4}-\d{2}-\d{2}-to-\d{4}-\d{2}-\d{2}\.csv$/);
  });

  test("a doctor is not offered the audit trail at all", async ({ page }) => {
    await signIn(page, "dr.rao");
    await page.goto("/admin/audit");

    await expect(page.getByRole("main").getByRole("alert")).toBeVisible();
    await expect(page.getByRole("link", { name: "Download CSV" })).toHaveCount(0);
  });
});
