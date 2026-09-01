import Link from "next/link";
import { load } from "@/lib/load";
import type { FloorDirectory } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Stat } from "@/components/ui";

/**
 * The building, floor by floor.
 *
 * <p>The first screen for data that has been in the database since the facility module landed and
 * has never been visible in a browser: 21 rooms and 12 bed positions, seeded with their as-drawn
 * dimensions. Grouped by floor because that is how somebody walks the building — a flat table of
 * rooms sorted by code is a list a computer likes.
 */
export default async function FacilityDirectoryPage() {
  const { data: floors, error } = await load<FloorDirectory[]>("/rooms/directory");

  const roomCount = (floors ?? []).reduce((total, floor) => total + floor.rooms.length, 0);
  const bookable = (floors ?? []).reduce(
    (total, floor) => total + floor.rooms.filter((room) => room.bookable).length,
    0,
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Room directory</h1>
        <p className="text-sm text-ink-muted">
          Every room in the building, grouped by floor. Reference data — a deployment replaces it.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      {floors && (
        <>
          <div className="grid gap-3 sm:grid-cols-3">
            <Stat label="Floors" value={floors.length} />
            <Stat label="Rooms" value={roomCount} />
            <Stat
              label="Bookable"
              value={bookable}
              hint="Clinical space that can carry an appointment"
            />
          </div>

          {floors.length === 0 ? (
            <Empty>No floors are configured.</Empty>
          ) : (
            floors.map((entry) => (
              <Card
                key={entry.floor.id}
                title={`${entry.floor.name} (${entry.floor.code})`}
                action={
                  <span className="text-xs text-ink-muted">
                    {entry.rooms.length} room{entry.rooms.length === 1 ? "" : "s"}
                  </span>
                }
              >
                {entry.rooms.length === 0 ? (
                  <Empty>Nothing on this floor yet.</Empty>
                ) : (
                  <ul className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
                    {entry.rooms.map((room) => (
                      <li key={room.id}>
                        <Link
                          href={`/facility/rooms?q=${encodeURIComponent(room.code)}`}
                          className="block rounded border border-line px-3 py-2 hover:bg-surface"
                        >
                          <span className="flex items-baseline justify-between gap-2">
                            <span className="numeric text-xs text-ink-muted">{room.code}</span>
                            {room.bookable && <Badge tone="accent">bookable</Badge>}
                          </span>
                          <span className="mt-0.5 block text-sm font-medium">{room.name}</span>
                          <span className="block text-xs text-ink-muted">
                            {room.roomTypeName}
                            {room.departmentCode ? ` · ${room.departmentCode}` : ""}
                          </span>
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </Card>
            ))
          )}
        </>
      )}
    </div>
  );
}
