import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type { Department } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table } from "@/components/ui";
import { EditRow, RecordForm } from "@/components/RecordForm";
import { createDepartment, updateDepartment } from "../../admin/actions";

/** Departments — the units staff, rooms, appointments and encounters all belong to. */
export default async function DepartmentsPage() {
  const { data: departments, error } = await load<Department[]>("/departments?includeInactive=true");
  const mayEdit = hasRole(await currentUser(), "ADMIN");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Departments</h1>
        <p className="text-sm text-ink-muted">
          The clinical and operational units staff and rooms belong to.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      {departments && (
        <Card title="Departments">
          {departments.length === 0 ? (
            <Empty>No departments are configured.</Empty>
          ) : (
            <Table head={["Code", "Name", "Description", "", ...(mayEdit ? [""] : [])]}>
              {departments.map((department) => (
                <tr key={department.id} className={department.active ? "" : "opacity-60"}>
                  <td className="numeric px-3 py-2 font-medium">{department.code}</td>
                  <td className="px-3 py-2">{department.name}</td>
                  <td className="px-3 py-2 text-ink-muted">{department.description ?? "—"}</td>
                  <td className="px-3 py-2">
                    {!department.active && <Badge tone="neutral">inactive</Badge>}
                  </td>
                  {mayEdit && (
                    <td className="px-3 py-2">
                      <EditRow label="Edit">
                        <RecordForm
                          action={updateDepartment}
                          hidden={{ code: department.code }}
                          columns={3}
                          submitLabel="Save"
                          fields={[
                            { name: "name", label: "Name", value: department.name },
                            {
                              name: "description",
                              label: "Description",
                              type: "textarea",
                              value: department.description,
                            },
                            {
                              name: "active",
                              label: "In use",
                              type: "checkbox",
                              value: department.active,
                            },
                          ]}
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
        <Card title="Add a department">
          <RecordForm
            action={createDepartment}
            columns={3}
            submitLabel="Add department"
            fields={[
              {
                name: "code",
                label: "Code",
                required: true,
                placeholder: "DERM",
                hint: "Fixed once created — staff, appointments and encounters all store it.",
              },
              { name: "name", label: "Name", required: true, placeholder: "Dermatology" },
              { name: "description", label: "Description", type: "textarea" },
            ]}
          />
          <p className="mt-3 text-xs text-ink-muted">
            Retiring a department takes it out of the pick-lists and keeps every row that points at
            it: the encounters recorded under it are still real. The code cannot be rewritten,
            because three services store it and none of them would learn it had changed.
          </p>
        </Card>
      )}
    </div>
  );
}
