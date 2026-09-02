// Shared configuration and thresholds for the k6 profiles.
//
// Everything is env-overridable so the same scripts run against localhost, a staging gateway,
// or a container network without editing a file:
//
//   k6 run -e BASE_URL=https://staging.example.org tests/perf/load.js

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// The seeded dev accounts. A perf run needs one account per role it exercises; a real
// environment should get its own throwaway accounts rather than reusing these.
export const USERS = {
  doctor: { username: __ENV.PERF_DOCTOR || 'dr.rao', password: __ENV.PERF_PASSWORD || 'ChangeMe!Dev2026' },
  reception: { username: __ENV.PERF_RECEPTION || 'reception', password: __ENV.PERF_PASSWORD || 'ChangeMe!Dev2026' },
  // The billing desk. Raising an invoice, taking a payment and issuing an invoice number are a
  // cashier's acts, and the profile exercises them as one rather than as an administrator: the
  // point of the leg is the contention on the counter and the payment statement, and both are
  // reached through the same authorisation a real desk has.
  cashier: { username: __ENV.PERF_CASHIER || 'cashier', password: __ENV.PERF_PASSWORD || 'ChangeMe!Dev2026' },
  // Used by setup() only, to provision the clinician the booking journey needs. Creating staff
  // is an administrative act, so the profile cannot bootstrap itself without it.
  admin: { username: __ENV.PERF_ADMIN || 'admin', password: __ENV.PERF_PASSWORD || 'ChangeMe!Dev2026' },
};

// How many patients setup() registers and every VU then shares. Booking and search need real
// rows; creating one per iteration would grow the database without bound during a soak.
export const PATIENT_POOL = Number(__ENV.PERF_PATIENT_POOL || 20);

// TLS: a dev stack uses the local CA from scripts/gen-certs.sh, which k6 will not trust. Set
// PERF_INSECURE_TLS=true for those runs, never for a real environment.
export const INSECURE_TLS = (__ENV.PERF_INSECURE_TLS || 'false') === 'true';

/**
 * Upper bound on k6's VU ids across every scenario in a profile, used to turn (__VU, __ITER) into
 * a single collision-free sequence number for slot allocation (see bookingJourney).
 *
 * This is a real invariant, not a tuning knob: raise a profile's maxVUs past this and two VUs
 * start computing the same appointment slot, the exclusion constraint fires, and
 * booking_conflicts - the metric that proves the constraint only fires when it should - fills up
 * with self-inflicted noise. journey.js checks it at runtime and fails loudly rather than
 * quietly producing garbage.
 */
export const MAX_VUS = Number(__ENV.PERF_MAX_VUS || 256);

/** Bookable slots per day at the 5-minute granularity the booking journey uses. */
export const SLOTS_PER_DAY = 288;

/**
 * Correctness budgets. These hold on every profile, at every load: a request that errors or a
 * check that fails is a defect whatever the throughput was.
 *
 * booking_conflicts deserves a word. Each VU books its own disjoint band of the calendar, so the
 * (clinician_id, tstzrange) exclusion constraint should never fire. A single conflict means
 * either the slot allocation in journey.js has a bug or the constraint is rejecting something it
 * should allow - both worth failing the run over.
 */
export const CORRECTNESS = {
  checks: ['rate>0.99'],
  http_req_failed: ['rate<0.01'],
  booking_conflicts: ['count<1'],
};

/**
 * Latency budgets, per endpoint because the endpoints are not comparable: a trigram search over
 * patients does more work than reading one row by id, and booking writes, publishes an event, and
 * calls the AI service. Argon2id makes login the slowest thing here by design.
 *
 * These are budgets, not measurements. Tighten them once you have a baseline on your own
 * hardware - a threshold nobody has ever seen fail is not protecting anything.
 *
 * Only the profiles that generate enough samples for a percentile to mean something apply these.
 * The smoke profile runs five iterations, where p(95) is indistinguishable from the cold-start
 * max, and the stress profile is deliberately run past its breaking point; both assert
 * CORRECTNESS alone.
 */
export const LATENCY = {
  'http_req_duration{endpoint:patient_search}': ['p(95)<400', 'p(99)<900'],
  'http_req_duration{endpoint:patient_read}': ['p(95)<200', 'p(99)<500'],
  'http_req_duration{endpoint:appointment_search}': ['p(95)<400', 'p(99)<900'],
  'http_req_duration{endpoint:availability}': ['p(95)<400', 'p(99)<900'],
  'http_req_duration{endpoint:lab_worklist}': ['p(95)<400', 'p(99)<900'],
  'http_req_duration{endpoint:login}': ['p(95)<1200'],
  'http_req_duration{endpoint:patient_create}': ['p(95)<700'],
  'http_req_duration{endpoint:booking}': ['p(95)<1500', 'p(99)<3000'],
};

/** What the load profile asserts: everything. */
export const thresholds = { ...CORRECTNESS, ...LATENCY };

