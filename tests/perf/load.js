// Load: the shape of a normal clinic day. Two scenarios running together, because that is how
// the system is actually used - a steady stream of lookups from clinicians, and a much thinner
// stream of bookings from the front desk.
//
// The ratio matters more than the absolute numbers: reads outnumber writes roughly 10:1, so a
// profile that books as often as it searches would be measuring a system nobody runs.
//
//   k6 run tests/perf/load.js
//   k6 run -e PERF_READ_VUS=100 -e PERF_BOOK_RATE=20 tests/perf/load.js

import { sleep } from 'k6';
import { thresholds, USERS } from './lib/config.js';
import {
  billingJourney,
  bookingJourney,
  displayJourney,
  login,
  readJourney,
  setupJourney,
} from './lib/journey.js';

const READ_VUS = Number(__ENV.PERF_READ_VUS || 30);
const BOOK_RATE = Number(__ENV.PERF_BOOK_RATE || 6); // bookings per second

export const options = {
  scenarios: {
    // Clinicians browsing: ramp up, hold, ramp down.
    clinical_reads: {
      executor: 'ramping-vus',
      exec: 'reads',
      startVUs: 1,
      stages: [
        { duration: '1m', target: READ_VUS },
        { duration: '5m', target: READ_VUS },
        { duration: '1m', target: 0 },
      ],
      gracefulRampDown: '30s',
    },
    // Front desk booking at a fixed arrival rate, which is what a queue actually looks like.
    // arrival-rate executors keep sending at the target rate even when the system slows down,
    // so a saturating backend shows up as latency and queueing instead of quietly reducing load.
    front_desk_bookings: {
      executor: 'ramping-arrival-rate',
      exec: 'bookings',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 60,
      stages: [
        { duration: '1m', target: BOOK_RATE },
        { duration: '5m', target: BOOK_RATE },
        { duration: '1m', target: 0 },
      ],
    },
  },
  thresholds,
};

export function setup() {
  return setupJourney();
}

export function reads(data) {
  const token = login(USERS.doctor);
  readJourney(token, data);
  // The corridor display, with no token. Its own rate-limit bucket at the gateway is
  // sized for screens rather than for people, and this is what exercises it.
  displayJourney(data);
  sleep(Math.random() * 2 + 1); // a human reading a chart, not a benchmark loop
}

export function bookings(data) {
  const token = login(USERS.reception);
  bookingJourney(token, data);
  // The billing desk, on the same arrival-rate scenario as the front desk: a consultation booked
  // is a consultation billed, and the two contend for nothing except the database.
  billingJourney(login(USERS.cashier), data);
}
