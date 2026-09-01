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

  return { runTag, patients, clinicianId: clinician.id, clinicianName: clinician.fullName, departmentCode };
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
