import { describe, expect, it } from "vitest";

import { formatDate, formatDateTime, formatTime } from "@/components/ui";

/**
 * The date formatters, and the one thing they have to get right.
 *
 * Every server-side day window on this platform is a business day in the deployment's zone: the
 * day book, the audit report, the disclosure register. These helpers used to render UTC, so between
 * 18:30 and midnight UTC every screen showed yesterday's date beside a filter counting today —
 * and a date that cannot be typed into its own date box is worse than no date, because it reads as
 * "nothing happened".
 *
 * The default zone is Asia/Kolkata, +05:30 with no daylight saving, which is what makes these
 * assertions exact rather than approximate.
 */
describe("formatDateTime", () => {
  it("renders an instant in the deployment's zone, not UTC", () => {
    // 18:45 UTC is 00:15 the next day in Kolkata. This is the case that was wrong.
    expect(formatDateTime("2026-09-03T18:45:00Z")).toBe("2026-09-04 00:15");
    expect(formatDate("2026-09-03T18:45:00Z")).toBe("2026-09-04");
    expect(formatTime("2026-09-03T18:45:00Z")).toBe("00:15");
  });

  it("renders midnight as 00:00 rather than 24:00", () => {
    // 18:30 UTC is exactly midnight in Kolkata, and hour12: false reports that hour as 24.
    expect(formatDateTime("2026-09-03T18:30:00Z")).toBe("2026-09-04 00:00");
  });

  it("keeps the same instant on the same day when the zones agree", () => {
    expect(formatDateTime("2026-09-03T06:00:00Z")).toBe("2026-09-03 11:30");
  });

  it("is stable across the year, because the default zone has no daylight saving", () => {
    expect(formatDateTime("2026-01-15T12:00:00Z")).toBe("2026-01-15 17:30");
    expect(formatDateTime("2026-07-15T12:00:00Z")).toBe("2026-07-15 17:30");
  });

  it("renders a zero-padded ISO date, whatever the runtime's locale", () => {
    // en-GB would give "04/09/2026" if the parts were pasted together in its own order. Built from
    // formatToParts for exactly this reason.
    expect(formatDate("2026-09-04T05:00:00Z")).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it("says so when there is no instant, rather than rendering an epoch", () => {
    expect(formatDateTime(null)).toBe("—");
    expect(formatDateTime(undefined)).toBe("—");
    expect(formatDate(null)).toBe("—");
  });
});
