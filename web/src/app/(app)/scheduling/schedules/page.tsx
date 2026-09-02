import { load } from "@/lib/load";
import type { Page, Staff } from "@/lib/types";
import { Card, Empty, ErrorNote, Table } from "@/components/ui";

type Schedule = {
  id: string;
  clinicianId: string;
  dayOfWeek: string;
  startTime: string;
  endTime: string;
  slotMinutes: number;
  active: boolean;
};

/**
 * A clinician's working pattern.
 *
 * <p>This screen is narrower than it should be, and the reason is worth stating rather than hiding:
 * the API can create a schedule and read one clinician's, but there is no list-all endpoint, no
 * update and no delete — so the screen asks for a clinician rather than showing the rota. Blackouts
 * are worse: they can be created and never read, so this page cannot show what time is blocked out.
 * Both are gaps in scheduling-service, not omissions here.
 */
export default async function SchedulesPage({
  searchParams,
}: {
  searchParams: Promise<{ clinicianId?: string }>;
}) {
  const { clinicianId = "" } = await searchParams;

  const { data: staff } = await load<Page<Staff>>("/staff?size=100");
  const clinicians = (staff?.content ?? []).filter((member) => member.userId && member.active);

  let schedules: Schedule[] | null = null;
  let error: string | null = null;
  if (clinicianId) {
    const result = await load<Schedule[]>(
      `/schedules/clinicians/${encodeURIComponent(clinicianId)}`,
    );
    schedules = result.data;
    error = result.error;
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Clinician schedules</h1>
        <p className="text-sm text-ink-muted">
          The working windows availability is generated from.
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
              </option>
            ))}
          </select>
        </div>
        <button
          type="submit"
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
        >
          Show
        </button>
      </form>

      {error && <ErrorNote>{error}</ErrorNote>}

      {!clinicianId && <Empty>Choose a clinician to see their working pattern.</Empty>}

      {schedules && (
        <Card title={`Working windows (${schedules.length})`}>
          {schedules.length === 0 ? (
            <Empty>No working pattern is configured for this clinician.</Empty>
          ) : (
            <Table head={["Day", "From", "To", "Slot length"]}>
              {schedules.map((schedule) => (
                <tr key={schedule.id} className={schedule.active ? "" : "opacity-60"}>
                  <td className="px-3 py-2 font-medium">{schedule.dayOfWeek}</td>
                  <td className="numeric px-3 py-2">{schedule.startTime}</td>
                  <td className="numeric px-3 py-2">{schedule.endTime}</td>
                  <td className="numeric px-3 py-2 text-ink-muted">{schedule.slotMinutes} min</td>
                </tr>
              ))}
            </Table>
          )}
        </Card>
      )}

      <Card title="What this screen cannot do">
        <ul className="list-disc space-y-1 pl-5 text-sm text-ink-muted">
          <li>
            There is no list-all endpoint, so the rota cannot be shown across clinicians — you have
            to pick one.
          </li>
          <li>A working window cannot be edited or removed once created.</li>
          <li>
            Blackouts can be created but never listed or cancelled, so this page cannot tell you what
            time is currently blocked out.
          </li>
        </ul>
      </Card>
    </div>
  );
}
