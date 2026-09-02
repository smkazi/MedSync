import { load } from "@/lib/load";
import { money } from "@/lib/money";
import { currentUser, hasRole } from "@/lib/session";
import type { ChargeItem, TaxRate } from "@/lib/types";
import { RecordForm } from "@/components/RecordForm";
import { Badge, Card, Empty, ErrorNote, Stat, Table } from "@/components/ui";
import { addChargeItem, updateChargeItem } from "../actions";

/**
 * The price list.
 *
 * <p>Administrators only, and separately from taking money. A cashier who could retune a price
 * could discount a procedure to zero and then record it as paid in full, and nothing downstream
 * would notice — which is why the platform keeps "may take a payment" and "may change what things
 * cost" as different authorities held by different people.
 *
 * <p>Editing a price changes what the <em>next</em> invoice charges. Invoices already raised keep
 * the price they carried, because each line snapshots it — the deliberate opposite of a room's
 * directions, which must always be current.
 */
export default async function ChargeItemsPage({
  searchParams,
}: {
  searchParams: Promise<{ q?: string; includeInactive?: string }>;
}) {
  const { q = "", includeInactive } = await searchParams;
  const mayPrice = hasRole(await currentUser(), "ADMIN");
  const showRetired = includeInactive === "true";

  const query = new URLSearchParams();
  if (q) query.set("q", q);
  if (showRetired) query.set("includeInactive", "true");

  const [items, rates] = await Promise.all([
    load<ChargeItem[]>(`/charge-items${query.size > 0 ? `?${query}` : ""}`),
    load<TaxRate[]>("/tax-rates"),
  ]);

  const rows = items.data ?? [];
  const taxable = rows.filter((item) => item.taxable);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Charge items</h1>
        <p className="text-sm text-ink-muted">What the hospital charges for, and what tax it carries.</p>
      </div>

      {items.error && <ErrorNote>{items.error}</ErrorNote>}

      <div className="grid gap-4 sm:grid-cols-3">
        <Stat label="Items" value={rows.length} hint={showRetired ? "including retired" : "chargeable"} />
        <Stat label="Taxable" value={taxable.length} hint="the rest are exempt" />
        <Stat label="Tax rates" value={(rates.data ?? []).length} hint="dated rows" />
      </div>

      <Card
        title="The list"
        action={
          <form className="flex items-center gap-2">
            <label htmlFor="q" className="text-xs text-ink-muted">
              Name
            </label>
            <input
              id="q"
              name="q"
              defaultValue={q}
              className="w-40 rounded border border-line bg-surface-raised px-2 py-1 text-xs"
            />
            <label className="flex items-center gap-1 text-xs text-ink-muted">
              <input
                type="checkbox"
                name="includeInactive"
                value="true"
                defaultChecked={showRetired}
              />
              retired too
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
          <Empty>Nothing matches.</Empty>
        ) : (
          <Table head={["Code", "Name", "Dept", "Price", "Tax", "", mayPrice ? "Change" : ""]}>
            {rows.map((item) => (
              <tr key={item.id} className={item.active ? "" : "text-ink-muted"}>
                <td className="numeric px-3 py-2">{item.code}</td>
                <td className="px-3 py-2">{item.name}</td>
                <td className="px-3 py-2 text-ink-muted">{item.departmentCode ?? "—"}</td>
                <td className="numeric px-3 py-2">{money(item.unitPrice)}</td>
                <td className="numeric px-3 py-2 text-ink-muted">
                  {item.taxable
                    ? `${item.taxRateCode} (${item.taxPercentToday ?? "—"}% today)`
                    : "exempt"}
                </td>
                <td className="px-3 py-2">
                  {item.active ? null : <Badge tone="critical">retired</Badge>}
                </td>
                <td className="px-3 py-2">
                  {mayPrice ? (
                    <RecordForm
                      action={updateChargeItem}
                      hidden={{ code: item.code }}
                      submitLabel="Save"
                      busyLabel="Saving…"
                      columns={3}
                      fields={[
                        { name: "name", label: "Name", value: item.name },
                        {
                          name: "unitPrice",
                          label: "Price",
                          type: "number",
                          step: "0.01",
                          value: item.unitPrice,
                        },
                        {
                          name: "active",
                          label: "Chargeable",
                          type: "checkbox",
                          value: item.active,
                        },
                      ]}
                    />
                  ) : null}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>

      {mayPrice && (
        <Card title="Add a charge item">
          <RecordForm
            action={addChargeItem}
            submitLabel="Add it"
            busyLabel="Adding…"
            fields={[
              { name: "code", label: "Code", required: true, placeholder: "CONSULT_SPEC" },
              { name: "name", label: "Name", required: true },
              {
                name: "departmentCode",
                label: "Department",
                hint: "Whose revenue it is. Optional.",
              },
              { name: "unitPrice", label: "Unit price", type: "number", required: true, step: "0.01" },
              {
                name: "taxable",
                label: "Taxable",
                type: "checkbox",
                hint: "Healthcare services by a clinical establishment are GST-exempt in India, so most clinical items are not taxable. What a hospital sells — a medicine, a consumable — is.",
              },
              {
                name: "taxRateCode",
                label: "Tax rate",
                type: "select",
                hint: "Required when taxable, and refused when not. A taxable item with no rate would be a silent zero.",
                options: [
                  { value: "", label: "— none —" },
                  ...Array.from(new Set((rates.data ?? []).map((rate) => rate.code))).map(
                    (code) => ({ value: code, label: code }),
                  ),
                ],
              },
            ]}
          />
        </Card>
      )}

      <p className="text-xs text-ink-muted">
        Changing a price changes what the next invoice charges and nothing that has already been
        raised. Retiring an item stops it being charged — including through charge capture, where an
        event naming a retired item is reported in the log rather than billed at a price somebody
        withdrew.
      </p>
    </div>
  );
}
