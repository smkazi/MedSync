import { load } from "@/lib/load";
import type { Disclosure, Page as PageResponse, PatientSummary } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDateTime } from "@/components/ui";

/**
 * What has been released about a patient, and under what.
 *
 * <p>The accounting of disclosures, and the screen a patient is entitled to be shown when they
 * ask who has seen their record. It is written at the moment of release rather than reconstructed
 * from logs, which is the difference between an answer and an archaeological exercise.
 *
 * <p>It shows size and resource counts and never content: a disclosure register carrying the
 * bundles would be a second copy of the medical record in the one table auditors are given broad
 * access to.
 */
export default async function DisclosuresPage({
  searchParams,
}: {
  searchParams: Promise<{ mrn?: string; from?: string; to?: string }>;
}) {
  const { mrn = "", from = "", to = "" } = await searchParams;

  // The ordinary patient search, not the narrow lookup: everybody who may read a consent already
  // has CLINICAL_READ, and `/patients/identify` exists for the one role that does not — the
  // billing desk. Using it here would have been a 403 for the front desk, which is exactly how
  // this screen first failed.
  const patients = mrn
    ? await load<PageResponse<PatientSummary>>(`/patients?q=${encodeURIComponent(mrn)}&size=10`)
    : { data: null, error: null };
  const patient = (patients.data?.content ?? [])[0];

  const period = new URLSearchParams();
  if (from) period.set("from", from);
  if (to) period.set("to", to);
  const disclosures = patient
    ? await load<Disclosure[]>(
        `/interop/disclosures?patientId=${patient.id}${period.size > 0 ? `&${period}` : ""}`,
      )
    : { data: null, error: null };

  const rows = disclosures.data ?? [];
  const shares = rows.filter((row) => row.kind === "CONSENTED_SHARE");
  const exports = rows.filter((row) => row.kind === "PATIENT_EXPORT");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">What has been shared</h1>
        <p className="text-sm text-ink-muted">
          Every release of a patient’s record, and the consent that authorised it.
        </p>
      </div>

      {patients.error && <ErrorNote>{patients.error}</ErrorNote>}
      {disclosures.error && <ErrorNote>{disclosures.error}</ErrorNote>}

      <Card
        title={patient ? patient.mrn : "Find a patient"}
        action={
          <form className="flex items-center gap-2">
            <label htmlFor="mrn" className="text-xs text-ink-muted">
              MRN
            </label>
            <input
              id="mrn"
              name="mrn"
              defaultValue={mrn}
              placeholder="MRN-2026-000001"
              className="w-40 rounded border border-line bg-surface-raised px-2 py-1 text-xs"
            />
            <label htmlFor="from" className="text-xs text-ink-muted">
              From
            </label>
            <input
              id="from"
              name="from"
              type="date"
              defaultValue={from}
              className="rounded border border-line bg-surface-raised px-2 py-1 text-xs"
            />
            <label htmlFor="to" className="text-xs text-ink-muted">
              To
            </label>
            <input
              id="to"
              name="to"
              type="date"
              defaultValue={to}
              className="rounded border border-line bg-surface-raised px-2 py-1 text-xs"
            />
            <button
              type="submit"
              className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
            >
              Show
            </button>
          </form>
        }
      >
        {!patient ? (
          <Empty>
            {mrn ? `No patient matches “${mrn}”.` : "Enter an MRN to see what has been released."}
          </Empty>
        ) : (
          <>
            <div className="grid gap-4 sm:grid-cols-3">
              <Stat label="Shared" value={shares.length} hint="to a third party, under consent" />
              <Stat label="Exported" value={exports.length} hint="handed to the patient" />
              <Stat label="Releases" value={rows.length} hint="in total" />
            </div>

            <div className="mt-4">
              {rows.length === 0 ? (
                <Empty>
                  {from || to
                    ? "Nothing about this patient was released in that period."
                    : "Nothing about this patient has ever been released."}
                </Empty>
              ) : (
                <Table
                  head={["When", "Kind", "What", "To", "Under", "Resources", "Size", "By"]}
                >
                  {rows.map((row) => (
                    <tr key={row.id}>
                      <td className="numeric px-3 py-2">{formatDateTime(row.releasedAt)}</td>
                      <td className="px-3 py-2">
                        <Badge tone={row.kind === "PATIENT_EXPORT" ? "accent" : "neutral"}>
                          {row.kind.toLowerCase().replaceAll("_", " ")}
                        </Badge>
                      </td>
                      <td className="px-3 py-2 text-xs">
                        {row.hiType.toLowerCase().replaceAll("_", " ")}
                      </td>
                      <td className="px-3 py-2">{row.recipient}</td>
                      <td className="numeric px-3 py-2 text-xs">
                        {row.artefactId ?? "— no consent: the patient’s own copy"}
                      </td>
                      <td className="numeric px-3 py-2">{row.resourceCount}</td>
                      <td className="numeric px-3 py-2 text-ink-muted">{row.byteCount} B</td>
                      <td className="px-3 py-2 text-ink-muted">{row.releasedBy}</td>
                    </tr>
                  ))}
                </Table>
              )}
            </div>
          </>
        )}
        <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
          {from || to
            ? `Bounded to ${from || "the beginning"} – ${to || "today"}, inclusive of both days. `
            : "Everything ever released, with no period set. "}
          A row with no consent behind it is an export handed to the patient themselves, which is
          not a disclosure to anybody else — the column says so rather than leaving a blank to be
          read as missing data. What was sent is counted and measured and never stored here.
        </p>
      </Card>
    </div>
  );
}
