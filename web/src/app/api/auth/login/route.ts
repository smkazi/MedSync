import type { NextResponse } from "next/server";
import { resumePath, seeOther } from "@/lib/redirect";
import { storeSession } from "@/lib/session";

/**
 * Exchanges credentials for a session.
 *
 * The token exchange happens here, on the server, and the tokens are written to httpOnly cookies.
 * The browser receives only a redirect — never a token — so client-side script has nothing to
 * steal.
 */
export async function POST(request: Request): Promise<NextResponse> {
  const form = await request.formData();
  const username = String(form.get("username") ?? "").trim();
  const password = String(form.get("password") ?? "");
  // Where to land. Validated here rather than trusted from the form, because a form field is no
  // harder to set than a query parameter — the page validating it before rendering it is about not
  // echoing a hostile value back, and this is the check that decides where anybody actually goes.
  const resume = resumePath(form.get("next")?.toString());

  // A mistyped password must not cost the destination: without this, one wrong attempt sends the
  // second, successful one to the dashboard, and the bounce might as well not have remembered.
  const refused = (message: string): NextResponse => {
    const query = new URLSearchParams({ error: message });
    if (resume !== "/") query.set("next", resume);
    return seeOther(`/login?${query}`);
  };

  if (!username || !password) {
    return refused("Enter a username and password");
  }

  const identity = process.env.IDENTITY_URL ?? "http://localhost:8081";
  let response: Response;
  try {
    response = await fetch(`${identity}/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
      cache: "no-store",
    });
  } catch {
    return refused("Cannot reach the sign-in service");
  }

  if (!response.ok) {
    // The platform deliberately returns the same message for an unknown user and a wrong
    // password; this passes it through rather than adding detail of its own.
    const problem = (await response.json().catch(() => null)) as { detail?: string } | null;
    const message = response.status === 423
      ? "Account locked after repeated failed attempts. Try again later."
      : (problem?.detail ?? "Sign-in failed");
    return refused(message);
  }

  const session = (await response.json()) as {
    accessToken: string;
    refreshToken: string;
    expiresIn: number;
    user: {
      id: string;
      username: string;
      fullName: string;
      roles: string[];
      mustChangePassword: boolean;
    };
  };

  await storeSession(session.accessToken, session.refreshToken, session.expiresIn, {
    id: session.user.id,
    username: session.user.username,
    fullName: session.user.fullName,
    roles: session.user.roles,
    mustChangePassword: session.user.mustChangePassword,
  });

  // Back where they were, if the middleware said where that was. A patient whose `next` is a
  // clinical path still lands in the portal: the middleware routes them there on the way through,
  // and this deliberately does not try to second-guess it here — one place decides which door each
  // account uses, and it is not this one.
  return seeOther(resume);
}
