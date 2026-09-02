import { load } from "@/lib/load";
import { money } from "@/lib/money";
import { currentUser, hasRole } from "@/lib/session";
import type { ChargeItem, Payer } from "@/lib/types";
import { RecordForm } from "@/components/RecordForm";
import { Badge, Card, Empty, ErrorNote, Stat, Table } from "@/components/ui";
import { addPayer, setTariff } from "../actions";

/**
 * Who pays, on what terms.
 *
 * <p>Four flags, and each one changes what the platform does rather than describing anything:
 * pre-authorisation is refused without a number, a direct settler is the only kind that can be
 * claimed from at all, a tax-exempt payer exempts every line whatever the charge item says, and a
 * co-pay is what makes a claim for the outstanding balance different from a claim for the total.
 *
 * <p>A tariff is the agreed price. Without one a payer's invoice prices at the list price, which is
 * a claim that will be short-paid — so the tariff table is the part of this screen that matters.
 */
export default async function PayersPage({
  searchParams,
}: {
  searchParams: Promise<{ payerCode?: string }>;
}) {
  const { payerCode = "" } = await searchParams;
  const mayPrice = hasRole(await currentUser(), "ADMIN");

  const [payers, chargeItems] = await Promise.all([
    load<Payer[]>("/payers"),
    load<ChargeItem[]>("/charge-items"),
  ]);

  const rows = payers.data ?? [];
  const chosen = rows.find((payer) => payer.code === payerCode);
  const directSettlers = rows.filter((payer) => payer.settlesDirectly);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Payers &amp; tariffs</h1>
        <p className="text-sm text-ink-muted">
          Insurers, schemes and self-paying patients — and what each has agreed to pay.
        </p>
      </div>

      {payers.error && <ErrorNote>{payers.error}</ErrorNote>}

      <div className="grid gap-4 sm:grid-cols-3">
        <Stat label="Payers" value={rows.length} hint="configured" />
        <Stat
          label="Settle directly"
          value={directSettlers.length}
          hint="the hospital can claim from these"
        />
        <Stat
          label="Agreed prices"
          value={rows.reduce((total, payer) => total + payer.tariffs.length, 0)}
          hint="tariff rows"
        />
      </div>

      <Card title="Payers">
        {rows.length === 0 ? (
          <Empty>No payers are configured.</Empty>
        ) : (
          <Table head={["Code", "Name", "Pre-auth", "Co-pay", "Settles", "Tax", "Tariffs", ""]}>
            {rows.map((payer) => (
              <tr key={payer.id} className={payer.code === payerCode ? "bg-accent-soft/40" : ""}>
                <td className="numeric px-3 py-2">{payer.code}</td>
                <td className="px-3 py-2">{payer.name}</td>
                <td className="px-3 py-2">
                  {payer.requiresPreauth ? <Badge tone="warn">required</Badge> : "—"}
                </td>
                <td className="px-3 py-2 text-ink-muted">{payer.allowsCopay ? "allowed" : "—"}</td>
                <td className="px-3 py-2 text-ink-muted">
                  {payer.settlesDirectly ? "directly" : "patient reclaims"}
                </td>
                <td className="px-3 py-2 text-ink-muted">
                  {payer.taxExempt ? "exempt" : "as charged"}
                </td>
                <td className="numeric px-3 py-2">{payer.tariffs.length}</td>
                <td className="px-3 py-2">
                  <a href={`/billing/payers?payerCode=${payer.code}`} className="text-xs underline">
                    {payer.code === payerCode ? "Shown" : "Tariffs"}
                  </a>
                </td>
              </tr>
            ))}
          </Table>
        )}
        <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
          A payer that requires pre-authorisation must also settle directly — the platform’s
          database refuses the combination that says otherwise, because demanding a number from a
          payer who never pays the hospital makes no sense.
        </p>
      </Card>

      {chosen && (
        <Card title={`${chosen.name}: agreed prices`}>
          {chosen.tariffs.length === 0 ? (
            <Empty>
              Nothing is agreed, so this payer’s invoices price at the list price.
            </Empty>
          ) : (
            <Table head={["Charge item", "List price", "Agreed", "Difference"]}>
              {chosen.tariffs.map((tariff) => (
                <tr key={tariff.chargeItemCode}>
                  <td className="px-3 py-2">
                    {tariff.chargeItemName}{" "}
                    <span className="text-xs text-ink-muted">({tariff.chargeItemCode})</span>
                  </td>
                  <td className="numeric px-3 py-2 text-ink-muted">{money(tariff.listPrice)}</td>
                  <td className="numeric px-3 py-2 font-semibold">{money(tariff.agreedPrice)}</td>
                  <td className="numeric px-3 py-2 text-ink-muted">
                    {tariff.listPrice - tariff.agreedPrice > 0
                      ? `−${money(tariff.listPrice - tariff.agreedPrice)}`
                      : "—"}
                  </td>
                </tr>
              ))}
            </Table>
          )}

          {mayPrice && (
            <div className="mt-4 border-t border-line pt-4">
              <RecordForm
                action={setTariff}
                hidden={{ payerCode: chosen.code }}
                submitLabel="Agree this price"
                busyLabel="Saving…"
                fields={[
                  {
                    name: "chargeItemCode",
                    label: "Charge item",
                    type: "select",
                    required: true,
                    options: [
                      { value: "", label: "— pick one —" },
                      ...(chargeItems.data ?? [])
                        .filter((item) => item.active)
                        .map((item) => ({
                          value: item.code,
                          label: `${item.name} — list ${money(item.unitPrice)}`,
                        })),
                    ],
                  },
                  { name: "price", label: "Agreed price", type: "number", required: true, step: "0.01" },
                ]}
              />
            </div>
          )}
        </Card>
      )}

      {mayPrice && (
        <Card title="Add a payer">
          <RecordForm
            action={addPayer}
            submitLabel="Add the payer"
            busyLabel="Adding…"
            fields={[
              { name: "code", label: "Code", required: true, placeholder: "TPA_B" },
              { name: "name", label: "Name", required: true },
              {
                name: "settlesDirectly",
                label: "Settles directly with the hospital",
                type: "checkbox",
                hint: "Off means the patient pays and reclaims themselves, and the hospital has no claim to raise.",
              },
              {
                name: "requiresPreauth",
                label: "Requires pre-authorisation",
                type: "checkbox",
                hint: "A claim without a number is refused. Only valid together with settling directly.",
              },
              { name: "allowsCopay", label: "Allows a co-pay", type: "checkbox" },
              {
                name: "taxExempt",
                label: "Tax-exempt",
                type: "checkbox",
                hint: "Exempts every line on their invoices, whatever the charge item says.",
              },
            ]}
          />
        </Card>
      )}

      <p className="text-xs text-ink-muted">
        No insurer’s or company’s name appears in this repository. The seeded payers are samples a
        deployment replaces with the schemes it actually has.
      </p>
    </div>
  );
}
