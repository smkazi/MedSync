import { NextResponse } from "next/server";
import { api, ApiError } from "@/lib/api";
import type { NoteSummary } from "@/lib/types";

/**
 * Proxies a summarisation request through the server, so the clinical note is sent with the
 * session's token from an httpOnly cookie and the browser never holds a credential.
 */
export async function POST(request: Request): Promise<NextResponse> {
  const body = (await request.json()) as { noteText?: string; patientAge?: number };
  if (!body.noteText || body.noteText.trim().length < 10) {
    return NextResponse.json({ detail: "There is not enough note text to summarise" }, { status: 400 });
  }
  try {
    const summary = await api<NoteSummary>("/ai/notes/summarize", {
      method: "POST",
      body: { note_text: body.noteText, patient_age: body.patientAge ?? null },
    });
    return NextResponse.json(summary);
  } catch (error) {
    const status = error instanceof ApiError ? error.status : 502;
    return NextResponse.json(
      { detail: error instanceof Error ? error.message : "Summarisation unavailable" },
      { status },
    );
  }
}
