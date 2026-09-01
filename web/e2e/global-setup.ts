/**
 * Provisions the fixtures the browser suite needs, before any test runs.
 *
 * This exists because the suite was silently depending on ambient data. Three tests searched for a
 * patient named "Nair" that happened to be in one developer's database, created by hand during an
 * earlier verification. They passed locally and failed the first time CI ran them against a fresh
 * PostgreSQL — the worst kind of test, because it is green exactly where nobody is looking.
 *
 * So the suite owns its data now. Same principle the REST Assured suite already follows: a test
 * that needs a row creates the row.
 *
 * Idempotent on purpose. It runs against a long-lived development database as often as against a
 * throwaway CI one, and re-running it must not accumulate duplicate charts.
 */

const GATEWAY = process.env.GATEWAY_URL ?? "http://localhost:8080";
const PASSWORD = process.env.SEED_PASSWORD ?? "ChangeMe!Dev2026";

/**
 * The surname the patient tests search for.
 *
 * Fixed rather than run-scoped, deliberately, and the opposite of the choice made in the API
 * suite. There the point is isolation between runs; here the point is that a human can open
 * /patients?q=nair in a browser and see what the test sees. Idempotency, not uniqueness, keeps
 * repeated runs honest.
 */
export const FIXTURE_SURNAME = "Nair";

/**
 * A national id that must never be rendered on a chart.
 *
 * The test asserting its absence is only meaningful if the value actually exists on the record —
 * otherwise it passes because the string was never there, which proves nothing about the
 * encryption or the response filtering.
 */
export const FIXTURE_NATIONAL_ID = "ABCDE1234F";

type Json = Record<string, unknown>;

async function login(username: string): Promise<string> {
  const response = await fetch(`${GATEWAY}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password: PASSWORD }),
  });
  if (!response.ok) {
    throw new Error(
      `e2e fixtures: could not sign in as ${username} (${response.status}). ` +
        `Is the stack running at ${GATEWAY} with the demo accounts seeded (HMS_SEED_ENABLED=true)?`,
    );
  }
  return ((await response.json()) as { accessToken: string }).accessToken;
}

async function call(token: string, path: string, init: RequestInit = {}): Promise<Response> {
  return fetch(`${GATEWAY}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      ...(init.headers ?? {}),
    },
  });
}

async function ensurePatient(frontDesk: string, clinician: string): Promise<void> {
  const found = await call(frontDesk, `/patients?q=${encodeURIComponent(FIXTURE_SURNAME)}&size=1`);
  if (!found.ok) {
    throw new Error(`e2e fixtures: patient search failed (${found.status})`);
  }
  const page = (await found.json()) as { totalElements: number; content: Json[] };
  if (page.totalElements > 0) {
    return;
  }

  const created = await call(frontDesk, "/patients", {
    method: "POST",
    body: JSON.stringify({
      firstName: "Meera",
      lastName: FIXTURE_SURNAME,
      dateOfBirth: "1982-03-14",
      sex: "FEMALE",
      bloodGroup: "O+",
      phone: "+971500000001",
      city: "Test City",
      // Encrypted at rest and excluded from every patient response. One test asserts it never
      // reaches the rendered chart, which only means something if it is really on the record.
      nationalId: FIXTURE_NATIONAL_ID,
      insurancePolicyNo: "POL-E2E-0001",
    }),
  });
  if (created.status !== 201) {
    throw new Error(`e2e fixtures: could not register the patient (${created.status}) - ${await created.text()}`);
  }
  const patient = (await created.json()) as { id: string };

  // A life-threatening allergy, because one test asserts the chart surfaces it as an alert without
  // the clinician having to scroll. Added by a clinician: recording an allergy is clinical writing,
  // and the front desk cannot do it.
  const allergy = await call(clinician, `/patients/${patient.id}/allergies`, {
    method: "POST",
    body: JSON.stringify({
      substance: "Penicillin",
      reaction: "Anaphylaxis",
      severity: "LIFE_THREATENING",
    }),
  });
  if (allergy.status !== 201) {
    throw new Error(`e2e fixtures: could not record the allergy (${allergy.status}) - ${await allergy.text()}`);
  }
}

async function globalSetup(): Promise<void> {
  const health = await fetch(`${GATEWAY}/actuator/health`).catch(() => null);
  if (!health?.ok) {
    throw new Error(
      `e2e fixtures: no stack answering at ${GATEWAY}. Start it first (make dev-test-stack), ` +
        "or point the suite elsewhere with GATEWAY_URL.",
    );
  }

  const frontDesk = await login("reception");
  const clinician = await login("dr.rao");
  await ensurePatient(frontDesk, clinician);
}

export default globalSetup;
