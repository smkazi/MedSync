import Link from "next/link";
import { load } from "@/lib/load";
import { money } from "@/lib/money";
import { currentUser, hasRole } from "@/lib/session";
import type { Claim, Payer } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDateTime, statusTone } from "@/components/ui";
import { denyClaim, settleClaim, submitClaim } from "../actions";

/**
 * What payers owe, and what came back.
 *
 * <p>Open claims by default: a settled claim is finished and a denied one is not, which is why
 * DENIED stays in the list. A claim a payer refused is money the hospital has treated somebody for
 * and not been paid for, and a screen that filed it away under "closed" would be a screen that
 * loses it.
 *
 * <p>Settling records an insurance payment against the invoice through the same path a cashier's
 * cash takes, so "collected" means one thing on this platform. The shortfall is shown and never
 * absorbed — the balance goes back to the patient or is written off, and that is a person's
 * decision.
 */
export default async function ClaimsPage({
  searchParams,
}: {
  searchParams: Promise<{ payerCode?: string; includeClosed?: string; problem?: string; done?: string }>;
}) {
  const { payerCode = "", includeClosed, problem, done } = await searchParams;
  const mayWrite = hasRole(await currentUser(), "ADMIN", "CASHIER");
  const closed = includeClosed === "true";

  const query = new URLSearchParams();
  if (payerCode) query.set("payerCode", payerCode);
  if (closed) query.set("includeClosed", "true");

  const [claims, payers] = await Promise.all([
    load<Claim[]>(`/claims${query.size > 0 ? `?${query}` : ""}`),
    load<Payer[]>("/payers"),
  ]);

  const rows = claims.data ?? [];
  const awaiting = rows.filter((claim) => claim.status === "SUBMITTED");
  const denied = rows.filter((claim) => claim.status === "DENIED");
  const shortfalls = rows.filter(
    (claim) => claim.status === "PARTIALLY_SETTLED" && claim.shortfall > 0,
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Claims</h1>
        <p className="text-sm text-ink-muted">
          One claim per invoice. A rejected claim is re-argued on its own row, never by raising a
          second one.
        </p>
      </div>

      {problem && <ErrorNote>{problem}</ErrorNote>}
      {done && (
        <p
          role="status"
          className="rounded-md border border-good/40 bg-good-soft px-3 py-2 text-sm text-good"
        >
          {done}
        </p>
      )}
      {claims.error && <ErrorNote>{claims.error}</ErrorNote>}

      <div className="grid gap-4 sm:grid-cols-3">
        <Stat label="With the payer" value={awaiting.length} hint="submitted, no answer yet" />
        <Stat label="Denied" value={denied.length} hint="somebody has to argue these" />
        <Stat label="Short-paid" value={shortfalls.length} hint="a balance to decide about" />
      </div>

      <Card
        title="Claims"
        action={
          <form className="flex items-center gap-2">
            <label htmlFor="payerCode" className="text-xs text-ink-muted">
              Payer
            </label>
            <select
              id="payerCode"
              name="payerCode"
              defaultValue={payerCode}
              className="rounded border border-line bg-surface-raised px-2 py-1 text-xs"
            >
              <option value="">all</option>
              {(payers.data ?? []).map((payer) => (
                <option key={payer.code} value={payer.code}>
                  {payer.name}
                </option>
              ))}
            </select>
            <label className="flex items-center gap-1 text-xs text-ink-muted">
              <input type="checkbox" name="includeClosed" value="true" defaultChecked={closed} />
              settled too
            </label>
            <button
              type="submit"
              className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
            >
              Show
            </button>
          </form>
        }
      >
        {rows.length === 0 ? (
          <Empty>No claims{payerCode ? ` for ${payerCode}` : ""}.</Empty>
        ) : (
          <Table
            head={["Invoice", "Payer", "Pre-auth", "Claimed", "Settled", "Short", "", "Submitted", ""]}
          >
            {rows.map((claim) => (
              <tr key={claim.id}>
                <td className="numeric px-3 py-2">
                  <Link href={`/billing/${claim.invoiceId}`} className="underline">
                    {claim.invoiceNumber}
                  </Link>
                </td>
                <td className="px-3 py-2">{claim.payerCode}</td>
                <td className="numeric px-3 py-2 text-ink-muted">{claim.preauthNo ?? "—"}</td>
                <td className="numeric px-3 py-2">{money(claim.claimedAmount)}</td>
                <td className="numeric px-3 py-2">{money(claim.settledAmount)}</td>
                <td className="numeric px-3 py-2 font-semibold">
                  {claim.shortfall > 0 ? money(claim.shortfall) : "—"}
                </td>
                <td className="px-3 py-2">
                  <Badge tone={statusTone(claim.status)}>
                    {claim.status.toLowerCase().replace("_", " ")}
                  </Badge>
                </td>
                <td className="numeric px-3 py-2 text-ink-muted">
                  {claim.submittedAt ? formatDateTime(claim.submittedAt) : "—"}
                </td>
                <td className="px-3 py-2">
                  {mayWrite && claim.status === "DRAFT" && (
                    <form action={submitClaim}>
                      <input type="hidden" name="claimId" value={claim.id} />
                      <button type="submit" className="text-xs underline">
                        Submit to payer
                      </button>
                    </form>
                  )}
                  {mayWrite && claim.status === "SUBMITTED" && (
                    <div className="space-y-2">
                      <form action={settleClaim} className="flex items-center gap-1">
                        <input type="hidden" name="claimId" value={claim.id} />
                        <label className="sr-only" htmlFor={`settled-${claim.id}`}>
                          Amount settled for {claim.invoiceNumber}
                        </label>
                        <input
                          id={`settled-${claim.id}`}
                          name="settledAmount"
                          type="number"
                          step="0.01"
                          required
                          defaultValue={claim.claimedAmount}
                          className="w-24 rounded border border-line bg-surface-raised px-2 py-1 text-xs"
                        />
                        <button type="submit" className="text-xs underline">
                          Settle
                        </button>
                      </form>
                      <form action={denyClaim} className="flex items-center gap-1">
                        <input type="hidden" name="claimId" value={claim.id} />
                        <label className="sr-only" htmlFor={`reason-${claim.id}`}>
                          Reason the payer gave for {claim.invoiceNumber}
                        </label>
                        <input
                          id={`reason-${claim.id}`}
                          name="reason"
                          required
                          placeholder="Reason the payer gave"
                          className="w-40 rounded border border-line bg-surface-raised px-2 py-1 text-xs"
                        />
                        <button type="submit" className="text-xs text-critical underline">
                          Denied
                        </button>
                      </form>
                    </div>
                  )}
                  {!mayWrite && <span className="text-xs text-ink-muted">read-only</span>}
                </td>
              </tr>
            ))}
          </Table>
        )}
        <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
          A settlement is money: it lands on the invoice as an insurance payment, so what has been
          collected is one number rather than two that can disagree. Settling for more than was
          claimed is refused — a claim raised for the wrong amount is corrected, not overpaid.
        </p>
      </Card>
    </div>
  );
}
