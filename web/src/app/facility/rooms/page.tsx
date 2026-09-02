import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type { Department, Page, Room, RoomType, Floor } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table } from "@/components/ui";
import { EditRow, RecordForm, type Field } from "@/components/RecordForm";
import { createRoom, updateRoom } from "../actions";

/**
 * Room search, with the filters the API actually supports.
 *
 * <p>Shows `bookableNow` rather than `bookable` in its own column, because they differ and the
 * difference is the whole point of the room-type work: a room may be marked bookable and still not
 * be schedulable, if its type allocates space by bed. A booking screen must obey `bookableNow`.
 */
export default async function RoomsPage({
  searchParams,
}: {
  searchParams: Promise<{ q?: string; type?: string; floor?: string; includeInactive?: string }>;
}) {
  const { q = "", type = "", floor = "", includeInactive } = await searchParams;

  const params = new URLSearchParams({ size: "200" });
  if (q) params.set("q", q);
  if (type) params.set("type", type);
  if (floor) params.set("floor", floor);
  if (includeInactive === "on") params.set("includeInactive", "true");

  const [{ data: rooms, error }, { data: types }, { data: floors }, { data: departments }] =
    await Promise.all([
      load<Page<Room>>(`/rooms?${params}`),
      load<RoomType[]>("/room-types"),
      load<Floor[]>("/floors"),
      load<Department[]>("/departments"),
    ]);
  const mayEdit = hasRole(await currentUser(), "ADMIN");

  const typeOptions = (types ?? []).map((roomType) => ({
    value: roomType.code,
    label: roomType.name,
  }));
  const floorOptions = (floors ?? []).map((entry) => ({
    value: entry.code,
    label: `${entry.name} (${entry.code})`,
  }));
  const departmentOptions = (departments ?? []).map((department) => ({
    value: department.code,
    label: department.name,
  }));

  /**
   * The shape of a room, as fields.
   *
   * <p>Shared between the add form and every edit form so the two cannot drift - the bug that
   * would produce is a field editable in one place and not the other, which nobody notices until
   * somebody needs it. `room` is undefined for the add form, where the code is required and
   * editable; on an edit it is absent, because a room's code is what the building calls it and
   * three services cache it.
   */
  const roomFields = (room?: Room): Field[] => [
    ...(room
      ? []
      : [{ name: "code", label: "Code", required: true, placeholder: "FF-DAY" } as Field]),
    { name: "name", label: "Name", required: !room, value: room?.name },
    {
      name: "roomTypeCode",
      label: "Type",
      type: "select",
      options: typeOptions,
      required: !room,
      value: room?.roomTypeCode,
    },
    {
      name: "floorCode",
      label: "Floor",
      type: "select",
      options: floorOptions,
      required: !room,
      value: room?.floorCode,
    },
    {
      name: "departmentCode",
      label: "Clinic",
      type: "select",
      options: departmentOptions,
      value: room?.departmentCode,
      hint: "A lobby has none.",
    },
    {
      name: "capacity",
      label: "Bed capacity",
      type: "number",
      value: room?.capacity,
      hint: "How many bed positions the room is designed for.",
    },
    { name: "widthFt", label: "Width (ft)", type: "number", step: "0.01", value: room?.widthFt },
    { name: "lengthFt", label: "Length (ft)", type: "number", step: "0.01", value: room?.lengthFt },
    {
      name: "directions",
      label: "Directions",
      type: "textarea",
      value: room?.directions,
      hint: "Printed on an appointment: \u201cFrom reception, follow the signs for General\u201d.",
    },
    { name: "notes", label: "Notes", type: "textarea", value: room?.notes },
    { name: "bookable", label: "Bookable", type: "checkbox", value: room?.bookable },
    ...(room
      ? [{ name: "active", label: "In use", type: "checkbox", value: room.active } as Field]
      : []),
  ];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Rooms</h1>
        <p className="text-sm text-ink-muted">
          {rooms ? `${rooms.totalElements} room(s)` : "Search the building."}
        </p>
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
            placeholder="GF-GEN, casualty, suite…"
            className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor="type" className="block text-sm font-medium">
            Type
          </label>
          <select
            id="type"
            name="type"
            defaultValue={type}
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          >
            <option value="">Any</option>
            {(types ?? []).map((roomType) => (
              <option key={roomType.code} value={roomType.code}>
                {roomType.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="floor" className="block text-sm font-medium">
            Floor
          </label>
          <select
            id="floor"
            name="floor"
            defaultValue={floor}
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          >
            <option value="">Any</option>
            {(floors ?? []).map((entry) => (
              <option key={entry.code} value={entry.code}>
                {entry.name}
              </option>
            ))}
          </select>
        </div>
        <label className="flex items-center gap-2 pb-2 text-sm">
          <input type="checkbox" name="includeInactive" defaultChecked={includeInactive === "on"} />
          Include inactive
        </label>
        <button
          type="submit"
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
        >
          Apply
        </button>
      </form>

      {error && <ErrorNote>{error}</ErrorNote>}

      {rooms && (
        <Card title="Rooms">
          {rooms.content.length === 0 ? (
            <Empty>No rooms match.</Empty>
          ) : (
            <Table
              head={[
                "Code", "Name", "Type", "Floor", "Dept", "Beds", "Size", "Bookable now", "",
                ...(mayEdit ? [""] : []),
              ]}
            >
              {rooms.content.map((room) => (
                <tr key={room.id} className={room.active ? "" : "opacity-60"}>
                  <td className="numeric px-3 py-2 font-medium">{room.code}</td>
                  <td className="px-3 py-2">{room.name}</td>
                  <td className="px-3 py-2 text-ink-muted">{room.roomTypeName}</td>
                  <td className="px-3 py-2 text-ink-muted">{room.floorName}</td>
                  <td className="px-3 py-2 text-ink-muted">{room.departmentCode ?? "—"}</td>
                  <td className="numeric px-3 py-2">
                    {room.capacity === 0 ? "—" : `${room.bedCount} / ${room.capacity}`}
                  </td>
                  <td className="numeric px-3 py-2 text-ink-muted">{room.dimensions ?? "—"}</td>
                  <td className="px-3 py-2">
                    {room.bookableNow ? (
                      <Badge tone="accent">yes</Badge>
                    ) : (
                      <span className="text-xs text-ink-muted">no</span>
                    )}
                  </td>
                  <td className="px-3 py-2">
                    {!room.active && <Badge tone="neutral">inactive</Badge>}
                  </td>
                  {mayEdit && (
                    <td className="px-3 py-2">
                      <EditRow label="Edit">
                        <RecordForm
                          action={updateRoom}
                          hidden={{ id: room.id }}
                          columns={3}
                          submitLabel="Save"
                          fields={roomFields(room)}
                        />
                      </EditRow>
                    </td>
                  )}
                </tr>
              ))}
            </Table>
          )}
        </Card>
      )}

      {mayEdit && (
        <Card title="Add a room">
          <RecordForm
            action={createRoom}
            columns={3}
            submitLabel="Add room"
            fields={roomFields()}
          />
        </Card>
      )}

      {rooms && rooms.content.some((room) => room.directions) && (
        <Card title="Wayfinding">
          <dl className="space-y-2 text-sm">
            {rooms.content
              .filter((room) => room.directions)
              .map((room) => (
                <div key={room.id} className="flex gap-3">
                  <dt className="numeric w-24 shrink-0 text-ink-muted">{room.code}</dt>
                  <dd className="italic">{room.directions}</dd>
                </div>
              ))}
          </dl>
        </Card>
      )}
    </div>
  );
}
