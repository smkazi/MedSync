import { NextResponse } from "next/server";
import { api, ApiError } from "@/lib/api";
import type { TriageResponse } from "@/lib/types";

/** Proxies a triage assessment server-side. */
export async function POST(request: Request): Promise<NextResponse> {
  const body = await request.json();
  try {
    return NextResponse.json(await api<TriageResponse>("/ai/triage", { method: "POST", body }));
  } catch (error) {
    const status = error instanceof ApiError ? error.status : 502;
    return NextResponse.json(
      { detail: error instanceof Error ? error.message : "Triage unavailable" },
      { status },
    );
  }
}
