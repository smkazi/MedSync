import Link from "next/link";
import { api } from "@/lib/api";
import { currentUser, hasRole } from "@/lib/session";
import type { Page, PatientSummary } from "@/lib/types";
import { Badge, ButtonLink, Card, Empty, ErrorNote, Table } from "@/components/ui";

/** Patient search. The query lives in the URL, so a result list is shareable and bookmarkable. */
export default async function PatientsPage({
  searchParams,
}: {
  searchParams: Promise<{ q?: string; includeInactive?: string }>;
}) {
  const { q = "", includeInactive } = await searchParams;
  const user = await currentUser();
  const mayRegister = hasRole(user, "ADMIN", "RECEPTIONIST", "DOCTOR", "NURSE");
  const params = new URLSearchParams({ size: "50" });
  if (q) params.set("q", q);
  if (includeInactive === "on") params.set("includeInactive", "true");

  let results: Page<PatientSummary> | null = null;
  let error: string | null = null;
  try {
    results = await api<Page<PatientSummary>>(`/patients?${params}`);
  } catch (caught) {
    error = caught instanceof Error ? caught.message : "Search failed";
  }

  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Patients</h1>
          <p className="text-sm text-ink-muted">Search by name, MRN or phone number.</p>
        </div>
        {mayRegister && <ButtonLink href="/patients/new">Register a patient</ButtonLink>}
      </div>

      <form className="flex flex-wrap items-end gap-3">
        <div className="grow">
          <label htmlFor="q" className="block text-sm font-medium">
            Search
          </label>
          <input
            id="q"
            name="q"
            defaultValue={q}
            placeholder="Nair, MRN-2026-000001, 98200…"
            className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
        </div>
        <label className="flex items-center gap-2 pb-2 text-sm">
          <input
            type="checkbox"
            name="includeInactive"
            defaultChecked={includeInactive === "on"}
            className="size-4 rounded border-line"
          />
          Include archived
        </label>
        <button
          type="submit"
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
        >
          Search
        </button>
      </form>

      {error && <ErrorNote>{error}</ErrorNote>}

      <Card title={results ? `${results.totalElements} matching` : "Results"}>
        {!results || results.content.length === 0 ? (
          <Empty>{q ? "No patient matches that search." : "Enter a search to begin."}</Empty>
        ) : (
          <Table head={["MRN", "Name", "Age", "Sex", "Phone", "Flags", ""]}>
            {results.content.map((patient) => (
              <tr key={patient.id}>
                <td className="numeric px-3 py-2">{patient.mrn}</td>
                <td className="px-3 py-2 font-medium">{patient.fullName}</td>
                <td className="numeric px-3 py-2">{patient.age}</td>
                <td className="px-3 py-2">{patient.sex}</td>
                <td className="numeric px-3 py-2">{patient.phone ?? "—"}</td>
                <td className="px-3 py-2">
                  <div className="flex gap-1">
                    {patient.hasCriticalAllergy && <Badge tone="critical">allergy</Badge>}
                    {!patient.active && <Badge tone="neutral">archived</Badge>}
                  </div>
                </td>
                <td className="px-3 py-2 text-right">
                  <Link href={`/patients/${patient.id}`} className="text-sm text-accent hover:underline">
                    Open chart
                  </Link>
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
    </div>
  );
}
