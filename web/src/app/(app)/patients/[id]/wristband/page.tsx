import Link from "next/link";
import { notFound } from "next/navigation";
import { api, apiText, ApiError } from "@/lib/api";
import { currentUser, hasRole } from "@/lib/session";
import type { Patient } from "@/lib/types";

/**
 * The patient's wristband, laid out to print.
 *
 * <p>Modelled on the specimen-label page and for the same reasons: the SVG is fetched on the server
 * and inlined as markup rather than referenced with `<img>`, because the bearer token lives in an
 * httpOnly cookie and a browser-issued request for it would arrive unauthenticated — and inlining
 * keeps the vectors going to the printer with no intermediate raster step, which is what preserves
 * the bar widths a scanner depends on.
 *
 * <p>Two bands rather than one. A wristband is cut to length and the second is the spare for the
 * one that is put on inside-out or cut short, which happens often enough that a page offering one
 * band is a page somebody prints twice.
 */
export default async function WristbandPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;

  let patient: Patient;
  try {
    patient = await api<Patient>(`/patients/${id}`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) notFound();
    throw error;
  }

  const user = await currentUser();
  const mayBand = hasRole(user, "ADMIN", "RECEPTIONIST", "DOCTOR", "NURSE");

  let band: string | null = null;
  let refusal: string | null = null;
  if (mayBand) {
    try {
      band = await apiText(`/patients/${id}/wristband`, "image/svg+xml");
    } catch (error) {
      // The platform's own words: an archived record explains itself, and so does anything else
      // that goes wrong here. A blank page beside a printer teaches nobody anything.
      refusal = error instanceof ApiError ? error.detail : "The wristband could not be rendered.";
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3 print:hidden">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Wristband</h1>
          <p className="numeric text-sm text-ink-muted">
            {patient.fullName} · {patient.mrn}
          </p>
        </div>
        <Link
          href={`/patients/${patient.id}`}
          className="rounded border border-line px-3 py-1.5 text-sm hover:bg-surface"
        >
          Back to the chart
        </Link>
      </div>

      {!mayBand && (
        <p className="rounded border border-line bg-surface p-4 text-sm text-ink-muted print:hidden">
          Banding a patient is the front desk&apos;s and the ward&apos;s job, and your role does not
          include it.
        </p>
      )}

      {refusal && (
        <p role="alert" className="rounded border border-bad/40 bg-bad-soft p-4 text-sm text-bad">
          {refusal}
        </p>
      )}

      {band && (
        <>
          <p className="rounded border border-line bg-surface p-3 text-sm text-ink-muted print:hidden">
            Check the name and date of birth against the patient before the band goes on. The
            barcode carries the MRN, which is what the medication round scans it for — a band on the
            wrong wrist defeats that check at the one point it cannot see.
          </p>
          <div className="flex flex-wrap gap-4">
            {["band", "spare"].map((which) => (
              <figure key={which} className="rounded border border-line bg-white p-2 print:border-0">
                {/*
                  The SVG comes from our own gateway, rendered by WristbandRenderer from the
                  patient's own record, and every interpolated value in it — the name included — is
                  XML-escaped at the source by an XMLStreamWriter. It is not user-supplied markup.
                */}
                <div dangerouslySetInnerHTML={{ __html: band }} />
                <figcaption className="mt-1 text-center text-xs text-ink-muted print:hidden">
                  {which === "band" ? "Band" : "Spare"}
                </figcaption>
              </figure>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
