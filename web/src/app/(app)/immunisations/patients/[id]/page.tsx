import Link from "next/link";
import { load } from "@/lib/load";
import { hasRole, currentUser } from "@/lib/session";
import type { ImmunisationRegister, VaccineProduct, Antigen } from "@/lib/types";
import { RecordForm, EditRow } from "@/components/RecordForm";
import {
  Badge,
  Card,
  Empty,
  ErrorNote,
  Stat,
  Table,
  formatDate,
  formatDateTime,
} from "@/components/ui";
import { recordDose, recordExemption, recordHistoricalDose, reportAdverseEvent } from "../../actions";
import {
  EXEMPTION_KINDS,
  HISTORICAL_SOURCES,
  OUTCOMES,
  SERIOUSNESS,
  SITES,
} from "../../state";

/**
 * One patient's immunisation register, and the forms that write to it.
 *
 * <p><strong>Recording happens here rather than on a blank form</strong>, and that is the same
 * argument S1e made for ordering a laboratory test from the chart: the person recording a dose is
 * looking at what this child has already had, and a form that could be filled in without that
 * context is a form that gets a dose recorded against the wrong patient. The MRN and the patient id
 * are hidden fields taken from the register that was just read, not typed.
 *
 * <p><strong>Two forms for two acts, not one form with a switch.</strong> A dose given here demands
 * a lot number; a dose from a card demands a sentence saying what was seen and refuses a lot. That
 * is two endpoints on the service for a reason its own comment gives — a flag on one endpoint is a
 * flag somebody forgets, which is how a remembered dose ends up in the register with an invented
 * lot number — and putting them side by side under two headings is how the screen says the same
 * thing.
 *
 * <p>The register shows what each dose covered rather than only what was injected: a child given
 * one pentavalent shot is protected against five things, and every later coverage question is asked
 * about the antigens. The lot number is displayed for the doses that have one, because it is the
 * column a recall reads and the whole reason the register is worth keeping.
 */
export default async function PatientRegisterPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const user = await currentUser();
  const mayRecord = hasRole(user, "ADMIN", "DOCTOR", "NURSE");

  const { data: register, error } = await load<ImmunisationRegister>(
    `/immunisations/patients/${id}`,
  );
  // The catalogue is readable by anybody signed in — it is a list of vaccine names with no patient
  // in it — so these two are fetched regardless of who is looking, and in parallel, because they
  // are independent of each other and of the register above.
  const [products, antigens] = await Promise.all([
    load<VaccineProduct[]>("/vaccines/products"),
    load<Antigen[]>("/vaccines/antigens"),
  ]);

  const productOptions = (products.data ?? [])
    .filter((product) => product.active)
    .map((product) => ({
      value: product.code,
      label: `${product.code} — ${product.name} (${product.antigenCodes.join(", ")})`,
    }));
  const antigenOptions = (antigens.data ?? [])
    .filter((antigen) => antigen.active)
    .map((antigen) => ({ value: antigen.code, label: `${antigen.code} — ${antigen.name}` }));

  const doses = register?.doses ?? [];
  const exemptions = register?.exemptions ?? [];
  const covered = new Set(doses.flatMap((dose) => dose.antigenCodes));

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Immunisation register</h1>
        <p className="text-sm text-ink-muted">
          {register ? (
            <>
              <span className="numeric">{register.patientMrn}</span> ·{" "}
              <Link href={`/patients/${id}`} className="text-accent hover:underline">
                the chart
              </Link>
            </>
          ) : (
            "A lifetime record of every dose, wherever it was given."
          )}
        </p>
      </div>

      {/*
        The service's own refusal, kept verbatim. A doctor reading a register for somebody they are
        not looking after is refused by the care-relationship narrowing with a sentence telling
        them how to open it — paraphrasing that here would leave the operator without the
        instruction.
      */}
      {error && <ErrorNote>{error}</ErrorNote>}

      {register && (
        <>
          <div className="grid gap-4 sm:grid-cols-3">
            <Stat label="Doses recorded" value={doses.length} />
            <Stat
              label="Antigens covered"
              value={covered.size}
              hint="what the doses protect against, not how many injections"
            />
            <Stat
              label="Exemptions"
              value={exemptions.filter((exemption) => exemption.live).length}
              hint="live only — an expired one stops applying"
            />
          </div>

          <Card title="Doses">
            {doses.length === 0 ? (
              <Empty>
                Nothing recorded. That is not the same as unvaccinated: a child who arrived with a
                card has doses this register does not know about until somebody enters them, which
                is what the second form below is for.
              </Empty>
            ) : (
              <Table
                head={["Given", "Vaccine", "Covers", "Lot", "Evidence", "Recorded", ""]}
              >
                {doses.map((dose) => (
                  <tr key={dose.id} className="border-t border-line align-top">
                    <td className="px-3 py-2 text-xs">
                      {formatDate(dose.givenOn)}
                      {dose.givenOnEstimated && (
                        <span className="block text-ink-muted">recollected date</span>
                      )}
                    </td>
                    <td className="px-3 py-2">
                      {dose.productName}
                      <span className="numeric block text-xs text-ink-muted">
                        {dose.productCode}
                        {dose.site ? ` · ${dose.site.toLowerCase().replace(/_/g, " ")}` : ""}
                      </span>
                    </td>
                    <td className="px-3 py-2 text-xs">{dose.antigenCodes.join(", ")}</td>
                    <td className="numeric px-3 py-2 text-xs">
                      {/*
                        Present exactly when the dose was given here, and the database enforces
                        that as a biconditional in both directions: a here-given dose without a
                        lot is refused, and a card dose carrying one is refused too.
                      */}
                      {dose.lotNo ?? <span className="text-ink-muted">—</span>}
                    </td>
                    <td className="px-3 py-2 text-xs">
                      {dose.source === "ADMINISTERED_HERE" ? (
                        <Badge tone="good">given here</Badge>
                      ) : dose.source === "HISTORICAL_DOCUMENTED" ? (
                        <Badge tone="accent">documented</Badge>
                      ) : (
                        <Badge tone="warn">reported</Badge>
                      )}
                      {dose.evidence && (
                        <span className="mt-1 block text-ink-muted">{dose.evidence}</span>
                      )}
                    </td>
                    <td className="px-3 py-2 text-xs text-ink-muted">
                      {formatDateTime(dose.recordedAt)}
                      <span className="block">{dose.recordedBy}</span>
                    </td>
                    <td className="px-3 py-2">
                      {dose.adverseEvents.length > 0 && (
                        <div className="mb-2 space-y-1">
                          {dose.adverseEvents.map((event) => (
                            <p key={event.id} className="text-xs">
                              <Badge
                                tone={event.seriousness === "SERIOUS" ? "critical" : "warn"}
                              >
                                {event.seriousness === "SERIOUS" ? "serious" : "non-serious"}
                              </Badge>{" "}
                              {formatDate(event.onsetOn)} — {event.description}
                            </p>
                          ))}
                        </div>
                      )}
                      {mayRecord && (
                        <EditRow label="Report an adverse event">
                          <RecordForm
                            action={reportAdverseEvent}
                            hidden={{ doseId: dose.id, patientId: register.patientId }}
                            fields={[
                              {
                                name: "onsetOn",
                                label: "Onset",
                                type: "date",
                                required: true,
                                hint: "cannot precede the dose",
                              },
                              {
                                name: "seriousness",
                                label: "Seriousness",
                                type: "select",
                                options: SERIOUSNESS,
                                required: true,
                              },
                              {
                                name: "outcome",
                                label: "Outcome",
                                type: "select",
                                options: OUTCOMES,
                                required: true,
                              },
                              {
                                name: "description",
                                label: "What happened",
                                type: "textarea",
                                required: true,
                                hint: "at least eight characters — a clinical description",
                              },
                            ]}
                            submitLabel="Report"
                          />
                        </EditRow>
                      )}
                    </td>
                  </tr>
                ))}
              </Table>
            )}
          </Card>

          <Card title="Exemptions">
            {exemptions.length === 0 ? (
              <Empty>None recorded.</Empty>
            ) : (
              <Table head={["Antigen", "Kind", "Reason", "Expires", "Recorded"]}>
                {exemptions.map((exemption) => (
                  <tr key={exemption.id} className="border-t border-line align-top">
                    <td className="numeric px-3 py-2">
                      {/* Null means every antigen, which is a different fact from a blank cell. */}
                      {exemption.antigenCode ?? (
                        <span className="text-ink-muted">every antigen</span>
                      )}
                    </td>
                    <td className="px-3 py-2">
                      <Badge tone={exemption.kind === "MEDICAL" ? "accent" : "warn"}>
                        {exemption.kind === "MEDICAL" ? "medical" : "refused"}
                      </Badge>
                      {!exemption.live && (
                        <span className="block text-xs text-ink-muted">no longer applies</span>
                      )}
                    </td>
                    <td className="px-3 py-2 text-xs">{exemption.reason}</td>
                    <td className="px-3 py-2 text-xs">
                      {exemption.expiresOn ? formatDate(exemption.expiresOn) : "—"}
                    </td>
                    <td className="px-3 py-2 text-xs text-ink-muted">{exemption.recordedBy}</td>
                  </tr>
                ))}
              </Table>
            )}
            <p className="mt-3 text-xs text-ink-muted">
              A <strong>medical</strong> exemption takes this child out of a coverage
              measure&apos;s denominator; a <strong>refusal</strong> deliberately does not. A clinic
              able to exclude refusals could report full coverage by recording refusals.
            </p>
          </Card>

          {mayRecord && (
            <>
              <Card title="Record a dose given here">
                <p className="mb-3 text-sm text-ink-muted">
                  The lot number is typed from the vial in your hand rather than picked from a list:
                  the label is the evidence, and a dropdown of lots the platform believes it has
                  would let a vial nobody received be recorded as given.
                </p>
                <RecordForm
                  action={recordDose}
                  hidden={{ patientId: register.patientId, patientMrn: register.patientMrn }}
                  fields={[
                    {
                      name: "productCode",
                      label: "Vaccine",
                      type: "select",
                      options: productOptions,
                      required: true,
                    },
                    { name: "lotNo", label: "Lot number", required: true },
                    { name: "givenOn", label: "Given on", type: "date", required: true },
                    {
                      name: "site",
                      label: "Site",
                      type: "select",
                      options: SITES,
                      required: true,
                    },
                    {
                      name: "encounterId",
                      label: "Encounter",
                      hint: "optional — links the dose to today's visit",
                    },
                  ]}
                  submitLabel="Record the dose"
                />
              </Card>

              <Card title="Record a dose given somewhere else">
                <p className="mb-3 text-sm text-ink-muted">
                  From a card, a letter, or a parent&apos;s memory. There is no lot number on this
                  form and the platform refuses one: the failure this prevents is not that
                  historical doses go unrecorded, it is that somebody types them in as if given here
                  with an invented lot, which puts fabricated evidence in the one column a recall
                  reads.
                </p>
                <RecordForm
                  action={recordHistoricalDose}
                  hidden={{ patientId: register.patientId, patientMrn: register.patientMrn }}
                  fields={[
                    {
                      name: "productCode",
                      label: "Vaccine",
                      type: "select",
                      options: productOptions,
                      required: true,
                    },
                    { name: "givenOn", label: "Given on", type: "date", required: true },
                    {
                      name: "source",
                      label: "Evidence held",
                      type: "select",
                      options: HISTORICAL_SOURCES,
                      required: true,
                      hint: "two grades, and a measure may count one and not the other",
                    },
                    {
                      name: "dateEstimated",
                      label: "The date is a recollection",
                      type: "checkbox",
                      hint: "carried through to every rate computed from it",
                    },
                    {
                      name: "evidence",
                      label: "What you saw",
                      type: "textarea",
                      required: true,
                      hint: "at least eight characters — 'card seen, entry dated 12/03/2024'",
                    },
                  ]}
                  submitLabel="Record from the card"
                />
              </Card>

              <Card title="Record an exemption">
                <RecordForm
                  action={recordExemption}
                  hidden={{ patientId: register.patientId, patientMrn: register.patientMrn }}
                  fields={[
                    {
                      name: "antigenCode",
                      label: "Antigen",
                      type: "select",
                      options: [
                        { value: "", label: "Every antigen" },
                        ...antigenOptions,
                      ],
                      hint: "leave as every antigen for a blanket exemption",
                    },
                    {
                      name: "kind",
                      label: "Kind",
                      type: "select",
                      options: EXEMPTION_KINDS,
                      required: true,
                    },
                    {
                      name: "expiresOn",
                      label: "Expires",
                      type: "date",
                      hint: "optional — an expired exemption stops applying on its own",
                    },
                    {
                      name: "reason",
                      label: "Reason",
                      type: "textarea",
                      required: true,
                      hint: "at least twenty characters, because 'medical' is not a reason",
                    },
                  ]}
                  submitLabel="Record the exemption"
                />
              </Card>
            </>
          )}
        </>
      )}
    </div>
  );
}
