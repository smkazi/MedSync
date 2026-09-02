import Link from "next/link";
import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type { Patient } from "@/lib/types";
import { Card, ErrorNote } from "@/components/ui";
import { EditPatientForm } from "../EditPatientForm";

/**
 * Editing a patient.
 *
 * <p>A screen of its own rather than fields that become editable in place on the chart: the chart
 * is read at a bedside by people who are not correcting it, and an accidental keystroke on a date
 * of birth is not something anybody would notice.
 */
export default async function EditPatientPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const user = await currentUser();

  // FRONT_DESK guards PATCH /patients/{id}. Showing the form to a role that cannot submit it
  // would just be a worse way to say 403.
  if (!hasRole(user, "ADMIN", "RECEPTIONIST", "DOCTOR", "NURSE")) {
    return (
      <div className="space-y-4">
        <h1 className="text-xl font-semibold tracking-tight">Edit patient</h1>
        <ErrorNote>Your role does not have permission to change a patient record.</ErrorNote>
      </div>
    );
  }

  const { data: patient, error } = await load<Patient>(`/patients/${id}`);
  if (!patient) {
    return (
      <div className="space-y-4">
        <h1 className="text-xl font-semibold tracking-tight">Edit patient</h1>
        <ErrorNote>{error ?? "This record could not be loaded."}</ErrorNote>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Edit {patient.fullName}</h1>
        <p className="numeric text-sm text-ink-muted">
          <Link href={`/patients/${patient.id}`} className="text-accent hover:underline">
            {patient.mrn}
          </Link>
        </p>
      </div>

      <Card title="Demographics">
        <EditPatientForm patient={patient} />
      </Card>
    </div>
  );
}
