import { api, ApiError } from "@/lib/api";

/**
 * Fetches for a page that would rather render an error than crash.
 *
 * <p>Every list screen was repeating the same six-line try/catch around one `api()` call. That is
 * fine once and noise sixteen times, and the repetition invited the two mistakes this avoids:
 * swallowing the platform's own message in favour of a generic one, and letting a 403 render as a
 * broken page instead of an explanation.
 *
 * <p>An {@link ApiError} keeps its `detail`, because the services write those for people — "Casualty
 * (GF-CAS) cannot take a booking" is worth showing verbatim. Anything else is reported as a failure
 * without inventing detail it does not have.
 */
export async function load<T>(path: string): Promise<{ data: T | null; error: string | null }> {
  try {
    return { data: await api<T>(path), error: null };
  } catch (caught) {
    if (caught instanceof ApiError) {
      return {
        data: null,
        error:
          caught.status === 403
            ? "Your role does not have access to this."
            : caught.detail,
      };
    }
    return { data: null, error: caught instanceof Error ? caught.message : "Request failed" };
  }
}
