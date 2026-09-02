import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type { Floor } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table } from "@/components/ui";
import { EditRow, RecordForm } from "@/components/RecordForm";
import { createFloor, updateFloor } from "../actions";

/** Floors, ordered as you would climb them. */
export default async function FloorsPage() {
  const { data: floors, error } = await load<Floor[]>("/floors?includeInactive=true");
  const mayEdit = hasRole(await currentUser(), "ADMIN");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Floors</h1>
        <p className="text-sm text-ink-muted">
          Level orders the building vertically; ground is 0, so a basement is negative.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      {floors && (
        <Card title="Floors">
          {floors.length === 0 ? (
            <Empty>No floors are configured.</Empty>
          ) : (
            <Table head={["Level", "Code", "Name", "", ...(mayEdit ? [""] : [])]}>
              {[...floors]
                .sort((a, b) => a.level - b.level)
                .map((floor) => (
                  <tr key={floor.id} className={floor.active ? "" : "opacity-60"}>
                    <td className="numeric px-3 py-2">{floor.level}</td>
                    <td className="numeric px-3 py-2 font-medium">{floor.code}</td>
                    <td className="px-3 py-2">{floor.name}</td>
                    <td className="px-3 py-2">
                      {!floor.active && <Badge tone="neutral">inactive</Badge>}
                    </td>
                    {mayEdit && (
                      <td className="px-3 py-2">
                        <EditRow label="Edit">
                          <RecordForm
                            action={updateFloor}
                            hidden={{ id: floor.id }}
                            columns={3}
                            submitLabel="Save"
                            fields={[
                              { name: "name", label: "Name", value: floor.name },
                              { name: "level", label: "Level", type: "number", value: floor.level },
                              { name: "active", label: "In use", type: "checkbox", value: floor.active },
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
        <Card title="Add a floor">
          <RecordForm
            action={createFloor}
            columns={3}
            submitLabel="Add floor"
            fields={[
              { name: "code", label: "Code", required: true, placeholder: "FF" },
              { name: "name", label: "Name", required: true, placeholder: "First Floor" },
              {
                name: "level",
                label: "Level",
                type: "number",
                required: true,
                hint: "Ground is 0; a basement is negative.",
              },
            ]}
          />
        </Card>
      )}
    </div>
  );
}
