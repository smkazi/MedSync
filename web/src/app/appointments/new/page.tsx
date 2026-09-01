import Link from "next/link";
import { redirect } from "next/navigation";
import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type { Availability, BookableRoom, Department, Page, Staff } from "@/lib/types";
import { Card, ErrorNote } from "@/components/ui";
import { BookingForm } from "./BookingForm";

/**
 * Book an appointment.
 *
 * <p>The screen is deliberately two steps in one page, and the reason is a bug it avoids rather than
 * a preference. Picking a clinician and a date is a GET — it puts both in the URL, so the slot list
 * is shareable and the back button works. Only then does the slot grid appear, and each slot carries
 * the **exact instant** the platform computed. Nothing on this page asks for a wall-clock time: a
 * `datetime-local` input hands back a string with no zone, and the browser's zone is not necessarily
 * the platform's, so a booking built from one is a timezone bug waiting for the first clinician who
 * travels.
 *
 * <p>It also means the slots offered are the slots the service will accept — `SlotCalculator` has
 * already removed the past, the blackouts and the taken ones, each with its reason.
 */
export default async function BookAppointmentPage({
  searchParams,
}: {
  searchParams: Promise<{ mrn?: string; clinicianId?: string; date?: string }>;
}) {
  const user = await currentUser();
  if (!user) redirect("/login");
  // A courtesy, not the control: POST /appointments carries @PreAuthorize(FRONT_DESK).
  if (!hasRole(user, "ADMIN", "RECEPTIONIST", "DOCTOR", "NURSE")) redirect("/appointments");

  const { mrn = "", clinicianId = "", date = "" } = await searchParams;
  const today = new Date().toISOString().slice(0, 10);
  const chosenDate = date || today;

  const [staffPage, departments, rooms] = await Promise.all([
    load<Page<Staff>>("/staff?size=200"),
    load<Department[]>("/departments"),
    load<BookableRoom[]>("/rooms/bookable"),
  ]);

  // Only a staff member with a platform login can be the clinician on an appointment: the
  // appointment's clinicianId *is* a user id.
  const clinicians = (staffPage.data?.content ?? [])
    .filter((member) => member.userId && member.active)
    .sort((a, b) => a.fullName.localeCompare(b.fullName));

  let availability: Availability | null = null;
  let slotError: string | null = null;
  if (clinicianId) {
    const result = await load<Availability>(
      `/appointments/availability?clinicianId=${encodeURIComponent(clinicianId)}&date=${chosenDate}`,
    );
    availability = result.data;
    slotError = result.error;
  }

  return (
    <div className="max-w-4xl space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Book an appointment</h1>
        <p className="text-sm text-ink-muted">
          Choose the clinician and the day, then pick from the slots the platform says are free.
        </p>
      </div>

      {clinicians.length === 0 && (
        <ErrorNote>
          No staff member has a platform login, so there is nobody to book with. Link a staff record
          to a user under Administration → Staff directory first.
        </ErrorNote>
      )}

      <Card title="Clinician and day">
        <form className="flex flex-wrap items-end gap-3">
          <input type="hidden" name="mrn" value={mrn} />
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
                  {member.fullName} — {member.designation}
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
            className="rounded-md border border-line px-4 py-2 text-sm font-medium hover:bg-surface"
          >
            Show slots
          </button>
        </form>
      </Card>

      {slotError && <ErrorNote>{slotError}</ErrorNote>}

      {clinicianId && availability && (
        <BookingForm
          mrn={mrn}
          clinicianId={clinicianId}
          clinicianName={
            clinicians.find((member) => member.userId === clinicianId)?.fullName ?? clinicianId
          }
          defaultDepartment={
            clinicians.find((member) => member.userId === clinicianId)?.departmentCode ?? ""
          }
          slots={availability.slots}
          slotMinutes={availability.slotMinutes}
          departments={(departments.data ?? []).filter((department) => department.active)}
          rooms={rooms.data ?? []}
        />
      )}

      <Link href="/appointments" className="inline-block text-sm text-ink-muted hover:text-ink">
        Back to the appointment book
      </Link>
    </div>
  );
}
