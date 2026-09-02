import { hasRole, type SessionUser } from "@/lib/session";

/**
 * The navigation, as data.
 *
 * <p>One place that says what the application contains. The old nav was five hard-coded links in
 * the layout, which drifted a long way behind the platform: floors, rooms, beds, departments, staff,
 * users, the audit trail and eight laboratory reference screens all had working, authorised APIs and
 * no way to reach them.
 *
 * Two rules hold here:
 *
 * 1. **Roles filter, they do not disable.** A receptionist is not shown a lab worklist greyed out —
 *    it is absent. Filtering happens on the server, in {@link menusFor}, so an item the user may not
 *    see is never serialised into the page at all. A disabled item in the markup is a disclosure of
 *    what exists and who else can reach it.
 * 2. **Every item leads to a real screen.** There used to be a `notBuilt` flag and a page behind it
 *    naming what a module still needed — the OPD queue, casualty, the pharmacy and finally Billing
 *    all passed through it. All of them are built, so the flag, the page and the "not built" badge
 *    are gone rather than kept as scaffolding whose every claim would be false. What is still
 *    missing from the platform is named in the README's Roadmap, which is where somebody looks for
 *    a roadmap; a menu is where somebody looks for a screen that works.
 */

/** Role names as the identity service issues them. */
export type RoleName =
  | "ADMIN"
  | "DOCTOR"
  | "NURSE"
  | "RECEPTIONIST"
  | "LAB_TECH"
  | "PATHOLOGIST"
  | "PHARMACIST"
  | "CASHIER";

export type MenuItem = {
  label: string;
  href: string;
  /** Undefined means every signed-in user. */
  roles?: RoleName[];
  /** Shown under the label — why the screen exists, or what it cannot do. */
  note?: string;
};

export type Menu = {
  label: string;
  /** A menu with no children is a plain link (Dashboard). */
  href?: string;
  roles?: RoleName[];
  items?: MenuItem[];
};

/** Everyone who may read clinical data at all — the widest tier the platform has. */
const CLINICAL_READ: RoleName[] = [
  "ADMIN",
  "DOCTOR",
  "NURSE",
  "RECEPTIONIST",
  "LAB_TECH",
  "PATHOLOGIST",
];
const FRONT_DESK: RoleName[] = ["ADMIN", "RECEPTIONIST", "DOCTOR", "NURSE"];
const LAB: RoleName[] = ["ADMIN", "DOCTOR", "NURSE", "LAB_TECH", "PATHOLOGIST"];
const LAB_WRITE: RoleName[] = ["ADMIN", "LAB_TECH", "PATHOLOGIST"];
const TRIAGE: RoleName[] = ["ADMIN", "DOCTOR", "NURSE", "RECEPTIONIST"];
// Who may tell a patient something, and read what they were told. Closed to the laboratory on
// purpose: releasing a report triggers a message through the event, not by anybody pressing a
// button, and the bench has no reason to originate one.
const NOTIFY: RoleName[] = ["ADMIN", "DOCTOR", "NURSE", "RECEPTIONIST"];
// Casualty and the wards. Deliberately narrower than CLINICAL_READ: a list of who is in casualty
// with what complaint and how sick they are is a chart in table form, and the front desk has no
// business reading it.
const BED_MANAGE: RoleName[] = ["ADMIN", "DOCTOR", "NURSE"];
// Who may read a medication order: the prescriber, the ward that gives it, and the pharmacy that
// fills it. Wider than who may write one, because the people who administer a medicine are not the
// people who ordered it and a nurse who cannot read the prescription cannot safely give it.
const MEDICATION_READ: RoleName[] = ["ADMIN", "DOCTOR", "NURSE", "PHARMACIST"];
// Giving a dose at the bedside. Deliberately not the pharmacy: dispensing hands the medicine over
// and administering puts it into a patient, and the loop is only a control while those are done by
// different people.
const MEDICATION_ADMINISTER: RoleName[] = ["ADMIN", "DOCTOR", "NURSE"];
// Who writes clinical content, and therefore who has any use for an order set. The same three
// roles as BED_MANAGE and MEDICATION_ADMINISTER, and a third name for the same reason `Roles.java`
// keeps LAB_CONFIG separate from LAB_VERIFY: allocating a bed, giving a dose and raising a set of
// orders are different acts that happen to share a role list today, and a change to one should not
// silently move the others.
const CLINICAL_WRITE: RoleName[] = ["ADMIN", "DOCTOR", "NURSE"];
// Who may read the money. Clinicians are in: a doctor asked what something will cost at the
// bedside needs an answer, and a platform that sent them to the billing desk to read a number
// would be routed around within a week. The bench and the pharmacy are out — neither raises an
// invoice nor takes money, and their charges arrive in billing as events with no screen at all.
const BILLING_READ: RoleName[] = ["ADMIN", "CASHIER", "DOCTOR", "NURSE", "RECEPTIONIST"];
// Who may raise an invoice, take a payment or move a claim. The oldest financial control there
// is: the person who decides what is owed is not the person who records that it was paid.
const BILLING_WRITE: RoleName[] = ["ADMIN", "CASHIER"];
const ADMIN_ONLY: RoleName[] = ["ADMIN"];

/**
 * The whole menu.
 *
 * Mirrors the authorisation the services actually enforce, so a visible item is one the API will
 * answer. Where the two could drift, the API is the authority — this list is a convenience, never a
 * control.
 */
export const MENUS: Menu[] = [
  { label: "Dashboard", href: "/" },

  {
    label: "Patients",
    items: [
      { label: "Patient register", href: "/patients", roles: CLINICAL_READ },
      { label: "Register a patient", href: "/patients/new", roles: FRONT_DESK },
    ],
  },

  {
    label: "Scheduling",
    items: [
      { label: "Appointment book", href: "/appointments", roles: CLINICAL_READ },
      { label: "Book an appointment", href: "/appointments/new", roles: FRONT_DESK },
      { label: "Clinician availability", href: "/scheduling/availability", roles: CLINICAL_READ },
      {
        label: "Lapsed appointments",
        href: "/scheduling/lapsed",
        roles: FRONT_DESK,
        note: "Booked, slot passed, never checked in",
      },
      { label: "Clinician schedules", href: "/scheduling/schedules", roles: ADMIN_ONLY },
      { label: "OPD token queue", href: "/scheduling/queue", roles: CLINICAL_READ },
      {
        label: "Waiting-room display",
        href: "/display/GF-GEN",
        roles: CLINICAL_READ,
        // The only menu item that leads somewhere unauthenticated, and it is here so somebody can
        // find the URL to type into a kiosk once. The room code in the link is the seeded general
        // OPD room; the display takes any room code.
        note: "Opens the corridor screen for GF-GEN",
      },
    ],
  },

  {
    label: "Clinical",
    items: [
      { label: "Triage", href: "/triage", roles: TRIAGE },
      {
        label: "Casualty board",
        href: "/casualty",
        roles: BED_MANAGE,
        note: "Sickest first, never arrival order",
      },
      { label: "Admissions & beds", href: "/admissions", roles: BED_MANAGE },
      {
        label: "Drug round",
        href: "/emar",
        roles: MEDICATION_ADMINISTER,
        note: "Scan the wristband, scan the medicine",
      },
      {
        label: "Order sets",
        href: "/order-sets",
        roles: CLINICAL_WRITE,
        note: "Read-only — applied from a chart",
      },
    ],
  },

  {
    label: "Laboratory",
    roles: LAB,
    items: [
      { label: "Worklist", href: "/laboratory", roles: LAB },
      { label: "Scan a tube", href: "/laboratory/scan", roles: LAB },
      { label: "Test catalogue", href: "/laboratory/catalogue", roles: LAB, note: "Read-only" },
      { label: "Reference ranges", href: "/laboratory/reference-ranges", roles: LAB },
      { label: "Interpretation rules", href: "/laboratory/interpretation", roles: LAB },
      { label: "Analyzers", href: "/laboratory/analyzers", roles: LAB, note: "Read-only" },
      {
        label: "Device messages",
        href: "/laboratory/device-messages",
        roles: LAB_WRITE,
        note: "Raw analyzer transmissions",
      },
    ],
  },

  {
    label: "Facility",
    items: [
      { label: "Room directory", href: "/facility", roles: CLINICAL_READ },
      { label: "Rooms", href: "/facility/rooms", roles: CLINICAL_READ },
      { label: "Floors", href: "/facility/floors", roles: CLINICAL_READ },
      { label: "Room types", href: "/facility/room-types", roles: CLINICAL_READ },
      { label: "Beds", href: "/facility/beds", roles: CLINICAL_READ },
      { label: "Departments", href: "/facility/departments", roles: CLINICAL_READ },
    ],
  },

  {
    label: "Pharmacy",
    roles: MEDICATION_READ,
    items: [
      {
        label: "Dispensing queue",
        href: "/pharmacy",
        roles: MEDICATION_READ,
        note: "Oldest first — nobody is more urgent at a counter",
      },
      { label: "Formulary", href: "/pharmacy/formulary", roles: MEDICATION_READ },
      { label: "Interactions", href: "/pharmacy/interactions", roles: MEDICATION_READ },
      { label: "Stock", href: "/pharmacy/stock", roles: MEDICATION_READ },
    ],
  },

  {
    label: "Billing",
    roles: BILLING_READ,
    items: [
      {
        label: "Invoices",
        href: "/billing",
        roles: BILLING_READ,
        note: "Open bills first — what is owed is the question",
      },
      { label: "Raise an invoice", href: "/billing/new", roles: BILLING_WRITE },
      {
        label: "Day book",
        href: "/billing/day-book",
        roles: BILLING_READ,
        note: "Billed, collected and outstanding, split by method",
      },
      { label: "Claims", href: "/billing/claims", roles: BILLING_READ },
      { label: "Charge items", href: "/billing/charge-items", roles: BILLING_READ },
      { label: "Payers & tariffs", href: "/billing/payers", roles: BILLING_READ },
      {
        label: "Tax rates",
        href: "/billing/tax-rates",
        roles: BILLING_READ,
        note: "Dated rows — an invoice keeps the rate it was raised under",
      },
    ],
  },

  {
    label: "Messaging",
    roles: NOTIFY,
    items: [
      {
        label: "Delivery log",
        href: "/messaging",
        roles: NOTIFY,
        note: "What was sent, and whether it arrived",
      },
      {
        label: "Message wording",
        href: "/messaging/templates",
        roles: NOTIFY,
        note: "Editable by an administrator",
      },
    ],
  },

  {
    label: "Administration",
    roles: ADMIN_ONLY,
    items: [
      { label: "Staff directory", href: "/admin/staff", roles: CLINICAL_READ },
      { label: "Users", href: "/admin/users", roles: ADMIN_ONLY },
      { label: "Roles", href: "/admin/roles", roles: ADMIN_ONLY, note: "Read-only" },
      { label: "Audit trail", href: "/admin/audit", roles: ADMIN_ONLY },
    ],
  },
];

/**
 * The menu this user may see, with unreachable items removed.
 *
 * <p>A menu whose every child was filtered away is dropped too — an empty dropdown is worse than no
 * dropdown. A menu with an explicit `roles` the user lacks is dropped whatever its children say.
 */
export function menusFor(user: SessionUser | null): Menu[] {
  if (!user) return [];

  return MENUS.flatMap((menu) => {
    if (menu.roles && !hasRole(user, ...menu.roles)) {
      return [];
    }
    if (!menu.items) {
      return [menu];
    }
    const items = menu.items.filter((item) => !item.roles || hasRole(user, ...item.roles));
    return items.length === 0 ? [] : [{ ...menu, items }];
  });
}

/** Every href a user can reach from the menu. Used by the test that walks them all. */
export function reachableHrefs(user: SessionUser | null): string[] {
  return menusFor(user).flatMap((menu) =>
    menu.href ? [menu.href] : (menu.items ?? []).map((item) => item.href),
  );
}
