import { load } from "@/lib/load";
import type { Bed } from "@/lib/types";
import { Card, Empty, ErrorNote, Stat, Table } from "@/components/ui";

/**
 * Bed positions, grouped by the room that holds them.
 *
 * <p>Positions, not occupancy. Nothing in the platform tracks who is in a bed yet — that arrives
 * with the admissions module — and this page says so rather than leaving a reader to assume an empty
 * "patient" column means the bed is free.
 */
export default async function BedsPage() {
  const { data: beds, error } = await load<Bed[]>("/beds");

  const byRoom = new Map<string, Bed[]>();
  for (const bed of beds ?? []) {
    const list = byRoom.get(bed.roomCode) ?? [];
    list.push(bed);
    byRoom.set(bed.roomCode, list);
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Beds</h1>
        <p className="text-sm text-ink-muted">
          Bed positions in the building. Occupancy is not tracked yet — that is the admissions
          module.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      {beds && (
        <>
          <div className="grid gap-3 sm:grid-cols-2">
            <Stat label="Bed positions" value={beds.length} />
            <Stat label="Rooms with beds" value={byRoom.size} />
          </div>

          {beds.length === 0 ? (
            <Empty>No beds are configured.</Empty>
          ) : (
            <Card title="By room">
              <Table head={["Room", "Name", "Floor", "Beds"]}>
                {[...byRoom.entries()].map(([roomCode, roomBeds]) => {
                  // The map is only ever populated by pushing, so a key always has at least one
                  // bed - but the compiler cannot know that, and asserting it would be worse than
                  // reading the first element defensively.
                  const first = roomBeds[0];
                  return (
                    <tr key={roomCode}>
                      <td className="numeric px-3 py-2 font-medium">{roomCode}</td>
                      <td className="px-3 py-2">{first?.roomName ?? "—"}</td>
                      <td className="px-3 py-2 text-ink-muted">{first?.floorName ?? "—"}</td>
                      <td className="numeric px-3 py-2">
                        {roomBeds.map((bed) => bed.code).join(", ")}
                      </td>
                    </tr>
                  );
                })}
              </Table>
            </Card>
          )}
        </>
      )}
    </div>
  );
}
