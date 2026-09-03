import { load } from "@/lib/load";
import type { ImagingProcedure } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Stat, Table } from "@/components/ui";

/**
 * What can be ordered, grouped by the room that does it.
 *
 * <p>Read-only, and the note beside it in the menu says so. The catalogue is configuration: a
 * department that starts doing MRI knees adds a row, and `docs/extensibility.md` records that as
 * configuration rather than code. There is no write endpoint for it yet and this page does not
 * pretend there is — the gap is named in the README rather than fronted by a form that would 405.
 *
 * <p>Readable by anybody signed in, like the laboratory catalogue, because it is a list of
 * examination names with no patient anywhere in it and an ordering screen that could not read it
 * would be an ordering screen with an empty select.
 */
export default async function ImagingCataloguePage() {
  const { data, error } = await load<ImagingProcedure[]>("/imaging/procedures");
  const rows = data ?? [];

  // Grouped in one pass, insertion-ordered, so the modalities appear in the order the platform
  // returns them rather than alphabetically — the catalogue's own ordering is deliberate.
  const byModality = new Map<string, ImagingProcedure[]>();
  for (const row of rows) {
    const group = byModality.get(row.modality);
    if (group) group.push(row);
    else byModality.set(row.modality, [row]);
  }

  const withContrast = rows.filter((row) => row.contrast);
  const longest = rows.reduce((most, row) => Math.max(most, row.minutes), 0);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Examination catalogue</h1>
        <p className="text-sm text-ink-muted">
          Every examination this department offers, and how long the room is held for it.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Examinations" value={rows.length} />
        <Stat label="Modalities" value={byModality.size} />
        <Stat
          label="Need contrast"
          value={withContrast.length}
          hint="consent and a cannula"
        />
        <Stat label="Longest" value={longest ? `${longest} min` : "—"} />
      </div>

      {rows.length === 0 ? (
        <Card title="The catalogue">
          <Empty>
            No examinations are configured. Radiology cannot be ordered until the catalogue has rows
            — the ordering screen offers what it finds here and nothing else.
          </Empty>
        </Card>
      ) : (
        [...byModality].map(([modality, procedures]) => (
          <Card key={modality} title={`${modality} — ${procedures.length} examination(s)`}>
            <Table head={["Code", "Examination", "Body part", "Room time", "Contrast"]}>
              {procedures.map((procedure) => (
                <tr key={procedure.code} className="border-t border-line">
                  <td className="numeric px-3 py-2">{procedure.code}</td>
                  <td className="px-3 py-2">{procedure.name}</td>
                  <td className="px-3 py-2">{procedure.bodyPart ?? "—"}</td>
                  <td className="numeric px-3 py-2">{procedure.minutes} min</td>
                  <td className="px-3 py-2">
                    {procedure.contrast ? <Badge tone="warn">yes</Badge> : "—"}
                  </td>
                </tr>
              ))}
            </Table>
          </Card>
        ))
      )}
    </div>
  );
}
