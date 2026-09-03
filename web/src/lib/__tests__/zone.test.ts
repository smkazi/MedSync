import { describe, expect, it } from "vitest";
import { instantFromLocal, localFromInstant } from "../zone";

/**
 * The conversion between a typed wall-clock time and an instant.
 *
 * <p>These run with `NEXT_PUBLIC_HMS_ZONE` unset, so the zone is the default `Asia/Kolkata` —
 * UTC+05:30, and deliberately a half-hour offset. A whole-hour zone would let an off-by-one in the
 * minutes go unnoticed, which is exactly the class of bug this module exists to prevent.
 */
describe("instantFromLocal", () => {
  it("reads a typed time in the display zone, not in UTC", () => {
    // 10:30 in Kolkata is 05:00 UTC. Read as UTC it would have been 10:30Z, five and a half hours
    // out — the whole defect this module answers.
    expect(instantFromLocal("2026-09-04T10:30")).toBe("2026-09-04T05:00:00.000Z");
  });

  it("carries the date back a day when the local time is before the offset", () => {
    expect(instantFromLocal("2026-09-04T02:00")).toBe("2026-09-03T20:30:00.000Z");
  });

  it("accepts seconds when a form sends them", () => {
    expect(instantFromLocal("2026-09-04T10:30:45")).toBe("2026-09-04T05:00:45.000Z");
  });

  it("round-trips through the formatter a screen renders with", () => {
    // The property that matters clinically: type 10:30, read back 10:30. A radiographer who books
    // a slot and sees a different time on the row assumes the platform moved it.
    const typed = "2026-09-04T10:30";
    const instant = instantFromLocal(typed);
    expect(instant).not.toBeNull();
    expect(localFromInstant(instant)).toBe(typed);
  });

  it("refuses anything that is not a local date-time rather than inventing one", () => {
    // `new Date("tomorrow")` is `Invalid Date`, which serialises as null and gets refused by the
    // platform as a missing field — a message about the wrong thing entirely.
    for (const bad of ["", "   ", "tomorrow", "2026-09-04", "10:30", "2026-13-04T10:30x", "NaN"]) {
      expect(instantFromLocal(bad), bad).toBeNull();
    }
  });

  it("takes a space in place of the T, which some browsers submit", () => {
    expect(instantFromLocal("2026-09-04 10:30")).toBe("2026-09-04T05:00:00.000Z");
  });
});

describe("localFromInstant", () => {
  it("gives a datetime-local input a value it can actually show", () => {
    // A raw ISO instant in `value` renders blank: the input wants exactly "YYYY-MM-DDTHH:mm".
    expect(localFromInstant("2026-09-04T05:00:00Z")).toBe("2026-09-04T10:30");
  });

  it("is blank for an absent or unparseable instant", () => {
    expect(localFromInstant(null)).toBe("");
    expect(localFromInstant(undefined)).toBe("");
    expect(localFromInstant("not an instant")).toBe("");
  });

  it("renders midnight as 00:00 rather than as 24:00 of the day before", () => {
    // 18:30Z is midnight in Kolkata, and `hour12: false` reports that hour as 24.
    expect(localFromInstant("2026-09-03T18:30:00Z")).toBe("2026-09-04T00:00");
  });
});
