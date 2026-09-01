import Link from "next/link";
import { redirect } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import type { LabOrder } from "@/lib/types";
import { ErrorNote } from "@/components/ui";

/**
 * Resolves a scanned accession number to its order and sends the browser there.
 *
 * A handheld scanner is a keyboard: it types the barcode's contents and presses Enter. So the
 * worklist's scan box is an ordinary form that submits here, and this page redirects — which means
 * the flow works with a scanner, with a phone camera app that can paste, and with somebody typing
 * the number off the label when the scanner is flat.
 *
 * A failed scan renders rather than redirecting. Sending someone back to the worklist with no
 * explanation would look like the scan had worked and found nothing, and a tube whose label does not
 * resolve is worth stopping for.
 */
export default async function ScanPage({
  searchParams,
}: {
  searchParams: Promise<{ accession?: string }>;
}) {
  const { accession } = await searchParams;
  const scanned = accession?.trim();

  if (!scanned) {
    redirect("/laboratory");
  }

  let order: LabOrder | undefined;
  let failure: string | undefined;
  try {
    order = await api<LabOrder>(`/lab/specimens/by-accession/${encodeURIComponent(scanned)}`);
  } catch (error) {
    failure = error instanceof ApiError ? error.detail : "The scan could not be resolved.";
  }

  if (order) {
    redirect(`/laboratory/${order.id}`);
  }

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold tracking-tight">Scan not recognised</h1>
      <ErrorNote>{failure}</ErrorNote>
      <p className="text-sm text-ink-muted">
        The label scanned as <span className="numeric font-medium">{scanned}</span>, which does not
        match a registered specimen. Check the tube against the collection list before running it —
        an unrecognised label is worth investigating rather than working around.
      </p>
      <Link href="/laboratory" className="text-sm text-accent hover:underline">
        Back to the worklist
      </Link>
    </div>
  );
}
