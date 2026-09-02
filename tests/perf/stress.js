// Stress: push past the expected peak until something gives, and record where. The point is not
// to pass - it is to find the breaking point and confirm the system degrades in the way it was
// designed to, rather than falling over.
//
// What to look for in the summary:
//   - booking_conflicts must stay 0. The exclusion constraint should never fire on distinct
//     slots, however contended the table is.
//   - noshow_scores_returned dropping while bookings keep succeeding is the circuit breaker
//     opening. That is the designed behaviour: an appointment books without a risk score rather
//     than failing.
//   - http_req_failed climbing above the threshold is the real failure, and the ramp stage where
//     it starts is the answer this profile exists to produce.
//
//   k6 run tests/perf/stress.js

import { sleep } from 'k6';
import { CORRECTNESS, USERS } from './lib/config.js';
import { login, setupJourney, readJourney, bookingJourney, displayJourney } from './lib/journey.js';

const PEAK = Number(__ENV.PERF_PEAK_VUS || 200);

export const options = {
  scenarios: {
    ramp_to_breaking: {
      executor: 'ramping-vus',
      exec: 'mixed',
      startVUs: 5,
      stages: [
        { duration: '1m', target: Math.round(PEAK * 0.25) },
        { duration: '2m', target: Math.round(PEAK * 0.5) },
        { duration: '2m', target: Math.round(PEAK * 0.75) },
        { duration: '2m', target: PEAK },
        { duration: '3m', target: PEAK },
        // Recovery: does throughput and latency come back once the load drops, or has something
        // stayed broken? A pool that never recovers shows up here and nowhere else.
        { duration: '2m', target: Math.round(PEAK * 0.25) },
        { duration: '1m', target: 0 },
      ],
      gracefulRampDown: '1m',
    },
  },
  thresholds: {
    ...CORRECTNESS,
    // Latency budgets are deliberately not asserted here: at breaking point they will be
    // exceeded, and that is the finding, not a broken test. Correctness still is asserted, with
    // one relaxation - a stressed system is allowed to shed some load; a corrupting one is not.
    http_req_failed: ['rate<0.05'],
    checks: ['rate>0.95'],
  },
};

export function setup() {
  return setupJourney();
}

export function mixed(data) {
  // One VU in ten books; the rest read. Same ratio as the load profile, more of them.
  const token = login(__VU % 10 === 0 ? USERS.reception : USERS.doctor);
  if (__VU % 10 === 0) {
    bookingJourney(token, data);
  } else {
    readJourney(token, data);
    // The corridor display, with no token. Its own rate-limit bucket at the gateway is
    // sized for screens rather than for people, and this is what exercises it.
    displayJourney(data);
  }
  sleep(0.5);
}
