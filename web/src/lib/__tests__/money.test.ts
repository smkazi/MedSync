import { describe, expect, it } from "vitest";

import { money } from "@/lib/money";

/**
 * Why this is tested at all: the bug it fixes was invisible.
 *
 * <p>The service keeps every amount at two decimal places from `numeric(14,2)` through
 * `BigDecimal` to the JSON on the wire — and then `JSON.parse` turned 500.00 into the number 500,
 * and an invoice rendered "500" in the total column beside "18.00" in the tax column. Nothing
 * failed, nothing logged, and the bill was simply wrong-looking in a way that invites an argument
 * at the counter.
 */
describe("money", () => {
  it("always shows two decimal places, whatever JSON parsing did to the number", () => {
    expect(money(500)).toBe("500.00");
    expect(money(0)).toBe("0.00");
    expect(money(168.5)).toBe("168.50");
    expect(money("400.00")).toBe("400.00");
  });

  it("groups in the Indian convention, because that is who reads these screens", () => {
    expect(money(4850)).toBe("4,850.00");
    expect(money(125000)).toBe("1,25,000.00");
  });

  it("renders an absent amount as a dash rather than as zero", () => {
    // A claim with no settlement is not a claim settled for nothing, and a screen that showed
    // 0.00 for both would say the payer had refused to pay when they simply have not answered.
    expect(money(null)).toBe("—");
    expect(money(undefined)).toBe("—");
    expect(money("")).toBe("—");
  });

  it("passes anything unparseable through rather than inventing a number", () => {
    expect(money("not a number")).toBe("not a number");
  });
});
