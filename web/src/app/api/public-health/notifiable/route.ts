import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import { apiBinary } from "@/lib/api";

/** The only two filters the return accepts. Anything else in the query string is dropped. */
const FORWARDED = ["from", "to"] as const;

/**
 * The notifiable-disease return as a CSV file.
 *
 * <p>Proxied through this app rather than linked at the gateway, for the reason every download on
 * this platform is: the access token lives in an httpOnly cookie the browser will not attach to a
 * cross-origin link.
 *
 * <p>Only the report's own filters are forwarded, following the audit export. Passing the query
 * string through verbatim would let a crafted link add any parameter the endpoint grows later, and
 * the point of a proxy is that it knows what it is proxying.
 */
export async function GET(request: NextRequest): Promise<NextResponse> {
  const wanted = new URLSearchParams();
  for (const key of FORWARDED) {
    const value = request.nextUrl.searchParams.get(key);
    if (value) wanted.set(key, value);
  }

  const query = wanted.toString();
  const result = await apiBinary(
    `/surveillance/notifiable.csv${query ? `?${query}` : ""}`,
    "text/csv",
  );
  if (!result.ok) {
    return NextResponse.json({ detail: result.detail }, { status: result.status });
  }
  return new NextResponse(result.bytes, {
    headers: {
      "Content-Type": "text/csv; charset=utf-8",
      // The service names the file for its period, and that name is kept: two returns in one
      // folder have to be tellable apart.
      "Content-Disposition": result.contentDisposition ?? `attachment; filename="notifiable.csv"`,
      "Cache-Control": "no-store",
    },
  });
}
