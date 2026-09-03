import Link from "next/link";
import { load } from "@/lib/load";
import { Badge, Card, Empty, ErrorNote, Table, formatDateTime } from "@/components/ui";
import type { LabOrder } from "@/lib/types";

export const metadata = { title: "Your report — MedSync" };

/**
 * One released report, in full.
 *
 * <p>The numbers and the interval each was read against, not a traffic light. "Abnormal" without a
 * value asks the patient to take the platform's word for a judgement that depends on which
 * reference interval was applied to them, and a patient comparing this year's haemoglobin with last
 * year's is doing something useful that a coloured dot cannot support.
 *
 * <p>The PDF link goes through this app's own route handler rather than at the gateway: the bearer
 * token is in an httpOnly cookie the browser cannot read, so a direct link would arrive with no
 * credential and answer 401.
 */
export default async function PortalReportPage({
  params,
}: {
  params: Promise<{ orderId: string }>;
}) {
  const { orderId } = await params;
  const order = await load<LabOrder>(`/portal/reports/${orderId}`);

  if (!order.data) {
    return (
      <div className="space-y-4">
        <h1 className="text-xl font-semibold tracking-tight">Your report</h1>
        <ErrorNote>{order.error ?? "This report could not be opened."}</ErrorNote>
        <Link href="/portal/results" className="text-sm underline">
          Back to your results
        </Link>
      </div>
    );
  }

  const report = order.data;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">
          {report.items.map((item) => item.testName).join(", ")}
        </h1>
        <p className="mt-1 text-sm text-ink-muted">
          Requested {formatDateTime(report.orderedAt)} · released and checked by a pathologist
        </p>
      </div>

      <Card
        title="Results"
        action={
          <a href={`/api/portal/reports/${report.id}`} className="text-sm underline">
            Download the signed report (PDF)
          </a>
        }
      >
        {report.results.length === 0 ? (
          <Empty>This report carries no measured values.</Empty>
        ) : (
          <Table head={["Test", "Result", "Unit", "Normal range", ""]}>
            {report.results.map((result) => (
              <tr key={result.id} className="border-t border-line">
                <td className="px-3 py-2">{result.displayName ?? result.parameter}</td>
                <td className="px-3 py-2 font-medium">{result.value}</td>
                <td className="px-3 py-2 text-ink-muted">{result.unit ?? "—"}</td>
                <td className="px-3 py-2 text-ink-muted">{result.referenceRange ?? "—"}</td>
                <td className="px-3 py-2">
                  {result.abnormal ? (
                    <Badge tone="warn">Outside the usual range</Badge>
                  ) : (
                    <Badge tone="good">Within range</Badge>
                  )}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>

      {/* A number outside a reference interval is not a diagnosis, and the commonest harm a portal
          does is a patient reading one as though it were. The sentence is deliberately plain. */}
      <p className="rounded-md border border-line bg-surface-raised px-4 py-3 text-sm text-ink-muted">
        A result outside the usual range is common and is often not a problem. What it means depends
        on the rest of your health, and your clinician will go through it with you. If you have not
        heard from the hospital and you are worried, telephone the department.
      </p>

      <Link href="/portal/results" className="text-sm underline">
        Back to your results
      </Link>
    </div>
  );
}
