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
  cancelledReason: string | null;
  noShowRisk: { score: number; band: string } | null;
  encounterId: string | null;
  /** Null when the booking has no room — a slot taken before one was assigned, or a teleconsult. */
  room: RoomView | null;
};

/**
 * Wayfinding, as a patient reads it.
 *
 * <p>Only `code` is stored on the appointment; the name, floor and directions are resolved live, so
 * a renamed room never leaves stale text on an existing booking. `resolved` is false when the
 * directory could not answer for the code — a decommissioned room, or a brief outage — and the UI
 * then shows the bare code rather than pretending there is no room.
 */
export type RoomView = {
  code: string;
  name: string | null;
  floorName: string | null;
  directions: string | null;
  resolved: boolean;
};

/** A room that can carry an appointment, from GET /rooms/bookable. */
export type BookableRoom = {
  id: string;
  code: string;
  name: string;
  roomTypeCode: string;
  roomTypeName: string;
  floorName: string | null;
  departmentCode: string | null;
  bookable: boolean;
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
  interpretation: Interpretation | null;
};

/** The narrative on a report: comments, plus the peripheral-smear morphology. */
export type Interpretation = {
  notes: string[];
  morphology: Morphology | null;
};

export type Morphology = {
  /** A pathologist's own comment. Present only when one was entered by hand. */
  comment: string | null;
  redCells: string | null;
  whiteCells: string | null;
  platelets: string | null;
  /** False when a human wrote it. Shown to the reader, not hidden. */
  derived: boolean;
};

/** `active` is returned by the API and was missing from this type until the facility screens read it. */
export type Department = {
  id: string;
  code: string;
  name: string;
  description: string | null;
  active: boolean;
};

export type Staff = {
  id: string;
  /** Null when the staff record has no platform login - a visiting consultant, say. */
  userId: string | null;
  employeeNo: string;
  fullName: string;
  designation: string;
  departmentCode: string | null;
  departmentName: string | null;
  specialty: string | null;
  /** Registration or licence number. Returned by the API; was missing from this type. */
  licenseNo: string | null;
  phone: string | null;
  email: string | null;
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

// ---- facility ---------------------------------------------------------------

export type Floor = { id: string; code: string; name: string; level: number; active: boolean };

export type RoomType = {
  code: string;
  name: string;
  description: string | null;
  /** Patients are seen or treated here. Governs beds and clinical filters. */
  clinical: boolean;
  /** Space is handed out as a bed rather than a calendar slot. */
  bedAllocated: boolean;
  /** Rooms of this type may carry appointments. */
  schedulable: boolean;
  displayOrder: number;
  active: boolean;
};

export type Room = {
  id: string;
  code: string;
  name: string;
  roomTypeCode: string;
  roomTypeName: string;
  clinical: boolean;
  bedAllocated: boolean;
  schedulable: boolean;
  floorCode: string;
  floorName: string;
  floorLevel: number;
  departmentCode: string | null;
  departmentName: string | null;
  capacity: number;
  bedCount: number;
  widthFt: number | null;
  lengthFt: number | null;
  /** Pre-formatted, e.g. 15'6" x 8'2". Null when the room was never measured. */
  dimensions: string | null;
  directions: string | null;
  bookable: boolean;
  /** bookable AND active AND the type is schedulable - what the booking screen must obey. */
  bookableNow: boolean;
  active: boolean;
  notes: string | null;
};

/** One room as the directory returns it - fewer fields than the full Room. */
export type DirectoryRoom = {
  id: string;
  code: string;
  name: string;
  roomTypeCode: string;
  roomTypeName: string;
  floorName: string;
  departmentCode: string | null;
  bookable: boolean;
};

export type FloorDirectory = { floor: Floor; rooms: DirectoryRoom[] };

export type Bed = {
  id: string;
  code: string;
  label: string | null;
  active: boolean;
  roomCode: string;
  roomName: string;
  floorName: string;
};

// ---- laboratory reference data ----------------------------------------------

export type CatalogEntry = {
  id: string;
  code: string;
  name: string;
  department: string;
  specimenType: string;
  parameters: string[];
};

export type ReferenceRange = {
  id: string;
  parameter: string;
  /** M or F. The platform uses one field for administrative gender and the clinical variable. */
  sex: string;
  normalLow: number | null;
  normalHigh: number | null;
  unit: string;
  displayName: string;
  /** Pre-formatted, e.g. "11.5 - 14.5". */
  referenceRange: string;
};

export type Analyzer = {
  id: string;
  name: string;
  model: string;
  protocol: string;
  transport: string;
  active: boolean;
  lastSeen: string | null;
};

export type MorphologyThreshold = { code: string; threshold: number; note: string };

export type RuleCondition = {
  id: string;
  parameters: string[];
  operator: string;
  threshold: number;
};

export type InterpretiveRule = {
  id: string;
  code: string;
  label: string;
  message: string;
  displayOrder: number;
  active: boolean;
  conditions: RuleCondition[];
};

export type DeviceMessage = {
  id: string;
  protocol: string;
  sampleId: string | null;
  matchedOrderId: string | null;
  parsedOk: boolean;
  resultCount: number | null;
  error: string | null;
  receivedAt?: string;
};

// ---- administration ---------------------------------------------------------

export type AdminUser = {
  id: string;
  username: string;
  email: string;
  fullName: string;
  active: boolean;
  mustChangePassword: boolean;
  roles: string[];
  lastLoginAt: string | null;
};

export type RoleSummary = { code: string; description: string };

export type AuditEntry = {
  id: string;
  service: string;
  action: string;
  entity: string;
  entityId: string | null;
  detail: string | null;
  username: string | null;
  correlationId: string | null;
  occurredAt: string;
};
