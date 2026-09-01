import { load } from "@/lib/load";
import type { AdminUser, Page } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table, formatDateTime } from "@/components/ui";

/** Platform accounts and the roles they hold. */
export default async function UsersPage({
  searchParams,
}: {
  searchParams: Promise<{ q?: string; role?: string }>;
}) {
  const { q = "", role = "" } = await searchParams;
  const params = new URLSearchParams({ size: "100" });
  if (q) params.set("q", q);
  if (role) params.set("role", role);

  const { data: users, error } = await load<Page<AdminUser>>(`/admin/users?${params}`);

  const initialPassword = (users?.content ?? []).filter((user) => user.mustChangePassword).length;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Users</h1>
        <p className="text-sm text-ink-muted">Accounts, their roles, and when they last signed in.</p>
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
            placeholder="username, name or email"
            className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
        </div>
        <button
          type="submit"
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
        >
          Search
        </button>
      </form>

      {error && <ErrorNote>{error}</ErrorNote>}

      {initialPassword > 0 && (
        <ErrorNote>
          {initialPassword} account(s) are still on their initial password. The platform records the
          flag and warns on sign-in, but nothing yet forces the change.
        </ErrorNote>
      )}

      {users && (
        <Card title={`Accounts (${users.totalElements})`}>
          {users.content.length === 0 ? (
            <Empty>No accounts match.</Empty>
          ) : (
            <Table head={["Username", "Name", "Email", "Roles", "Last sign-in", ""]}>
              {users.content.map((user) => (
                <tr key={user.id} className={user.active ? "" : "opacity-60"}>
                  <td className="px-3 py-2 font-medium">{user.username}</td>
                  <td className="px-3 py-2">{user.fullName}</td>
                  <td className="px-3 py-2 text-ink-muted">{user.email}</td>
                  <td className="px-3 py-2">
                    <span className="flex flex-wrap gap-1">
                      {user.roles.map((held) => (
                        <Badge key={held} tone="accent">
                          {held}
                        </Badge>
                      ))}
                    </span>
                  </td>
                  <td className="numeric px-3 py-2 text-ink-muted">
                    {user.lastLoginAt ? formatDateTime(user.lastLoginAt) : "never"}
                  </td>
                  <td className="px-3 py-2">
                    <span className="flex gap-1">
                      {!user.active && <Badge tone="neutral">disabled</Badge>}
                      {user.mustChangePassword && <Badge tone="critical">initial password</Badge>}
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
