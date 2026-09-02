"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { readForm, refused, withoutBlanks, type FormState } from "@/lib/form";
import { submit } from "@/lib/mutate";
import type { LabOrder, LabResult, MorphologyThreshold, ReferenceRange } from "@/lib/types";
import { ORDER_FIELDS, RESULT_ROW_FIELDS } from "./state";

/**
 * The laboratory's write path.
 *
 * <p>Unlike the facility screens, this is not a set of independent forms over one table — it is a
 * chain of custody, and the roles are the point. A clinician orders, a technician collects the tube
 * and enters what the analyzer or their own eyes produced, and only a pathologist verifies, at
 * which point the report is released and becomes the thing another clinician treats from. Three
 * acts, three roles, enforced in the service by `CLINICAL_WRITE`, `LAB_WRITE` and `LAB_VERIFY`. The
 * screens mirror that: each button is rendered only for the role that owns it, and the service
 * refuses it anyway for anybody who reaches the endpoint another way.
 *
 * <p>Ordering happens on the encounter chart rather than here. That is what CPOE means, and a
 * clinician ordering a test is already looking at the diagnosis that justifies it.
 */

/** Row actions land back on a page with the outcome in the query string, like the chart's. */
function back(path: string, problem: string | null, done: string | null): never {
  revalidatePath(path);
  const params = new URLSearchParams();
  if (problem) params.set("problem", problem);
  if (done) params.set("done", done);
  redirect(`${path}?${params}`);
}

// ---- ordering, from the chart -----------------------------------------------

/**
 * Raises an order for the tests ticked on an encounter.
 *
 * <p>Two refusals from this endpoint say different things and both are worth showing verbatim:
 * "Unknown test code 'X'" means the code is not in the catalogue at all, while "Test 'X' is no
 * longer orderable" means it is there and retired. The first is a bug in whatever offered the
 * checkbox; the second is a decision the laboratory made, and the ordering clinician needs to know
 * which.
 */
export async function orderTests(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, ORDER_FIELDS);
  // Repeated checkbox name, so getAll rather than get: `get` would take the first ticked box and
  // silently order one test out of six.
  const testCodes = form.getAll("testCodes").map(String).filter((code) => code !== "");

  if (testCodes.length === 0) {
    return {
      values,
      fieldErrors: { testCodes: "Pick at least one test." },
      error: null,
      done: null,
    };
  }

  const result = await submit<LabOrder>("/lab/orders", "POST", {
    ...withoutBlanks(values),
    testCodes,
  });
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath(`/encounters/${values.encounterId}`);
  revalidatePath("/laboratory");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: `Ordered ${testCodes.join(", ")}.`,
  };
}

// ---- the chain: collect, result, verify -------------------------------------

export async function collectSpecimen(form: FormData): Promise<void> {
  const id = String(form.get("orderId") ?? "");
  const specimenType = String(form.get("specimenType") ?? "").trim();

  const result = await submit<{ accessionNo: string }>(
    `/lab/orders/${id}/specimens`,
    "POST",
    specimenType ? { specimenType } : {},
  );
  revalidatePath("/laboratory");
  back(
    `/laboratory/${id}`,
    result.ok ? null : result.error,
    // The accession number is the only thing that matters next: it goes on the tube, and the
    // labels sheet is keyed by it.
    result.ok ? `Collected. Accession ${result.data.accessionNo}.` : null,
  );
}

/**
 * Records a batch of hand-entered results.
 *
 * <p>Rows arrive as three repeated names in document order, so a panel of any width posts without
 * the form knowing its own size. A row with no value is dropped rather than sent: an unmeasured
 * parameter is not a parameter measured as blank, and the service would refuse the empty batch
 * with a message about validation rather than about what the technician actually did.
 *
 * <p>Re-entering a parameter <em>amends</em> the existing result rather than adding a second row —
 * the service enforces one current value per parameter with a unique constraint — so the screen
 * says so before the button is pressed.
 */
export async function enterResults(_previous: FormState, form: FormData): Promise<FormState> {
  const id = String(form.get("orderId") ?? "");
  const [parameters, values, units] = RESULT_ROW_FIELDS.map((field) =>
    form.getAll(field).map((entry) => String(entry).trim()),
  ) as [string[], string[], string[]];

  const rows = parameters
    .map((parameter, index) => ({
      parameter,
      value: values[index] ?? "",
      unit: units[index] ?? "",
    }))
    .filter((row) => row.parameter !== "" && row.value !== "");

  if (rows.length === 0) {
    return {
      values: {},
      fieldErrors: {},
      error: "Enter a value for at least one parameter.",
      done: null,
    };
  }

  const result = await submit<LabResult[]>(`/lab/orders/${id}/results`, "POST", {
    results: rows.map((row) => ({
      parameter: row.parameter,
      value: row.value,
      ...(row.unit ? { unit: row.unit } : {}),
    })),
  });
  if (!result.ok) {
    // Bean Validation names the failing element by its index — `results[0].parameter` — and the
    // form has no field by that name, so a refusal would render nowhere at all: `refused` drops
    // the banner as soon as there are field errors, on the assumption each input shows its own.
    // Re-keying by parameter is what puts the message next to the row it is about.
    return refused(
      {},
      {
        ...result,
        fieldErrors: Object.fromEntries(
          Object.entries(result.fieldErrors).map(([field, message]) => {
            const index = Number(/^results\[(\d+)\]/.exec(field)?.[1] ?? Number.NaN);
            const row = rows[index];
            return [row ? row.parameter : field, message];
          }),
        ),
      },
    );
  }
  revalidatePath(`/laboratory/${id}`);
  revalidatePath("/laboratory");
  const abnormal = result.data.filter((entry) => entry.abnormal).length;
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done:
      abnormal === 0
        ? `${result.data.length} result(s) recorded.`
        : `${result.data.length} result(s) recorded, ${abnormal} outside the reference interval.`,
  };
}

/**
 * Verifies every result on an order, which is the same act as releasing it.
 *
 * <p>There is no second step and no separate release button, so the screen has to say that: the
 * platform's own answer is "n result(s) verified and released" and it is shown verbatim rather
 * than reworded into something that sounds provisional.
 */
export async function verifyOrder(form: FormData): Promise<void> {
  const id = String(form.get("orderId") ?? "");
  const result = await submit<{ message: string }>(`/lab/orders/${id}/verify`, "POST");
  revalidatePath("/laboratory");
  back(`/laboratory/${id}`, result.ok ? null : result.error, result.ok ? result.data.message : null);
}

/**
 * Cancels an order.
 *
 * <p>Offered only while nothing has been recorded, because the service refuses afterwards with
 * "Results have already been recorded; this order cannot be cancelled" — a number that exists
 * cannot be made not to have existed.
 */
export async function cancelOrder(form: FormData): Promise<void> {
  const id = String(form.get("orderId") ?? "");
  const result = await submit<{ message: string }>(`/lab/orders/${id}`, "DELETE");
  revalidatePath("/laboratory");
  back(`/laboratory/${id}`, result.ok ? null : result.error, result.ok ? "Order cancelled." : null);
}

// ---- configuration: the three tiers of threshold ----------------------------

/**
 * One configuration write, refreshing the page that lists it.
 *
 * <p>Numbers, not strings: `normalLow` is a `BigDecimal` on the service and `"11.5"` is not 11.5 to
 * a bean that validates `@Digits`. The same coercion the facility actions do, for the same reason.
 */
async function retune<T>(
  path: string,
  values: Record<string, string>,
  numbers: readonly string[],
  refresh: string[],
  done: string,
): Promise<FormState> {
  const body: Record<string, unknown> = {};
  for (const [field, value] of Object.entries(withoutBlanks(values))) {
    const text = String(value);
    if (numbers.includes(field)) {
      body[field] = Number(text);
    } else if (text === "true" || text === "false") {
      body[field] = text === "true";
    } else {
      body[field] = text;
    }
  }
  if (Object.keys(body).length === 0) {
    return { values, fieldErrors: {}, error: "Nothing to change.", done: null };
  }

  const result = await submit<T>(path, "PATCH", body);
  if (!result.ok) {
    return refused(values, result);
  }
  for (const page of refresh) {
    revalidatePath(page);
  }
  return { values: {}, fieldErrors: {}, error: null, done };
}

/**
 * Retunes a reference interval — the first tier, which decides whether a value is flagged H or L.
 *
 * <p>Sparse, so patching the low bound alone leaves the high one alone. The service checks the
 * <em>resulting</em> pair rather than the submitted one and refuses an inverted interval naming
 * both numbers, because an inverted interval marks every subsequent value for that parameter as
 * high, on every report, until somebody notices.
 */
export async function updateReferenceRange(
  _previous: FormState,
  form: FormData,
): Promise<FormState> {
  const id = String(form.get("id") ?? "");
  const values = readForm(form, ["normalLow", "normalHigh"] as const);
  return retune<ReferenceRange>(
    `/lab/reference-ranges/${id}`,
    values,
    ["normalLow", "normalHigh"],
    ["/laboratory/reference-ranges"],
    "Interval updated.",
  );
}

/** Retunes the second tier: the wording a rule prints, and whether it fires at all. */
export async function updateInterpretiveRule(
  _previous: FormState,
  form: FormData,
): Promise<FormState> {
  const code = String(form.get("code") ?? "");
  const values = readForm(form, ["message", "active"] as const);
  return retune(
    `/lab/interpretive-rules/${code}`,
    values,
    [],
    ["/laboratory/interpretation"],
    `${code} updated.`,
  );
}

/**
 * Retunes the third tier: the cut-off that decides what the cells get called.
 *
 * <p>The number only. The note is what appears verbatim on a signed report, and rewording that is
 * a different act from moving a threshold — so the service has no setter for it and this form has
 * no field for it.
 */
export async function updateMorphologyThreshold(
  _previous: FormState,
  form: FormData,
): Promise<FormState> {
  const code = String(form.get("code") ?? "");
  const values = readForm(form, ["threshold"] as const);
  return retune<MorphologyThreshold>(
    `/lab/morphology-thresholds/${code}`,
    values,
    ["threshold"],
    ["/laboratory/interpretation"],
    `${code} updated.`,
  );
}
