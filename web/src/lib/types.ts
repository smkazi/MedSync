/** The platform's API shapes, as this UI consumes them. */

export type Page<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type PatientSummary = {
  id: string;
  mrn: string;
  fullName: string;
  dateOfBirth: string;
  age: number;
  sex: string;
  phone: string | null;
  active: boolean;
  hasCriticalAllergy: boolean;
};

export type Allergy = {
  id: string;
  substance: string;
  reaction: string | null;
  severity: "MILD" | "MODERATE" | "SEVERE" | "LIFE_THREATENING";
  critical: boolean;
  recordedBy: string | null;
  recordedAt: string;
};

export type Patient = PatientSummary & {
  firstName: string;
  lastName: string;
  bloodGroup: string | null;
  email: string | null;
  city: string | null;
  insuranceProvider: string | null;
  emergencyContactName: string | null;
  emergencyContactPhone: string | null;
  notes: string | null;
  deceased: boolean;
  allergies: Allergy[];
};

export type Appointment = {
  id: string;
  patientId: string;
  patientMrn: string;
  clinicianId: string;
  clinicianName: string | null;
  departmentCode: string;
  startsAt: string;
  endsAt: string;
  status: "BOOKED" | "CHECKED_IN" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED" | "NO_SHOW";
  priority: "ROUTINE" | "URGENT" | "STAT";
  reason: string | null;
  bookedBy: string;
  checkedInAt: string | null;
  noShowRisk: { score: number; band: string } | null;
  encounterId: string | null;
};

export type Slot = {
  startsAt: string;
  endsAt: string;
  available: boolean;
  unavailableReason: string | null;
};

export type Availability = {
  clinicianId: string;
  date: string;
  slotMinutes: number;
  slots: Slot[];
};

export type ClinicalNote = {
  id: string;
  revision: number;
  subjective: string | null;
  objective: string | null;
  assessment: string | null;
  plan: string | null;
  author: string;
  signed: boolean;
  signedAt: string | null;
  signedBy: string | null;
  amendsId: string | null;
};

export type Vitals = {
  id: string;
  recordedAt: string;
  recordedBy: string;
  heartRate: number | null;
  systolicBp: number | null;
  diastolicBp: number | null;
  respiratoryRate: number | null;
  temperatureC: number | null;
  oxygenSaturation: number | null;
  weightKg: number | null;
  heightCm: number | null;
  painScore: number | null;
  consciousness: string | null;
  bodyMassIndex: number | null;
};

export type Diagnosis = {
  id: string;
  icd10Code: string;
  description: string;
  category: "PRIMARY" | "SECONDARY" | "PROVISIONAL";
  recordedBy: string;
};

export type Encounter = {
  id: string;
  appointmentId: string | null;
  patientId: string;
  patientMrn: string;
  clinicianId: string;
  departmentCode: string;
  encounterType: string;
  startedAt: string;
  endedAt: string | null;
  status: "OPEN" | "CLOSED";
  notes: ClinicalNote[];
  vitals: Vitals[];
  diagnoses: Diagnosis[];
};

export type LabResult = {
  id: string;
  parameter: string;
  displayName: string;
  value: string | null;
  unit: string;
  referenceRange: string;
  flag: string;
  abnormal: boolean;
  source: "ANALYZER" | "MANUAL" | "DERIVED";
  status: "ENTERED" | "VERIFIED" | "AMENDED";
  enteredBy: string | null;
  verifiedBy: string | null;
};

export type LabOrderSummary = {
  id: string;
  patientId: string;
  patientMrn: string;
  priority: "ROUTINE" | "URGENT" | "STAT";
  status: string;
  orderedAt: string;
  testCount: number;
  resultCount: number;
  hasAbnormalResults: boolean;
  accessionNo: string | null;
};

export type Histogram = {
  group: string;
  x: number[];
  y: number[];
  xLabel: string;
  indices: Record<string, number>;
};

export type LabOrder = LabOrderSummary & {
  patientSex: string;
  orderedBy: string;
  departmentCode: string;
  clinicalNotes: string | null;
  items: { id: string; testCode: string; testName: string }[];
  specimens: { id: string; accessionNo: string; specimenType: string; status: string }[];
  results: LabResult[];
  histograms: Histogram[];
};

export type Department = { id: string; code: string; name: string; description: string | null };

export type Staff = {
  id: string;
  userId: string | null;
  employeeNo: string;
  fullName: string;
  designation: string;
  departmentCode: string | null;
  departmentName: string | null;
  specialty: string | null;
  active: boolean;
};

export type NoteSummary = {
  result: {
    summary: string;
    presenting_complaint: string;
    key_findings: string[];
    assessment: string;
    plan: string[];
    follow_up: string;
    red_flags: string[];
  };
  provenance: { model: string; fallback_used: boolean; confidence: number; disclaimer: string };
};

export type CodeSuggestion = {
  code: string;
  description: string;
  score: number;
  matched_terms: string[];
};

export type CodingResponse = {
  suggestions: CodeSuggestion[];
  provenance: { model: string; fallback_used: boolean; confidence: number; disclaimer: string };
};

export type TriageResponse = {
  acuity: number;
  acuity_label: string;
  target_assessment_minutes: number;
  drivers: string[];
  red_flags: string[];
  recommended_disposition: string;
  provenance: { model: string; fallback_used: boolean; confidence: number; disclaimer: string };
};
