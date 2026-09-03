import { NextResponse } from "next/server";
import { apiBinary } from "@/lib/api";

/**
 * Download and transmit: the patient's whole record as a FHIR bundle, saved to their machine.
 *
 * <p>Streamed through this app for the same reason the report is — the token is in an httpOnly
 * cookie — and offered as an attachment because its purpose is to be saved and given to somebody.
 */
export async function GET(): Promise<NextResponse> {
  const result = await apiBinary("/portal/records/export", "application/json");
  if (!result.ok) {
    return NextResponse.json({ detail: result.detail }, { status: result.status });
  }
  return new NextResponse(result.bytes, {
    headers: {
      "Content-Type": "application/json",
      "Content-Disposition":
        result.contentDisposition ?? `attachment; filename="health-record.fhir.json"`,
      "Cache-Control": "no-store",
    },
  });
}
