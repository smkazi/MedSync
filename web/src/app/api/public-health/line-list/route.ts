import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import { apiBinary } from "@/lib/api";

/** The only two filters the line list accepts. Anything else in the query string is dropped. */
const FORWARDED = ["from", "to"] as const;

/**
 * The notifiable line list as the file that goes to the authority.
 *
 * <p><strong>This route is a write, whatever its verb says.</strong> Fetching it makes
 * scheduling-service register a disclosure against every patient the list names, and only then does
 * a file exist. It stays a GET because a browser download is a navigation and the service is the
 * authority on the ordering — but that is why the screen above it puts the warning next to the
 * button rather than treating this as another table export.
 *
 * <p>The 503 is worth passing through faithfully rather than flattening to "something went wrong":
 * it means the disclosure register could not record the notification, so nothing was produced and
 * the same request will work once the register is reachable. The service's own sentence says
 * exactly that, and it is what reaches the operator.
 */
export async function GET(request: NextRequest): Promise<NextResponse> {
  const wanted = new URLSearchParams();
  for (const key of FORWARDED) {
    const value = request.nextUrl.searchParams.get(key);
    if (value) wanted.set(key, value);
  }

  const query = wanted.toString();
  const result = await apiBinary(
    `/surveillance/notifiable/line-list.csv${query ? `?${query}` : ""}`,
    "text/csv",
  );
  if (!result.ok) {
    return NextResponse.json({ detail: result.detail }, { status: result.status });
  }
  return new NextResponse(result.bytes, {
    headers: {
      "Content-Type": "text/csv; charset=utf-8",
      "Content-Disposition":
        result.contentDisposition ?? `attachment; filename="notifiable-line-list.csv"`,
      // Harder-edged here than on the aggregate: a cached copy of a file naming patients, sitting
      // in a shared browser's disk cache, is a disclosure nobody registered.
      "Cache-Control": "no-store",
    },
  });
}
