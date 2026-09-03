/**
 * The deployment's display zone, and the two directions across it.
 *
 * <p>`ui.tsx` has rendered instants in this zone since the UTC-on-screen defect: the day book, the
 * audit report and the disclosure register all window a business day in `HMS_ZONE`, so a screen
 * showing UTC puts a date on itself that cannot be typed into its own date box.
 *
 * <p>What was missing is the way back. A `datetime-local` input hands over "2026-09-04T10:30" with
 * no zone at all, and every obvious way to turn that into an instant is wrong somewhere:
 * `new Date(local)` in a server action reads the *server's* `TZ` (a container's, usually UTC), and
 * doing it in the browser reads the *operator's* — so the same slot typed at the same console
 * becomes a different instant depending on where the code ran and which laptop it ran on. Neither
 * is the zone the screen renders back in, which means a time typed as 10:30 could reappear as
 * 05:00 and look like the platform had moved it.
 *
 * <p>So the conversion is pinned to `DISPLAY_ZONE` on both sides. Type 10:30, read back 10:30,
 * wherever the process runs.
 */

/**
 * The zone every screen renders in and every typed wall-clock time is read in.
 *
 * <p>`NEXT_PUBLIC_` because both directions are needed in client components as well as server ones,
 * and a value only the server could read would give one instant two different faces.
 */
export const DISPLAY_ZONE = process.env.NEXT_PUBLIC_HMS_ZONE ?? "Asia/Kolkata";

/**
 * `2026-09-04T10:30`, optionally with seconds. What every `datetime-local` input submits.
 *
 * <p>Written out as repeated `\d` rather than with `{4}` and `{2}` counts because `safe-regex` —
 * which is what `security/detect-unsafe-regex` runs — sums a pattern's bounded repetitions and
 * warns past its limit. There is nothing to backtrack here: every quantifier is a fixed length and
 * none is nested, so the warning was a false positive and this is the shape that does not raise it.
 * Suppressing the rule inline would have hidden a real one later.
 */
const LOCAL_PATTERN = /^(\d\d\d\d)-(\d\d)-(\d\d)[T ](\d\d):(\d\d)(?::(\d\d))?$/;

/**
 * Reads the wall-clock time an instant shows in {@link DISPLAY_ZONE}, as though that reading were
 * itself UTC. The difference between this and the instant is the zone's offset at that moment.
 */
function wallClockOf(time: number): number {
  const found = new Map(new Intl.DateTimeFormat("en-GB", {
    timeZone: DISPLAY_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  }).formatToParts(new Date(time)).map((part) => [part.type, part.value]));
  const read = (type: Intl.DateTimeFormatPartTypes): number => Number(found.get(type) ?? "0");
  // `hour12: false` reports midnight as 24 — the same normalisation `partsIn` makes, and for the
  // same reason: 24:00 of one day is 00:00 of it, not of the next.
  const hour = read("hour") === 24 ? 0 : read("hour");
  return Date.UTC(read("year"), read("month") - 1, read("day"), hour, read("minute"), read("second"));
}

/**
 * Turns a typed local date-and-time into an ISO instant, reading it in {@link DISPLAY_ZONE}.
 *
 * <p>Returns null for anything that is not a well-formed local date-time, so a caller can answer
 * "that is not a time" itself rather than posting `Invalid Date` and letting the platform refuse a
 * body it cannot explain.
 *
 * <p>Two passes, not one. The offset has to be read *at* an instant, and the only instant available
 * to read it at is the guess — which for a time near a DST transition can sit on the wrong side of
 * it, giving an offset an hour out. Correcting the guess and re-reading settles it. India has no DST
 * and this is still worth the four lines: the zone is configurable, and a platform deployed
 * somewhere with a summer time would otherwise book every appointment in one half of the year an
 * hour off. A local time that a transition skips entirely lands on the instant the clock jumped to,
 * which is the conventional answer and the only one that exists.
 */
export function instantFromLocal(local: string): string | null {
  const match = LOCAL_PATTERN.exec(local.trim());
  if (!match) return null;
  const [, year, month, day, hour, minute, second] = match;
  const wanted = Date.UTC(
    Number(year),
    Number(month) - 1,
    Number(day),
    Number(hour),
    Number(minute),
    second ? Number(second) : 0,
  );
  if (!Number.isFinite(wanted)) return null;

  let instant = wanted;
  for (let pass = 0; pass < 2; pass += 1) {
    instant = wanted - (wallClockOf(instant) - instant);
  }
  return new Date(instant).toISOString();
}

/**
 * The value a `datetime-local` input needs in order to show an instant that already exists.
 *
 * <p>The mirror of {@link instantFromLocal}, and needed wherever a form edits a booking rather than
 * making one: an input whose `value` is a raw ISO instant renders blank in every browser.
 */
export function localFromInstant(iso: string | null | undefined): string {
  if (!iso) return "";
  const time = new Date(iso).getTime();
  if (!Number.isFinite(time)) return "";
  return new Date(wallClockOf(time)).toISOString().slice(0, 16);
}
