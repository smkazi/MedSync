import type { NextResponse } from "next/server";
import { seeOther } from "@/lib/redirect";
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

  if (!username || !password) {
    return seeOther("/login?error=Enter+a+username+and+password");
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
    return seeOther("/login?error=Cannot+reach+the+sign-in+service");
  }

  if (!response.ok) {
    // The platform deliberately returns the same message for an unknown user and a wrong
    // password; this passes it through rather than adding detail of its own.
    const problem = (await response.json().catch(() => null)) as { detail?: string } | null;
    const message = response.status === 423
      ? "Account locked after repeated failed attempts. Try again later."
      : (problem?.detail ?? "Sign-in failed");
    return seeOther(`/login?error=${encodeURIComponent(message)}`);
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

  return seeOther("/");
}
