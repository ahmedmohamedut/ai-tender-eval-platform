import { Card, Flex, Input, Select, Space, Table, Typography } from "antd";
import { useState } from "react";
import {
  Button,
  ConfirmDialog,
  EmptyState,
  ErrorNotice,
  Field,
  Loading,
  StatusChip,
  rowHeader,
  rowHeaderComponents,
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
      <Typography.Title level={1} style={{ fontSize: 24, marginTop: 0 }}>
        Users
      </Typography.Title>
      <Card style={{ marginBottom: 16 }}>
        <Flex gap={16} wrap align="flex-end" role="search">
          <div style={{ flex: 1, minWidth: 240 }}>
            <label htmlFor="user-search" style={{ display: "block", marginBottom: 4 }}>
              Search users
            </label>
            <Input.Search
              id="user-search"
              allowClear
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="Name or email"
            />
          </div>
          <div>
            <label htmlFor="user-role" style={{ display: "block", marginBottom: 4 }}>
              Role
            </label>
            <Select
              id="user-role"
              value={role}
              onChange={(v) => setRole(v as UserRole | "")}
              style={{ minWidth: 180 }}
              options={[
                { value: "", label: "All roles" },
                ...ROLES.map((r) => ({ value: r, label: roleLabel[r] })),
              ]}
            />
          </div>
          <div>
            <label htmlFor="user-status" style={{ display: "block", marginBottom: 4 }}>
              Status
            </label>
            <Select
              id="user-status"
              value={status}
              onChange={(v) => setStatus(v as UserStatus | "")}
              style={{ minWidth: 180 }}
              options={[
                { value: "", label: "All statuses" },
                ...(["invited", "active", "deactivated"] as UserStatus[]).map((s) => ({
                  value: s,
                  label: userStatusLabel[s],
                })),
              ]}
            />
          </div>
          <Button variant="primary" onClick={() => setInviting(true)}>
            Invite user
          </Button>
        </Flex>
      </Card>

      {error && <ErrorNotice error={error} />}
      {state.status === "loading" && <Loading label="Loading users" />}
      {state.status === "error" && <ErrorNotice error={state.error} onRetry={reload} />}
      {state.status === "ready" &&
        (state.data.items.length === 0 ? (
          <EmptyState title="No users match">
            Change the search or the filters, or invite a user.
          </EmptyState>
        ) : (
          <Table<User>
            rowKey="id"
            components={rowHeaderComponents}
            dataSource={state.data.items}
            pagination={false}
            scroll={{ x: "max-content" }}
            title={() => (
              <Typography.Text strong>
                Users ({state.data.total ?? state.data.items.length})
              </Typography.Text>
            )}
            columns={[
              { title: "Name", onCell: rowHeader, render: (_, u) => u.display_name },
              { title: "Email", dataIndex: "email" },
              { title: "Role", render: (_, u) => roleLabel[u.role] },
              {
                title: "Status",
                render: (_, u) => (
                  <StatusChip label={userStatusLabel[u.status]} tone={userStatusTone[u.status]} />
                ),
              },
              {
                title: "Actions",
                render: (_, u) => (
                  <Space wrap>
                    <Button onClick={() => setChanging(u)}>Change role</Button>
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
                  </Space>
                ),
              },
            ]}
          />
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
          <Input
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
          <Input
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
          <Select
            id={id}
            value={role}
            onChange={(v) => setRole(v)}
            style={{ width: "100%" }}
            options={ROLES.map((r) => ({ value: r, label: roleLabel[r] }))}
          />
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
          <Select
            id={id}
            value={role}
            onChange={(v) => setRole(v)}
            style={{ width: "100%" }}
            options={ROLES.map((r) => ({ value: r, label: roleLabel[r] }))}
          />
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
        {({ id }) => (
          <Input.TextArea id={id} value={note} onChange={(e) => setNote(e.target.value)} />
        )}
      </Field>
    </ConfirmDialog>
  );
}

export { problemMessage, ApiError };
