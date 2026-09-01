import { load } from "@/lib/load";
import type { RoomType } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table } from "@/components/ui";

/**
 * The room-type vocabulary — the platform's worked example of configuration over code.
 *
 * <p>These began as a Java enum with the behaviour each type implied living in a switch. They are
 * rows now, and the behaviour is the three flag columns below. The page shows them as flags rather
 * than hiding them behind the type name, because they are what the booking and bed logic actually
 * reads: a casualty bay is clinical, bed-allocated and never schedulable, and that combination is
 * why an outpatient cannot be booked into one.
 */
export default async function RoomTypesPage() {
  const { data: types, error } = await load<RoomType[]>("/room-types?includeInactive=true");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Room types</h1>
        <p className="text-sm text-ink-muted">
          Configuration, not code. Adding a type needs no deployment — the same rules govern it the
          moment the row exists.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      {types && (
        <>
          <Card title="Types">
            {types.length === 0 ? (
              <Empty>No room types are configured.</Empty>
            ) : (
              <Table
                head={["Code", "Name", "Clinical", "Bed-allocated", "Schedulable", "Description", ""]}
              >
                {types.map((type) => (
                  <tr key={type.code} className={type.active ? "" : "opacity-60"}>
                    <td className="numeric px-3 py-2 font-medium">{type.code}</td>
                    <td className="px-3 py-2">{type.name}</td>
                    <td className="px-3 py-2">{type.clinical ? "yes" : "—"}</td>
                    <td className="px-3 py-2">{type.bedAllocated ? "yes" : "—"}</td>
                    <td className="px-3 py-2">
                      {type.schedulable ? <Badge tone="accent">yes</Badge> : "—"}
                    </td>
                    <td className="px-3 py-2 text-ink-muted">{type.description ?? "—"}</td>
                    <td className="px-3 py-2">
                      {!type.active && <Badge tone="neutral">inactive</Badge>}
                    </td>
                  </tr>
                ))}
              </Table>
            )}
          </Card>

          <Card title="What the flags mean">
            <dl className="space-y-2 text-sm">
              <div className="flex gap-3">
                <dt className="w-32 shrink-0 font-medium">Clinical</dt>
                <dd className="text-ink-muted">
                  Patients are seen or treated here. Non-clinical space cannot carry a bed capacity.
                </dd>
              </div>
              <div className="flex gap-3">
                <dt className="w-32 shrink-0 font-medium">Bed-allocated</dt>
                <dd className="text-ink-muted">
                  Space is handed out as a bed rather than a calendar slot — casualty, a ward, a
                  suite.
                </dd>
              </div>
              <div className="flex gap-3">
                <dt className="w-32 shrink-0 font-medium">Schedulable</dt>
                <dd className="text-ink-muted">
                  Rooms of this type may carry appointments. Never true at the same time as
                  bed-allocated: a database constraint refuses that combination, because it would let
                  a booked outpatient be sent to a resuscitation position.
                </dd>
              </div>
            </dl>
          </Card>
        </>
      )}
    </div>
  );
}
