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
