import { expect, test, type Page } from "@playwright/test";
import { CLINICIAN, fixtureMrn, nextWeekday } from "./chart";
import { FIXTURE_SURNAME } from "./global-setup";
import { signIn } from "./sign-in";

/**
 * The OPD token queue, and the screen in the corridor.
 *
 * <p>The assertion that matters is the last one, and it is the reason the display is a separate
 * endpoint with a separate DTO: the page is served to a browser with no session at all, to a screen
 * that every visitor and passer-by in the building can read, and it must contain a room code and
 * some numbers and nothing else.
 */

const ROOM = "GF-GEN";

/**
 * Everything on a corridor display is either the page's own fixed copy or a number.
 *
 * <p>An invariant rather than a denylist, and both display tests use it so that the populated and
 * the quiet screen are each held to it. The denylist version of this check forbade the substring
 * "patient", which the empty-state copy legitimately contains, so its verdict depended on whether
 * anybody happened to be in today's queue.
 */
async function showsNothingButFixedCopyAndNumbers(page: Page, roomCode: string) {
  const shown = (await page.getByRole("main").innerText()).trim();
  const remainder = shown
    .replaceAll(roomCode, "")
    .replace(/now serving/i, "")
    .replace(/next/i, "")
    .replace(/no patients waiting\./i, "")
    .replace(/[\u2014\u2013]/g, "")
    .trim();
  expect(remainder, `the display shows fixed copy and numbers, nothing else; it read: ${shown}`)
    .toMatch(/^[\d\s]*$/);

  // The identity-shaped strings as well, over the whole document rather than the rendered text: a
  // value carried in an attribute, a data island or the streamed payload is not visible in the
  // corridor but is still served to anybody who views source.
  const html = (await page.content()).toLowerCase();
  for (const forbidden of [
    FIXTURE_SURNAME.toLowerCase(),
    "mrn-",
    "patientid",
    "patientmrn",
    "clinicianid",
    "appointmentid",
    "fullname",
  ]) {
    expect(html, `the display must not contain "${forbidden}"`).not.toContain(forbidden);
  }
  // A uuid anywhere is a leaked identifier whatever it identifies - with one exception that has
  // to be taken out first, or the check is a false positive on every run. Next stamps a CSP nonce
  // into the streamed payload and it is uuid-shaped, but it is fresh random bytes per request and
  // identifies nobody. Removed by name rather than by weakening the pattern, so a real uuid
  // sitting next to it is still caught.
  const withoutNonces = html.replace(
    /nonce[^a-z0-9]{0,8}[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g,
    "nonce",
  );
  expect(withoutNonces, "the display must carry no identifiers")
    .not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/);
}

test.describe("the OPD token queue", () => {
  test("checking in issues a number, and starting the consultation calls it", async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, "reception");
    const mrn = await fixtureMrn(page);

    // A future weekday, not today, and that is not a workaround. The seeded clinic runs 09:00 to
    // 13:00, so after lunch there is no bookable slot left today for anybody — every one is
    // answered "in the past" — and a test that books through the availability grid would skip
    // itself every afternoon. A skipped test is not a passing test.
    //
    // The staff board takes a date, so it shows the future day's queue quite happily. The
    // corridor display deliberately does not: it shows today and nothing else, which is why the
    // display's own properties are asserted separately below.
    const date = nextWeekday(3);

    await page.goto(`/appointments/new?mrn=${encodeURIComponent(mrn)}`);
    await page
      .getByRole("combobox", { name: "Clinician" })
      .selectOption({ label: `${CLINICIAN} — Consultant Physician` });
    await page.getByLabel("Date").fill(date);
    await page.getByRole("button", { name: "Show slots" }).click();

    const radio = page.locator('input[name="startsAt"]:not([disabled])').first();
    await expect(radio).toBeVisible();
    const time = ((await radio.locator("xpath=..").locator("span.numeric").textContent()) ?? "").trim();
    await radio.check();
    await page.getByLabel("Department").selectOption("GEN");
    await page.getByRole("combobox", { name: "Room" }).selectOption(ROOM);
    await page.getByRole("button", { name: "Book appointment" }).click();
    await expect(page.getByRole("status")).toContainText(/Booked/);

    await page.goto(`/appointments?from=${date}&to=${date}`);
    const row = () =>
      page
        .getByRole("row")
        .filter({ hasText: mrn })
        .filter({ hasText: new RegExp(`^${time}\\u2013`) });
    await row().getByRole("button", { name: "Check in" }).click();
    // Waited for, not assumed. The button posts a server action that redirects, and clicking only
    // dispatches it — navigating straight on races the write and reads the board a moment too
    // early, which is how this test first failed with a stale count.
    await expect(row()).toContainText("CHECKED_IN");

    // The board is a read-only view of what the appointment book just did. There are no buttons on
    // it on purpose: a second set of controls would be a second source of truth about the same
    // morning.
    await page.goto(`/scheduling/queue?room=${ROOM}&date=${date}`);
    // Asserted on the board's own table rather than on the summary tiles: a tile is a label and a
    // number in a div, so "Waiting: 1" and "Waiting: 0" are both just text on the page. A row
    // whose status reads "waiting" is the fact.
    const board = () => page.getByRole("region", { name: `${ROOM} — ${date}` });
    await expect(board()).toBeVisible();
    await expect(board().getByRole("row").filter({ hasText: "waiting" }).first()).toBeVisible();
    // Counted rather than asserted at zero. The board is per room per day and the day is fixed, so
    // a development database carries whatever earlier runs of this suite left in it — "no number
    // has been called" is true on a fresh database and false on the second run, which is the
    // worst kind of assertion. What is invariant is that starting this consultation calls exactly
    // one more number.
    const calledBefore = await board().getByRole("row").filter({ hasText: "called" }).count();

    // The clinician starts the consultation, not the front desk — the appointment book only
    // offers Start to a role that may write clinical content, which is why this test needs two
    // identities. That is the queue's whole shape in one line: reception issues the number and
    // the consulting room calls it.
    await signIn(page, "dr.rao");
    await page.goto(`/appointments?from=${date}&to=${date}`);
    await row().getByRole("button", { name: "Start" }).click();
    await expect(row()).toContainText("IN_PROGRESS");

    await page.goto(`/scheduling/queue?room=${ROOM}&date=${date}`);
    // One more number called. Which is the whole behaviour: the queue is a by-product of the
    // appointment lifecycle rather than something anybody maintains alongside it.
    await expect(board().getByRole("row").filter({ hasText: "called" })).toHaveCount(
      calledBefore + 1,
    );
  });

  test("the corridor display needs no session and shows numbers only", async ({ page }) => {
    // No signIn, and cookies cleared: this is the point of the test. The middleware allowlists
    // /display and the endpoint behind it is the platform's one unauthenticated path.
    await page.context().clearCookies();
    await page.goto(`/display/${ROOM}`);

    await expect(page.getByRole("heading", { name: ROOM })).toBeVisible();
    await expect(page.getByRole("region", { name: "Now serving" })).toBeVisible();
    await expect(page.getByText("Now serving")).toBeVisible();

    // Not bounced to a sign-in form, which is what a wall screen would otherwise be showing.
    await expect(page).toHaveURL(new RegExp(`/display/${ROOM}$`));
    await expect(page.getByRole("button", { name: "Sign in" })).toHaveCount(0);

    // No chrome at all: no navigation, and nothing to click away from.
    await expect(page.getByRole("navigation", { name: "Main" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Sign out" })).toHaveCount(0);
    await expect(page.getByRole("link")).toHaveCount(0);

    // And nothing about anybody.
    await showsNothingButFixedCopyAndNumbers(page, ROOM);

    // The populated corridor board - a real number being called, with nothing about the person it
    // belongs to - is proven end to end in tests/api's QueueJourneyIT, which books today through
    // the API and reads /public/queue directly. It cannot be proven here: this suite books a
    // future weekday (see above) and the display shows today and nothing else, both deliberately.
  });

  test("a room with no queue shows a quiet screen rather than an error", async ({ page }) => {
    await page.context().clearCookies();
    await page.goto("/display/ZZ-NOBODY");

    // A display is switched on before the first patient arrives. A screen in a corridor showing
    // an error page on a quiet morning tells a waiting room the hospital's computers are broken.
    await expect(page.getByText("No patients waiting.")).toBeVisible();
    await expect(page.getByText(/error/i)).toHaveCount(0);
    // Held to the same invariant, because this is the state a display spends the night in.
    await showsNothingButFixedCopyAndNumbers(page, "ZZ-NOBODY");
  });
});
