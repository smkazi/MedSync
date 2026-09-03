import { NextResponse } from "next/server";

/**
 * A see-other redirect after a form post.
 *
 * The Location is written as a path rather than a full URL. Next resolves it against the origin it
 * is serving on before the response leaves, so the header on the wire is absolute either way —
 * but writing it relative keeps this code from having to guess a host, which is what
 * `new URL(path, request.url)` amounts to when the app sits behind a proxy.
 *
 * Note that the resolved origin is Next's own, so the app must be reached at the host it is
 * configured with; a mismatched host loses the SameSite=Strict session cookie across the redirect.
 */
export function seeOther(path: string): NextResponse {
  return new NextResponse(null, { status: 303, headers: { Location: path } });
}

/** Where a sign-in lands when there is nowhere to resume to. */
const DASHBOARD = "/";

/**
 * A control character: anything below space, or delete.
 *
 * <p>Written as a scan rather than as a regular expression character class on purpose — a class
 * containing the literal bytes is unreadable in a diff and unreviewable in a code review, and the
 * one thing this check must be is obvious.
 *
 * <p>It is here for one reason above the others: the value it guards ends up in a `Location`
 * header, and a carriage return or newline in it would split that header. The whole range is
 * refused rather than that one pair, because none of it can occur in a path this app produced.
 */
function hasControlCharacter(value: string): boolean {
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    if (code < 0x20 || code === 0x7f) return true;
  }
  return false;
}

/**
 * Where to send somebody after they sign in, given the `next` the middleware wrote when it bounced
 * them.
 *
 * <p>Almost all of this is refusal, and that is the point. `next` travels in a URL, so it is
 * caller-supplied by definition: `/login?next=https://elsewhere.example` is the classic open
 * redirect, and it is worth more against a hospital's sign-in page than against most, because the
 * page it lands on can be a copy of this one that keeps whatever is typed into it. The parameter is
 * therefore treated as a *claim* about a path on this app, and anything that is not plainly one is
 * discarded in favour of the dashboard — never repaired, because a value worth repairing is a value
 * somebody built.
 *
 * <p>Three refusals are worth naming:
 *
 * <ul>
 *   <li>`//elsewhere.example` is protocol-relative and leaves this origin, as does `/\elsewhere` on
 *       any browser that normalises the backslash — so exactly one leading slash is required and a
 *       backslash anywhere is refused.
 *   <li>Whitespace and control characters, per {@link hasControlCharacter}.
 *   <li>`/api/**`, even though it is on this origin. Those are route handlers rather than pages,
 *       and `next=/api/auth/logout` would make signing in sign you straight back out — which reads
 *       as a broken login rather than as an attack, and is the more useful trick for it.
 * </ul>
 */
export function resumePath(raw: string | null | undefined): string {
  if (!raw || !raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\")) {
    return DASHBOARD;
  }
  if (/[\\\s]/.test(raw) || hasControlCharacter(raw)) return DASHBOARD;

  // Parsed against a throwaway origin so that anything which would change the origin — an
  // authority, a scheme, a stray host — shows up as a mismatch rather than having to be caught by
  // eye. What is returned is re-serialised from the parse, so the value used downstream is the
  // normalised one rather than the string that arrived.
  let url: URL;
  try {
    url = new URL(raw, "https://medsync.invalid");
  } catch {
    return DASHBOARD;
  }
  if (url.origin !== "https://medsync.invalid") return DASHBOARD;

  // Sign-in itself is not a destination: it is where the bounced request has just come from, and
  // resuming onto it is a loop.
  if (url.pathname === "/login" || url.pathname.startsWith("/api/")) return DASHBOARD;

  return `${url.pathname}${url.search}`;
}
