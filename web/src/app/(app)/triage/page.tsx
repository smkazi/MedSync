import { TriageForm } from "@/components/TriageForm";
import { Card } from "@/components/ui";

/** Triage intake: vitals and complaint in, an explained acuity out. */
export default function TriagePage() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Triage</h1>
        <p className="text-sm text-ink-muted">
          Record what was measured. The assessment states exactly what set the acuity, so you can
          disagree with it.
        </p>
      </div>
      <Card>
        <TriageForm />
      </Card>
    </div>
  );
}
