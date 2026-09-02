import { describe, expect, it } from "vitest";
import { readForm, refused, withoutBlanks, type Refusal } from "@/lib/form";

/**
 * The form helpers, and one rule that cost a debugging session.
 *
 * <p>A checkbox posts nothing when unticked, so every form here pairs one with a hidden twin
 * posting "false" — the field then always reaches a sparse PATCH, and a blank does not read as
 * "leave it alone". Which of the two values wins is decided entirely by `FormData.get()`, and it
 * returns the *first*, not the last. With the twin written above the checkbox, every box read as
 * false however it was set: a room type ticked clinical and schedulable was created as neither.
 */

function form(entries: [string, string][]): FormData {
  const data = new FormData();
  for (const [name, value] of entries) {
    data.append(name, value);
  }
  return data;
}

describe("readForm", () => {
  it("reads the named fields, trimmed, and treats absent as blank", () => {
    const values = readForm(form([["name", "  Ground Floor  "]]), ["name", "level"] as const);

    expect(values.name).toBe("Ground Floor");
    expect(values.level).toBe("");
  });

  it("takes the FIRST value when a name is repeated, which is what orders a checkbox pair", () => {
    // This is the assertion the checkbox layout depends on. If it ever reads the last value
    // instead, RecordForm's ticked-checkbox-then-hidden-false ordering inverts every flag on
    // every administrative form at once, silently.
    expect(readForm(form([["clinical", "true"], ["clinical", "false"]]), ["clinical"]).clinical)
      .toBe("true");
    expect(readForm(form([["clinical", "false"]]), ["clinical"]).clinical).toBe("false");
  });
});

describe("withoutBlanks", () => {
  it("drops empty strings so an untouched optional field is absent, not empty", () => {
    // An empty string is not "no value" to a service validating with @Pattern or @Email - it is a
    // value that fails.
    expect(withoutBlanks({ phone: "", city: "Kochi" })).toEqual({ city: "Kochi" });
  });

  it("keeps a literal 'false', which is a value rather than a blank", () => {
    expect(withoutBlanks({ active: "false" })).toEqual({ active: "false" });
  });
});

describe("refused", () => {
  const failure = (fieldErrors: Record<string, string>): Refusal => ({
    ok: false,
    status: 400,
    error: "One or more fields are invalid",
    fieldErrors,
    body: undefined,
  });

  it("suppresses the banner when the service named specific fields", () => {
    // Each input says its own piece; a banner repeating "something is invalid" above them adds
    // nothing but noise.
    const state = refused({ dateOfBirth: "2099-01-01" }, failure({ dateOfBirth: "must be past" }));

    expect(state.error).toBeNull();
    expect(state.fieldErrors.dateOfBirth).toBe("must be past");
    expect(state.values.dateOfBirth).toBe("2099-01-01");
  });

  it("shows the service's own message when no field was named", () => {
    expect(refused({}, failure({})).error).toBe("One or more fields are invalid");
  });
});
