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
 * 2. **`notBuilt` is honest, not decorative.** These modules have no backend whatsoever, and the
 *    item leads to a page that says so. The alternative — hiding them — was considered and rejected:
 *    seeing the whole shape of the product is useful, and a menu that quietly omits half the roadmap
 *    is its own kind of misleading. What is not acceptable is a screen that looks like it works.
 */

/** Role names as the identity service issues them. */
export type RoleName =
  | "ADMIN"
  | "DOCTOR"
  | "NURSE"
  | "RECEPTIONIST"
  | "LAB_TECH"
  | "PATHOLOGIST";

export type MenuItem = {
  label: string;
  href: string;
  /** Undefined means every signed-in user. */
  roles?: RoleName[];
  /** No backend exists. The link goes to the not-built page, which says what is missing. */
  notBuilt?: boolean;
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
      { label: "Casualty board", href: "/not-built/casualty", notBuilt: true },
      { label: "Admissions & beds", href: "/not-built/admissions", notBuilt: true },
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
    items: [
      { label: "Dispensing queue", href: "/not-built/dispensing", notBuilt: true },
      { label: "Formulary", href: "/not-built/formulary", notBuilt: true },
      { label: "Stock", href: "/not-built/stock", notBuilt: true },
    ],
  },

  {
    label: "Billing",
    items: [
      { label: "Invoices", href: "/not-built/invoices", notBuilt: true },
      { label: "Payments", href: "/not-built/payments", notBuilt: true },
      { label: "Charge items", href: "/not-built/charge-items", notBuilt: true },
      { label: "Payers & tariffs", href: "/not-built/payers", notBuilt: true },
      { label: "Claims", href: "/not-built/claims", notBuilt: true },
      { label: "Receivables", href: "/not-built/receivables", notBuilt: true },
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
