import { load } from "@/lib/load";
import type { DeviceMessage, Page } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table } from "@/components/ui";

/**
 * The analyzer transmission log.
 *
 * <p>The one place a technician can see why an upload did not land. A transmission that parsed but
 * matched no order is the interesting row: the sample id on the tube and the accession the platform
 * issued did not agree, and nothing else in the system will say so.
 */
export default async function DeviceMessagesPage() {
  const { data: messages, error } = await load<Page<DeviceMessage>>("/lab/device-messages?size=100");

  const unmatched = (messages?.content ?? []).filter(
    (message) => message.parsedOk && !message.matchedOrderId,
  ).length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Device messages</h1>
        <p className="text-sm text-ink-muted">
          Raw transmissions from the analyzers, newest first.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      {unmatched > 0 && (
        <ErrorNote>
          {unmatched} transmission(s) parsed but matched no order. The sample id the instrument sent
          does not match an accession number — check the tube against the collection list.
        </ErrorNote>
      )}

      {messages && (
        <Card title={`Transmissions (${messages.totalElements})`}>
          {messages.content.length === 0 ? (
            <Empty>No analyzer transmissions have been received.</Empty>
          ) : (
            <Table head={["Protocol", "Sample", "Parsed", "Results", "Matched", "Error"]}>
              {messages.content.map((message) => (
                <tr key={message.id}>
                  <td className="px-3 py-2">
                    <Badge tone="accent">{message.protocol}</Badge>
                  </td>
                  <td className="numeric px-3 py-2">{message.sampleId ?? "—"}</td>
                  <td className="px-3 py-2">
                    {message.parsedOk ? (
                      "yes"
                    ) : (
                      <Badge tone="critical">failed</Badge>
                    )}
                  </td>
                  <td className="numeric px-3 py-2">{message.resultCount ?? "—"}</td>
                  <td className="px-3 py-2">
                    {message.matchedOrderId ? (
                      <span className="text-xs text-ink-muted">matched</span>
                    ) : message.parsedOk ? (
                      <Badge tone="critical">no order</Badge>
                    ) : (
                      "—"
                    )}
                  </td>
                  <td className="px-3 py-2 text-xs text-ink-muted">{message.error ?? "—"}</td>
                </tr>
              ))}
            </Table>
          )}
        </Card>
      )}
    </div>
  );
}
