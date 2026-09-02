import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type { Bed, Page, Room } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Stat, Table } from "@/components/ui";
import { EditRow, RecordForm } from "@/components/RecordForm";
import { addBed, updateBed } from "../actions";

/**
 * Bed positions, grouped by the room that holds them.
 *
 * <p>Positions, not occupancy. Nothing in the platform tracks who is in a bed yet — that arrives
 * with the admissions module — and this page says so rather than leaving a reader to assume an empty
 * "patient" column means the bed is free.
 */
export default async function BedsPage() {
  const mayEdit = hasRole(await currentUser(), "ADMIN");
  // Decommissioned positions are listed here and nowhere else. Anything that allocates a bed reads
  // the default list, because a bed out of service is not allocatable.
  const [{ data: beds, error }, { data: rooms }] = await Promise.all([
    load<Bed[]>(mayEdit ? "/beds?includeInactive=true" : "/beds"),
    load<Page<Room>>("/rooms?size=200"),
  ]);

  const byRoom = new Map<string, Bed[]>();
  for (const bed of beds ?? []) {
    const list = byRoom.get(bed.roomCode) ?? [];
    list.push(bed);
    byRoom.set(bed.roomCode, list);
  }

  // Only clinical rooms: the service refuses a bed anywhere else, and its message names the type.
  const bedRooms = (rooms?.content ?? [])
    .filter((room) => room.clinical && room.active)
    .map((room) => ({ value: room.code, label: `${room.code} — ${room.name}` }));
  const inService = (beds ?? []).filter((bed) => bed.active);

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
            <Stat label="Bed positions" value={inService.length} />
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

          {mayEdit && beds.length > 0 && (
            <Card title="Positions">
              <Table head={["Room", "Bed", "Label", "", ""]}>
                {beds.map((bed) => (
                  <tr key={bed.id} className={bed.active ? "" : "opacity-60"}>
                    <td className="numeric px-3 py-2">{bed.roomCode}</td>
                    <td className="numeric px-3 py-2 font-medium">{bed.code}</td>
                    <td className="px-3 py-2 text-ink-muted">{bed.label ?? "—"}</td>
                    <td className="px-3 py-2">
                      {!bed.active && <Badge tone="neutral">out of service</Badge>}
                    </td>
                    <td className="px-3 py-2">
                      <EditRow label="Edit">
                        <RecordForm
                          action={updateBed}
                          hidden={{ id: bed.id }}
                          columns={2}
                          submitLabel="Save"
                          fields={[
                            { name: "label", label: "Label", value: bed.label },
                            {
                              name: "active",
                              label: "In service",
                              type: "checkbox",
                              value: bed.active,
                            },
                          ]}
                        />
                      </EditRow>
                    </td>
                  </tr>
                ))}
              </Table>
              <p className="mt-3 text-xs text-ink-muted">
                Taking a position out of service keeps the row — admissions recorded in it stay
                valid — and drops it out of the list bed allocation reads.
              </p>
            </Card>
          )}
        </>
      )}

      {mayEdit && (
        <Card title="Add a bed">
          <RecordForm
            action={addBed}
            columns={3}
            submitLabel="Add bed"
            fields={[
              { name: "roomCode", label: "Room", type: "select", options: bedRooms, required: true },
              { name: "code", label: "Bed code", required: true, placeholder: "CAS-7" },
              { name: "label", label: "Label", placeholder: "Bay 7, screened" },
            ]}
          />
          <p className="mt-3 text-xs text-ink-muted">
            Beds belong in clinical rooms only, and never beyond the room&apos;s designed capacity —
            a room with more positions recorded than it has means one of those beds is somewhere
            else. Both are refused by the platform, with the numbers.
          </p>
        </Card>
      )}
    </div>
  );
}
