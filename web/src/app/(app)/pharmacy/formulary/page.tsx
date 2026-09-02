import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type { FormularyEntry } from "@/lib/types";
import { RecordForm } from "@/components/RecordForm";
import { Badge, Card, Empty, ErrorNote, Table } from "@/components/ui";
import { DOSE_FORMS } from "../state";
import { addToFormulary, retireFromFormulary } from "../actions";

/**
 * What this hospital stocks, and what each product actually contains.
 *
 * <p>The ingredients column is the reason this screen matters more than a price list would. Every
 * safety check in the platform runs on those codes rather than on the name: a patient allergic to
 * penicillin is allergic to it under every brand it has been sold under, and an entry with no
 * ingredients passes every check by having nothing to match.
 *
 * <p>Neither the code nor the ingredient list can be edited afterwards, and that is deliberate:
 * both are referenced by prescriptions already written, and editing an ingredient list in place
 * would silently change what past orders were checked against. Correcting one means retiring the
 * entry and adding it again, which is visible.
 */
export default async function FormularyPage({
  searchParams,
}: {
  searchParams: Promise<{ q?: string; problem?: string; done?: string; includeInactive?: string }>;
}) {
  const { q = "", problem, done, includeInactive } = await searchParams;
  const mayEdit = hasRole(await currentUser(), "ADMIN", "PHARMACIST");

  const query = new URLSearchParams();
  if (q) query.set("q", q);
  if (includeInactive) query.set("includeInactive", "true");
  const { data, error } = await load<FormularyEntry[]>(
    `/pharmacy/formulary${query.toString() ? `?${query}` : ""}`,
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Formulary</h1>
        <p className="text-sm text-ink-muted">
          What may be prescribed here, and what each product contains.
        </p>
      </div>

      {problem && <ErrorNote>{problem}</ErrorNote>}
      {done && (
        <p
          role="status"
          className="rounded-md border border-good/40 bg-good-soft px-3 py-2 text-sm text-good"
        >
          {done}
        </p>
      )}
      {error && <ErrorNote>{error}</ErrorNote>}

      <Card title="The catalogue">
        <form className="mb-4 flex flex-wrap items-end gap-3">
          <div className="grow">
            <label htmlFor="q" className="block text-sm font-medium">
              Search
            </label>
            <input
              id="q"
              name="q"
              defaultValue={q}
              placeholder="Name"
              className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
            />
          </div>
          <label className="flex items-center gap-2 pb-2 text-sm">
            <input
              type="checkbox"
              name="includeInactive"
              defaultChecked={Boolean(includeInactive)}
              className="size-4"
            />
            Include retired
          </label>
          <button
            type="submit"
            className="rounded-md border border-line px-4 py-2 text-sm font-medium hover:bg-surface"
          >
            Search
          </button>
        </form>

        {(data ?? []).length === 0 ? (
          <Empty>Nothing matches that search.</Empty>
        ) : (
          <Table head={["Medicine", "Contains", "In stock", "First expiry", "", ""]}>
            {(data ?? []).map((entry) => (
              <tr key={entry.id} className={entry.active ? "" : "opacity-60"}>
                <td className="px-3 py-2">
                  <span className="font-medium">{entry.label}</span>
                  <span className="numeric ml-2 text-xs text-ink-muted">{entry.code}</span>
                  {entry.controlled && (
                    <Badge tone="warn">controlled</Badge>
                  )}
                  {!entry.active && <Badge tone="neutral">retired</Badge>}
                </td>
                <td className="px-3 py-2 text-xs">
                  {/* Class markers included. An allergy recorded as "penicillin" blocks every
                      product that names it, which is how a class allergy works without a second
                      mechanism. */}
                  {entry.ingredients.join(", ")}
                </td>
                <td className="numeric px-3 py-2">
                  {entry.unitsInStock === 0 ? (
                    <span className="text-ink-muted">none</span>
                  ) : (
                    entry.unitsInStock
                  )}
                </td>
                <td className="numeric px-3 py-2 text-ink-muted">
                  {entry.earliestExpiry ?? "—"}
                </td>
                <td className="px-3 py-2" />
                <td className="px-3 py-2">
                  {mayEdit && (
                    <form action={retireFromFormulary}>
                      <input type="hidden" name="code" value={entry.code} />
                      <input type="hidden" name="active" value={entry.active ? "false" : "true"} />
                      <button
                        type="submit"
                        className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
                      >
                        {entry.active ? "Retire" : "Restore"}
                      </button>
                    </form>
                  )}
                </td>
              </tr>
            ))}
          </Table>
        )}
        <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
          Retired, never deleted: prescriptions written last year name these codes, and removing one
          would leave them pointing at nothing. A retired entry cannot be prescribed and the refusal
          says so, so that a prescriber looks for a substitute rather than correcting a typo that
          was not one.
        </p>
      </Card>

      {mayEdit && (
        <Card title="Add a medicine">
          <RecordForm
            action={addToFormulary}
            columns={2}
            submitLabel="Add to the formulary"
            busyLabel="Adding…"
            fields={[
              { name: "code", label: "Code", required: true, hint: "Short, unique, and permanent." },
              { name: "name", label: "Name", required: true },
              { name: "form", label: "Form", type: "select", required: true, options: DOSE_FORMS },
              { name: "strength", label: "Strength", required: true, placeholder: "500 mg" },
              { name: "unit", label: "Unit", required: true, placeholder: "tablet" },
              {
                name: "controlled",
                label: "Controlled drug",
                type: "checkbox",
                hint: "Recorded, not enforced: there is no controlled-drug register yet, and a flag implying one would be a claim the platform cannot keep.",
              },
              {
                name: "ingredients",
                label: "Ingredients",
                required: true,
                placeholder: "AMOXICILLIN, PENICILLIN",
                hint: "Comma-separated, including class markers. This is what every allergy and interaction check runs on, and it cannot be edited afterwards.",
              },
            ]}
          />
        </Card>
      )}
    </div>
  );
}
