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
    /**
     * The parsed error body, verbatim.
     *
     * Kept because not every failure fits the platform's `ApiError` problem shape. The duplicate
     * patient 409 answers with `{message, candidates}` — the candidate charts are the entire point
     * of that response, and folding it to a `detail` string would throw them away and leave the
     * front desk with "this looks like a duplicate" and no way to look.
     */
    readonly body?: unknown,
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
      payload,
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

/**
 * Fetches a binary resource from the gateway, on the server, for a browser to download.
 *
 * <p>The same reason {@link apiText} exists: the bearer token lives in an httpOnly cookie the
 * browser never sees, so an `<a href="/portal/reports/x.pdf">` pointed at the gateway would arrive
 * unauthenticated. The portal's download links point at a route handler in this app, which calls
 * this and streams the bytes back with the platform's own content type and filename.
 *
 * <p>Returns the status and body on a failure rather than throwing, because a route handler has to
 * answer with a status code and not with an exception page.
 */
export async function apiBinary(
  path: string,
  accept = "*/*",
): Promise<
  | { ok: true; bytes: ArrayBuffer; contentType: string; contentDisposition: string | null }
  | { ok: false; status: number; detail: string }
> {
  const token = await accessToken();
  const response = await fetch(`${GATEWAY}${path}`, {
    headers: {
      Accept: accept,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    cache: "no-store",
  });
  if (!response.ok) {
    const problem = safeParse(await response.text()) as { detail?: string; title?: string } | undefined;
    return {
      ok: false,
      status: response.status,
      detail: problem?.detail ?? problem?.title ?? `Request failed (${response.status})`,
    };
  }
  return {
    ok: true,
    bytes: await response.arrayBuffer(),
    contentType: response.headers.get("content-type") ?? "application/octet-stream",
    contentDisposition: response.headers.get("content-disposition"),
  };
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
