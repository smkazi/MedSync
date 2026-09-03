"use client";

import { useActionState, useId } from "react";
import { ErrorNote } from "@/components/ui";
import { EMPTY_IMAGING_STATE } from "./state";
import { fileStudy } from "./actions";

/**
 * Files one DICOM instance that came off a modality.
 *
 * <p>The only form on the platform that carries a file, which is why it is bespoke rather than a
 * {@link import("@/components/RecordForm").RecordForm}: that component's field vocabulary is text,
 * number, select and checkbox, and a file is none of them.
 *
 * <p>The confirmation is the platform's own sentence, rendered verbatim. Whether the study matched
 * an order and whether the pixels were archived are two facts a radiographer acts on — an unmatched
 * study is somebody's job to resolve, and an unarchived one means the images are still only on the
 * scanner — and the service says both in words. Rewording them here would make this screen's
 * opinion of what happened compete with what actually did.
 *
 * <p>`accept` is a hint to the file picker and nothing more. The platform decides what a DICOM file
 * is, by reading the header, and refuses anything else with a reason; a filter in the browser that
 * looked authoritative would only teach a radiographer to trust it.
 */
export function UploadForm() {
  const [state, formAction, pending] = useActionState(fileStudy, EMPTY_IMAGING_STATE);
  const id = useId();
  const fieldError = state.fieldErrors.file;

  return (
    // The encoding is stated rather than left to React. A server action posted from a form with no
    // JavaScript is a native browser submit, and a native submit of a file input under the default
    // urlencoded encoding sends the file's *name* and none of its bytes.
    <form action={formAction} encType="multipart/form-data" className="space-y-3">
      {state.error && <ErrorNote>{state.error}</ErrorNote>}
      {state.done && (
        <p
          role="status"
          className="rounded-md border border-good/40 bg-good-soft px-3 py-2 text-sm text-good"
        >
          {state.done}
        </p>
      )}

      <div>
        <label htmlFor={id} className="block text-sm font-medium">
          DICOM file
        </label>
        <input
          id={id}
          name="file"
          type="file"
          accept=".dcm,application/dicom"
          aria-invalid={fieldError ? true : undefined}
          className={`mt-1 block w-full max-w-md rounded-md border bg-surface-raised px-3 py-2 text-sm file:mr-3 file:rounded file:border-0 file:bg-surface file:px-3 file:py-1 file:text-sm ${
            fieldError ? "border-critical" : "border-line"
          }`}
        />
        {fieldError && <p className="mt-1 text-xs text-critical">{fieldError}</p>}
      </div>

      <button
        type="submit"
        disabled={pending}
        className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-60"
      >
        {pending ? "Filing…" : "File this study"}
      </button>
    </form>
  );
}
