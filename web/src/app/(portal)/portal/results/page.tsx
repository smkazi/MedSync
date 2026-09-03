import Link from "next/link";
import { load } from "@/lib/load";
import { Badge, Card, Empty, ErrorNote, Table, formatDateTime } from "@/components/ui";
import type { PortalReport } from "@/lib/types";

export const metadata = { title: "Your test results — MedSync" };

/**
 * A patient's own laboratory tests, and the reports that have been released.
 *
 * <p>The list says what stage a test has reached and never what it found. A result entered at the
 * bench is provisional — it may be an analyzer artefact, a mislabelled tube or a dilution nobody has
 * repeated — so this screen shows "In the laboratory" and no numbers until a pathologist has
 * verified it. That is not caution about the platform's arithmetic; it is the workflow the whole
 * laboratory is built around, and publishing round it would make the patient the first reader of a
 * number that may be wrong.
 */
export default async function PortalResults() {
  const reports = await load<PortalReport[]>("/portal/reports");
  const rows = reports.data ?? [];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Your test results</h1>
        <p className="mt-1 text-sm text-ink-muted">
          A report appears here once a pathologist has checked and released it. Until then the
          numbers are provisional and are not shown.
        </p>
      </div>

      <Card title="Laboratory tests">
        {reports.error ? <ErrorNote>{reports.error}</ErrorNote> : null}
        {rows.length === 0 ? (
          <Empty>No laboratory tests on your record yet.</Empty>
        ) : (
          <Table head={["Requested", "Tests", "Progress", ""]}>
            {rows.map((report) => (
              <tr key={report.orderId} className="border-t border-line">
                <td className="px-3 py-2">{formatDateTime(report.orderedAt)}</td>
                <td className="px-3 py-2">{report.tests.join(", ")}</td>
                <td className="px-3 py-2">
                  <Badge tone={report.reportAvailable ? "good" : "neutral"}>{report.progress}</Badge>
                </td>
                <td className="px-3 py-2">
                  {report.reportAvailable ? (
                    <Link href={`/portal/results/${report.orderId}`} className="text-sm underline">
                      Open report
                    </Link>
                  ) : (
                    <span className="text-xs text-ink-muted">Not released yet</span>
                  )}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
    </div>
  );
}
