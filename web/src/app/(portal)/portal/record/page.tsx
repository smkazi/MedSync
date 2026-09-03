import { load } from "@/lib/load";
import { Badge, Card, Empty, ErrorNote, Table, formatDateTime } from "@/components/ui";
import type { MyDisclosure, PortalProfile } from "@/lib/types";

export const metadata = { title: "My record — MedSync" };

/**
 * What the hospital holds about this patient, and a copy of it to take away.
 *
 * <p>The download is the "transmit" half of the certification criterion this satisfies: a FHIR
 * bundle of the patient's visits, released results and prescriptions, saved as a file they can give
 * to another hospital. It goes through this app's own route handler rather than a link at the
 * gateway, because the bearer token is in an httpOnly cookie the browser cannot read.
 *
 * <p>The allergy list is the most valuable thing on this screen. It is what refuses a prescription
 * later, and an allergy recorded wrongly is the single most useful error for a patient to notice —
 * which is why it is here and why the page says what to do about it.
 */
export default async function PortalRecord() {
  const profile = await load<PortalProfile>("/portal/me");
  // Whose record this is comes from the signed claim in the session, so there is nothing to pass
  // and nothing to tamper with. A failure here must not take the whole page down: the allergy list
  // above is the most important thing on this screen.
  const released = await load<MyDisclosure[]>("/portal/records/disclosures");

  if (!profile.data) {
    return (
      <div className="space-y-4">
        <h1 className="text-xl font-semibold tracking-tight">My record</h1>
        <ErrorNote>{profile.error ?? "Your record could not be read."}</ErrorNote>
      </div>
    );
  }

  const me = profile.data;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">My record</h1>
        <p className="mt-1 text-sm text-ink-muted">
          What this hospital holds about you. Please check it and tell the front desk if anything is
          wrong.
        </p>
      </div>

      <Card title="You">
        <dl className="grid gap-3 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-ink-muted">Name</dt>
            <dd>{me.fullName}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Hospital number</dt>
            <dd>{me.mrn}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Date of birth</dt>
            <dd>
              {me.dateOfBirth} <span className="text-ink-muted">({me.age})</span>
            </dd>
          </div>
          <div>
            <dt className="text-ink-muted">Sex recorded</dt>
            <dd>{me.sex}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Blood group</dt>
            <dd>{me.bloodGroup ?? "Not recorded"}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Telephone</dt>
            <dd>{me.phone ?? "Not recorded"}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Email</dt>
            <dd>{me.email ?? "Not recorded"}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Address</dt>
            <dd>
              {[me.addressLine1, me.addressLine2, me.city, me.state, me.postalCode, me.country]
                .filter(Boolean)
                .join(", ") || "Not recorded"}
            </dd>
          </div>
          <div>
            <dt className="text-ink-muted">Insurance</dt>
            <dd>{me.insuranceProvider ?? "None recorded"}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Emergency contact</dt>
            <dd>
              {me.emergencyContactName
                ? `${me.emergencyContactName}${me.emergencyContactPhone ? ` · ${me.emergencyContactPhone}` : ""}`
                : "None recorded"}
            </dd>
          </div>
        </dl>
      </Card>

      <Card title="Allergies the hospital has recorded" tone={me.allergies.some((a) => a.critical) ? "critical" : "default"}>
        {me.allergies.length === 0 ? (
          <Empty>None recorded. If you have an allergy, please tell the front desk.</Empty>
        ) : (
          <Table head={["Substance", "What happened", "How serious", ""]}>
            {me.allergies.map((allergy) => (
              <tr key={`${allergy.substance}-${allergy.recordedAt}`} className="border-t border-line">
                <td className="px-3 py-2 font-medium">{allergy.substance}</td>
                <td className="px-3 py-2 text-ink-muted">{allergy.reaction ?? "Not recorded"}</td>
                <td className="px-3 py-2">
                  <Badge tone={allergy.critical ? "critical" : "warn"}>{allergy.severity}</Badge>
                </td>
                <td className="px-3 py-2 text-xs text-ink-muted">
                  Recorded {formatDateTime(allergy.recordedAt)}
                </td>
              </tr>
            ))}
          </Table>
        )}
        <p className="mt-3 text-sm text-ink-muted">
          This list is what stops a medicine being prescribed or dispensed to you. If anything here
          is wrong, or something is missing, tell the front desk before your next appointment.
        </p>
      </Card>

      <Card title="What has left this hospital about you">
        {released.error ? (
          <ErrorNote>{released.error}</ErrorNote>
        ) : (released.data ?? []).length === 0 ? (
          <Empty>
            Nothing about you has been released to anybody, and you have not yet downloaded a copy
            yourself.
          </Empty>
        ) : (
          <Table head={["When", "What", "To whom", "Why", "How much"]}>
            {(released.data ?? []).map((row) => (
              <tr key={row.id} className="border-t border-line">
                <td className="numeric px-3 py-2">{formatDateTime(row.releasedAt)}</td>
                <td className="px-3 py-2 text-xs">{row.hiType.toLowerCase().replaceAll("_", " ")}</td>
                <td className="px-3 py-2">
                  {row.kind === "PATIENT_EXPORT" ? "You — your own copy" : row.recipient}
                </td>
                <td className="numeric px-3 py-2 text-xs text-ink-muted">
                  {row.artefactId ?? "Your own copy, so no consent was needed"}
                </td>
                <td className="numeric px-3 py-2 text-ink-muted">
                  {row.resourceCount} item{row.resourceCount === 1 ? "" : "s"}
                </td>
              </tr>
            ))}
          </Table>
        )}
        <p className="mt-3 text-sm text-ink-muted">
          This is written the moment a record leaves, not reconstructed afterwards. If a release
          here is one you did not agree to, tell the front desk — the hospital can tell you exactly
          what was sent and revoke the consent behind it.
        </p>
      </Card>

      <Card
        title="Take a copy with you"
        action={
          <a href="/api/portal/record" className="text-sm underline">
            Download my record
          </a>
        }
      >
        <p className="text-sm text-ink-muted">
          A machine-readable copy of your visits, your released test results and your prescriptions,
          in the FHIR format other hospitals and health apps can read. It is a file you can save and
          give to whoever you choose. Every download is recorded in this hospital&apos;s disclosure
          log, as any other copy leaving the platform would be.
        </p>
      </Card>
    </div>
  );
}
