import { accessToken } from "@/lib/session";

/**
 * Server-side client for the platform gateway.
 *
 * Every call runs on the server and attaches the session's bearer token from the httpOnly
 * cookie. The browser never talks to the gateway directly, so it never needs — or receives — a
 * token.
 */

const GATEWAY = process.env.GATEWAY_URL ?? "http://localhost:8080";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly detail: string,
    readonly fieldErrors?: Record<string, string>,
  ) {
    super(detail);
    this.name = "ApiError";
  }
}

type RequestOptions = {
  method?: string;
  body?: unknown;
  /** Server components render per request; platform data must never be cached across users. */
  revalidate?: number | false;
};

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const token = await accessToken();
  const response = await fetch(`${GATEWAY}${path}`, {
    method: options.method ?? "GET",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    cache: "no-store",
  });

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  const payload = text ? safeParse(text) : undefined;

  if (!response.ok) {
    const problem = payload as
      | { detail?: string; title?: string; errors?: Record<string, string>; message?: string }
      | undefined;
    throw new ApiError(
      response.status,
      problem?.detail ?? problem?.message ?? problem?.title ?? `Request failed (${response.status})`,
      problem?.errors,
    );
  }
  return payload as T;
}

/**
 * Fetches a non-JSON resource from the gateway as text, on the server.
 *
 * Exists for the specimen label, which the gateway returns as SVG. It has to be fetched server-side
 * like everything else: the bearer token lives in an httpOnly cookie and the browser never sees it,
 * so an `<img src="/lab/...">` would arrive unauthenticated. The caller inlines the markup instead.
 */
export async function apiText(path: string, accept = "*/*"): Promise<string> {
  const token = await accessToken();
  const response = await fetch(`${GATEWAY}${path}`, {
    headers: {
      Accept: accept,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    cache: "no-store",
  });
  const text = await response.text();
  if (!response.ok) {
    const problem = safeParse(text) as { detail?: string; title?: string } | undefined;
    throw new ApiError(
      response.status,
      problem?.detail ?? problem?.title ?? `Request failed (${response.status})`,
    );
  }
  return text;
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return { detail: text };
  }
}

/** True when a failure is the session's rather than the request's. */
export function isAuthError(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 401 || error.status === 403);
}
