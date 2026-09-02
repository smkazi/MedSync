import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type { InteractionPairing } from "@/lib/types";
import { RecordForm } from "@/components/RecordForm";
import { Badge, Card, Empty, ErrorNote, Table } from "@/components/ui";
import { SEVERITIES, severityTone } from "../state";
import { recordInteraction } from "../actions";

/**
 * Which pairs of ingredients should not be given together, and what to do instead.
 *
 * <p>Readable by anybody who may read a medication order, on purpose: a nurse told that two
 * medicines interact should be able to look up what the platform thinks and what it advises, rather
 * than only meeting the rule as a refusal.
 *
 * <p>One row per pair, stored with the two ingredients sorted. Two rows — one for each direction —
 * is a table where the same pairing can carry two different severities, and which one fires depends
 * on the order the caller happened to pass its ingredients in.
 */
export default async function InteractionsPage({
  searchParams,
}: {
  searchParams: Promise<{ problem?: string; done?: string }>;
}) {
  const { problem, done } = await searchParams;
  const mayEdit = hasRole(await currentUser(), "ADMIN", "PHARMACIST");
  const { data, error } = await load<InteractionPairing[]>("/pharmacy/interactions");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Interactions</h1>
        <p className="text-sm text-ink-muted">
          Pairs of ingredients the platform checks every prescription against.
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

      <Card title="Known pairings">
        {(data ?? []).length === 0 ? (
          <Empty>No pairings are recorded.</Empty>
        ) : (
          <Table head={["Pair", "Severity", "What happens", "What to do instead", "Source"]}>
            {(data ?? []).map((pair) => (
              <tr key={pair.id}>
                <td className="numeric px-3 py-2 text-xs">
                  {pair.ingredientA} + {pair.ingredientB}
                </td>
                <td className="px-3 py-2">
                  <Badge tone={severityTone(pair.severity)}>{pair.severity.toLowerCase()}</Badge>
                </td>
                <td className="px-3 py-2 text-xs">{pair.effect}</td>
                {/* The column that earns the table its keep. "These interact" gets dismissed;
                    "monitor INR weekly for the first month" does not. */}
                <td className="px-3 py-2 text-xs font-medium">{pair.management}</td>
                <td className="px-3 py-2 text-xs text-ink-muted">{pair.source ?? "—"}</td>
              </tr>
            ))}
          </Table>
        )}
        <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
          <strong>Contraindicated</strong> refuses a prescription outright and no reason unlocks it.
          At or above the deployment&apos;s threshold — <strong>major</strong> by default — the
          prescriber may go ahead having written down why, and the reason travels with the
          prescription to the pharmacy. Below it, the pairing is reported and does not block:
          interrupting for every minor interaction is how a hospital teaches its clinicians to
          dismiss the dialog without reading it.
        </p>
      </Card>

      {mayEdit && (
        <Card title="Record a pairing">
          <RecordForm
            action={recordInteraction}
            columns={2}
            submitLabel="Record"
            busyLabel="Recording…"
            fields={[
              {
                name: "ingredientA",
                label: "First ingredient",
                required: true,
                placeholder: "WARFARIN",
                hint: "Or a class marker such as NSAID, which covers every product carrying it.",
              },
              { name: "ingredientB", label: "Second ingredient", required: true, placeholder: "NSAID" },
              {
                name: "severity",
                label: "Severity",
                type: "select",
                required: true,
                options: SEVERITIES,
              },
              { name: "source", label: "Source", placeholder: "Reference used" },
              {
                name: "effect",
                label: "What happens",
                required: true,
                hint: "The mechanism or the consequence, in a sentence.",
              },
              {
                name: "management",
                label: "What to do instead",
                required: true,
                hint: "Required. A warning with no action attached is one clinicians learn to dismiss.",
              },
            ]}
          />
          <p className="mt-3 text-xs text-ink-muted">
            Recording a pair that already exists updates it rather than adding a second row — the
            pair is the identity, and the order the two ingredients are typed in does not matter.
          </p>
        </Card>
      )}
    </div>
  );
}
