import { load } from "@/lib/load";
import type { OrderSet } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table } from "@/components/ui";

/**
 * The order sets this hospital has, as a reference list.
 *
 * <p>Read-only, deliberately, and the page says so. A set is <em>applied</em> from an open chart —
 * that is where the patient and the encounter are — so an Apply button here would have nothing to
 * apply it to. What this screen is for is the other question: "what does the sepsis set actually
 * contain", asked away from a patient, by somebody deciding whether to use it or to change it.
 *
 * <p>Composing one is administrative and has no screen. That is a named gap rather than an
 * oversight: the shape of a set is a clinical governance decision — a template applied in one
 * click by anybody who may chart — and a form that let one be typed in without review would be
 * the wrong control. It is `POST /order-sets`, admin-only, until there is a review workflow to
 * put in front of it.
 */
export default async function OrderSetsPage() {
  const { data, error } = await load<OrderSet[]>("/order-sets?includeInactive=true");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Order sets</h1>
        <p className="text-sm text-ink-muted">
          What each set raises. Applied from a chart, where the patient is.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      {(data ?? []).length === 0 ? (
        <Card title="Sets">
          <Empty>No order sets are configured.</Empty>
        </Card>
      ) : (
        (data ?? []).map((set) => (
          <Card key={set.id} title={`${set.name} — ${set.code}`}>
            {set.description && <p className="mb-3 text-sm text-ink-muted">{set.description}</p>}
            {!set.active && (
              <p className="mb-3">
                <Badge tone="neutral">retired</Badge>
                <span className="ml-2 text-xs text-ink-muted">
                  Kept so that orders raised from it can still be traced back, and refused if
                  anybody tries to apply it.
                </span>
              </p>
            )}
            <Table head={["Raises", "Code", "Detail"]}>
              {set.items.map((item) => (
                <tr key={item.id}>
                  <td className="px-3 py-2">
                    <Badge tone={item.kind === "MEDICATION" ? "warn" : "neutral"}>
                      {item.kind === "MEDICATION" ? "medicine" : "test"}
                    </Badge>
                  </td>
                  <td className="numeric px-3 py-2">{item.code}</td>
                  <td className="px-3 py-2 text-sm">
                    {item.kind === "MEDICATION" ? (
                      <>
                        {item.dose}, {item.frequency}, {item.durationDays} day(s), {item.quantity}{" "}
                        to dispense
                        {item.instructions && (
                          <span className="block text-xs text-ink-muted">{item.instructions}</span>
                        )}
                      </>
                    ) : (
                      <span className="text-ink-muted">{item.priority?.toLowerCase()}</span>
                    )}
                  </td>
                </tr>
              ))}
            </Table>
            <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
              {set.departmentCode
                ? `Offered in ${set.departmentCode}, and to anybody who asks for it by name.`
                : "General: offered in every department."}
            </p>
          </Card>
        ))
      )}

      <Card title="Why there is no form here">
        <p className="text-sm text-ink-muted">
          A set is applied in one click by anybody who may chart, so what goes into one is a
          clinical governance decision rather than a data-entry task. The endpoint exists and is
          administrator-only (<span className="numeric">POST /order-sets</span>); a screen for it
          waits for a review step to put in front of it, and the README says so rather than this
          page pretending the feature is missing.
        </p>
      </Card>
    </div>
  );
}
