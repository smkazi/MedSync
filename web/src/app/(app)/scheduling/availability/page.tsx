import { load } from "@/lib/load";
import type { Page, Slot, Staff } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, formatTime } from "@/components/ui";

type Availability = { clinicianId: string; date: string; slots: Slot[] };

/**
 * A clinician's day, slot by slot.
 *
 * <p>Every slot carries why it is unavailable rather than simply being absent, which is the
 * difference between "fully booked" and "not working today" — and the front desk needs to tell a
 * patient which. `ROOM_IN_USE` appears here too, now that a booking holds a room.
 */
export default async function AvailabilityPage({
  searchParams,
}: {
  searchParams: Promise<{ clinicianId?: string; date?: string }>;
}) {
  const { clinicianId = "", date = "" } = await searchParams;
  const today = new Date().toISOString().slice(0, 10);
  const chosenDate = date || today;

  const { data: staff } = await load<Page<Staff>>("/staff?size=100");
  // Only staff with a login can be a clinician on an appointment.
  const clinicians = (staff?.content ?? []).filter((member) => member.userId && member.active);

  let availability: Availability | null = null;
  let error: string | null = null;
  if (clinicianId) {
    const result = await load<Availability>(
      `/appointments/availability?clinicianId=${encodeURIComponent(clinicianId)}&date=${chosenDate}`,
    );
    availability = result.data;
    error = result.error;
  }

  const open = availability?.slots.filter((slot) => slot.available).length ?? 0;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Clinician availability</h1>
        <p className="text-sm text-ink-muted">
          Slots for one clinician on one day, each marked bookable or not, with the reason.
        </p>
      </div>

      <form className="flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="clinicianId" className="block text-sm font-medium">
            Clinician
          </label>
          <select
            id="clinicianId"
            name="clinicianId"
            defaultValue={clinicianId}
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          >
            <option value="">Choose…</option>
            {clinicians.map((member) => (
              <option key={member.id} value={member.userId ?? ""}>
                {member.fullName}
                {member.designation ? ` — ${member.designation}` : ""}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="date" className="block text-sm font-medium">
            Date
          </label>
          <input
            id="date"
            name="date"
            type="date"
            defaultValue={chosenDate}
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
        </div>
        <button
          type="submit"
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
        >
          Show
        </button>
      </form>

      {error && <ErrorNote>{error}</ErrorNote>}

      {!clinicianId && (
        <Empty>Choose a clinician to see their day.</Empty>
      )}

      {availability && (
        <Card title={`${chosenDate} — ${open} of ${availability.slots.length} slots open`}>
          {availability.slots.length === 0 ? (
            <Empty>
              No slots at all on this date — the clinician has no working pattern configured for this
              day of the week.
            </Empty>
          ) : (
            <ul className="grid gap-2 sm:grid-cols-3 lg:grid-cols-4">
              {availability.slots.map((slot) => (
                <li
                  key={slot.startsAt}
                  className={`rounded border px-3 py-2 text-sm ${
                    slot.available ? "border-line" : "border-line bg-surface opacity-70"
                  }`}
                >
                  <span className="numeric block font-medium">
                    {formatTime(slot.startsAt)}–{formatTime(slot.endsAt)}
                  </span>
                  {slot.available ? (
                    <Badge tone="accent">open</Badge>
                  ) : (
                    <span className="block text-xs text-ink-muted">
                      {slot.unavailableReason
                        ? slot.unavailableReason.toLowerCase().replace(/_/g, " ")
                        : "unavailable"}
                    </span>
                  )}
                </li>
              ))}
            </ul>
          )}
        </Card>
      )}
    </div>
  );
}
