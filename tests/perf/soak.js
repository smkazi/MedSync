// Soak: modest load held for a long time. This profile is looking for the failures that only
// appear with duration - connection and thread pools that leak, Hibernate caches that grow,
// JWTs that expire mid-session without the client noticing, log volumes that fill a disk,
// and the slow upward drift in latency that a short run cannot distinguish from noise.
//
// Default is one hour. Override for a real overnight run:
//   k6 run -e PERF_SOAK_DURATION=8h tests/perf/soak.js
//
// Note on writes: the booking journey inserts rows, so an 8h soak leaves a lot of appointments
// behind. Point it at a database you are willing to truncate, and set PERF_SOAK_BOOK_RATE=0 to
// soak the read paths alone.

import { sleep } from 'k6';
import { CORRECTNESS, LATENCY, USERS } from './lib/config.js';
import { login, setupJourney, radiologyJourney, readJourney, bookingJourney, displayJourney } from './lib/journey.js';

const DURATION = __ENV.PERF_SOAK_DURATION || '1h';
const VUS = Number(__ENV.PERF_SOAK_VUS || 15);
const BOOK_RATE = Number(__ENV.PERF_SOAK_BOOK_RATE || 1);

const scenarios = {
  steady_reads: {
    executor: 'constant-vus',
    exec: 'reads',
    vus: VUS,
    duration: DURATION,
  },
};

if (BOOK_RATE > 0) {
  scenarios.steady_bookings = {
    executor: 'constant-arrival-rate',
    exec: 'bookings',
    rate: BOOK_RATE,
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: 10,
    maxVUs: 30,
  };
}

export const options = {
  scenarios,
  thresholds: {
    ...CORRECTNESS,
    ...LATENCY,
    // Tighter than the load profile on purpose. Under steady modest load, latency should be flat;
    // if p99 drifts past this over the run, something is accumulating.
    'http_req_duration{endpoint:patient_search}': ['p(95)<400', 'p(99)<800'],
    'http_req_duration{endpoint:patient_read}': ['p(95)<200', 'p(99)<400'],
  },
};

export function setup() {
  return setupJourney();
}

export function reads(data) {
  // Logging in every iteration is deliberate: a soak that reuses one token for hours never
  // exercises token expiry, and expiry is exactly the kind of thing that only breaks after a
  // long run. Argon2id makes this the most expensive call in the profile - that is the trade.
  const token = login(USERS.doctor);
  readJourney(token, data);
  // The radiography room, on its own identity, one iteration in five.
  //
  // Its own identity because a doctor is refused the worklist; one in five because it needs its own
  // sign-in and Argon2id is the most expensive call in the profile. Doing it every iteration would
  // double this scenario's auth load, which changes what the login numbers mean and brings the
  // gateway's auth bucket forward — so the instrument would have been measuring itself. A
  // seven-minute run still leaves hundreds of worklist samples, which is more than p(95) needs.
  if (__ITER % 5 === 0) radiologyJourney(login(USERS.radiographer));
  // The corridor display, with no token. Its own rate-limit bucket at the gateway is
  // sized for screens rather than for people, and this is what exercises it.
  displayJourney(data);
  sleep(Math.random() * 3 + 2);
}

export function bookings(data) {
  const token = login(USERS.reception);
  bookingJourney(token, data);
}
