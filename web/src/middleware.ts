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
    pathname === "/login" || pathname.startsWith("/api/auth") || pathname.startsWith("/_next");

  /*
   * A request with no session cookie is redirected to sign-in before any page renders. This is a
   * convenience, not the security boundary: every service independently validates the bearer
   * token, so a forged cookie buys nothing.
   */
  const hasSession = request.cookies.has("medsync_at");
  if (!hasSession && !isPublic) {
    const url = new URL("/login", request.url);
    url.searchParams.set("next", pathname);
    return NextResponse.redirect(url);
  }
  if (hasSession && pathname === "/login") {
    return NextResponse.redirect(new URL("/", request.url));
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
