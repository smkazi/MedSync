import { load } from "@/lib/load";
import type { Antigen, VaccineProduct } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table } from "@/components/ui";

/**
 * The vaccine catalogue: products, and what each one contains.
 *
 * <p><strong>Two tables joined, and the join is the whole design.</strong> This is the pharmacy's
 * argument one department along, and it should be read in the same words its schema uses:
 * "ingredients, not brand names, are what the checks run on". The immunisation form of it is that a
 * child vaccinated against measles is vaccinated against it under every trade name and inside every
 * combination product it arrived in.
 *
 * <p>Product-only would fail the question "is this child covered for Hib?", which would need code
 * that knows pentavalent contains Hib — the exact failure `docs/extensibility.md` records as its
 * worked example. Antigen-only would fail a recall, which names a <em>lot of a product</em>, and a
 * register that cannot say which arm a recalled vial went into cannot do the one thing a register
 * is for in a bad week. So both, joined.
 *
 * <p><strong>A contents list is written once and never edited</strong>, which is why there is no
 * form to change one here: a child recorded as having had PENTA in 2024 had whatever PENTA
 * contained in 2024, and rewriting that row would silently rewrite what every historical dose is
 * taken to have covered. A reformulated vaccine is a new product code.
 */
export default async function VaccinesPage() {
  const [products, antigens] = await Promise.all([
    load<VaccineProduct[]>("/vaccines/products"),
    load<Antigen[]>("/vaccines/antigens"),
  ]);

  const rows = products.data ?? [];
  const list = antigens.data ?? [];
  // Which antigens no active product covers. Worth surfacing rather than leaving to be discovered:
  // a schedule row for an antigen nothing supplies produces a due list nobody can act on.
  const supplied = new Set(rows.filter((row) => row.active).flatMap((row) => row.antigenCodes));
  const unsupplied = list.filter((antigen) => antigen.active && !supplied.has(antigen.code));

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Vaccines</h1>
        <p className="text-sm text-ink-muted">
          What the store carries, and what each product protects against. Coverage is asked about
          antigens; a recall names a lot of a product; the register holds both.
        </p>
      </div>

      {products.error && <ErrorNote>{products.error}</ErrorNote>}
      {antigens.error && <ErrorNote>{antigens.error}</ErrorNote>}

      {unsupplied.length > 0 && (
        <p className="rounded-md border border-warn/40 bg-warn-soft px-3 py-2 text-sm text-warn">
          {unsupplied.length} antigen(s) are on the list and in no active product:{" "}
          {unsupplied.map((antigen) => antigen.code).join(", ")}. A schedule row for one of these
          produces a due list nobody can act on.
        </p>
      )}

      <Card title="Products">
        {rows.length === 0 ? (
          <Empty>Nothing in the catalogue.</Empty>
        ) : (
          <Table head={["Code", "Product", "Manufacturer", "Route", "Doses per vial", "Covers", ""]}>
            {rows.map((product) => (
              <tr key={product.code} className="border-t border-line">
                <td className="numeric px-3 py-2">{product.code}</td>
                <td className="px-3 py-2">{product.name}</td>
                <td className="px-3 py-2 text-xs">{product.manufacturer ?? "—"}</td>
                <td className="px-3 py-2 text-xs">
                  {product.route.toLowerCase().replace(/_/g, " ")}
                </td>
                <td className="numeric px-3 py-2">{product.dosesPerVial}</td>
                <td className="px-3 py-2 text-xs">{product.antigenCodes.join(", ")}</td>
                <td className="px-3 py-2">
                  {product.active ? (
                    <Badge tone="good">orderable</Badge>
                  ) : (
                    // A retired product still takes a card dose and takes no new one: a child
                    // vaccinated with it in 2023 was still vaccinated.
                    <Badge tone="neutral">retired</Badge>
                  )}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>

      <Card title="Antigens">
        {list.length === 0 ? (
          <Empty>Nothing on the list.</Empty>
        ) : (
          <Table head={["Code", "Antigen", "Protects against", "In products", ""]}>
            {list.map((antigen) => {
              const inProducts = rows.filter((product) =>
                product.antigenCodes.includes(antigen.code),
              );
              return (
                <tr key={antigen.code} className="border-t border-line">
                  <td className="numeric px-3 py-2">{antigen.code}</td>
                  <td className="px-3 py-2">{antigen.name}</td>
                  <td className="px-3 py-2 text-xs">{antigen.protectsAgainst ?? "—"}</td>
                  <td className="px-3 py-2 text-xs">
                    {inProducts.length === 0 ? (
                      <span className="text-ink-muted">none</span>
                    ) : (
                      inProducts.map((product) => product.code).join(", ")
                    )}
                  </td>
                  <td className="px-3 py-2">
                    {antigen.active ? null : <Badge tone="neutral">retired</Badge>}
                  </td>
                </tr>
              );
            })}
          </Table>
        )}
      </Card>

      <p className="text-xs text-ink-muted">
        Read-only here. A product and its contents list are added by an administrator through the
        API and there is deliberately no edit form for a contents list: a child recorded as having
        had a combination product had whatever that product contained on the day, and rewriting the
        row would silently rewrite what every historical dose is taken to have covered. A
        reformulated vaccine is a new code.
      </p>
    </div>
  );
}
