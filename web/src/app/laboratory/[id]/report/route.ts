import { accessToken } from "@/lib/session";

/**
 * Streams the report PDF from the gateway.
 *
 * A route handler rather than a link straight to the gateway, because the bearer token lives in an
 * httpOnly cookie: a browser-issued request would arrive unauthenticated. This is the one place the
 * pattern is unavoidable — a PDF cannot be inlined into a server-rendered page the way the specimen
 * label's SVG is.
 *
 * The upstream status is passed through rather than flattened. A 400 ("no results yet") and a 403
 * ("not your chart to read") are different answers, and the browser should see which one it got.
 */
export async function GET(
  _request: Request,
  { params }: { params: Promise<{ id: string }> },
): Promise<Response> {
  const { id } = await params;
  const token = await accessToken();
  const gateway = process.env.GATEWAY_URL ?? "http://localhost:8080";

  const upstream = await fetch(`${gateway}/lab/orders/${encodeURIComponent(id)}/report.pdf`, {
    headers: {
      Accept: "application/pdf",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    cache: "no-store",
  });

  if (!upstream.ok) {
    return new Response(await upstream.text(), {
      status: upstream.status,
      headers: { "Content-Type": upstream.headers.get("Content-Type") ?? "application/json" },
    });
  }

  return new Response(await upstream.arrayBuffer(), {
    status: 200,
    headers: {
      "Content-Type": "application/pdf",
      "Content-Disposition":
        upstream.headers.get("Content-Disposition") ?? `inline; filename="report-${id}.pdf"`,
      // Patient data: never cached by the browser or anything between it and here.
      "Cache-Control": "no-store",
    },
  });
}
