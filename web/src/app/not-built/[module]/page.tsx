import Link from "next/link";
import { notFound } from "next/navigation";

/**
 * The page behind a menu item whose backend does not exist.
 *
 * <p>Deliberately plain, and deliberately not a mock-up. A clinical screen that looks functional but
 * is not is worse than an absent one: somebody will read a number off it. So this carries no table,
 * no empty state that implies data could arrive, and no disabled buttons hinting at a workflow —
 * only what is missing and what it would take.
 *
 * <p>The alternative was hiding these from the menu entirely. Showing them was chosen because the
 * whole shape of the product is useful to see, and a menu that silently omits half the roadmap
 * misleads in its own way. What is not acceptable is pretending.
 */

type Module = {
  title: string;
  /** What the screen would do, in one sentence. */
  purpose: string;
  /** The service and endpoints it needs before a screen is possible. */
  needs: string;
  /** Where it sits in the build order. */
  phase: string;
};

const MODULES: Record<string, Module> = {
  "opd-queue": {
    title: "OPD token queue",
    purpose:
      "Issue a token at check-in and call patients in order, so the waiting area is not a scrum" +
      " around the reception desk.",
    needs:
      "scheduling-service: queue_counters and queue_tokens tables, token issuance on check-in," +
      " and GET /queue/{roomCode}.",
    phase: "Phase 2",
  },
  "waiting-display": {
    title: "Waiting-room display",
    purpose:
      "A large-type screen for a wall monitor showing the room and the token now being served.",
    needs:
      "scheduling-service: GET /public/queue/{roomCode} — unauthenticated and PHI-free. No name," +
      " no MRN, no reason for attendance, because a waiting-room screen is visible to every" +
      " stranger in the building.",
    phase: "Phase 2",
  },
  casualty: {
    title: "Casualty board",
    purpose:
      "The live bay: bed occupancy plus the waiting queue ordered by triage acuity, sickest first." +
      " The AI triage acuity already exists and nothing consumes it yet.",
    needs:
      "admissions-service (not created): casualty_attendances, a partial unique index enforcing" +
      " one patient per bed, and GET /casualty/board.",
    phase: "Phase 3",
  },
  admissions: {
    title: "Admissions & beds",
    purpose: "In-patient census, bed map by floor, and transfers between beds.",
    needs:
      "admissions-service (not created): admissions, bed_transfers, and a bed_occupancy table both" +
      " casualty and in-patient paths write through, so one bed cannot be occupied twice.",
    phase: "Phase 4",
  },
  dispensing: {
    title: "Dispensing queue",
    purpose:
      "Work through prescriptions with the patient's allergies on screen, refusing a dispense that" +
      " matches a severe or life-threatening one.",
    needs: "pharmacy-service (not created): prescriptions, dispenses, and the allergy check.",
    phase: "Phase 5",
  },
  formulary: {
    title: "Formulary",
    purpose: "The drug list a prescriber picks from, with form, strength and ingredients.",
    needs: "pharmacy-service (not created): the formulary table and its CRUD.",
    phase: "Phase 5",
  },
  stock: {
    title: "Stock",
    purpose:
      "Batches on hand with expiry dates, first-expiry-first-out selection, and expired batches" +
      " refused outright.",
    needs: "pharmacy-service (not created): stock_batches.",
    phase: "Phase 5",
  },
  invoices: {
    title: "Invoices",
    purpose: "Raise and settle an invoice for a consultation, a lab order or a stay.",
    needs:
      "billing-service (not created): invoices with prices snapshotted onto the lines, and" +
      " posted_charges keyed so a redelivered event cannot bill a patient twice.",
    phase: "Track B",
  },
  payments: {
    title: "Payments",
    purpose: "Take a payment against an invoice and show the balance.",
    needs:
      "billing-service (not created): payments, plus the single-statement update that refuses an" +
      " overpayment atomically rather than losing to a concurrent payment at another counter.",
    phase: "Track B",
  },
  "charge-items": {
    title: "Charge items",
    purpose: "The priced service list — consultations, tests, procedures — and its tax treatment.",
    needs:
      "billing-service (not created): charge_items and dated tax_rates. Diagnostic services by a" +
      " clinical establishment are GST-exempt in India, so exempt is the default, not an" +
      " afterthought.",
    phase: "Track B",
  },
  payers: {
    title: "Payers & tariffs",
    purpose:
      "Cash, corporate and TPA payers with their negotiated rates, so a scheme is priced correctly.",
    needs:
      "billing-service (not created): payers with behaviour as columns, and payer_tariffs.",
    phase: "Track B",
  },
  claims: {
    title: "Claims",
    purpose: "Submit and track a cashless claim, including pre-authorisation.",
    needs:
      "billing-service (not created) for the claim itself; NHCX submission additionally needs the" +
      " ABDM work in Track D.",
    phase: "Track B, then D",
  },
  receivables: {
    title: "Receivables",
    purpose: "What is owed, by whom, and for how long.",
    needs: "billing-service (not created): an ageing query over invoices and payments.",
    phase: "Track B",
  },
};

export function generateStaticParams() {
  return Object.keys(MODULES).map((module) => ({ module }));
}

export default async function NotBuiltPage({ params }: { params: Promise<{ module: string }> }) {
  const { module } = await params;
  const detail = MODULES[module];
  // An unknown slug is a 404, not a generic "not built" page. Otherwise a typo in the menu would
  // render as a feature that merely has not shipped, and the broken link would never be noticed.
  if (!detail) notFound();

  return (
    <div className="max-w-2xl space-y-5">
      <div>
        <p className="text-xs uppercase tracking-wide text-ink-muted">Not built yet</p>
        <h1 className="mt-1 text-xl font-semibold tracking-tight">{detail.title}</h1>
      </div>

      <p className="text-sm">{detail.purpose}</p>

      <div className="rounded border border-line bg-surface p-4 text-sm">
        <p className="font-medium">What it needs first</p>
        <p className="mt-1 text-ink-muted">{detail.needs}</p>
        <p className="mt-3 text-ink-muted">
          Scheduled in <span className="font-medium text-ink">{detail.phase}</span>.
        </p>
      </div>

      <p className="text-sm text-ink-muted">
        This screen is in the menu so the shape of the platform is visible, not because it is
        partly working. There is no data behind it and nothing here is a placeholder for real
        values.
      </p>

      <Link href="/" className="inline-block text-sm text-accent hover:underline">
        Back to the dashboard
      </Link>
    </div>
  );
}
