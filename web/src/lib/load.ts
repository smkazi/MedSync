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

/**
 * Every page of a paged collection, concatenated.
 *
 * <p>For pick-lists, and only for pick-lists. A `<select>` of clinicians or logins has to contain
 * the row somebody is looking for or the screen is simply wrong — there is no "next page" inside a
 * dropdown — and the platform caps a page at 100 rows whatever `size` a caller asks for
 * (`Math.min(size, 100)` in the controllers). So `?size=200` does not mean what it looks like it
 * means: it returns the first hundred and drops the rest silently.
 *
 * <p>That is not theoretical. A browser test creating one account per run tipped a development
 * database past a hundred logins, and the staff screen then could not link a staff record to any
 * account whose username sorted after the hundredth. The screen had been green for weeks and was
 * wrong for any hospital with more than a hundred staff.
 *
 * <p>Bounded at {@link MAX_PICKLIST_PAGES} pages rather than trusting the server's own count: a
 * dropdown with two thousand options is a broken screen anyway, and this way a paging bug cannot
 * turn one render into an unbounded loop. Requests are sequential because they are cheap and
 * because the total is not known until the first answer arrives.
 */
const MAX_PICKLIST_PAGES = 20;

export async function loadAll<T>(
  path: string,
): Promise<{ data: T[] | null; error: string | null }> {
  const joiner = path.includes("?") ? "&" : "?";
  const rows: T[] = [];
  for (let page = 0; page < MAX_PICKLIST_PAGES; page++) {
    const answer = await load<{ content: T[]; totalPages: number }>(
      `${path}${joiner}page=${page}&size=100`,
    );
    if (answer.error) return { data: null, error: answer.error };
    rows.push(...(answer.data?.content ?? []));
    if (page + 1 >= (answer.data?.totalPages ?? 1)) break;
  }
  return { data: rows, error: null };
}
