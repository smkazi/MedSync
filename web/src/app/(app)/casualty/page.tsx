import Link from "next/link";
import { load } from "@/lib/load";
import type { BedState, CasualtyAttendance, Page as PageResponse, PatientSummary } from "@/lib/types";
import { RecordForm } from "@/components/RecordForm";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDateTime } from "@/components/ui";
import { ACUITIES, TARGET_MINUTES } from "./state";
import {
  dischargeFromCasualty,
  leftWithoutBeingSeen,
  placeInBed,
  recordArrival,
  retriage,
} from "./actions";

/**
 * The casualty board.
 *
 * <p>Ordered sickest first, and that ordering is not this page's choice: it comes from the query in
 * admissions-service, which has no sort parameter. A casualty queue served in the order people
 * arrived kills the person who arrived last and is the sickest, and a board that could be re-sorted
 * by a column header would be a board somebody sorts by arrival on a busy night.
 *
 * <p>What the colour means is the wait against the level's own target, not the wait itself. Two
 * hours is fine for a level 5 and a catastrophe for a level 2, so a board coloured by minutes alone
 * would shout at the wrong patients.
 */
export default async function CasualtyPage({
  searchParams,
}: {
  searchParams: Promise<{ problem?: string; done?: string; mrn?: string }>;
}) {
  const { problem, done, mrn = "" } = await searchParams;

  const [board, beds, patients] = await Promise.all([
    load<CasualtyAttendance[]>("/casualty"),
    load<BedState[]>("/casualty/beds"),
    mrn
      ? load<PageResponse<PatientSummary>>(`/patients?q=${encodeURIComponent(mrn)}&size=10`)
      : Promise.resolve({ data: null, error: null }),
  ]);

  const waiting = (board.data ?? []).filter((row) => row.status === "WAITING");
  const inBed = (board.data ?? []).filter((row) => row.status === "IN_BED");
  const freeBeds = (beds.data ?? []).filter((bed) => !bed.occupied);
  const breaching = (board.data ?? []).filter(
    (row) => row.waitingMinutes > (TARGET_MINUTES[row.triageAcuity] ?? 240),
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Casualty board</h1>
        <p className="text-sm text-ink-muted">
          Everybody in the department, sickest first. Ties go to whoever has waited longest.
        </p>
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
      {board.error && <ErrorNote>{board.error}</ErrorNote>}
      {beds.error && <ErrorNote>{beds.error}</ErrorNote>}

      <div className="grid gap-4 sm:grid-cols-4">
        <Stat label="Waiting" value={waiting.length} hint="triaged, no bed yet" />
        <Stat label="In a bay" value={inBed.length} hint="being seen" />
        <Stat
          label="Free bays"
          value={freeBeds.length}
          hint={`of ${(beds.data ?? []).length}`}
        />
        <Stat
          label="Over target"
          value={breaching.length}
          hint="waited longer than their level allows"
        />
      </div>

      <Card title="The department">
        {(board.data ?? []).length === 0 ? (
          <Empty>Nobody is in the department.</Empty>
        ) : (
          <Table
            head={["Acuity", "Complaint", "MRN", "Arrived", "Waited", "Where", "", ""]}
          >
            {(board.data ?? []).map((row) => {
              const target = TARGET_MINUTES[row.triageAcuity] ?? 240;
              const over = row.waitingMinutes > target;
              return (
                <tr key={row.id} className={row.triageAcuity <= 2 ? "bg-critical-soft/30" : ""}>
                  <td className="px-3 py-2">
                    {/* Coloured by level, not by wait: an acuity 1 is red the moment they arrive. */}
                    <Badge
                      tone={
                        row.triageAcuity === 1
                          ? "critical"
                          : row.triageAcuity === 2
                            ? "warn"
                            : "neutral"
                      }
                    >
                      {row.triageAcuity}
                    </Badge>
                  </td>
                  <td className="px-3 py-2">{row.presentingComplaint}</td>
                  <td className="numeric px-3 py-2">
                    <Link
                      href={`/patients/${row.patientId}`}
                      className="text-accent hover:underline"
                    >
                      {row.patientMrn}
                    </Link>
                  </td>
                  <td className="numeric px-3 py-2 text-ink-muted">
                    {formatDateTime(row.arrivedAt)}
                  </td>
                  <td className="numeric px-3 py-2">
                    <span className={over ? "font-semibold text-critical" : ""}>
                      {row.waitingMinutes} min
                    </span>
                    {/* The target, so the number means something. Two hours is fine for a level 5
                        and a catastrophe for a level 2. */}
                    <span className="ml-1 text-xs text-ink-muted">/ {target}</span>
                  </td>
                  <td className="px-3 py-2">
                    {row.bedCode ? (
                      <span className="numeric">
                        {row.bedCode}
                        <span className="ml-1 text-xs text-ink-muted">{row.roomCode}</span>
                      </span>
                    ) : (
                      <span className="text-xs text-ink-muted">waiting</span>
                    )}
                  </td>
                  <td className="px-3 py-2">
                    {row.status === "WAITING" && freeBeds.length > 0 && (
                      <form action={placeInBed} className="flex items-center gap-1">
                        <input type="hidden" name="attendanceId" value={row.id} />
                        <select
                          name="bedId"
                          aria-label={`Bay for ${row.patientMrn}`}
                          className="rounded border border-line bg-surface-raised px-1.5 py-1 text-xs"
                        >
                          {freeBeds.map((bed) => (
                            <option key={bed.bedId} value={bed.bedId}>
                              {bed.bedCode}
                            </option>
                          ))}
                        </select>
                        <button
                          type="submit"
                          className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
                        >
                          Place
                        </button>
                      </form>
                    )}
                  </td>
                  <td className="px-3 py-2">
                    <div className="flex flex-wrap items-center gap-1">
                      <form action={retriage} className="flex items-center gap-1">
                        <input type="hidden" name="attendanceId" value={row.id} />
                        <select
                          name="triageAcuity"
                          defaultValue={String(row.triageAcuity)}
                          aria-label={`Re-triage ${row.patientMrn}`}
                          className="rounded border border-line bg-surface-raised px-1.5 py-1 text-xs"
                        >
                          {ACUITIES.map((acuity) => (
                            <option key={acuity.value} value={acuity.value}>
                              {acuity.value}
                            </option>
                          ))}
                        </select>
                        <button
                          type="submit"
                          className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
                        >
                          Re-triage
                        </button>
                      </form>
                      <form action={dischargeFromCasualty}>
                        <input type="hidden" name="attendanceId" value={row.id} />
                        <button
                          type="submit"
                          className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
                        >
                          Discharge
                        </button>
                      </form>
                      <form action={leftWithoutBeingSeen}>
                        <input type="hidden" name="attendanceId" value={row.id} />
                        <button
                          type="submit"
                          className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
                        >
                          Left
                        </button>
                      </form>
                      <Link
                        href={`/admissions?admit=${row.id}`}
                        className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
                      >
                        Admit
                      </Link>
                    </div>
                  </td>
                </tr>
              );
            })}
          </Table>
        )}
        <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
          There is no way to sort this table. The order is the service&apos;s — acuity, then arrival
          — and a column somebody could sort by arrival on a busy night would defeat the point of
          triage. <strong>Left</strong> records that a patient gave up and went home, which is a
          standard quality metric and deliberately not the same outcome as a discharge.
        </p>
      </Card>

      <Card title="Somebody has arrived">
        <form className="mb-4 flex flex-wrap items-end gap-3">
          <div className="grow">
            <label htmlFor="mrn" className="block text-sm font-medium">
              Find the patient
            </label>
            <input
              id="mrn"
              name="mrn"
              defaultValue={mrn}
              placeholder="MRN or surname"
              className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
            />
          </div>
          <button
            type="submit"
            className="rounded-md border border-line px-4 py-2 text-sm font-medium hover:bg-surface"
          >
            Search
          </button>
        </form>

        {patients.data && patients.data.content.length === 0 && (
          <Empty>No patient matches “{mrn}”. Register them first.</Empty>
        )}

        {patients.data && patients.data.content.length > 0 && (
          <RecordForm
            action={recordArrival}
            columns={2}
            submitLabel="Triage and admit to the board"
            busyLabel="Recording…"
            fields={[
              {
                name: "patientId",
                label: "Patient",
                type: "select",
                required: true,
                options: patients.data.content.map((patient) => ({
                  value: patient.id,
                  label: `${patient.fullName} — ${patient.mrn}`,
                })),
              },
              {
                name: "patientMrn",
                label: "MRN",
                required: true,
                hint: "Cached on the attendance, so the board reads without a patient lookup.",
              },
              {
                name: "triageAcuity",
                label: "Triage level",
                type: "select",
                required: true,
                options: ACUITIES,
                hint: "Required, with no default. An untriaged patient sorted as though they were a 3 is exactly what this board exists to prevent.",
              },
              {
                name: "presentingComplaint",
                label: "Presenting complaint",
                required: true,
                hint: "In the patient's own words where possible. It goes on the board, not in the audit trail.",
              },
            ]}
          />
        )}
      </Card>
    </div>
  );
}
