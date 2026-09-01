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
 * It provisions two things: the patient the chart tests open, and one laboratory order driven all
 * the way to a released report. The second exists because the laboratory test used to skip itself
 * when no released order was present, which in CI was every single run - a test that is green
 * because it never executed is the same defect as one that depends on ambient data, just quieter.
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

/** A real catalogue code, seeded by the laboratory's V1 migration. */
export const FIXTURE_LAB_TEST_CODE = "CBC";

/**
 * A haemoglobin below the female reference interval (11.5 - 14.5 g/dL).
 *
 * The value and the interval both matter to the assertions, so they live here rather than being
 * repeated as literals in the spec.
 */
export const FIXTURE_LOW_HAEMOGLOBIN = "9.8";
export const FIXTURE_HAEMOGLOBIN_RANGE = "11.5 - 14.5";

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

/** Who the fixture patient is, once provisioned. The lab order needs both of these. */
type FixturePatient = { id: string; mrn: string };

async function ensurePatient(frontDesk: string, clinician: string): Promise<FixturePatient> {
  const found = await call(frontDesk, `/patients?q=${encodeURIComponent(FIXTURE_SURNAME)}&size=1`);
  if (!found.ok) {
    throw new Error(`e2e fixtures: patient search failed (${found.status})`);
  }
  const page = (await found.json()) as {
    totalElements: number;
    content: { id: string; mrn: string }[];
  };
  if (page.totalElements > 0) {
    const existing = page.content[0];
    return { id: existing.id, mrn: existing.mrn };
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
  const patient = (await created.json()) as FixturePatient;

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
  return patient;
}

/**
 * Drives one CBC order all the way to a released report.
 *
 * The laboratory test used to skip when the worklist was empty, and in CI it always was: nothing
 * released an order, so `Reference` and `Flag` were never asserted anywhere it mattered. A skipped
 * test is not a passing test, and it is exactly the failure mode the patient fixture above was
 * written to end.
 *
 * Four calls under three different identities, because the roles enforce separation of duties and a
 * fixture that routed around it with an admin token would be quietly testing a system nobody runs:
 * a clinician orders, a technician collects and enters, and only a pathologist releases.
 */
async function ensureVerifiedLabOrder(
  clinician: string,
  technician: string,
  pathologist: string,
  patient: FixturePatient,
): Promise<void> {
  const released = await call(pathologist, "/lab/orders?status=VERIFIED&size=1");
  if (!released.ok) {
    throw new Error(`e2e fixtures: lab worklist query failed (${released.status})`);
  }
  if (((await released.json()) as { totalElements: number }).totalElements > 0) {
    return;
  }

  const ordered = await call(clinician, "/lab/orders", {
    method: "POST",
    body: JSON.stringify({
      patientId: patient.id,
      patientMrn: patient.mrn,
      // Selects the female reference ranges. Getting this wrong would not fail loudly - it would
      // silently compare against the male interval, and the flag below is the whole point.
      patientSex: "F",
      testCodes: [FIXTURE_LAB_TEST_CODE],
      clinicalNotes: "Provisioned by the browser end-to-end fixture.",
    }),
  });
  if (ordered.status !== 201) {
    throw new Error(`e2e fixtures: could not raise the lab order (${ordered.status}) - ${await ordered.text()}`);
  }
  const orderId = ((await ordered.json()) as { id: string }).id;

  const collected = await call(technician, `/lab/orders/${orderId}/specimens`, {
    method: "POST",
    body: JSON.stringify({ specimenType: "WHOLE_BLOOD" }),
  });
  if (collected.status !== 201) {
    throw new Error(`e2e fixtures: could not collect the specimen (${collected.status}) - ${await collected.text()}`);
  }

  const resulted = await call(technician, `/lab/orders/${orderId}/results`, {
    method: "POST",
    body: JSON.stringify({
      results: [
        // Deliberately below the female interval, so the report carries a real flag. With every
        // value in range the Flag column renders an em dash and the test would pass on a report
        // that flags nothing - proving the column exists, not that flagging works.
        { parameter: "HGB", value: FIXTURE_LOW_HAEMOGLOBIN, unit: "g/dL" },
        { parameter: "WBC", value: "7.4", unit: "10^3/uL" },
        { parameter: "PLT", value: "250", unit: "10^3/uL" },
      ],
    }),
  });
  if (!resulted.ok) {
    throw new Error(`e2e fixtures: could not enter results (${resulted.status}) - ${await resulted.text()}`);
  }

  const verified = await call(pathologist, `/lab/orders/${orderId}/verify`, { method: "POST" });
  if (!verified.ok) {
    throw new Error(`e2e fixtures: could not release the report (${verified.status}) - ${await verified.text()}`);
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
  const technician = await login("lab.tech");
  const pathologist = await login("dr.pathan");

  const patient = await ensurePatient(frontDesk, clinician);
  await ensureVerifiedLabOrder(clinician, technician, pathologist, patient);
}

export default globalSetup;
