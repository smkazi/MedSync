import type { PublicQueueBoard } from "@/lib/types";

/**
 * The waiting-room display.
 *
 * <p>A screen mounted in a corridor, so everything about this page is decided by who can see it:
 * every visitor, delivery driver and passer-by in the building. It shows a room code, the number
 * being called, and the next few waiting. It shows no name, no MRN, no clinician, no department and
 * no count of how many people are waiting — that last one because "you are fourteenth" plus a
 * visible arrival order is enough for a stranger to work out who is who.
 *
 * <p>That is not enforced here. It is enforced by `GET /public/queue/{room}` returning a type with
 * nowhere to put any of it, which is the difference between a rule and a habit.
 *
 * <p><strong>No session, no navigation, nothing clickable.</strong> The kiosk browser has no
 * clinician signed in and never will, and a display with a link on it is a display somebody
 * navigates away from and nobody notices for a week. It sits outside the `(app)` route group so it
 * inherits none of the application's chrome, and the middleware allowlists `/display` so it is not
 * bounced to a sign-in page.
 *
 * <p>Refreshed by `<meta http-equiv="refresh">` rather than JavaScript: a corridor screen that
 * stops updating because a hydration error scrolled past at 6am is worse than one that reloads a
 * whole page every fifteen seconds, and the page is four numbers.
 */
export default async function DisplayPage({ params }: { params: Promise<{ roomCode: string }> }) {
  const { roomCode } = await params;

  // Called directly rather than through `api()`, which attaches the session cookie: there is no
  // session here, and the endpoint takes no token. `load()` is not used either - it is fine, but
  // this page's failure mode has to be "keep showing the last thing" rather than an error card.
  let board: PublicQueueBoard | null = null;
  try {
    const response = await fetch(
      `${process.env.GATEWAY_URL ?? "http://localhost:8080"}/public/queue/${encodeURIComponent(roomCode)}`,
      { cache: "no-store" },
    );
    if (response.ok) {
      board = (await response.json()) as PublicQueueBoard;
    }
  } catch {
    // Swallowed on purpose. A corridor screen showing a stack trace, or the word "error", tells a
    // waiting room that the hospital's computers are broken. Showing the room code and a dash
    // says nothing untrue and is what a paper sign would have done.
  }

  const room = board?.roomCode ?? roomCode.toUpperCase();

  return (
    // A <main>, not a <div>, and it is the page's own rather than the application's: this route
    // sits outside the `(app)` group precisely so it inherits no content column. A page with no
    // main landmark is a page a screen reader has to guess at — and it is also what the menu
    // link-sweep looks for to tell "rendered" from "the server threw", so the tag earns its keep
    // twice.
    <main className="flex min-h-dvh flex-col items-center justify-center gap-12 bg-surface px-8 py-12">
      {/* 15 seconds: fast enough that somebody sitting down sees their number appear, slow enough
          that a screen in every corridor is not a load pattern. The endpoint sets a 10-second
          cache header for the same reason. */}
      <meta httpEquiv="refresh" content="15" />

      <h1 className="numeric text-center text-5xl font-semibold tracking-tight text-ink-muted">
        {room}
      </h1>

      <section className="text-center" aria-label="Now serving">
        <p className="text-3xl font-medium uppercase tracking-widest text-ink-muted">Now serving</p>
        <p className="numeric mt-4 text-[14rem] font-bold leading-none text-accent">
          {board?.nowServing ?? "—"}
        </p>
      </section>

      {(board?.upcoming.length ?? 0) > 0 && (
        <section className="text-center" aria-label="Next">
          <p className="text-2xl font-medium uppercase tracking-widest text-ink-muted">Next</p>
          <p className="numeric mt-3 flex flex-wrap justify-center gap-8 text-6xl font-semibold">
            {board?.upcoming.map((number) => (
              <span key={number}>{number}</span>
            ))}
          </p>
        </section>
      )}

      {board && board.nowServing === null && board.upcoming.length === 0 && (
        <p className="text-2xl text-ink-muted">No patients waiting.</p>
      )}
    </main>
  );
}
