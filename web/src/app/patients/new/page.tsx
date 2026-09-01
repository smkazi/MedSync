import { redirect } from "next/navigation";
import { currentUser, hasRole } from "@/lib/session";
import { RegisterPatientForm } from "./RegisterPatientForm";

/**
 * Register a patient.
 *
 * <p>The role check here is a courtesy, not the control. `POST /patients` carries
 * `@PreAuthorize(Roles.FRONT_DESK)` and refuses anyone else whatever this page renders; rendering
 * the form to a technician who cannot submit it would just be a worse way to say 403.
 */
export default async function RegisterPatientPage() {
  const user = await currentUser();
  if (!user) redirect("/login");
  if (!hasRole(user, "ADMIN", "RECEPTIONIST", "DOCTOR", "NURSE")) {
    redirect("/patients");
  }

  return (
    <div className="max-w-3xl space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Register a patient</h1>
        <p className="text-sm text-ink-muted">
          The MRN is issued by the platform on save — it is never typed in, so two desks cannot
          allocate the same one.
        </p>
      </div>

      <RegisterPatientForm />
    </div>
  );
}
