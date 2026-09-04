"use server";

import { revalidatePath } from "next/cache";
import { readForm, refused, withoutBlanks } from "@/lib/form";
import { submit } from "@/lib/mutate";
import type {
  AdverseEvent,
  ImmunisationDose,
  ImmunisationExemption,
  VaccineLot,
} from "@/lib/types";
import {
  AEFI_FIELDS,
  EXEMPTION_FIELDS,
  HISTORICAL_DOSE_FIELDS,
  RECEIVE_LOT_FIELDS,
  RECORD_DOSE_FIELDS,
  WITHDRAW_LOT_FIELDS,
  type ImmunisationFormState,
} from "./state";

/**
 * The register's write layer.
 *
 * <p>Every action here posts and re-reads; none of them converts anything. That is deliberate and
 * it is the whole reason this module has no zone import, where radiology needed one: an
 * immunisation schedule is arithmetic on `LocalDate`s from a date of birth, and a dose given on the
 * 14th was given on the 14th in every timezone the platform might be read in. The moment a form
 * here submits a wall-clock *time*, that stops being true — so it does not.
 */

/** Every immunisation screen a write can change, plus the patient's own register. */
function revalidateRegister(patientId?: string): void {
  revalidatePath("/immunisations");
  if (patientId) {
    revalidatePath(`/immunisations/patients/${patientId}`);
  }
}

export async function recordDose(
  _previous: ImmunisationFormState,
  form: FormData,
): Promise<ImmunisationFormState> {
  const values = readForm(form, RECORD_DOSE_FIELDS);
  const result = await submit<ImmunisationDose>(
    "/immunisations",
    "POST",
    withoutBlanks(values),
  );
  if (!result.ok) {
    return refused(values, result);
  }
  revalidateRegister(values.patientId);
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done:
      `Recorded. ${result.data.productName}, lot ${result.data.lotNo} — which covers ` +
      // The antigens rather than the product, because that is the fact the register holds and the
      // thing every later coverage question is asked about. A nurse who gave one injection should
      // see the five diseases it counted for.
      `${result.data.antigenCodes.join(", ")}.`,
  };
}

/**
 * A dose given somewhere else.
 *
 * <p>No lot number in the body at all, and the service refuses `ADMINISTERED_HERE` here by name
 * with a message pointing at the other endpoint. The failure mode this prevents is not that
 * historical doses go unrecorded; it is that somebody types them in as if given here with an
 * invented lot, which puts fabricated evidence in the one column a recall reads.
 */
export async function recordHistoricalDose(
  _previous: ImmunisationFormState,
  form: FormData,
): Promise<ImmunisationFormState> {
  const values = readForm(form, HISTORICAL_DOSE_FIELDS);
  const result = await submit<ImmunisationDose>("/immunisations/historical", "POST", {
    ...withoutBlanks(values),
    // A checkbox, so it is present either way — see RecordForm for why the hidden twin exists.
    dateEstimated: values.dateEstimated === "true",
  });
  if (!result.ok) {
    return refused(values, result);
  }
  revalidateRegister(values.patientId);
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done:
      `Recorded from ${result.data.source === "HISTORICAL_DOCUMENTED" ? "a document" : "a report"}` +
      `${result.data.givenOnEstimated ? ", with the date marked as recollected" : ""}. ` +
      "No lot number, because this dose was not given here.",
  };
}

/**
 * Why a child will not be vaccinated.
 *
 * <p>The reason has a twenty-character floor on the service, the same one break-glass sets and for
 * the same reason: this takes a child out of a coverage measure's denominator, and "medical" is
 * what a free-text box collects when it does not insist on a sentence.
 */
export async function recordExemption(
  _previous: ImmunisationFormState,
  form: FormData,
): Promise<ImmunisationFormState> {
  const values = readForm(form, EXEMPTION_FIELDS);
  const result = await submit<ImmunisationExemption>(
    "/immunisations/exemptions",
    "POST",
    withoutBlanks(values),
  );
  if (!result.ok) {
    return refused(values, result);
  }
  revalidateRegister(values.patientId);
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done:
      result.data.kind === "MEDICAL"
        ? "Recorded. A medical contraindication takes this child out of the coverage denominator."
        : "Recorded. A refusal is on the register and deliberately does not change the coverage " +
          "denominator — a clinic that could exclude refusals could report full coverage by " +
          "recording refusals.",
  };
}

export async function reportAdverseEvent(
  _previous: ImmunisationFormState,
  form: FormData,
): Promise<ImmunisationFormState> {
  const doseId = String(form.get("doseId") ?? "");
  const patientId = String(form.get("patientId") ?? "");
  const values = readForm(form, AEFI_FIELDS);
  const result = await submit<AdverseEvent>(
    `/immunisations/${doseId}/adverse-events`,
    "POST",
    withoutBlanks(values),
  );
  if (!result.ok) {
    return refused(values, result);
  }
  revalidateRegister(patientId);
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: result.data.reportable
      ? "Recorded, and flagged as reportable. Nothing transmits it — see the README's gaps."
      : "Recorded.",
  };
}

export async function receiveLot(
  _previous: ImmunisationFormState,
  form: FormData,
): Promise<ImmunisationFormState> {
  const values = readForm(form, RECEIVE_LOT_FIELDS);
  const result = await submit<VaccineLot>("/vaccines/lots", "POST", {
    ...withoutBlanks(values),
    quantity: Number(values.quantity),
    ...(values.vvmStage ? { vvmStage: Number(values.vvmStage) } : {}),
  });
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/immunisations/lots");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: `Received. ${result.data.quantityOnHand} dose(s) of ${result.data.productName}, expiring ${result.data.expiresOn}.`,
  };
}

export async function withdrawLot(
  _previous: ImmunisationFormState,
  form: FormData,
): Promise<ImmunisationFormState> {
  const lotId = String(form.get("lotId") ?? "");
  const values = readForm(form, WITHDRAW_LOT_FIELDS);
  const result = await submit<VaccineLot>(`/vaccines/lots/${lotId}/withdraw`, "POST", values);
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/immunisations/lots");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done:
      "Withdrawn. It cannot be given now — and the doses already recorded against it keep their " +
      "lot number, which is what makes a recall answerable.",
  };
}
