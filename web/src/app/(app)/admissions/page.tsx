import Link from "next/link";
import { load, loadAll } from "@/lib/load";
import type { Admission, BedState, CasualtyAttendance, Staff } from "@/lib/types";
import { RecordForm } from "@/components/RecordForm";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDateTime } from "@/components/ui";
import { ADMISSION_SOURCES } from "../casualty/state";
import { admit, discharge, transfer } from "../casualty/actions";

/**
 * The in-patient census and the bed map.
 *
 * <p>Two views of the same fact from opposite ends: the census is who is in, the bed map is what is
 * free. Both are needed because the questions differ — "where is Mrs Nair" and "can we take another
 * admission" — and answering the second from the first means counting what is absent.
 *
 * <p>The bed map is composed by admissions-service from the facility directory and its own
 * occupancy table. patient-service deliberately keeps no occupancy flag on a bed: a flag written by
 * one service and maintained by another is a flag that goes stale, and a stale bed map is how two
 * patients end up being sent to one bed.
 */
export default async function AdmissionsPage({
  searchParams,
}: {
  searchParams: Promise<{ problem?: string; done?: string; room?: string; admit?: string }>;
}) {
  const { problem, done, room = "", admit: admitAttendance = "" } = await searchParams;

  const [census, beds, board, staff] = await Promise.all([
    load<Admission[]>(`/admissions${room ? `?room=${encodeURIComponent(room)}` : ""}`),
    load<BedState[]>("/admissions/beds"),
    // Only when admitting from casualty: the form needs the attendance's patient, and asking for
    // the board otherwise is a read nobody looked at.
    admitAttendance
      ? load<CasualtyAttendance[]>("/casualty")
      : Promise.resolve({ data: null, error: null }),
    // A page, not a list. `/staff` is paged like every other collection on the platform, and
    // typing it as an array is how this screen threw a server error the first time a browser test
    // clicked Admit - the same mistake the room picker made with `/rooms`.
    admitAttendance
      ? loadAll<Staff>("/staff")
      : Promise.resolve({ data: null, error: null }),
  ]);

  const admitting = (board.data ?? []).find((row) => row.id === admitAttendance) ?? null;
  const freeBeds = (beds.data ?? []).filter((bed) => !bed.occupied);
  const rooms = [...new Set((beds.data ?? []).map((bed) => bed.roomCode))].sort();
  const byRoom = new Map<string, BedState[]>();
  for (const bed of beds.data ?? []) {
    byRoom.set(bed.roomCode, [...(byRoom.get(bed.roomCode) ?? []), bed]);
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Admissions and beds</h1>
        <p className="text-sm text-ink-muted">Who is in, and what is free.</p>
      </div>

      {problem && <ErrorNote>{problem}</ErrorNote>}
      {done && (
        <p
          role="status"
          className="rounded-md border border-good/40 bg-good-soft px-3 py-2 text-sm text-good"
        >
          {done}
        </p>
      )}
      {census.error && <ErrorNote>{census.error}</ErrorNote>}
      {beds.error && <ErrorNote>{beds.error}</ErrorNote>}

      <div className="grid gap-4 sm:grid-cols-3">
        <Stat label="In-patients" value={(census.data ?? []).length} hint="currently admitted" />
        <Stat label="Free beds" value={freeBeds.length} hint={`of ${(beds.data ?? []).length}`} />
        <Stat
          label="Occupancy"
          value={
            (beds.data ?? []).length === 0
              ? "—"
              : `${Math.round((((beds.data ?? []).length - freeBeds.length) / (beds.data ?? []).length) * 100)}%`
          }
          hint="beds with somebody in them"
        />
      </div>

      {admitting && (
        <Card title={`Admit ${admitting.patientMrn} from casualty`}>
          <p className="mb-3 text-xs text-ink-muted">
            Acuity {admitting.triageAcuity}, {admitting.presentingComplaint}, waiting{" "}
            {admitting.waitingMinutes} minutes. Admitting closes the casualty attendance and frees
            its bay in the same transaction — a bay left held is a bay the department believes it
            does not have.
          </p>
          <RecordForm
            action={admit}
            columns={2}
            submitLabel="Admit"
            busyLabel="Admitting…"
            hidden={{
              patientId: admitting.patientId,
              patientMrn: admitting.patientMrn,
              attendanceId: admitting.id,
            }}
            fields={[
              {
                name: "bedId",
                label: "Ward bed",
                type: "select",
                required: true,
                options: freeBeds.map((bed) => ({
                  value: bed.bedId,
                  label: `${bed.bedCode} — ${bed.roomName}`,
                })),
                hint: "Free beds only, verified against the facility directory when you submit. If somebody takes it first the service refuses and names the bed.",
              },
              {
                name: "admittingClinicianId",
                label: "Admitting clinician",
                type: "select",
                required: true,
                options: (staff.data ?? [])
                  .filter((member) => member.userId)
                  .map((member) => ({
                    value: member.userId as string,
                    label: `${member.fullName} — ${member.designation}`,
                  })),
              },
              { name: "source", label: "Source", type: "select", value: "CASUALTY",
                options: ADMISSION_SOURCES },
              { name: "expectedDischarge", label: "Expected discharge", type: "date" },
            ]}
          />
        </Card>
      )}

      <Card
        title={`Census${room ? ` — ${room}` : ""}`}
        action={
          <form className="flex items-center gap-2">
            <label htmlFor="room" className="text-xs text-ink-muted">
              Room
            </label>
            <select
              id="room"
              name="room"
              defaultValue={room}
              className="rounded border border-line bg-surface-raised px-2 py-1 text-xs"
            >
              <option value="">all</option>
              {rooms.map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
            <button
              type="submit"
              className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
            >
              Show
            </button>
          </form>
        }
      >
        {(census.data ?? []).length === 0 ? (
          <Empty>Nobody is admitted{room ? ` in ${room}` : ""}.</Empty>
        ) : (
          <Table head={["Bed", "MRN", "Source", "Admitted", "Stay", "Expected out", "", ""]}>
            {(census.data ?? []).map((row) => (
              <tr key={row.id}>
                <td className="numeric px-3 py-2 font-medium">
                  {row.bedCode}
                  <span className="ml-1 text-xs text-ink-muted">{row.roomCode}</span>
                </td>
                <td className="numeric px-3 py-2">
                  <Link href={`/patients/${row.patientId}`} className="text-accent hover:underline">
                    {row.patientMrn}
                  </Link>
                </td>
                <td className="px-3 py-2">
                  <Badge tone={row.source === "CASUALTY" ? "warn" : "neutral"}>
                    {row.source.toLowerCase()}
                  </Badge>
                </td>
                <td className="numeric px-3 py-2 text-ink-muted">
                  {formatDateTime(row.admittedAt)}
                </td>
                <td className="numeric px-3 py-2">{row.lengthOfStayDays} day(s)</td>
                <td className="numeric px-3 py-2 text-ink-muted">
                  {row.expectedDischarge ?? "—"}
                </td>
                <td className="px-3 py-2">
                  {freeBeds.length > 0 && (
                    <form action={transfer} className="flex flex-wrap items-center gap-1">
                      <input type="hidden" name="admissionId" value={row.id} />
                      <select
                        name="toBedId"
                        aria-label={`Move ${row.patientMrn} to`}
                        className="rounded border border-line bg-surface-raised px-1.5 py-1 text-xs"
                      >
                        {freeBeds.map((bed) => (
                          <option key={bed.bedId} value={bed.bedId}>
                            {bed.bedCode}
                          </option>
                        ))}
                      </select>
                      <input
                        name="reason"
                        required
                        placeholder="reason"
                        aria-label={`Reason for moving ${row.patientMrn}`}
                        className="w-32 rounded border border-line bg-surface-raised px-1.5 py-1 text-xs"
                      />
                      <button
                        type="submit"
                        className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
                      >
                        Move
                      </button>
                    </form>
                  )}
                </td>
                <td className="px-3 py-2">
                  <form action={discharge} className="flex items-center gap-1">
                    <input type="hidden" name="admissionId" value={row.id} />
                    <input
                      name="summary"
                      placeholder="summary"
                      aria-label={`Discharge summary for ${row.patientMrn}`}
                      className="w-32 rounded border border-line bg-surface-raised px-1.5 py-1 text-xs"
                    />
                    <button
                      type="submit"
                      className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
                    >
                      Discharge
                    </button>
                  </form>
                </td>
              </tr>
            ))}
          </Table>
        )}
        <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
          A move needs a reason and is kept as its own row rather than overwriting the bed —
          &ldquo;how many times was this patient moved overnight&rdquo; is an infection-control
          question that an overwritten bed code cannot answer. The two occupancy writes happen in
          one transaction, so there is no moment in which the patient is in two beds; if the
          destination has just been taken the whole move rolls back and the patient stays where
          they were.
        </p>
      </Card>

      <Card title="Bed map">
        {(beds.data ?? []).length === 0 ? (
          <Empty>No in-patient beds are configured.</Empty>
        ) : (
          <div className="space-y-4">
            {[...byRoom.entries()].map(([roomCode, roomBeds]) => (
              <div key={roomCode}>
                <h3 className="text-sm font-medium">
                  {roomBeds[0]?.roomName ?? roomCode}
                  <span className="numeric ml-2 text-xs text-ink-muted">{roomCode}</span>
                  <span className="ml-2 text-xs text-ink-muted">
                    {roomBeds.filter((bed) => !bed.occupied).length} of {roomBeds.length} free
                  </span>
                </h3>
                <div className="mt-2 flex flex-wrap gap-2">
                  {roomBeds.map((bed) => (
                    <span
                      key={bed.bedId}
                      title={
                        bed.occupied && bed.occupiedSince
                          ? `Occupied since ${formatDateTime(bed.occupiedSince)}`
                          : "Free"
                      }
                      className={`numeric rounded-md border px-3 py-2 text-xs ${
                        bed.occupied
                          ? "border-critical/40 bg-critical-soft text-critical"
                          : "border-good/40 bg-good-soft text-good"
                      }`}
                    >
                      {bed.bedCode}
                    </span>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}
