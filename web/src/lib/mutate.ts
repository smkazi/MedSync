import { api, ApiError } from "@/lib/api";
import type { Refusal } from "@/lib/form";

/**
 * Writes, for a form that would rather render a refusal than crash.
 *
 * <p>The read side has {@link import("@/lib/load").load}; this is its counterpart. It exists for the
 * same reason: twenty server actions were about to repeat the same try/catch, and the repetition
 * invited the two mistakes it avoids — losing the platform's own message in favour of a generic one,
 * and dropping the parts of an error body that are the point of the response.
 *
 * <p><strong>The services write their refusals for people.</strong> "Room GF-GEN is already in use
 * at that time. Pick another room or another slot." is a different instruction to the front desk
 * than "That slot has just been taken; please pick another", and both come from the same 409 on the
 * same endpoint. A form that flattened those to "Conflict" would be throwing away the only thing
 * that tells somebody what to do next. So `error` is the service's `detail`, verbatim, and `body`
 * keeps whatever else it sent.
 */

export type Submitted<T> = { ok: true; data: T } | Refusal;

export async function submit<T>(
  path: string,
  method: "POST" | "PATCH" | "PUT" | "DELETE",
  body?: unknown,
): Promise<Submitted<T>> {
  try {
    return { ok: true, data: await api<T>(path, { method, body }) };
  } catch (caught) {
    if (caught instanceof ApiError) {
      return {
        ok: false,
        status: caught.status,
        error:
          caught.status === 403
            ? "Your role does not have permission to do that."
            : caught.detail,
        fieldErrors: caught.fieldErrors ?? {},
        body: caught.body,
      };
    }
    return {
      ok: false,
      status: 0,
      error: caught instanceof Error ? caught.message : "Request failed",
      fieldErrors: {},
      body: undefined,
    };
  }
}

