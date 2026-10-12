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
import { problemMessage, roleLabel, userStatusLabel, userStatusTone } from "../lib/labels";
import type { User, UserRole, UserStatus } from "../lib/types";
import { useAsync } from "../lib/useAsync";

const ROLES: UserRole[] = ["admin", "member", "observer"];

/** PG-07 User management (T2/T1): invite, change role, deactivate with a handling choice. */
export function Users() {
  const api = useApi();
  const announce = useAnnounce();
  const [q, setQ] = useState("");
  const [role, setRole] = useState<UserRole | "">("");
  const [status, setStatus] = useState<UserStatus | "">("");
  const [inviting, setInviting] = useState(false);
  const [deactivating, setDeactivating] = useState<User | null>(null);
  const [changing, setChanging] = useState<User | null>(null);
  const [error, setError] = useState<Error | null>(null);

  const { state, reload } = useAsync(
    () =>
      unwrap(
        api.GET("/users", {
          params: {
            query: {
              q: q || undefined,
              role: role || undefined,
              status: status || undefined,
              limit: 100,
            },
          },
        }),
      ),
    [api, q, role, status],
  );

  const act = async (message: string, f: () => Promise<unknown>) => {
    setError(null);
    try {
      await f();
      announce(message);
      reload();
    } catch (e) {
      setError(e as Error);
    }
  };

  return (
    <>
      <h1>Users</h1>
      <div className="toolbar" role="search">
        <div className="field grow" style={{ margin: 0 }}>
          <label htmlFor="user-search">Search users</label>
          <input
            id="user-search"
            type="search"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Name or email"
          />
        </div>
        <div className="field" style={{ margin: 0 }}>
          <label htmlFor="user-role">Role</label>
          <select
            id="user-role"
            value={role}
            onChange={(e) => setRole(e.target.value as UserRole | "")}
          >
            <option value="">All roles</option>
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {roleLabel[r]}
              </option>
            ))}
          </select>
        </div>
        <div className="field" style={{ margin: 0 }}>
          <label htmlFor="user-status">Status</label>
          <select
            id="user-status"
            value={status}
            onChange={(e) => setStatus(e.target.value as UserStatus | "")}
          >
            <option value="">All statuses</option>
            {(["invited", "active", "deactivated"] as UserStatus[]).map((s) => (
              <option key={s} value={s}>
                {userStatusLabel[s]}
              </option>
            ))}
          </select>
        </div>
        <Button variant="primary" onClick={() => setInviting(true)}>
          Invite user
        </Button>
      </div>

      {error && <ErrorNotice error={error} />}
      {state.status === "loading" && <Loading label="Loading users" />}
      {state.status === "error" && <ErrorNotice error={state.error} onRetry={reload} />}
      {state.status === "ready" &&
        (state.data.items.length === 0 ? (
          <EmptyState title="No users match">
            Change the search or the filters, or invite a user.
          </EmptyState>
        ) : (
          <div className="table-wrap">
            <table>
              <caption>Users ({state.data.total ?? state.data.items.length})</caption>
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Email</th>
                  <th scope="col">Role</th>
                  <th scope="col">Status</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody>
                {state.data.items.map((u) => (
                  <tr key={u.id}>
                    <th scope="row">{u.display_name}</th>
                    <td>{u.email}</td>
                    <td>{roleLabel[u.role]}</td>
                    <td>
                      <StatusChip
                        label={userStatusLabel[u.status]}
                        tone={userStatusTone[u.status]}
                      />
                    </td>
                    <td>
                      <Button onClick={() => setChanging(u)}>Change role</Button>{" "}
                      {u.status === "invited" && (
                        <Button
                          onClick={() =>
                            act("Invitation sent again.", () =>
                              unwrap(
                                api.POST("/users/{userId}/resend-invitation", {
                                  params: { path: { userId: u.id } },
                                }),
                              ),
                            )
                          }
                        >
                          Resend invitation
                        </Button>
                      )}
                      {u.status === "deactivated" ? (
                        <Button
                          onClick={() =>
                            act(`${u.display_name} is active again.`, () =>
                              unwrap(
                                api.POST("/users/{userId}/reactivate", {
                                  params: { path: { userId: u.id } },
                                }),
                              ),
                            )
                          }
                        >
                          Reactivate
                        </Button>
                      ) : (
                        <Button onClick={() => setDeactivating(u)}>Deactivate</Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}

      <InviteDialog
        open={inviting}
        onOpenChange={setInviting}
        onInvite={(body) =>
          act(`Invitation sent to ${body.email}.`, async () => {
            await unwrap(api.POST("/users", { body }));
            setInviting(false);
          })
        }
      />
      <ChangeRoleDialog
        user={changing}
        onClose={() => setChanging(null)}
        onChange={(user, newRole) =>
          act(`${user.display_name} is now ${roleLabel[newRole]}.`, async () => {
            await unwrap(
              api.PATCH("/users/{userId}", {
                params: {
                  path: { userId: user.id },
                  header: { "If-Match": `"${user.row_version}"` },
                },
                body: { role: newRole },
              }),
            );
            setChanging(null);
          })
        }
      />
      <DeactivateDialog
        user={deactivating}
        onClose={() => setDeactivating(null)}
        onDeactivate={(user, handling, note) =>
          act(`${user.display_name} is deactivated.`, async () => {
            await unwrap(
              api.POST("/users/{userId}/deactivate", {
                params: { path: { userId: user.id } },
                body: { handling, note: note || null },
              }),
            );
            setDeactivating(null);
          })
        }
      />
    </>
  );
}

function InviteDialog({
  open,
  onOpenChange,
  onInvite,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  onInvite: (body: { email: string; display_name: string; role: UserRole }) => void;
}) {
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [role, setRole] = useState<UserRole>("member");
  const valid = /^\S+@\S+\.\S+$/.test(email) && name.trim().length > 0;
  return (
    <ConfirmDialog
      open={open}
      onOpenChange={onOpenChange}
      title="Invite user"
      description="The person gets an email with a link to set a password."
      confirmLabel="Send invitation"
      onConfirm={() => valid && onInvite({ email: email.trim(), display_name: name.trim(), role })}
    >
      <Field label="Email (required)">
        {({ id }) => (
          <input
            id={id}
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        )}
      </Field>
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
      <Field label="Role">
        {({ id }) => (
          <select id={id} value={role} onChange={(e) => setRole(e.target.value as UserRole)}>
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {roleLabel[r]}
              </option>
            ))}
          </select>
        )}
      </Field>
    </ConfirmDialog>
  );
}

function ChangeRoleDialog({
  user,
  onClose,
  onChange,
}: {
  user: User | null;
  onClose: () => void;
  onChange: (u: User, r: UserRole) => void;
}) {
  const [role, setRole] = useState<UserRole>("member");
  return (
    <ConfirmDialog
      open={user !== null}
      onOpenChange={(o) => !o && onClose()}
      title={`Change role of ${user?.display_name ?? ""}`}
      description="The new role applies from their next request."
      confirmLabel="Change role"
      onConfirm={() => user && onChange(user, role)}
    >
      <Field label="Role">
        {({ id }) => (
          <select id={id} value={role} onChange={(e) => setRole(e.target.value as UserRole)}>
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {roleLabel[r]}
              </option>
            ))}
          </select>
        )}
      </Field>
    </ConfirmDialog>
  );
}

function DeactivateDialog({
  user,
  onClose,
  onDeactivate,
}: {
  user: User | null;
  onClose: () => void;
  onDeactivate: (u: User, handling: "kept_as_history", note: string) => void;
}) {
  const [note, setNote] = useState("");
  return (
    <ConfirmDialog
      open={user !== null}
      onOpenChange={(o) => !o && onClose()}
      title={`Deactivate ${user?.display_name ?? ""}`}
      description="Their sessions end now. Their work in progress is kept as read-only history."
      confirmLabel="Deactivate user"
      danger
      onConfirm={() => user && onDeactivate(user, "kept_as_history", note)}
    >
      <Field label="Note">
        {({ id }) => <textarea id={id} value={note} onChange={(e) => setNote(e.target.value)} />}
      </Field>
    </ConfirmDialog>
  );
}

export { problemMessage, ApiError };
