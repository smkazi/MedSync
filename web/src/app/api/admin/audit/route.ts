import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import { apiBinary } from "@/lib/api";

/** The filters the export accepts. Anything else in the query string is ignored, not forwarded. */
const FORWARDED = ["entity", "action", "actorId", "username", "from", "to"] as const;

/**
 * The audit report as a CSV file.
 *
 * <p>Streamed through this app rather than linked at the gateway, for the reason every download on
 * this platform is: the access token lives in an httpOnly cookie the browser will not attach to a
 * cross-origin link.
 *
 * <p>Only the report's own filters are forwarded. Passing the query string through verbatim would
 * let a crafted link add `size=1000000` or any future parameter the endpoint grows, and the point
 * of a proxy is that it knows what it is proxying.
 */
export async function GET(request: NextRequest): Promise<NextResponse> {
  const wanted = new URLSearchParams();
  for (const key of FORWARDED) {
    const value = request.nextUrl.searchParams.get(key);
    if (value) wanted.set(key, value);
  }

  const query = wanted.toString();
  const result = await apiBinary(`/admin/audit.csv${query ? `?${query}` : ""}`, "text/csv");
  if (!result.ok) {
    return NextResponse.json({ detail: result.detail }, { status: result.status });
  }
  return new NextResponse(result.bytes, {
    headers: {
      "Content-Type": "text/csv; charset=utf-8",
      "Content-Disposition": result.contentDisposition ?? `attachment; filename="audit.csv"`,
      "Cache-Control": "no-store",
    },
  });
}
