import type { NextResponse } from "next/server";
import { seeOther } from "@/lib/redirect";
import { clearSession, refreshToken } from "@/lib/session";

/** Signs out, revoking the refresh token server-side rather than only forgetting it. */
export async function POST(): Promise<NextResponse> {
  const token = await refreshToken();
  if (token) {
    const identity = process.env.IDENTITY_URL ?? "http://localhost:8081";
    // Best effort: the local session is cleared either way, but revoking means a stolen
    // refresh token is dead rather than merely forgotten.
    await fetch(`${identity}/auth/logout`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken: token }),
      cache: "no-store",
    }).catch(() => undefined);
  }
  await clearSession();
  return seeOther("/login");
}
