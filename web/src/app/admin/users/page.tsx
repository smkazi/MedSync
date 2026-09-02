import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type { AdminUser, Page, RoleSummary } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table, formatDateTime } from "@/components/ui";
import { EditRow, RecordForm } from "@/components/RecordForm";
import { createUser, resetPassword, updateUser } from "../actions";

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

  const mayEdit = hasRole(await currentUser(), "ADMIN");
  const [{ data: users, error }, { data: roles }] = await Promise.all([
    load<Page<AdminUser>>(`/admin/users?${params}`),
    load<RoleSummary[]>("/admin/roles"),
  ]);

  const initialPassword = (users?.content ?? []).filter((user) => user.mustChangePassword).length;
  const roleOptions = (roles ?? []).map((entry) => ({ value: entry.code, label: entry.code }));

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

      {/* Not a live region. This is a standing fact about the account list, not the outcome of an
          action, so announcing it on every page load would be noise - and it left the page with two
          role="status" elements, only one of which anybody had submitted anything to. */}
      {initialPassword > 0 && (
        <p className="rounded-md border border-warn/40 bg-warn-soft px-3 py-2 text-sm text-warn">
          {initialPassword} account(s) are still on the password they were issued with. Each is
          issued a session with no roles until it changes, so it can sign in and do nothing else —
          this is a fact about them, not a warning about the platform.
        </p>
      )}

      {users && (
        <Card title={`Accounts (${users.totalElements})`}>
          {users.content.length === 0 ? (
            <Empty>No accounts match.</Empty>
          ) : (
            <Table
              head={[
                "Username", "Name", "Email", "Roles", "Last sign-in", "",
                ...(mayEdit ? [""] : []),
              ]}
            >
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
                  {mayEdit && (
                    <td className="space-y-2 px-3 py-2">
                      <EditRow label="Edit">
                        <RecordForm
                          action={updateUser}
                          hidden={{ id: user.id }}
                          columns={2}
                          submitLabel="Save"
                          fields={[
                            { name: "fullName", label: "Full name", value: user.fullName },
                            { name: "email", label: "Email", value: user.email },
                            {
                              name: "roles",
                              label: "Roles",
                              type: "multicheck",
                              options: roleOptions,
                              values: user.roles,
                              hint: "Leaving every box clear leaves the roles unchanged. To remove access, disable the account.",
                            },
                            { name: "active", label: "Enabled", type: "checkbox", value: user.active },
                          ]}
                        />
                      </EditRow>
                      <EditRow label="Reset password">
                        <RecordForm
                          action={resetPassword}
                          hidden={{ id: user.id, username: user.username }}
                          columns={1}
                          submitLabel="Reset it"
                          fields={[
                            {
                              name: "newPassword",
                              label: "New password",
                              required: true,
                              hint: "At least 12 characters. Every session is signed out, and the account must change it before it can do anything.",
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
        <Card title="Create an account">
          <RecordForm
            action={createUser}
            columns={2}
            submitLabel="Create account"
            fields={[
              { name: "username", label: "Username", required: true },
              { name: "fullName", label: "Full name", required: true },
              { name: "email", label: "Email", required: true },
              {
                name: "password",
                label: "Initial password",
                required: true,
                hint: "At least 12 characters. A handover, not a secret to keep.",
              },
              {
                name: "roles",
                label: "Roles",
                type: "multicheck",
                options: roleOptions,
                required: true,
              },
            ]}
          />
          <p className="mt-3 text-xs text-ink-muted">
            The account is created owing a password change, so the password typed here gets it as
            far as the change-password screen and no further. That is deliberate: whoever creates
            an account knows the password they chose for it.
          </p>
        </Card>
      )}
    </div>
  );
}
