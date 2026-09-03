import Link from "next/link";
import { load } from "@/lib/load";
import type { Hl7Exchange, Page } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDateTime } from "@/components/ui";

/**
 * The HL7 v2 message log.
 *
 * <p>An interface engine is judged almost entirely on one question — "what did you actually receive
 * at nine o'clock?" — and the messages worth asking about are the ones that did not parse. So the
 * raw text is on the row, and the failures filter is not a convenience: an interface running for a
 * week has tens of thousands of accepted messages and a dozen that matter, and a screen that makes
 * somebody page through the former to reach the latter is a screen nobody opens twice.
 *
 * <p>The acknowledgement code is shown as itself rather than translated into "ok" and "failed".
 * AA, AE and AR are what the sender saw and what they will quote down the telephone, and the
 * difference between the last two — stop sending this, versus try again — is the whole reason there
 * are three.
 */
export default async function Hl7Page({
  searchParams,
}: {
  searchParams: Promise<{ failures?: string }>;
}) {
  const { failures } = await searchParams;
  const failuresOnly = failures === "1";

  const log = await load<Page<Hl7Exchange>>(
    `/hl7/messages?size=50${failuresOnly ? "&failuresOnly=true" : ""}`,
  );
  const rows = log.data?.content ?? [];
  const failed = rows.filter((row) => row.error !== null || row.ackCode === "AE" || row.ackCode === "AR");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">HL7 interface</h1>
        <p className="text-sm text-ink-muted">
          Every v2 message in and out, exactly as it crossed the boundary.
        </p>
      </div>

      {log.error && <ErrorNote>{log.error}</ErrorNote>}

      <div className="grid gap-4 sm:grid-cols-3">
        <Stat
          label={failuresOnly ? "Failures shown" : "Messages shown"}
          value={String(rows.length)}
          hint={`of ${log.data?.totalElements ?? 0} recorded`}
        />
        <Stat
          label="Not accepted"
          value={String(failed.length)}
          hint="answered AE or AR, or never parsed"
        />
        <Stat
          label="Inbound"
          value={String(rows.filter((row) => row.direction === "IN").length)}
          hint="the rest were sent from here"
        />
      </div>

      <Card
        title={failuresOnly ? "What went wrong" : "Messages"}
        action={
          <Link
            href={failuresOnly ? "/sharing/hl7" : "/sharing/hl7?failures=1"}
            className="text-xs underline"
          >
            {failuresOnly ? "Show everything" : "Only what failed"}
          </Link>
        }
      >
        {rows.length === 0 ? (
          <Empty>
            {failuresOnly
              ? "Nothing has failed."
              : "No HL7 message has crossed the boundary yet."}
          </Empty>
        ) : (
          <Table
            head={["When", "", "Type", "Control id", "Peer", "Ack", "What happened"]}
          >
            {rows.map((row) => (
              <tr key={row.id}>
                <td className="numeric px-3 py-2">{formatDateTime(row.receivedAt)}</td>
                <td className="px-3 py-2 text-xs text-ink-muted">
                  {row.direction === "IN" ? "in" : "out"} · {row.transport.toLowerCase()}
                </td>
                <td className="px-3 py-2">{row.messageType ?? "—"}</td>
                <td className="numeric px-3 py-2 text-xs">{row.controlId ?? "—"}</td>
                <td className="px-3 py-2 text-xs text-ink-muted">
                  {row.direction === "IN"
                    ? (row.sendingApplication ?? row.peer ?? "—")
                    : (row.receivingApplication ?? row.peer ?? "—")}
                </td>
                <td className="px-3 py-2">
                  {/*
                    Shown as the code itself. "AE" and "AR" mean different things to the sender —
                    stop, versus try again — and collapsing both into "failed" throws away the only
                    part of the answer they can act on.
                  */}
                  {row.ackCode ? (
                    <Badge tone={row.ackCode === "AA" ? "good" : "critical"}>{row.ackCode}</Badge>
                  ) : (
                    <span className="text-xs text-ink-muted">—</span>
                  )}
                </td>
                <td className="px-3 py-2 text-xs">
                  {row.error ? (
                    <span className="text-critical">{row.error}</span>
                  ) : (
                    <span className="text-ink-muted">{row.ackText ?? "accepted"}</span>
                  )}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>

      {rows.length > 0 && (
        <Card title="The most recent message, as it arrived">
          {/*
            Verbatim, in a monospaced block, with the segment separators made visible by rendering
            each on its own line. This is the artefact somebody needs when a sender insists they
            sent something: a re-serialised version would be this platform's opinion of the message
            rather than the message.
          */}
          <pre className="overflow-x-auto rounded border border-line bg-surface-raised p-3 text-xs">
            {rows[0]?.raw.split(/\r\n|\r|\n/).filter(Boolean).join("\n")}
          </pre>
          {rows[0]?.ackRaw && (
            <>
              <h3 className="mt-4 text-sm font-medium">And what was said back</h3>
              <pre className="mt-1 overflow-x-auto rounded border border-line bg-surface-raised p-3 text-xs">
                {rows[0].ackRaw.split(/\r\n|\r|\n/).filter(Boolean).join("\n")}
              </pre>
            </>
          )}
        </Card>
      )}

      <p className="text-xs text-ink-muted">
        Messages arrive over HTTP through the gateway, which needs a token, or over MLLP on a raw
        socket, which has no authentication of any kind and is off unless a deployment turns it on.
        An accepted message is recorded and published as an event; nothing on this platform consumes
        those events yet, so <strong>AA means the message arrived, parsed and was stored</strong> —
        which is what an interface engine promises, and no more.
      </p>
    </div>
  );
}
