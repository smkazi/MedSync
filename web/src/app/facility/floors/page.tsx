import { load } from "@/lib/load";
import type { Floor } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table } from "@/components/ui";

/** Floors, ordered as you would climb them. */
export default async function FloorsPage() {
  const { data: floors, error } = await load<Floor[]>("/floors?includeInactive=true");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Floors</h1>
        <p className="text-sm text-ink-muted">
          Level orders the building vertically; ground is 0, so a basement is negative.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      {floors && (
        <Card title="Floors">
          {floors.length === 0 ? (
            <Empty>No floors are configured.</Empty>
          ) : (
            <Table head={["Level", "Code", "Name", ""]}>
              {[...floors]
                .sort((a, b) => a.level - b.level)
                .map((floor) => (
                  <tr key={floor.id} className={floor.active ? "" : "opacity-60"}>
                    <td className="numeric px-3 py-2">{floor.level}</td>
                    <td className="numeric px-3 py-2 font-medium">{floor.code}</td>
                    <td className="px-3 py-2">{floor.name}</td>
                    <td className="px-3 py-2">
                      {!floor.active && <Badge tone="neutral">inactive</Badge>}
                    </td>
                  </tr>
                ))}
            </Table>
          )}
        </Card>
      )}
    </div>
  );
}
