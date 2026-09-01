import { cookies } from "next/headers";

/**
 * Session handling.
 *
 * The access token lives in an httpOnly cookie and is attached to platform calls **server-side
 * only**. It is never sent to the browser and never touches client JavaScript, so an XSS bug in a
 * dependency cannot read a clinician's token.
 */

const ACCESS_COOKIE = "medsync_at";
const REFRESH_COOKIE = "medsync_rt";
const USER_COOKIE = "medsync_user";

export type SessionUser = {
  id: string;
  username: string;
  fullName: string;
  roles: string[];
  mustChangePassword: boolean;
};

const cookieOptions = {
  httpOnly: true,
  sameSite: "strict" as const,
  secure: process.env.COOKIE_SECURE === "true",
  path: "/",
};

export async function storeSession(
  accessToken: string,
  refreshToken: string,
  expiresIn: number,
  user: SessionUser,
): Promise<void> {
  const store = await cookies();
  store.set(ACCESS_COOKIE, accessToken, { ...cookieOptions, maxAge: expiresIn });
  // The refresh token outlives the access token; it is the only way to get a new one.
  store.set(REFRESH_COOKIE, refreshToken, { ...cookieOptions, maxAge: 60 * 60 * 24 * 30 });
  // Readable by the server to render the UI. Not httpOnly-exempt: it holds no credential,
  // only who is signed in and what they may see.
  store.set(USER_COOKIE, JSON.stringify(user), { ...cookieOptions, maxAge: 60 * 60 * 24 * 30 });
}

export async function clearSession(): Promise<void> {
  const store = await cookies();
  for (const name of [ACCESS_COOKIE, REFRESH_COOKIE, USER_COOKIE]) {
    store.delete(name);
  }
}

export async function accessToken(): Promise<string | undefined> {
  return (await cookies()).get(ACCESS_COOKIE)?.value;
}

export async function refreshToken(): Promise<string | undefined> {
  return (await cookies()).get(REFRESH_COOKIE)?.value;
}

export async function currentUser(): Promise<SessionUser | null> {
  const raw = (await cookies()).get(USER_COOKIE)?.value;
  if (!raw) return null;
  try {
    return JSON.parse(raw) as SessionUser;
  } catch {
    // A cookie we cannot parse is treated as no session rather than crashing the page.
    return null;
  }
}

/** Whether the signed-in user holds any of the given roles. */
export function hasRole(user: SessionUser | null, ...roles: string[]): boolean {
  if (!user) return false;
  return user.roles.some((role) => roles.includes(role));
}

export const SESSION_COOKIES = { ACCESS_COOKIE, REFRESH_COOKIE, USER_COOKIE };
