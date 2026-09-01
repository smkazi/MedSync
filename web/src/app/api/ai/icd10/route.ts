import { NextResponse } from "next/server";
import { api, ApiError } from "@/lib/api";
import type { CodingResponse } from "@/lib/types";

/** Proxies an ICD-10 suggestion request server-side, for the same reason as summarisation. */
export async function POST(request: Request): Promise<NextResponse> {
  const body = (await request.json()) as { text?: string };
  if (!body.text || body.text.trim().length < 3) {
    return NextResponse.json({ detail: "Enter some diagnosis text first" }, { status: 400 });
  }
  try {
    const suggestions = await api<CodingResponse>("/ai/icd10/suggest", {
      method: "POST",
      body: { text: body.text, max_suggestions: 6 },
    });
    return NextResponse.json(suggestions);
  } catch (error) {
    const status = error instanceof ApiError ? error.status : 502;
    return NextResponse.json(
      { detail: error instanceof Error ? error.message : "Coding suggestions unavailable" },
      { status },
    );
  }
}
