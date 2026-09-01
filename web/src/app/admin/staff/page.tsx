import { load } from "@/lib/load";
import type { Department, Page, Staff } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table } from "@/components/ui";

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

  const [{ data: staff, error }, { data: departments }] = await Promise.all([
    load<Page<Staff>>(`/staff?${params}`),
    load<Department[]>("/departments"),
  ]);

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
              head={["Employee no", "Name", "Designation", "Department", "Specialty", "Licence", ""]}
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
                </tr>
              ))}
            </Table>
          )}
        </Card>
      )}
    </div>
  );
}
