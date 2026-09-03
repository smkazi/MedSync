import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

/**
 * Route guard and Content-Security-Policy.
 *
 * The CSP lives here because it needs a per-request nonce. Next emits an inline bootstrap script,
 * so a policy of `script-src 'self'` alone silently blocks hydration and every client component
 * stops working — with no console error a server-side test would notice. Next reads the nonce from
 * the CSP on the incoming request and stamps it onto its own script tags; anything injected
 * without that nonce still cannot execute.
 */
function contentSecurityPolicy(nonce: string): string {
  return [
    "default-src 'self'",
    // strict-dynamic lets scripts the nonced bootstrap loads run, without opening the door to
    // arbitrary inline script.
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'`,
    // Tailwind emits a stylesheet, but Next still inlines critical styles.
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self'",
    // The browser only ever talks to this origin; the gateway is called server-side.
    "connect-src 'self'",
    "frame-ancestors 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "object-src 'none'",
  ].join("; ");
}

/**
 * Whether the signed-in account is a patient rather than a member of staff.
 *
 * <p>Read off the session cookie for the same reason {@link owesAPasswordChange} is, and with the
 * same caveat: this is routing, not authorisation. Forging the cookie either way buys a fully drawn
 * UI in which every request comes back 403, because the roles that matter are the ones signed into
 * the bearer token and every portal endpoint in five services checks them independently.
 */
function isPatientSession(request: NextRequest): boolean {
  const raw = request.cookies.get("medsync_user")?.value;
  if (!raw) return false;
  try {
    return ((JSON.parse(raw) as { roles?: string[] }).roles ?? []).includes("PATIENT");
  } catch {
    return false;
  }
}

/**
 * Whether this session still owes a password change.
 *
 * <p>Read off the session cookie rather than by asking the platform, because the middleware runs
 * on every request and a network call there would tax every navigation. It is not the security
 * boundary either way: such an account holds a token minted with no roles, so the platform refuses
 * it everywhere regardless of what this cookie says. Forging the cookie to say `false` buys a
 * fully drawn UI in which every single request comes back 403.
 */
function owesAPasswordChange(request: NextRequest): boolean {
  const raw = request.cookies.get("medsync_user")?.value;
  if (!raw) return false;
  try {
    return (JSON.parse(raw) as { mustChangePassword?: boolean }).mustChangePassword === true;
  } catch {
    return false;
  }
}

export function middleware(request: NextRequest): NextResponse {
  const { pathname } = request.nextUrl;
  const isPublic =
    pathname === "/login"
    || pathname.startsWith("/api/auth")
    || pathname.startsWith("/_next")
    // The waiting-room display. A kiosk browser in a corridor has no clinician signed in and never
    // will, so bouncing it to /login would leave a sign-in form on the wall. What it renders comes
    // from the platform's one unauthenticated endpoint, which returns a room code and some numbers
    // - there is nothing behind this exemption to protect.
    || pathname.startsWith("/display/");

  /*
   * A request with no session cookie is redirected to sign-in before any page renders. This is a
   * convenience, not the security boundary: every service independently validates the bearer
   * token, so a forged cookie buys nothing.
   */
  const hasSession = request.cookies.has("medsync_at");
  if (!hasSession && !isPublic) {
    const url = new URL("/login", request.url);
    url.searchParams.set("next", pathname);
    // Say which of the two things happened. The access cookie lives fifteen minutes and the
    // refresh cookie thirty days, so a request carrying the second without the first is a session
    // that ran out rather than someone who never signed in. Without this the app simply vanishes
    // mid-sentence and the platform gets blamed for losing the user's work.
    if (request.cookies.has("medsync_rt")) {
      url.searchParams.set("error", "Your session timed out. Please sign in again.");
    }
    return NextResponse.redirect(url);
  }
  if (hasSession && pathname === "/login") {
    return NextResponse.redirect(new URL(isPatientSession(request) ? "/portal" : "/", request.url));
  }

  // An account on its initial password is sent to the one screen it can use. Signing out has to
  // stay reachable, or the only way off this screen would be to clear cookies by hand.
  if (
    hasSession
    && !isPublic
    && pathname !== "/change-password"
    && owesAPasswordChange(request)
  ) {
    return NextResponse.redirect(new URL("/change-password", request.url));
  }

  // Two doors, and neither opens onto the other. A patient signing in lands in the portal and is
  // sent back to it if they type a clinical path; a member of staff typing /portal is shown the
  // portal layout's explanation rather than a redirect loop, because an administrator following a
  // link a patient sent them should be told what happened.
  //
  // Redirect rather than 403 for the patient direction, deliberately: the platform will refuse the
  // request anyway, and a patient who followed an old bookmark to /appointments is better served by
  // their own appointments page than by an error. Change-password and sign-out stay reachable from
  // either side, which is why they are exempt.
  const patientOnly = pathname === "/portal" || pathname.startsWith("/portal/");
  if (
    hasSession
    && !isPublic
    && !patientOnly
    && pathname !== "/change-password"
    && isPatientSession(request)
  ) {
    return NextResponse.redirect(new URL("/portal", request.url));
  }

  const nonce = crypto.randomUUID();
  const csp = contentSecurityPolicy(nonce);

  // Set on the request so Next can apply the nonce to its own scripts, and on the response so the
  // browser enforces it.
  const headers = new Headers(request.headers);
  headers.set("Content-Security-Policy", csp);
  headers.set("x-nonce", nonce);

  const response = NextResponse.next({ request: { headers } });
  response.headers.set("Content-Security-Policy", csp);
  return response;
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
