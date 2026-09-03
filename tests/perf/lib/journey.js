// The clinical journey the perf profiles replay, and the setup that makes it replayable.
//
// One rule shapes all of it: a profile must be able to run for hours without changing its own
// results. That means the read paths are the bulk of the traffic, the writes are bounded, and
// nothing a VU does can collide with another VU's work.

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, USERS, PATIENT_POOL, INSECURE_TLS, MAX_VUS, SLOTS_PER_DAY } from './config.js';

export const bookingConflicts = new Counter('booking_conflicts');
export const noShowScored = new Counter('noshow_scores_returned');
export const journeyDuration = new Trend('journey_duration', true);

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function params(tag, token) {
  const p = {
    headers: { ...JSON_HEADERS },
    tags: { endpoint: tag },
    // k6's default is to fail the request on a 4xx; we want to inspect the status ourselves so
    // an expected 409 is a data point rather than an error.
    responseCallback: http.expectedStatuses({ min: 200, max: 409 }),
  };
  if (token) p.headers.Authorization = `Bearer ${token}`;
  if (INSECURE_TLS) p.insecureSkipTLSVerify = true;
  return p;
}

export function login(user) {
  const res = http.post(`${BASE_URL}/auth/login`, JSON.stringify(user), params('login'));
  if (res.status === 429) {
    // The gateway's rate limiter and a load test want opposite things, so say exactly that
    // instead of letting the run report a mysterious 90% failure rate. The auth bucket is the
    // one that bites first: it defaults to 20/min and every iteration here signs in.
    fail(
      'The gateway rate-limited this run (429). A load profile cannot run against the ' +
        'production limits - start the stack with HMS_RATE_LIMIT_ENABLED=false, or raise ' +
        'HMS_RATE_LIMIT_AUTH_RPM and HMS_RATE_LIMIT_RPM well above the offered rate.',
    );
  }
  const ok = check(res, {
    'login returns 200': (r) => r.status === 200,
    'login returns an access token': (r) => !!(r.json() || {}).accessToken,
  });
  if (!ok) fail(`login failed for ${user.username}: ${res.status} ${res.body}`);
  return res.json('accessToken');
}

/**
 * Registers the shared patient pool and resolves a clinician to book against. Runs once, in the
 * k6 init/setup phase, and its return value is handed to every VU.
 */
export function setupJourney() {
  const admin = login(USERS.admin);
  const frontDesk = login(USERS.reception);

  const departmentRes = http.get(`${BASE_URL}/departments`, params('departments', admin));
  if (departmentRes.status !== 200) fail(`cannot read departments: ${departmentRes.status} ${departmentRes.body}`);
  const departments = (departmentRes.json() || []).filter((d) => d.active);
  if (departments.length === 0) fail('no active departments - run the migrations before a perf run');

  // Every run gets its own tag. It keeps successive runs from matching each other's rows in the
  // search assertions, and keeps duplicate detection from rejecting the second run's inserts.
  const runTag = `PERF${Date.now().toString(36).toUpperCase()}`;

  // Every run also gets its own clinician, and this is not cosmetic. The double-booking guard is
  // an exclusion constraint over (clinician_id, tstzrange), and the booking journey picks its
  // slot deterministically from (VU, iteration) so that no two VUs can collide. Deterministic
  // slots would collide with the PREVIOUS run's appointments on a shared clinician, turning
  // booking_conflicts - the metric that proves the constraint only fires when it should - into
  // noise. A run-scoped clinician removes that whole class of false positive, and lets two perf
  // runs share a database.
  //
  // It leaves one staff row behind per run. That is the price, and it is why this belongs on a
  // database you are willing to truncate.
  const created = http.post(
    `${BASE_URL}/staff`,
    JSON.stringify({
      employeeNo: `PERF-${runTag}`,
      fullName: `Perf Clinician ${runTag}`,
      designation: 'Consultant',
      departmentCode: departments[0].code,
      specialty: 'General Medicine',
    }),
    params('staff_create', admin),
  );
  if (created.status !== 201 && created.status !== 200) {
    fail(`could not provision the run's clinician: ${created.status} ${created.body}`);
  }
  const clinician = created.json();
  const departmentCode = clinician.departmentCode || departments[0].code;
  const patients = [];
  for (let i = 0; i < PATIENT_POOL; i++) {
    const body = {
      firstName: `Perf${i}`,
      lastName: runTag,
      dateOfBirth: `19${60 + (i % 40)}-0${1 + (i % 9)}-1${i % 10}`,
      sex: i % 2 === 0 ? 'FEMALE' : 'MALE',
      phone: `+9715${String(1000000 + i)}`,
      city: 'Perf City',
      forceDuplicate: true,
    };
    const res = http.post(`${BASE_URL}/patients`, JSON.stringify(body), params('patient_create', frontDesk));
    if (res.status !== 201 && res.status !== 200) {
      fail(`patient pool creation failed at ${i}: ${res.status} ${res.body}`);
    }
    patients.push({ id: res.json('id'), mrn: res.json('mrn') });
  }

  // A room of its own, for the same reason as the clinician: room bookings are guarded by an
  // exclusion constraint over (room_id, tstzrange), so a shared room turns the deterministic slot
  // choice into a collision with the previous run. It also gives the display profile a queue that
  // belongs to this run, since a token queue is per room per day and does not reset.
  const roomRes = http.post(
    `${BASE_URL}/rooms`,
    JSON.stringify({
      code: `PR-${runTag}`.slice(0, 16),
      name: `Perf Room ${runTag}`,
      roomTypeCode: 'CONSULTATION',
      floorCode: 'GF',
      capacity: 1,
      bookable: true,
    }),
    params('room_create', admin),
  );
  if (roomRes.status !== 201 && roomRes.status !== 200) {
    fail(`could not provision the run's room: ${roomRes.status} ${roomRes.body}`);
  }

  // One encounter, for the read journey's chart read, and it is opened here rather than per
  // iteration for a reason the other fixtures do not have: an encounter is a clinical record on a
  // real patient's chart, so a profile that opened one per VU per iteration would leave a soak's
  // worth of consultations behind on twenty patients. One is enough to measure a read.
  //
  // It is opened by, and for, the clinician the read journey signs in as. Since the care-team
  // narrowing, who may read a chart is decided by membership of encounter_care_team rather than by
  // a role, and opening an encounter enrols both its clinician and whoever opened it — so this is
  // also what makes the chart read below a 200. The clinician id is a *login* id, checked against
  // staff.user_id before it is written, which is why it comes from /auth/me and not from the run's
  // own staff row: that row has no login, deliberately, and the encounter would be refused.
  const doctor = login(USERS.doctor);
  const me = http.get(`${BASE_URL}/auth/me`, params('whoami', doctor));
  if (me.status !== 200) {
    fail(`cannot resolve the read journey's clinician: ${me.status} ${me.body}`);
  }
  const chartPatient = patients[0];
  const encounter = http.post(
    `${BASE_URL}/encounters`,
    JSON.stringify({
      patientId: chartPatient.id,
      patientMrn: chartPatient.mrn,
      clinicianId: me.json('id'),
      departmentCode,
      encounterType: 'OUTPATIENT',
    }),
    params('encounter_open', doctor),
  );
  if (encounter.status !== 201 && encounter.status !== 200) {
    fail(`could not open the run's encounter: ${encounter.status} ${encounter.body}`);
  }

  return {
    runTag,
    patients,
    // The *staff* id, and the booking journey wants it that way only because nothing validates an
    // appointment's clinician_id: what it needs is a UUID unique to this run, so the exclusion
    // constraint over (clinician_id, tstzrange) cannot meet the previous run's appointments. The
    // encounter above is the endpoint where that column is validated, and it uses a login id. If a
    // later slice validates the booking column too, this is the line that has to become one.
    clinicianId: clinician.id,
    clinicianName: clinician.fullName,
    departmentCode,
    roomCode: roomRes.json('code'),
    encounterId: encounter.json('id'),
  };
}

/** Reads only. This is the shape of most real traffic and it is safe to run at any rate. */
export function readJourney(token, data) {
  const started = Date.now();
  const patient = data.patients[Math.floor(Math.random() * data.patients.length)];

  const search = http.get(
    `${BASE_URL}/patients?q=${encodeURIComponent(data.runTag)}&page=0&size=20`,
    params('patient_search', token),
  );
  check(search, {
    'patient search returns 200': (r) => r.status === 200,
    'patient search finds the pool': (r) => (r.json('totalElements') || 0) >= 1,
  });

  const read = http.get(`${BASE_URL}/patients/${patient.id}`, params('patient_read', token));
  check(read, {
    'patient read returns 200': (r) => r.status === 200,
    'patient read returns the right row': (r) => r.json('mrn') === patient.mrn,
  });

  // The encounter chart. It is here because since the care-team narrowing every chart read runs a
  // membership query against encounter_care_team before the record is touched, and that is the
  // hottest read path on the platform — a guard nobody measures is a guard nobody notices slowing
  // down. The token is the clinician who opened it in setup, so this measures a member's read,
  // which is the read every clinician looking after a patient makes. A non-member's 403 is a
  // correctness question and it is asserted in tests/api, not in a profile whose thresholds are
  // about latency.
  const chart = http.get(`${BASE_URL}/encounters/${data.encounterId}`, params('chart_read', token));
  check(chart, {
    'chart read returns 200': (r) => r.status === 200,
    'chart read returns the encounter asked for': (r) => r.json('id') === data.encounterId,
  });

  const today = new Date().toISOString().slice(0, 10);
  const appointments = http.get(
    `${BASE_URL}/appointments?from=${today}&to=${today}&page=0&size=50`,
    params('appointment_search', token),
  );
  check(appointments, { 'appointment search returns 200': (r) => r.status === 200 });

  const availability = http.get(
    `${BASE_URL}/appointments/availability?clinicianId=${data.clinicianId}&date=${today}`,
    params('availability', token),
  );
  check(availability, { 'availability returns 200': (r) => r.status === 200 });

  const worklist = http.get(`${BASE_URL}/lab/orders?status=ORDERED&page=0&size=20`, params('lab_worklist', token));
  check(worklist, { 'lab worklist returns 200': (r) => r.status === 200 });

  journeyDuration.add(Date.now() - started);
}

/**
 * Maps this iteration to an appointment slot that no other iteration in the run can claim.
 *
 * Getting this wrong is easy and quiet. An earlier version spread slots as
 * (__VU % 30, __ITER % 12, __ITER * 5 % 60), which reads as "every VU owns its own band" but is
 * not injective: once k6 hands out VU ids above 30 the day repeats, and two VUs on the same
 * modular iteration land on the same minute. A 20-VU load run produced 1229 self-inflicted
 * conflicts that way - all of them reported as constraint violations that the platform was right
 * to raise, and none of them a defect.
 *
 * So: fold (__VU, __ITER) into one sequence number, then walk that sequence through the calendar
 * five minutes at a time. Injective as long as __VU stays below MAX_VUS, which is asserted rather
 * than assumed.
 */
function slotFor() {
  if (__VU >= MAX_VUS) {
    fail(
      `VU id ${__VU} is at or above MAX_VUS (${MAX_VUS}); slot allocation would collide. ` +
        'Raise PERF_MAX_VUS above the profile\'s maxVUs.',
    );
  }
  const seq = __ITER * MAX_VUS + __VU;
  const base = new Date();
  base.setUTCHours(0, 0, 0, 0);
  base.setUTCDate(base.getUTCDate() + 30 + Math.floor(seq / SLOTS_PER_DAY));
  base.setUTCMinutes((seq % SLOTS_PER_DAY) * 5);
  return base;
}

/**
 * Books one appointment into a slot no other iteration will touch, so a 409 can only mean the
 * exclusion constraint fired on something the test did not cause. That is why booking_conflicts
 * is a hard threshold rather than a reported number.
 */
export function bookingJourney(token, data) {
  const patient = data.patients[(__VU + __ITER) % data.patients.length];
  const base = slotFor();

  const body = {
    patientId: patient.id,
    patientMrn: patient.mrn,
    clinicianId: data.clinicianId,
    clinicianName: data.clinicianName,
    departmentCode: data.departmentCode,
    startsAt: base.toISOString(),
    durationMinutes: 5,
    // The run's own room. The room exclusion constraint is over (room_id, tstzrange) just as the
    // clinician one is, and the slot allocation above already gives every iteration a slot nobody
    // else will touch - so booking into a room costs nothing here and makes the second constraint
    // part of what the profile exercises. Sharing a seeded room instead would have produced
    // cross-run conflicts exactly as a shared clinician once did.
    roomCode: data.roomCode,
    priority: 'ROUTINE',
    reason: 'performance profile',
    travelDistanceKm: 12,
    hasReminderContact: true,
  };

  const res = http.post(`${BASE_URL}/appointments`, JSON.stringify(body), params('booking', token));
  if (res.status === 409) {
    bookingConflicts.add(1);
  }
  check(res, { 'booking returns 201': (r) => r.status === 201 });

  // The no-show score comes from the AI service through a circuit breaker. A null score is a
  // correct degraded response, not a failure - but it is worth counting, because a profile where
  // the score vanishes under load has found the breaker opening.
  if (res.status === 201 && res.json('noShowRisk')) {
    noShowScored.add(1);
  }
}

/**
 * The corridor display: the platform's one unauthenticated endpoint.
 *
 * <p>Worth measuring on its own rather than folding into {@link readJourney}, because its traffic
 * shape is unlike anything else here. A wall screen polls on a timer forever, there is one per
 * corridor, and none of them signs in — so this is the only path where the offered rate is set by
 * how many screens are mounted rather than by how many people are working. It has its own
 * rate-limit bucket at the gateway for that reason, and this is what proves the bucket is sized
 * for it.
 *
 * <p>No token, deliberately, and no `Authorization` header is constructed: a request that
 * accidentally carried one would be measuring the authenticated path.
 */
/**
 * Raises an invoice, prices it, issues it and collects the balance.
 *
 * <p>Its own invoice per iteration, so a 409 here cannot be self-inflicted the way the booking
 * journey's could — nothing is shared except the two things worth measuring under contention:
 * the financial-year counter that issues invoice numbers (one statement, `ON CONFLICT … RETURNING`)
 * and the conditional `UPDATE` that takes a payment. Both are single statements by design, and a
 * profile that never made two cashiers meet on them would not be testing that design.
 *
 * <p>Every amount comes back from the platform. The check asserts the invoice was paid in full,
 * which is the one assertion that catches a lost update: if two payments could both land, the
 * status would say PAID while `amount_paid` had been silently restored.
 */
/**
 * The radiography room's two reads, as a radiographer.
 *
 * <p>Its own journey rather than a leg of `readJourney`, because it needs a different identity: a
 * doctor is refused the worklist, deliberately, so measuring it on the clinical token would measure
 * a 403. The same reason the billing leg is separate.
 *
 * <p>The worklist is the read worth a threshold. It is ordered priority-then-time over
 * `idx_imaging_worklist`, which is the one index on this platform whose ordering is itself the
 * clinical rule — a STAT head CT asked for a minute ago goes ahead of a routine knee film booked
 * this morning — and an ordering that falls off its index still returns the right rows, just
 * slowly, so nothing but a profile would notice.
 */
export function radiologyJourney(token) {
  const worklist = http.get(`${BASE_URL}/imaging/worklist`, params('imaging_worklist', token));
  check(worklist, {
    'imaging worklist returns 200': (r) => r.status === 200,
    // Ordering asserted rather than assumed, and asserted on what came back rather than on a
    // count: an empty worklist is a legitimate state on a quiet database and is not a defect, so
    // the check is that whatever is there is in the right order.
    'imaging worklist is priority before time': (r) => {
      const rows = r.json() || [];
      const rank = { STAT: 0, URGENT: 1, ROUTINE: 2 };
      return rows.every((row, i) => i === 0 || rank[rows[i - 1].priority] <= rank[row.priority]);
    },
  });

  const unmatched = http.get(`${BASE_URL}/imaging/studies/unmatched`, params('imaging_unmatched', token));
  check(unmatched, { 'unmatched studies return 200': (r) => r.status === 200 });
}

export function billingJourney(token, data) {
  const started = Date.now();
  const patient = data.patients[Math.floor(Math.random() * data.patients.length)];

  const raised = http.post(
    `${BASE_URL}/invoices`,
    JSON.stringify({ patientId: patient.id, patientMrn: patient.mrn }),
    params('invoice_create', token),
  );
  const numbered = check(raised, {
    'invoice raised': (r) => r.status === 201,
    'invoice carries a number': (r) => /\/\d{5}$/.test(r.json('number') || ''),
  });
  if (!numbered) return;

  const id = raised.json('id');
  const line = http.post(
    `${BASE_URL}/invoices/${id}/lines`,
    JSON.stringify({ chargeItemCode: 'CONSULT_OP', qty: 1 }),
    params('invoice_line', token),
  );
  check(line, { 'charge added': (r) => r.status === 200 });
  const total = line.json('total');

  const issued = http.post(`${BASE_URL}/invoices/${id}/issue`, null, params('invoice_issue', token));
  check(issued, { 'invoice issued': (r) => r.status === 200 });

  const paid = http.post(
    `${BASE_URL}/invoices/${id}/payments`,
    JSON.stringify({ amount: total, method: 'CASH' }),
    params('payment', token),
  );
  check(paid, {
    'payment accepted': (r) => r.status === 200,
    'invoice is paid in full': (r) => r.json('status') === 'PAID' && r.json('outstanding') === 0,
  });

  journeyDuration.add(Date.now() - started);
}

export function displayJourney(data) {
  const res = http.get(
    `${BASE_URL}/public/queue/${encodeURIComponent(data.roomCode)}`,
    params('public_queue'),
  );
  check(res, {
    'the display answers 200 with no token': (r) => r.status === 200,
    // Structural, and it is the assertion that matters: if a field ever appears here that names a
    // person, it appears on every screen in the building at once.
    'the display carries only a room code and numbers': (r) => {
      const body = r.json() || {};
      return Object.keys(body).sort().join(',') === 'nowServing,roomCode,upcoming';
    },
  });
}
