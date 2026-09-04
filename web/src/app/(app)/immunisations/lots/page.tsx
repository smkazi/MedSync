import { load } from "@/lib/load";
import type { VaccineLot, VaccineProduct } from "@/lib/types";
import { RecordForm, EditRow } from "@/components/RecordForm";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDate } from "@/components/ui";
import { receiveLot, withdrawLot } from "../actions";

/**
 * Vaccine stock, one vaccine at a time.
 *
 * <p><strong>Product-first, and that is the API's shape rather than a preference.</strong>
 * {@code GET /vaccines/lots} requires a {@code productCode} and there is no all-lots read — which
 * this screen was written against the assumption of, and found out by being built. It turns out to
 * be the right shape anyway: a fridge is worked one vaccine at a time, and the question somebody
 * standing at it asks is "which BCG do I open", not "list everything we own". A whole-store view is
 * named in the README's gaps rather than faked by looping over the catalogue, which would be N
 * requests to render one table.
 *
 * <p><strong>Earliest expiry first, and an expired lot cannot be given.</strong> The same two rules
 * the pharmacy's batches follow, for the same reason: a fridge holds several lots of one vaccine and
 * the one that expires soonest is the one to open, and a dose from an expired vial is a dose that
 * may not have worked — which for a vaccine is a child recorded as protected and not protected.
 *
 * <p><strong>The vial monitor stage is recorded and enforced by nothing</strong>, and the screen
 * says so in prose rather than showing a green tick somebody would rely on. It is what a person
 * read off the label at receipt; this platform has no cold-chain telemetry, nothing here knows what
 * a fridge did overnight, and a platform that refused a dose on a number it could not keep current
 * would be refusing on the strength of last month's reading.
 *
 * <p><strong>Withdrawing a lot does not touch the doses recorded against it.</strong> That is the
 * point of a recall: the doses keep their lot number, so "which arms did this vial go into" stays
 * answerable, and only the giving of new ones stops.
 */
export default async function VaccineLotsPage({
  searchParams,
}: {
  searchParams: Promise<{ productCode?: string }>;
}) {
  const { productCode = "" } = await searchParams;

  const { data: catalogue, error: catalogueError } =
    await load<VaccineProduct[]>("/vaccines/products");
  const products = (catalogue ?? []).filter((product) => product.active);
  const selected = productCode || products[0]?.code || "";
  const productOptions = products.map((product) => ({
    value: product.code,
    label: `${product.code} — ${product.name}`,
  }));

  const { data: lots, error } = selected
    ? await load<VaccineLot[]>(`/vaccines/lots?productCode=${encodeURIComponent(selected)}`)
    : { data: null, error: null };

  const rows = lots ?? [];
  const today = new Date().toISOString().slice(0, 10);
  const usable = rows.filter((lot) => lot.usable);
  const expiringSoon = usable.filter((lot) => {
    const days = (Date.parse(lot.expiresOn) - Date.parse(today)) / 86_400_000;
    return days >= 0 && days <= 60;
  });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Vaccine stock</h1>
        <p className="text-sm text-ink-muted">
          One vaccine at a time, earliest expiry first. An expired or withdrawn lot cannot be given
          — the service refuses it rather than warning about it. The vial monitor stage is{" "}
          <strong>recorded and enforced by nothing</strong>: it is what somebody read off the label
          at receipt, this platform has no cold-chain telemetry, and the judgement is a
          person&apos;s, at the fridge, with the vial in their hand.
        </p>
      </div>

      {catalogueError && <ErrorNote>{catalogueError}</ErrorNote>}
      {error && <ErrorNote>{error}</ErrorNote>}

      {products.length === 0 && !catalogueError ? (
        <Empty>
          No vaccine is in the catalogue, so there is nothing to hold stock of. A dose cannot be
          recorded as given here without a lot number, so the register is closed to new doses until
          a product and a lot exist.
        </Empty>
      ) : (
        <>
          <form className="flex flex-wrap items-end gap-3">
            <div>
              <label htmlFor="productCode" className="block text-xs text-ink-muted">
                Vaccine
              </label>
              <select
                id="productCode"
                name="productCode"
                defaultValue={selected}
                className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
              >
                {productOptions.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </div>
            <button
              type="submit"
              className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
            >
              Show its lots
            </button>
          </form>

          <div className="grid gap-4 sm:grid-cols-3">
            <Stat
              label="Doses on hand"
              value={usable.reduce((total, lot) => total + lot.quantityOnHand, 0)}
              hint={`usable lots of ${selected}`}
            />
            <Stat label="Usable lots" value={usable.length} />
            <Stat
              label="Expiring within 60 days"
              value={expiringSoon.length}
              hint="open these first"
            />
          </div>

          <Card title="Lots">
            {rows.length === 0 ? (
              <Empty>
                Nothing received for {selected}. A dose of it cannot be recorded as given here
                without a lot number, so this vaccine is closed to new doses until something
                arrives.
              </Empty>
            ) : (
              <Table head={["Lot", "Vaccine", "Expires", "On hand", "Received", "VVM", "State", ""]}>
                {rows.map((lot) => (
                  <tr key={lot.id} className="border-t border-line align-top">
                    <td className="numeric px-3 py-2">{lot.lotNo}</td>
                    <td className="px-3 py-2">
                      {lot.productName}
                      <span className="numeric block text-xs text-ink-muted">
                        {lot.productCode}
                      </span>
                    </td>
                    <td className="numeric px-3 py-2 text-xs">{formatDate(lot.expiresOn)}</td>
                    <td className="numeric px-3 py-2">{lot.quantityOnHand}</td>
                    <td className="numeric px-3 py-2 text-xs">{formatDate(lot.receivedOn)}</td>
                    <td className="numeric px-3 py-2 text-xs">
                      {/*
                        A number with no colour and no tick. It is a reading somebody took at
                        receipt, and nothing on this platform keeps it current — dressing it up as
                        a status would invite somebody to trust it.
                      */}
                      {lot.vvmStage ?? <span className="text-ink-muted">not read</span>}
                    </td>
                    <td className="px-3 py-2">
                      {lot.usable ? (
                        <Badge tone="good">usable</Badge>
                      ) : lot.withdrawnReason ? (
                        <>
                          <Badge tone="critical">withdrawn</Badge>
                          <span className="mt-1 block text-xs text-ink-muted">
                            {lot.withdrawnReason}
                          </span>
                        </>
                      ) : (
                        <Badge tone="warn">expired</Badge>
                      )}
                    </td>
                    <td className="px-3 py-2">
                      {lot.usable && (
                        <EditRow label="Withdraw">
                          <p className="mb-2 text-xs text-ink-muted">
                            Stops new doses. The doses already recorded against this lot keep their
                            lot number, which is what makes a recall answerable.
                          </p>
                          <RecordForm
                            action={withdrawLot}
                            hidden={{ lotId: lot.id }}
                            columns={1}
                            fields={[
                              {
                                name: "reason",
                                label: "Why",
                                type: "textarea",
                                required: true,
                                hint: "a recall notice, a cold-chain break, a broken seal",
                              },
                            ]}
                            submitLabel="Withdraw the lot"
                          />
                        </EditRow>
                      )}
                    </td>
                  </tr>
                ))}
              </Table>
            )}
          </Card>

          <Card title="Receive a lot">
            <RecordForm
              action={receiveLot}
              fields={[
                {
                  name: "productCode",
                  label: "Vaccine",
                  type: "select",
                  options: productOptions,
                  value: selected,
                  required: true,
                },
                { name: "lotNo", label: "Lot number", required: true },
                { name: "expiresOn", label: "Expires", type: "date", required: true },
                {
                  name: "quantity",
                  label: "Doses received",
                  type: "number",
                  required: true,
                  step: "1",
                },
                {
                  name: "vvmStage",
                  label: "VVM stage",
                  type: "number",
                  step: "1",
                  hint: "optional — recorded and enforced by nothing, see above",
                },
              ]}
              submitLabel="Receive"
            />
          </Card>
        </>
      )}
    </div>
  );
}
