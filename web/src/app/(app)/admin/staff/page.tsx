import { load, loadAll } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type { AdminUser, Department, Page, Staff } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table } from "@/components/ui";
import { EditRow, RecordForm, type Field } from "@/components/RecordForm";
import { createStaff, updateStaff } from "../actions";

/** The staff directory — who works here, in which department, under what licence. */
export default async function StaffPage({
  searchParams,
}: {
  searchParams: Promise<{ q?: string; department?: string; includeInactive?: string }>;
}) {
  const { q = "", department = "", includeInactive } = await searchParams;
  const params = new URLSearchParams({ size: "100" });
  if (q) params.set("q", q);
  if (department) params.set("department", department);
  if (includeInactive === "on") params.set("includeInactive", "true");

  const mayEdit = hasRole(await currentUser(), "ADMIN");
  const [{ data: staff, error }, { data: departments }, { data: accounts }] = await Promise.all([
    load<Page<Staff>>(`/staff?${params}`),
    load<Department[]>("/departments"),
    // Only an administrator may read accounts, and only they can link one, so nobody else asks.
    // Every page, not the first: the platform caps a page at 100 rows however large a `size`
    // asks for, and a hospital with more than a hundred logins would find the account it wanted
    // simply missing from this dropdown.
    mayEdit ? loadAll<AdminUser>("/admin/users") : Promise.resolve({ data: null, error: null }),
  ]);

  const departmentOptions = (departments ?? []).map((entry) => ({
    value: entry.code,
    label: entry.name,
  }));

  /**
   * The accounts that could be linked, plus whichever one this record already has.
   *
   * <p>A staff record and a login are separate things and `userId` is the link. Offering an
   * account that is already linked elsewhere would invite two staff rows claiming one login, and
   * the clinician pick-list on the booking screen reads this directory - so a doctor who can sign
   * in but has no staff row cannot be booked, which is the reason this field exists at all.
   */
  const linkable = (current: string | null): { value: string; label: string }[] => {
    const taken = new Set(
      (staff?.content ?? []).map((member) => member.userId).filter((id): id is string => id !== null),
    );
    return (accounts ?? [])
      .filter((account) => account.id === current || !taken.has(account.id))
      .map((account) => ({ value: account.id, label: `${account.fullName} (${account.username})` }));
  };

  const staffFields = (member?: Staff): Field[] => [
    ...(member
      ? []
      : [
          {
            name: "employeeNo",
            label: "Employee no",
            required: true,
            hint: "Fixed once created.",
          } as Field,
        ]),
    { name: "fullName", label: "Full name", required: !member, value: member?.fullName },
    { name: "designation", label: "Designation", required: !member, value: member?.designation },
    {
      name: "departmentCode",
      label: "Department",
      type: "select",
      options: departmentOptions,
      value: member?.departmentCode,
    },
    { name: "specialty", label: "Specialty", value: member?.specialty },
    { name: "licenseNo", label: "Licence no", value: member?.licenseNo },
    { name: "phone", label: "Phone", value: member?.phone },
    { name: "email", label: "Email", value: member?.email },
    {
      name: "userId",
      label: "Platform login",
      type: "select",
      options: linkable(member?.userId ?? null),
      value: member?.userId,
      hint: "Optional. A visiting consultant has none.",
    },
    ...(member
      ? [{ name: "active", label: "In post", type: "checkbox", value: member.active } as Field]
      : []),
  ];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Staff directory</h1>
        <p className="text-sm text-ink-muted">
          Clinical and operational staff. A staff record may or may not be linked to a login.
        </p>
      </div>

      <form className="flex flex-wrap items-end gap-3">
        <div className="grow">
          <label htmlFor="q" className="block text-sm font-medium">
            Search
          </label>
          <input
            id="q"
            name="q"
            defaultValue={q}
            placeholder="name, employee number, specialty"
            className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor="department" className="block text-sm font-medium">
            Department
          </label>
          <select
            id="department"
            name="department"
            defaultValue={department}
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          >
            <option value="">Any</option>
            {(departments ?? []).map((entry) => (
              <option key={entry.code} value={entry.code}>
                {entry.name}
              </option>
            ))}
          </select>
        </div>
        <label className="flex items-center gap-2 pb-2 text-sm">
          <input type="checkbox" name="includeInactive" defaultChecked={includeInactive === "on"} />
          Include inactive
        </label>
        <button
          type="submit"
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
        >
          Search
        </button>
      </form>

      {error && <ErrorNote>{error}</ErrorNote>}

      {staff && (
        <Card title={`Staff (${staff.totalElements})`}>
          {staff.content.length === 0 ? (
            <Empty>No staff match.</Empty>
          ) : (
            <Table
              head={[
                "Employee no", "Name", "Designation", "Department", "Specialty", "Licence", "",
                ...(mayEdit ? [""] : []),
              ]}
            >
              {staff.content.map((member) => (
                <tr key={member.id} className={member.active ? "" : "opacity-60"}>
                  <td className="numeric px-3 py-2">{member.employeeNo ?? "—"}</td>
                  <td className="px-3 py-2 font-medium">{member.fullName}</td>
                  <td className="px-3 py-2 text-ink-muted">{member.designation ?? "—"}</td>
                  <td className="px-3 py-2 text-ink-muted">{member.departmentName ?? "—"}</td>
                  <td className="px-3 py-2 text-ink-muted">{member.specialty ?? "—"}</td>
                  <td className="numeric px-3 py-2 text-ink-muted">{member.licenseNo ?? "—"}</td>
                  <td className="px-3 py-2">
                    <span className="flex gap-1">
                      {!member.active && <Badge tone="neutral">inactive</Badge>}
                      {!member.userId && (
                        <Badge tone="neutral">no login</Badge>
                      )}
                    </span>
                  </td>
                  {mayEdit && (
                    <td className="px-3 py-2">
                      <EditRow label="Edit">
                        <RecordForm
                          action={updateStaff}
                          hidden={{ id: member.id }}
                          columns={3}
                          submitLabel="Save"
                          fields={staffFields(member)}
                        />
                      </EditRow>
                    </td>
                  )}
                </tr>
              ))}
            </Table>
          )}
        </Card>
      )}

      {mayEdit && (
        <Card title="Add a staff record">
          <RecordForm
            action={createStaff}
            columns={3}
            submitLabel="Add to the directory"
            fields={staffFields()}
          />
          <p className="mt-3 text-xs text-ink-muted">
            A clinician has to be here to be bookable: the pick-list on the booking screen reads
            this directory, not the account list. Linking a login is optional and separate.
          </p>
        </Card>
      )}
    </div>
  );
}
