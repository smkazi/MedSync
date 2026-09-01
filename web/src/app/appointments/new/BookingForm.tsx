"use client";

import { useActionState } from "react";
import { ErrorNote, formatTime } from "@/components/ui";
import type { BookableRoom, Department, Slot } from "@/lib/types";
import { bookAppointment } from "../actions";
import { EMPTY_BOOKING_STATE } from "../state";

/**
 * The booking form.
 *
 * <p>Slots are radio buttons whose value is the platform's own ISO instant, submitted unchanged.
 * Unavailable slots are rendered — disabled, with the reason — rather than omitted, because
 * "fully booked" and "not working today" are different things to tell a patient, and a grid with
 * holes in it says neither.
 */
export function BookingForm({
  mrn,
  clinicianId,
  clinicianName,
  defaultDepartment,
  slots,
  slotMinutes,
  departments,
  rooms,
}: {
  mrn: string;
  clinicianId: string;
  clinicianName: string;
  defaultDepartment: string;
  slots: Slot[];
  slotMinutes: number;
  departments: Department[];
  rooms: BookableRoom[];
}) {
  const [state, formAction, pending] = useActionState(bookAppointment, EMPTY_BOOKING_STATE);
  const open = slots.filter((slot) => slot.available);

  return (
    <form action={formAction} className="space-y-5">
      <input type="hidden" name="clinicianId" value={clinicianId} />
      <input type="hidden" name="clinicianName" value={clinicianName} />
      <input type="hidden" name="durationMinutes" value={slotMinutes} />

      {state.error && <ErrorNote>{state.error}</ErrorNote>}

      <div className="rounded-lg border border-line bg-surface-raised p-4">
        <p className="text-sm font-medium">
          {clinicianName}
          <span className="ml-2 font-normal text-ink-muted">
            {open.length} of {slots.length} slot{slots.length === 1 ? "" : "s"} free
          </span>
        </p>

        {slots.length === 0 ? (
          <p className="mt-3 text-sm text-ink-muted">
            No slots that day — the clinician has no working pattern for it. Add one under
            Scheduling → Clinician schedules.
          </p>
        ) : (
          <fieldset className="mt-3">
            <legend className="sr-only">Slot</legend>
            <div className="grid gap-2 sm:grid-cols-3 lg:grid-cols-4">
              {slots.map((slot) => (
                <label
                  key={slot.startsAt}
                  className={`flex cursor-pointer items-center gap-2 rounded border px-2 py-1.5 text-sm ${
                    slot.available
                      ? "border-line hover:bg-surface"
                      : "cursor-not-allowed border-line/60 bg-surface text-ink-muted"
                  }`}
                >
                  <input
                    type="radio"
                    name="startsAt"
                    value={slot.startsAt}
                    disabled={!slot.available}
                    className="size-4"
                  />
                  <span className="numeric">{formatTime(slot.startsAt)}</span>
                  {!slot.available && (
                    <span className="ml-auto text-[0.65rem]">{slot.unavailableReason}</span>
                  )}
                </label>
              ))}
            </div>
          </fieldset>
        )}
        {state.fieldErrors.startsAt && (
          <p className="mt-2 text-xs text-critical">{state.fieldErrors.startsAt}</p>
        )}
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <div>
          <label htmlFor="mrn" className="block text-sm font-medium">
            Patient MRN<span className="ml-0.5 text-accent">*</span>
          </label>
          <input
            id="mrn"
            name="mrn"
            required
            defaultValue={state.values.mrn || mrn}
            placeholder="MRN-2026-000001"
            aria-invalid={state.fieldErrors.mrn ? true : undefined}
            className={`mt-1 w-full rounded-md border bg-surface-raised px-3 py-2 text-sm ${
              state.fieldErrors.mrn ? "border-critical" : "border-line"
            }`}
          />
          {state.fieldErrors.mrn ? (
            <p className="mt-1 text-xs text-critical">{state.fieldErrors.mrn}</p>
          ) : (
            <p className="mt-1 text-xs text-ink-muted">
              Typed or scanned. Resolved to a chart before the booking is written.
            </p>
          )}
        </div>

        <div>
          <label htmlFor="departmentCode" className="block text-sm font-medium">
            Department<span className="ml-0.5 text-accent">*</span>
          </label>
          <select
            id="departmentCode"
            name="departmentCode"
            required
            defaultValue={state.values.departmentCode || defaultDepartment}
            className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          >
            <option value="">Choose…</option>
            {departments.map((department) => (
              <option key={department.code} value={department.code}>
                {department.name} ({department.code})
              </option>
            ))}
          </select>
        </div>

        <div>
          <label htmlFor="roomCode" className="block text-sm font-medium">
            Room
          </label>
          <select
            id="roomCode"
            name="roomCode"
            defaultValue={state.values.roomCode ?? ""}
            className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          >
            <option value="">No room yet</option>
            {rooms.map((room) => (
              <option key={room.code} value={room.code}>
                {room.name} ({room.code}) · {room.floorName}
              </option>
            ))}
          </select>
          <p className="mt-1 text-xs text-ink-muted">
            Optional. A room is held exclusively for the slot, so a second booking into it is
            refused — by the database, not by this form.
          </p>
        </div>

        <div>
          <label htmlFor="priority" className="block text-sm font-medium">
            Priority
          </label>
          <select
            id="priority"
            name="priority"
            defaultValue={state.values.priority || "ROUTINE"}
            className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          >
            <option value="ROUTINE">Routine</option>
            <option value="URGENT">Urgent</option>
            <option value="STAT">Stat</option>
          </select>
        </div>
      </div>

      <div>
        <label htmlFor="reason" className="block text-sm font-medium">
          Reason for attendance
        </label>
        <input
          id="reason"
          name="reason"
          defaultValue={state.values.reason ?? ""}
          className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
        />
        {state.fieldErrors.reason && (
          <p className="mt-1 text-xs text-critical">{state.fieldErrors.reason}</p>
        )}
      </div>

      <button
        type="submit"
        disabled={pending || open.length === 0}
        className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-60"
      >
        {pending ? "Booking…" : "Book appointment"}
      </button>
    </form>
  );
}
