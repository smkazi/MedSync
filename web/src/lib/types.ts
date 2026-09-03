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
  addressLine1: string | null;
  addressLine2: string | null;
  city: string | null;
  state: string | null;
  postalCode: string | null;
  country: string | null;
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
  /** NEWS2 scores 2 for any supplemental oxygen, so it is recorded rather than inferred. */
  onSupplementalOxygen: boolean;
  bodyMassIndex: number | null;
  news2: News2 | null;
};

/**
 * The early warning score for one set of observations.
 *
 * `missing` is rendered, not hidden: a NEWS2 of 3 from four observations is a different fact from
 * a NEWS2 of 3 from seven, and a screen that could not tell them apart would invite a wrong
 * reading. `escalation` is the hospital's own policy and can be absent if no row is configured —
 * the score is still shown, because a configuration gap is not a reason to withhold a number a
 * clinician is looking at.
 */
export type News2 = {
  total: number;
  band: "NONE" | "LOW" | "LOW_MEDIUM" | "MEDIUM" | "HIGH";
  anyParameterScoredThree: boolean;
  components: { parameter: string; value: string; score: number }[];
  missing: string[];
  escalation: { monitoring: string; response: string; setting: string } | null;
};

export type EscalationPolicy = {
  id: string;
  band: News2["band"];
  monitoring: string;
  response: string;
  setting: string;
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
  /** M, F, or null. Nullable since laboratory V5: "not recorded" is a real state, and an order
   *  carrying it gets no sex-specific reference interval rather than the male one by default. */
  patientSex: string | null;
  orderedBy: string;
  /** The service names this `department`, not `departmentCode`, and this said the latter. */
  department: string | null;
  /** The encounter it was raised from, when a clinician ordered it from a chart. */
  encounterId: string | null;
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

// ---- outbound messaging -----------------------------------------------------

export type NotificationChannel = "LOG" | "EMAIL" | "SMS";

export type NotificationCategory =
  | "LAB_REPORT_READY"
  | "APPOINTMENT_CONFIRMED"
  | "APPOINTMENT_REMINDER"
  | "APPOINTMENT_CANCELLED"
  | "PORTAL_MESSAGE";

/**
 * One attempt to tell somebody something.
 *
 * `SUPPRESSED` is a real outcome rather than a failure: nothing was sent, on purpose, because
 * there was nowhere to send it or the record says not to. `failedReason` carries the platform's
 * own words either way.
 */
export type Notification = {
  id: string;
  channel: NotificationChannel;
  category: NotificationCategory;
  recipient: string | null;
  subject: string | null;
  body: string;
  status: "SENT" | "FAILED" | "SUPPRESSED";
  attempts: number;
  patientId: string | null;
  reference: string | null;
  createdAt: string;
  sentAt: string | null;
  failedReason: string | null;
};

/** What this deployment can actually send with, so a screen does not offer what does not exist. */
export type MessagingCapabilities = {
  channels: NotificationChannel[];
  contactLookupConfigured: boolean;
};

export type MessageTemplate = {
  id: string;
  category: NotificationCategory;
  channel: NotificationChannel;
  subject: string | null;
  body: string;
  active: boolean;
};

// ---- the outpatient token queue ---------------------------------------------

export type TokenStatus = "WAITING" | "CALLED" | "DONE";

export type QueueEntry = {
  tokenNumber: number;
  status: TokenStatus;
  issuedAt: string;
  calledAt: string | null;
  /** How the desk gets from a number to a patient. Absent from the public board by design. */
  appointmentId: string;
};

export type QueueBoard = {
  roomCode: string;
  serviceDate: string;
  nowServing: number | null;
  tokens: QueueEntry[];
};

/**
 * The corridor display's data.
 *
 * A separate type from {@link QueueBoard} rather than a subset, mirroring the service: this shape
 * has nowhere to put a name, an MRN or an id, so the PHI-free rendering is a property of the type
 * rather than of a component staying careful.
 */
export type PublicQueueBoard = {
  roomCode: string;
  nowServing: number | null;
  upcoming: number[];
};

// ---- casualty and in-patient admissions -------------------------------------

export type AttendanceStatus =
  | "WAITING"
  | "IN_BED"
  | "ADMITTED"
  | "DISCHARGED"
  | "LEFT_WITHOUT_BEING_SEEN";

/**
 * A casualty attendance.
 *
 * `triageAcuity` is 1 (immediate) to 5 (non-urgent), and the board is ordered by it before
 * arrival time — a queue served in the order people arrived kills the person who arrived last and
 * is the sickest. `waitingMinutes` is computed by the service on every read rather than stored,
 * so it is right whenever the board is looked at.
 */
export type CasualtyAttendance = {
  id: string;
  patientId: string;
  patientMrn: string;
  arrivedAt: string;
  triageAcuity: number;
  presentingComplaint: string;
  bedId: string | null;
  bedCode: string | null;
  roomCode: string | null;
  status: AttendanceStatus;
  admissionId: string | null;
  closedAt: string | null;
  triagedBy: string;
  waitingMinutes: number;
};

export type AdmissionSource = "CASUALTY" | "ELECTIVE" | "TRANSFER" | "MATERNITY";

export type BedTransfer = {
  id: string;
  fromBedCode: string;
  toBedCode: string;
  movedAt: string;
  movedBy: string;
  reason: string;
};

export type Admission = {
  id: string;
  patientId: string;
  patientMrn: string;
  attendanceId: string | null;
  bedId: string;
  bedCode: string;
  roomCode: string;
  admittingClinicianId: string;
  source: AdmissionSource;
  admittedAt: string;
  expectedDischarge: string | null;
  dischargedAt: string | null;
  dischargeSummary: string | null;
  status: "ADMITTED" | "DISCHARGED";
  lengthOfStayDays: number;
  transfers: BedTransfer[];
};

/**
 * A bed and whether anybody is in it.
 *
 * Composed by admissions-service from the facility directory and its own occupancy table:
 * patient-service deliberately keeps no occupancy flag on a bed, because a flag written by one
 * service and maintained by another goes stale.
 */
export type BedState = {
  bedId: string;
  bedCode: string;
  label: string;
  roomCode: string;
  roomName: string;
  floorName: string;
  occupied: boolean;
  occupantType: "CASUALTY" | "ADMISSION" | null;
  occupantId: string | null;
  occupiedSince: string | null;
};

// ---- pharmacy: the closed medication loop ---------------------------------

export type InteractionSeverity = "MINOR" | "MODERATE" | "MAJOR" | "CONTRAINDICATED";

/** What a safety check decided. Three answers, because only one of them is "fine". */
export type CheckOutcome = "CLEAR" | "OVERRIDABLE" | "REFUSED";

export type FormularyEntry = {
  id: string;
  code: string;
  name: string;
  form: string;
  strength: string;
  unit: string;
  label: string;
  controlled: boolean;
  active: boolean;
  /** Including class markers: an allergy to "penicillin" matches every product that names it. */
  ingredients: string[];
  unitsInStock: number;
  earliestExpiry: string | null;
};

export type InteractionPairing = {
  id: string;
  ingredientA: string;
  ingredientB: string;
  severity: InteractionSeverity;
  effect: string;
  /** What to do instead. The field that makes the warning actionable rather than dismissible. */
  management: string;
  source: string | null;
};

export type AllergyFinding = {
  substance: string;
  reaction: string | null;
  severity: string;
  drugCode: string;
  /** The ingredient or product name that triggered it, so a prescriber can check the reasoning. */
  matchedOn: string;
  overridable: boolean;
};

export type InteractionFinding = {
  ingredientA: string;
  ingredientB: string;
  drugA: string;
  drugB: string;
  severity: InteractionSeverity;
  effect: string;
  management: string;
  overridable: boolean;
};

export type SafetyCheck = {
  outcome: CheckOutcome;
  allergies: AllergyFinding[];
  interactions: InteractionFinding[];
  message: string;
};

export type AdministrationStatus = "GIVEN" | "REFUSED" | "OMITTED";

export type DoseRecord = {
  id: string;
  prescriptionItemId: string;
  scheduledFor: string;
  administeredAt: string | null;
  administeredBy: string;
  status: AdministrationStatus;
  refusalReason: string | null;
};

export type PrescriptionItem = {
  id: string;
  drugCode: string;
  drugName: string;
  dose: string;
  frequency: string;
  durationDays: number;
  quantity: number;
  instructions: string | null;
  quantityDispensed: number;
  outstanding: number;
  administrations: DoseRecord[];
};

export type Prescription = {
  id: string;
  encounterId: string | null;
  patientId: string;
  patientMrn: string;
  prescriberId: string;
  prescriberName: string;
  status: "ACTIVE" | "COMPLETED" | "CANCELLED";
  /** Why a warning was accepted. Clinical content, which is why it is not in the audit detail. */
  overrideReason: string | null;
  issuedAt: string;
  cancelledAt: string | null;
  items: PrescriptionItem[];
};

export type StockBatch = {
  id: string;
  drugCode: string;
  drugName: string | null;
  batchNo: string;
  expiresOn: string;
  quantityOnHand: number;
  receivedOn: string;
  expired: boolean;
  daysToExpiry: number;
};

export type DispenseRecord = {
  id: string;
  prescriptionItemId: string;
  drugName: string;
  batchNo: string;
  expiresOn: string;
  quantity: number;
  dispensedBy: string;
  dispensedAt: string;
  outstanding: number;
};

// ---- order sets and care plans -------------------------------------------

export type OrderSetKind = "LAB" | "MEDICATION";

export type OrderSetItem = {
  id: string;
  kind: OrderSetKind;
  code: string;
  dose: string | null;
  frequency: string | null;
  durationDays: number | null;
  quantity: number | null;
  instructions: string | null;
  priority: string | null;
  displayOrder: number;
};

export type OrderSet = {
  id: string;
  code: string;
  name: string;
  description: string | null;
  departmentCode: string | null;
  active: boolean;
  items: OrderSetItem[];
};

export type GoalStatus = "OPEN" | "MET" | "NOT_MET" | "ABANDONED";

export type CareGoal = {
  id: string;
  description: string;
  /** One of the encounter's own diagnoses, or null for a goal that belongs to the admission. */
  problemCode: string | null;
  targetDate: string | null;
  status: GoalStatus;
  progressNote: string | null;
  updatedBy: string;
  /** Past its date and still open — what a ward round wants highlighted. */
  overdue: boolean;
};

export type CarePlan = {
  id: string;
  encounterId: string;
  patientId: string;
  patientMrn: string;
  title: string;
  status: "ACTIVE" | "COMPLETED" | "CANCELLED";
  createdBy: string;
  createdAt: string;
  closedAt: string | null;
  goals: CareGoal[];
};

// ---- billing ---------------------------------------------------------------

/**
 * Enough to put a name to an MRN, from the narrow endpoint a cashier holds.
 *
 * <p>Deliberately not {@link PatientSummary}. A billing desk has to be sure it is invoicing the
 * right person and has no business reading a date of birth, a phone number or a critical-allergy
 * marker — so the endpoint behind this answers four fields and the role that may call it is not in
 * CLINICAL_READ at all.
 */
export type PatientIdentity = {
  id: string;
  mrn: string;
  fullName: string;
  active: boolean;
};

export type InvoiceStatus = "DRAFT" | "ISSUED" | "PAID" | "CANCELLED";

export type PaymentMethod = "CASH" | "CARD" | "UPI" | "BANK_TRANSFER" | "INSURANCE";

export type ClaimStatus = "DRAFT" | "SUBMITTED" | "SETTLED" | "PARTIALLY_SETTLED" | "DENIED";

/**
 * Amounts arrive as JSON numbers with two decimal places and are kept as numbers here.
 *
 * <p>Which is safe for display and not for arithmetic: every figure on these screens is one the
 * platform computed, and nothing in the browser adds two of them together. The moment a screen
 * needs to total a column it must ask the service for the total, because a floating point sum of
 * money is exactly the bug `numeric(14,2)` and `BigDecimal` exist to prevent on the other side.
 */
export type InvoiceLine = {
  id: string;
  chargeItemCode: string;
  description: string;
  qty: number;
  unitPrice: number;
  discount: number;
  taxPercent: number;
  taxAmount: number;
  lineTotal: number;
};

export type Payment = {
  id: string;
  amount: number;
  method: PaymentMethod;
  reference: string | null;
  receivedBy: string;
  receivedAt: string;
};

export type Invoice = {
  id: string;
  number: string;
  patientId: string;
  patientMrn: string;
  encounterId: string | null;
  payerCode: string | null;
  status: InvoiceStatus;
  subtotal: number;
  discount: number;
  taxTotal: number;
  total: number;
  amountPaid: number;
  outstanding: number;
  invoiceDate: string;
  issuedAt: string | null;
  cancelledAt: string | null;
  cancelledReason: string | null;
  lines: InvoiceLine[];
  payments: Payment[];
};

export type ChargeItem = {
  id: string;
  code: string;
  name: string;
  departmentCode: string | null;
  unitPrice: number;
  taxable: boolean;
  taxRateCode: string | null;
  taxPercentToday: number | null;
  active: boolean;
};

export type TaxRate = {
  id: string;
  code: string;
  name: string;
  percent: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  inForceToday: boolean;
};

export type PayerTariff = {
  chargeItemCode: string;
  chargeItemName: string;
  listPrice: number;
  agreedPrice: number;
};

export type Payer = {
  id: string;
  code: string;
  name: string;
  requiresPreauth: boolean;
  allowsCopay: boolean;
  settlesDirectly: boolean;
  taxExempt: boolean;
  active: boolean;
  tariffs: PayerTariff[];
};

export type Claim = {
  id: string;
  invoiceId: string;
  invoiceNumber: string;
  payerCode: string;
  preauthNo: string | null;
  submittedAt: string | null;
  status: ClaimStatus;
  claimedAmount: number;
  settledAmount: number | null;
  shortfall: number;
  denialReason: string | null;
};

export type MethodTotal = {
  method: PaymentMethod;
  amount: number;
  count: number;
};

export type DayBook = {
  on: string;
  billed: number;
  collected: number;
  outstanding: number;
  invoices: number;
  payments: number;
  byMethod: MethodTotal[];
};

// ---- interoperability ------------------------------------------------------

export type ConsentStatus = "REQUESTED" | "GRANTED" | "DENIED" | "REVOKED" | "EXPIRED";

export type HiType =
  | "OP_CONSULTATION"
  | "DIAGNOSTIC_REPORT"
  | "PRESCRIPTION"
  | "DISCHARGE_SUMMARY"
  | "IMMUNIZATION_RECORD"
  | "HEALTH_DOCUMENT_RECORD"
  | "WELLNESS_RECORD";

export type PurposeCode =
  | "CARE_MANAGEMENT"
  | "BREAK_THE_GLASS"
  | "PUBLIC_HEALTH"
  | "PAYMENT"
  | "RESEARCH"
  | "SELF_REQUESTED";

export type Consent = {
  id: string;
  artefactId: string;
  patientId: string;
  patientMrn: string;
  requester: string;
  requesterId: string | null;
  purposeCode: PurposeCode;
  purposeText: string | null;
  status: ConsentStatus;
  /**
   * Whether this consent would authorise a disclosure right now.
   *
   * <p>Computed by the service on every read rather than stored, so a screen cannot show a lapsed
   * consent as usable because a housekeeping job has not run.
   */
  live: boolean;
  hiTypes: HiType[];
  coversFrom: string;
  coversTo: string;
  expiresAt: string;
  grantedAt: string | null;
  deniedAt: string | null;
  revokedAt: string | null;
  revokedReason: string | null;
  signed: boolean;
};

export type Disclosure = {
  id: string;
  consentId: string | null;
  artefactId: string | null;
  patientId: string;
  patientMrn: string;
  hiType: HiType;
  kind: "CONSENTED_SHARE" | "PATIENT_EXPORT" | "CARE_SUMMARY";
  recipient: string;
  resourceCount: number;
  byteCount: number;
  releasedBy: string;
  releasedAt: string;
};

export type ShareOutcome = {
  disclosureId: string;
  artefactId: string;
  hiType: HiType;
  resourceCount: number;
  byteCount: number;
  /** False when the platform recorded the release and sent nothing — the honest default. */
  transmitted: boolean;
  gateway: string;
  message: string;
};
