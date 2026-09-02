"use server";

import { revalidatePath } from "next/cache";
import { readForm, refused, withoutBlanks, type FormState } from "@/lib/form";
import { submit } from "@/lib/mutate";
import type { Bed, Floor, Room, RoomType } from "@/lib/types";

/**
 * Facility master data: floors, room types, rooms and bed positions.
 *
 * <p>Every write here is `ADMIN_ONLY` in the service, and every one of them is sparse on update: a
 * field left blank is absent from the JSON rather than sent empty, so editing a room's directions
 * does not clear its notes.
 *
 * <p>Two conversions are the whole reason these are not one generic function. The services take
 * **numbers** for levels, capacities and dimensions and **booleans** for the flags, and JSON
 * `"0"` is not `0` while `"false"` is emphatically not `false`. And a room is read by `code` but
 * written by `id` — the row has to carry both, which is why every edit form is given the id and
 * every read path uses the code.
 */

const NUMBER_FIELDS = new Set(["level", "capacity", "widthFt", "lengthFt", "displayOrder"]);
const BOOLEAN_FIELDS = new Set([
  "clinical",
  "bedAllocated",
  "schedulable",
  "bookable",
  "active",
]);

/**
 * Turns submitted strings into the JSON the services validate.
 *
 * <p>Blanks are dropped first, so an untouched optional field is absent. What survives is coerced
 * by name: a bean with a `Short level` rejects `"1"` outright, and a `Boolean active` given
 * `"false"` would be true.
 */
function body(values: Record<string, string>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [field, value] of Object.entries(withoutBlanks(values))) {
    const text = String(value);
    if (NUMBER_FIELDS.has(field)) {
      out[field] = Number(text);
    } else if (BOOLEAN_FIELDS.has(field)) {
      out[field] = text === "true";
    } else {
      out[field] = text;
    }
  }
  return out;
}

/** One write, one refreshed page, the service's own message on a refusal. */
async function write<T>(
  path: string,
  method: "POST" | "PATCH",
  values: Record<string, string>,
  refresh: string[],
  done: string,
): Promise<FormState> {
  const result = await submit<T>(path, method, body(values));
  if (!result.ok) {
    return refused(values, result);
  }
  for (const page of refresh) {
    revalidatePath(page);
  }
  return { values: {}, fieldErrors: {}, error: null, done };
}

// ---- floors -----------------------------------------------------------------

const FLOOR_FIELDS = ["code", "name", "level"] as const;

export async function createFloor(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, FLOOR_FIELDS);
  return write<Floor>("/floors", "POST", values, ["/facility/floors", "/facility"],
    `Floor ${values.code.toUpperCase()} added.`);
}

export async function updateFloor(_previous: FormState, form: FormData): Promise<FormState> {
  const id = String(form.get("id") ?? "");
  const values = readForm(form, ["name", "level", "active"] as const);
  return write<Floor>(`/floors/${id}`, "PATCH", values, ["/facility/floors", "/facility"],
    "Floor updated.");
}

// ---- room types -------------------------------------------------------------

const ROOM_TYPE_FIELDS = [
  "code",
  "name",
  "description",
  "clinical",
  "bedAllocated",
  "schedulable",
  "displayOrder",
] as const;

export async function createRoomType(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, ROOM_TYPE_FIELDS);
  return write<RoomType>("/room-types", "POST", values,
    ["/facility/room-types", "/facility/rooms"],
    `Room type ${values.code.toUpperCase()} added.`);
}

export async function updateRoomType(_previous: FormState, form: FormData): Promise<FormState> {
  const code = String(form.get("code") ?? "");
  const values = readForm(form, [
    "name", "description", "clinical", "bedAllocated", "schedulable", "displayOrder", "active",
  ] as const);
  // Read by code and written by code: unlike a room, a type's code *is* its primary key.
  return write<RoomType>(`/room-types/${code}`, "PATCH", values,
    ["/facility/room-types", "/facility/rooms"], `Room type ${code} updated.`);
}

// ---- rooms ------------------------------------------------------------------

const ROOM_FIELDS = [
  "code",
  "name",
  "roomTypeCode",
  "floorCode",
  "departmentCode",
  "capacity",
  "widthFt",
  "lengthFt",
  "directions",
  "bookable",
  "notes",
] as const;

export async function createRoom(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, ROOM_FIELDS);
  return write<Room>("/rooms", "POST", values, ["/facility/rooms", "/facility"],
    `Room ${values.code.toUpperCase()} added.`);
}

export async function updateRoom(_previous: FormState, form: FormData): Promise<FormState> {
  // By id, not by code. `GET /rooms/{code}` and `PATCH /rooms/{id}` are deliberately asymmetric -
  // a code is what people say out loud and an id is what survives a rename - so the table row has
  // to carry both.
  const id = String(form.get("id") ?? "");
  const values = readForm(form, [
    "name", "roomTypeCode", "floorCode", "departmentCode", "capacity",
    "widthFt", "lengthFt", "directions", "bookable", "active", "notes",
  ] as const);
  return write<Room>(`/rooms/${id}`, "PATCH", values,
    ["/facility/rooms", "/facility", "/facility/beds"], "Room updated.");
}

// ---- beds -------------------------------------------------------------------

export async function addBed(_previous: FormState, form: FormData): Promise<FormState> {
  const roomCode = String(form.get("roomCode") ?? "");
  const values = readForm(form, ["code", "label"] as const);
  // The service refuses a bed beyond the room's designed capacity, and says so with the numbers.
  // That refusal is the useful one here: a bay with more positions recorded than it has means one
  // of those beds is somewhere else.
  return write<Bed>(`/rooms/${roomCode}/beds`, "POST", values,
    ["/facility/beds", "/facility/rooms"], `Bed ${values.code.toUpperCase()} added to ${roomCode}.`);
}

export async function updateBed(_previous: FormState, form: FormData): Promise<FormState> {
  const id = String(form.get("id") ?? "");
  const values = readForm(form, ["label", "active"] as const);
  return write<Bed>(`/beds/${id}`, "PATCH", values,
    ["/facility/beds", "/facility/rooms"], "Bed updated.");
}
