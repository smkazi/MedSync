import { NextResponse } from "next/server";
import { apiBinary } from "@/lib/api";

/**
 * The patient's own released report, streamed through this app.
 *
 * <p>A route handler rather than a link straight at the gateway, for the reason every other
 * platform call in this app is made server-side: the bearer token is in an httpOnly cookie the
 * browser cannot read, so a direct link would arrive with no credential at all and answer 401.
 *
 * <p>No id is invented here and none is trusted — the order id is passed through and the platform
 * refuses it unless it belongs to the signed-in patient. `no-store` is repeated on the way out
 * because a report is patient data and must not sit in a shared cache, and the platform having
 * said so does not make this app's response say it.
 */
export async function GET(
  _request: Request,
  { params }: { params: Promise<{ orderId: string }> },
): Promise<NextResponse> {
  const { orderId } = await params;
  const result = await apiBinary(`/portal/reports/${orderId}.pdf`, "application/pdf");
  if (!result.ok) {
    return NextResponse.json({ detail: result.detail }, { status: result.status });
  }
  return new NextResponse(result.bytes, {
    headers: {
      "Content-Type": result.contentType,
      "Content-Disposition": result.contentDisposition ?? `inline; filename="report.pdf"`,
      "Cache-Control": "no-store",
    },
  });
}
