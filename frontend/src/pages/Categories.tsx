import { useState } from "react";
import {
  Button,
  ConfirmDialog,
  EmptyState,
  ErrorNotice,
  Field,
  Loading,
  StatusChip,
  useAnnounce,
} from "../components/ui";
import { ApiError, unwrap, useApi } from "../lib/api";
import type { components } from "../api/generated/schema";
import { useAsync } from "../lib/useAsync";

type Category = components["schemas"]["Category"];

const TAKEN = "A category with this name already exists.";

/**
 * Requirement categories (UX-REQ-04, administrator): the managed list the extraction, the
 * requirements and the scoring weights use. A category in use is deactivated, never deleted.
 */
export function Categories() {
  const api = useApi();
  const announce = useAnnounce();
  const { state, reload } = useAsync(
    () => unwrap(api.GET("/categories", { params: { query: { include_inactive: true } } })),
    [api],
  );
  const [adding, setAdding] = useState(false);
  const [renaming, setRenaming] = useState<Category | null>(null);
  const [deactivating, setDeactivating] = useState<Category | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [taken, setTaken] = useState(false);

  const act = async (message: string, f: () => Promise<unknown>) => {
    setError(null);
    setTaken(false);
    try {
      await f();
      announce(message);
      reload();
    } catch (e) {
      if (e instanceof ApiError && e.code === "already_exists") {
        setTaken(true);
      } else {
        setError(e as Error);
      }
    }
  };

  const patch = (c: Category, body: { name: string; sort_order?: number; is_active?: boolean }) =>
    unwrap(api.PATCH("/categories/{categoryId}", { params: { path: { categoryId: c.id } }, body }));

  const list = state.status === "ready" ? state.data : [];
  const nextOrder = list.reduce((m, c) => Math.max(m, c.sort_order), 0) + 1;

  const move = (c: Category, other: Category) =>
    act(`${c.name} moved.`, async () => {
      await patch(c, { name: c.name, sort_order: other.sort_order });
      await patch(other, { name: other.name, sort_order: c.sort_order });
    });

  return (
    <>
      <h1>Categories</h1>
      <p className="help">
        Requirements are grouped by these categories, and the AI chooses from the active ones when
        it extracts requirements. A category that is in use is deactivated, not deleted.
      </p>
      <div className="toolbar">
        <Button variant="primary" onClick={() => setAdding(true)}>
          Add category
        </Button>
      </div>

      {error && <ErrorNotice error={error} />}
      {taken && (
        <div role="alert" className="card" style={{ borderColor: "var(--error)" }}>
          <p>
            <strong>{TAKEN}</strong>
          </p>
        </div>
      )}
      {state.status === "loading" && <Loading label="Loading categories" />}
      {state.status === "error" && <ErrorNotice error={state.error} onRetry={reload} />}
      {state.status === "ready" &&
        (list.length === 0 ? (
          <EmptyState title="No categories yet">
            Add the categories your tenders use. Until you do, extracted requirements have no
            category and a baseline cannot be confirmed.
          </EmptyState>
        ) : (
          <div className="table-wrap">
            <table>
              <caption>Categories ({list.length})</caption>
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Order</th>
                  <th scope="col">State</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody>
                {list.map((c, i) => (
                  <tr key={c.id}>
                    <th scope="row">{c.name}</th>
                    <td>{c.sort_order}</td>
                    <td>
                      <StatusChip
                        label={c.is_active ? "Active" : "Deactivated"}
                        tone={c.is_active ? "success" : "neutral"}
                      />
                    </td>
                    <td>
                      <Button
                        aria-label="Move up"
                        disabled={i === 0}
                        onClick={() => void move(c, list[i - 1])}
                      >
                        Move up
                      </Button>{" "}
                      <Button
                        aria-label="Move down"
                        disabled={i === list.length - 1}
                        onClick={() => void move(c, list[i + 1])}
                      >
                        Move down
                      </Button>{" "}
                      <Button onClick={() => setRenaming(c)}>Rename</Button>{" "}
                      {c.is_active ? (
                        <Button onClick={() => setDeactivating(c)}>Deactivate</Button>
                      ) : (
                        <Button
                          onClick={() =>
                            void act(`${c.name} is active again.`, () =>
                              patch(c, { name: c.name, is_active: true }),
                            )
                          }
                        >
                          Activate
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}

      <NameDialog
        key={`add-${adding}`}
        open={adding}
        title="Add category"
        confirmLabel="Add"
        initial=""
        onOpenChange={setAdding}
        onSubmit={(name) =>
          void act(`${name} added.`, async () => {
            await unwrap(api.POST("/categories", { body: { name, sort_order: nextOrder } }));
            setAdding(false);
          })
        }
      />
      <NameDialog
        key={`rename-${renaming?.id ?? "none"}`}
        open={renaming !== null}
        title={`Rename ${renaming?.name ?? ""}`}
        confirmLabel="Rename"
        initial={renaming?.name ?? ""}
        onOpenChange={(o) => !o && setRenaming(null)}
        onSubmit={(name) =>
          renaming &&
          void act(`Renamed to ${name}.`, async () => {
            await patch(renaming, { name });
            setRenaming(null);
          })
        }
      />
      <ConfirmDialog
        open={deactivating !== null}
        onOpenChange={(o) => !o && setDeactivating(null)}
        title={`Deactivate ${deactivating?.name ?? ""}`}
        description="The AI stops choosing it and it disappears from the pick lists. Requirements that already use it keep it."
        confirmLabel="Deactivate category"
        onConfirm={() =>
          deactivating &&
          void act(`${deactivating.name} is deactivated.`, async () => {
            await patch(deactivating, { name: deactivating.name, is_active: false });
            setDeactivating(null);
          })
        }
      />
    </>
  );
}

function NameDialog({
  open,
  title,
  confirmLabel,
  initial,
  onOpenChange,
  onSubmit,
}: {
  open: boolean;
  title: string;
  confirmLabel: string;
  initial: string;
  onOpenChange: (o: boolean) => void;
  onSubmit: (name: string) => void;
}) {
  const [name, setName] = useState(initial);
  const valid = name.trim().length > 0;
  return (
    <ConfirmDialog
      open={open}
      onOpenChange={onOpenChange}
      title={title}
      description="Use a short name that committee members will recognise."
      confirmLabel={confirmLabel}
      onConfirm={() => valid && onSubmit(name.trim())}
    >
      <Field label="Name (required)">
        {({ id }) => (
          <input
            id={id}
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
          />
        )}
      </Field>
    </ConfirmDialog>
  );
}
