import { load } from "@/lib/load";
import type { Department } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table } from "@/components/ui";

/** Departments. Read and create exist in the API; there is no update or deactivate endpoint. */
export default async function DepartmentsPage() {
  const { data: departments, error } = await load<Department[]>("/departments?includeInactive=true");

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
            <Table head={["Code", "Name", "Description", ""]}>
              {departments.map((department) => (
                <tr key={department.id} className={department.active ? "" : "opacity-60"}>
                  <td className="numeric px-3 py-2 font-medium">{department.code}</td>
                  <td className="px-3 py-2">{department.name}</td>
                  <td className="px-3 py-2 text-ink-muted">{department.description ?? "—"}</td>
                  <td className="px-3 py-2">
                    {!department.active && <Badge tone="neutral">inactive</Badge>}
                  </td>
                </tr>
              ))}
            </Table>
          )}
        </Card>
      )}

      <p className="text-sm text-ink-muted">
        The API can list and create departments but has no update or deactivate endpoint, so there is
        nothing to edit here yet — a gap in the service, not the screen.
      </p>
    </div>
  );
}
