// Smoke: one virtual user, one pass of everything. Not a load test - a sanity check that the
// stack answers correctly and that the other profiles are worth starting. Run this first; if it
// is red, the load numbers from the other profiles mean nothing.
//
//   k6 run tests/perf/smoke.js

import { sleep } from 'k6';
import { CORRECTNESS, USERS } from './lib/config.js';
import { login, setupJourney, readJourney, bookingJourney } from './lib/journey.js';

export const options = {
  scenarios: {
    smoke: { executor: 'shared-iterations', vus: 1, iterations: 5, maxDuration: '2m' },
  },
  // Correctness only: five iterations do not make a percentile. Latency budgets live on the
  // load and soak profiles, which generate enough samples for p(95) to mean something.
  thresholds: CORRECTNESS,
};

export function setup() {
  return setupJourney();
}

export default function (data) {
  const token = login(USERS.doctor);
  readJourney(token, data);
  bookingJourney(token, data);
  sleep(1);
}
