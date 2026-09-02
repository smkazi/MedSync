import Link from "next/link";
import { load } from "@/lib/load";
import type { BookableRoom, QueueBoard } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDateTime } from "@/components/ui";

/**
 * The OPD token queue, as the desk and the consulting room see it.
 *
 * <p>The numbers come from the appointment lifecycle rather than from anything anybody maintains: a
 * token is issued at check-in and called when the consultation starts. So this screen has no
 * buttons — moving the queue along is what the appointment book's Check in and Start already do,
 * and a second set of controls would be a second source of truth about the same morning.
 *
 * <p>What it does have is the link to the corridor display, because somebody has to open that on
 * the kiosk once and the URL is otherwise a thing to be told.
 */
export default async function QueuePage({
  searchParams,
}: {
  searchParams: Promise<{ room?: string; date?: string }>;
}) {
  const { room = "", date = "" } = await searchParams;
  const roomCode = room.trim().toUpperCase();

  const [rooms, board] = await Promise.all([
    // `/rooms/bookable` rather than `/rooms?bookable=true`: the latter is a paged response and
    // the filter is not a parameter it takes, so it answered every room in the building wrapped in
    // a page — which rendered as a server error the first time this screen was opened. The
    // bookable list is the picker the booking screen already uses.
    load<BookableRoom[]>("/rooms/bookable"),
    roomCode
      ? load<QueueBoard>(`/queue/${encodeURIComponent(roomCode)}${date ? `?date=${date}` : ""}`)
      : Promise.resolve({ data: null, error: null }),
  ]);

  const shown = board.data;
  const tokens = shown?.tokens ?? [];
  const waiting = tokens.filter((token) => token.status === "WAITING");
  const done = tokens.filter((token) => token.status === "DONE");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">OPD token queue</h1>
        <p className="text-sm text-ink-muted">
          Numbers issued at check-in and called when the consultation begins.
        </p>
      </div>

      <form className="flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="room" className="block text-sm font-medium">
            Room
          </label>
          <select
            id="room"
            name="room"
            defaultValue={roomCode}
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          >
            <option value="">—</option>
            {(rooms.data ?? []).map((option) => (
              <option key={option.code} value={option.code}>
                {option.code} — {option.name}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="date" className="block text-sm font-medium">
            Day
          </label>
          <input
            id="date"
            name="date"
            type="date"
            defaultValue={date}
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
        </div>
        <button
          type="submit"
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
        >
          Show queue
        </button>
      </form>

      {rooms.error && <ErrorNote>{rooms.error}</ErrorNote>}
      {board.error && <ErrorNote>{board.error}</ErrorNote>}

      {!roomCode && <Empty>Pick a room to see its queue.</Empty>}

      {shown && (
        <>
          <div className="grid gap-4 sm:grid-cols-3">
            <Stat
              label="Now serving"
              value={shown.nowServing ?? "—"}
              hint={shown.nowServing === null ? "nothing called yet" : "called in the corridor"}
            />
            <Stat label="Waiting" value={waiting.length} hint="checked in, not yet called" />
            <Stat label="Seen" value={done.length} hint="consultation finished" />
          </div>

          <Card
            title={`${shown.roomCode} — ${shown.serviceDate}`}
            action={
              <Link
                href={`/display/${shown.roomCode}`}
                target="_blank"
                rel="noopener"
                className="rounded border border-line px-3 py-1 text-xs hover:bg-surface"
              >
                Open the corridor display
              </Link>
            }
          >
            {tokens.length === 0 ? (
              <Empty>Nobody has checked in for this room today.</Empty>
            ) : (
              <Table head={["Number", "Status", "Issued", "Called", ""]}>
                {tokens.map((token) => (
                  <tr key={token.tokenNumber} className={token.status === "DONE" ? "opacity-60" : ""}>
                    <td className="numeric px-3 py-2 text-lg font-semibold">{token.tokenNumber}</td>
                    <td className="px-3 py-2">
                      <Badge
                        tone={
                          token.status === "CALLED"
                            ? "accent"
                            : token.status === "WAITING"
                              ? "warn"
                              : "neutral"
                        }
                      >
                        {token.status.toLowerCase()}
                      </Badge>
                    </td>
                    <td className="numeric px-3 py-2 text-ink-muted">
                      {formatDateTime(token.issuedAt)}
                    </td>
                    <td className="numeric px-3 py-2 text-ink-muted">
                      {token.calledAt ? formatDateTime(token.calledAt) : "—"}
                    </td>
                    <td className="px-3 py-2">
                      {/* The one thing this board has that the corridor's does not: a way from a
                          number back to a person. It is why the two are different endpoints. */}
                      <Link
                        href={`/appointments?from=${shown.serviceDate}&to=${shown.serviceDate}`}
                        className="text-xs text-accent hover:underline"
                      >
                        Find in the book
                      </Link>
                    </td>
                  </tr>
                ))}
              </Table>
            )}
            <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
              There are no buttons here on purpose. A number is issued by <strong>Check in</strong>{" "}
              and called by <strong>Start</strong> in the appointment book, so the queue cannot drift
              out of step with the appointments it is a queue of. The corridor display shows the same
              numbers and nothing else — no name, no MRN, not even how many people are waiting.
            </p>
          </Card>
        </>
      )}
    </div>
  );
}
