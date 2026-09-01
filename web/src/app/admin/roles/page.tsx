import { load } from "@/lib/load";
import type { RoleSummary } from "@/lib/types";
import { Card, Empty, ErrorNote, Table } from "@/components/ui";

/** The role vocabulary. Fixed by design — granting a role a capability is a code change. */
export default async function RolesPage() {
  const { data: roles, error } = await load<RoleSummary[]>("/admin/roles");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Roles</h1>
        <p className="text-sm text-ink-muted">What each role is for.</p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      {roles && (
        <Card title="Roles">
          {roles.length === 0 ? (
            <Empty>No roles are configured.</Empty>
          ) : (
            <Table head={["Code", "Description"]}>
              {roles.map((role) => (
                <tr key={role.code}>
                  <td className="numeric px-3 py-2 font-medium">{role.code}</td>
                  <td className="px-3 py-2 text-ink-muted">{role.description}</td>
                </tr>
              ))}
            </Table>
          )}
        </Card>
      )}

      <p className="text-sm text-ink-muted">
        Read-only, and deliberately so. The role list is data, but what a role may <em>do</em> is
        named in compiled authorisation expressions — so granting a capability is a code change, not
        a configuration one. Assigning an existing role to a user is done from the Users screen.
      </p>
    </div>
  );
}
