import { useEffect, useMemo, useState, type FormEvent } from "react";
import { useNavigate, useParams } from "react-router";
import {
  Button,
  ConfirmDialog,
  ErrorNotice,
  Field,
  Loading,
  StatusChip,
  useAnnounce,
} from "../components/ui";
import { ApiError, toApiError, unwrap, useApi } from "../lib/api";
import { problemMessage } from "../lib/labels";
import type { Readiness, Tender, UserRef } from "../lib/types";
import { useAsync } from "../lib/useAsync";

type FieldErrors = Record<string, string>;

function fieldErrors(e: unknown): FieldErrors {
  const out: FieldErrors = {};
  if (e instanceof ApiError) {
    for (const f of e.problem.errors ?? []) {
      out[f.field] = f.message;
    }
    if (e.code === "name_taken") {
      out.name = problemMessage("name_taken", e.problem.title);
    }
  }
  return out;
}

/** PG-03 Create or edit tender (T2): one column, the readiness checklist beside it. */
export function TenderForm() {
  const { tenderId } = useParams();
  const editing = Boolean(tenderId);
  const api = useApi();
  const navigate = useNavigate();
  const announce = useAnnounce();

  const existing = useAsync(
    async () =>
      editing
        ? unwrap(api.GET("/tenders/{tenderId}", { params: { path: { tenderId: tenderId! } } }))
        : null,
    [api, tenderId],
  );

  if (existing.state.status === "loading") return <Loading label="Loading the tender" />;
  if (existing.state.status === "error") {
    return <ErrorNotice error={existing.state.error} onRetry={existing.reload} />;
  }
  return (
    <TenderFormBody
      key={existing.state.data?.id ?? "new"}
      tender={existing.state.data}
      onSaved={(t, message) => {
        announce(message);
        if (!editing) navigate(`/tenders/${t.id}/edit`);
        else existing.reload();
      }}
      reload={existing.reload}
      api={api}
    />
  );
}

function TenderFormBody({
  tender,
  onSaved,
  reload,
  api,
}: {
  tender: Tender | null;
  onSaved: (t: Tender, message: string) => void;
  reload: () => void;
  api: ReturnType<typeof useApi>;
}) {
  const announce = useAnnounce();
  const [name, setName] = useState(tender?.name ?? "");
  const [reference, setReference] = useState(tender?.reference_no ?? "");
  const [description, setDescription] = useState(tender?.description ?? "");
  const [due, setDue] = useState(tender?.due_date ?? "");
  const [picked, setPicked] = useState<UserRef[]>([]);
  const [chairId, setChairId] = useState("");
  const [errors, setErrors] = useState<FieldErrors>({});
  const [failure, setFailure] = useState<Error | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);

  const archived = tender?.archived ?? false;

  async function save(e: FormEvent) {
    e.preventDefault();
    setErrors({});
    setFailure(null);
    setBusy(true);
    try {
      if (!tender) {
        const created = await unwrap(
          api.POST("/tenders", {
            body: {
              name,
              reference_no: reference || null,
              description: description || null,
              member_ids: picked.map((p) => p.id),
              chair_id: chairId,
              evaluation_due_date: due || null,
            },
          }),
        );
        onSaved(created, `Tender ${created.name} created.`);
      } else {
        const updated = await unwrap(
          api.PATCH("/tenders/{tenderId}", {
            params: {
              path: { tenderId: tender.id },
              header: { "If-Match": `"${tender.row_version}"` },
            },
            body: { name, reference_no: reference || null, description: description || null },
          }),
        );
        if ((due || "") !== (tender.due_date ?? "")) {
          await unwrap(
            api.PUT("/tenders/{tenderId}/stage-dates", {
              params: { path: { tenderId: tender.id } },
              body: { items: due ? [{ stage: "evaluation", due_date: due }] : [] },
            }),
          );
        }
        onSaved(updated, "Tender saved.");
      }
    } catch (err) {
      setErrors(fieldErrors(err));
      if (!(err instanceof ApiError) || Object.keys(fieldErrors(err)).length === 0) {
        setFailure(err as Error);
      }
    } finally {
      setBusy(false);
    }
  }

  async function open() {
    if (!tender) return;
    setBusy(true);
    try {
      const opened = await unwrap(
        api.POST("/tenders/{tenderId}/open", { params: { path: { tenderId: tender.id } } }),
      );
      setConfirmOpen(false);
      onSaved(opened, "The tender is open to the committee.");
    } catch (err) {
      setConfirmOpen(false);
      setFailure(err as Error);
    } finally {
      setBusy(false);
    }
  }

  const openAction = tender?.actions?.open;

  return (
    <>
      <h1>{tender ? "Edit tender" : "Create tender"}</h1>
      {failure && <ErrorNotice error={failure} onRetry={reload} />}
      <div
        style={{
          display: "grid",
          gap: 24,
          gridTemplateColumns: "minmax(0, 640px) minmax(240px, 1fr)",
        }}
      >
        <form onSubmit={save} noValidate aria-describedby="form-help">
          <p id="form-help" className="help">
            Fields marked required must be filled. Saving keeps the tender as a draft.
          </p>
          <Field label="Name (required)" error={errors.name}>
            {({ id, describedBy, invalid }) => (
              <input
                id={id}
                type="text"
                value={name}
                required
                aria-required="true"
                aria-invalid={invalid}
                aria-describedby={describedBy}
                disabled={archived}
                onChange={(e) => setName(e.target.value)}
              />
            )}
          </Field>
          <Field label="Reference number" error={errors.reference_no}>
            {({ id, describedBy }) => (
              <input
                id={id}
                type="text"
                value={reference}
                aria-describedby={describedBy}
                disabled={archived}
                onChange={(e) => setReference(e.target.value)}
              />
            )}
          </Field>
          <Field label="Description">
            {({ id, describedBy }) => (
              <textarea
                id={id}
                value={description}
                aria-describedby={describedBy}
                disabled={archived}
                onChange={(e) => setDescription(e.target.value)}
              />
            )}
          </Field>
          <Field label="Evaluation due date" help="Optional. Reminders use it.">
            {({ id, describedBy }) => (
              <input
                id={id}
                type="date"
                value={due}
                aria-describedby={describedBy}
                disabled={archived}
                onChange={(e) => setDue(e.target.value)}
              />
            )}
          </Field>

          {!tender && (
            <CommitteePicker
              api={api}
              picked={picked}
              onPick={setPicked}
              chairId={chairId}
              onChair={setChairId}
              memberError={errors.member_ids}
              chairError={errors.chair_id}
            />
          )}
          <Button variant="primary" type="submit" disabled={busy || archived}>
            {tender ? "Save changes" : "Create tender"}
          </Button>
        </form>

        <aside aria-label="Readiness">
          <Readiness tender={tender} api={api} />
          {tender && (
            <p>
              <Button
                variant="primary"
                disabledReason={
                  openAction?.allowed === false ? (openAction.reason ?? undefined) : undefined
                }
                onClick={() => setConfirmOpen(true)}
              >
                Open to committee
              </Button>
            </p>
          )}
        </aside>
      </div>

      {tender && (
        <CommitteeEditor
          tender={tender}
          api={api}
          onChanged={() => {
            announce("Committee updated.");
            reload();
          }}
        />
      )}

      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title="Open to committee"
        description="Members are notified and see the tender from this moment. You can still edit the tender afterwards."
        confirmLabel="Open to committee"
        onConfirm={open}
        busy={busy}
      />
    </>
  );
}

function Readiness({ tender, api }: { tender: Tender | null; api: ReturnType<typeof useApi> }) {
  const state = useAsync(
    async (): Promise<Readiness | null> =>
      tender
        ? unwrap(
            api.GET("/tenders/{tenderId}/readiness", { params: { path: { tenderId: tender.id } } }),
          )
        : null,
    [api, tender?.id, tender?.row_version],
  );
  return (
    <section className="card">
      <h2>Ready to open</h2>
      {!tender && <p className="help">Create the tender to see what is still missing.</p>}
      {state.state.status === "ready" && state.state.data && (
        <ul className="checklist">
          {state.state.data.items.map((i) => (
            <li key={i.key}>
              <span aria-hidden="true">{i.done ? "✓" : "○"}</span>
              <span>
                {i.label}
                <span className="visually-hidden">{i.done ? " (done)" : " (missing)"}</span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function CommitteePicker({
  api,
  picked,
  onPick,
  chairId,
  onChair,
  memberError,
  chairError,
}: {
  api: ReturnType<typeof useApi>;
  picked: UserRef[];
  onPick: (u: UserRef[]) => void;
  chairId: string;
  onChair: (id: string) => void;
  memberError?: string;
  chairError?: string;
}) {
  const [q, setQ] = useState("");
  const [found, setFound] = useState<UserRef[]>([]);
  useEffect(() => {
    let live = true;
    unwrap(api.GET("/users/lookup", { params: { query: { q: q || undefined, limit: 25 } } })).then(
      (r) => live && setFound(r.items),
      () => live && setFound([]),
    );
    return () => {
      live = false;
    };
  }, [api, q]);
  const pickedIds = useMemo(() => new Set(picked.map((p) => p.id)), [picked]);
  const toggle = (u: UserRef) => {
    const next = pickedIds.has(u.id) ? picked.filter((p) => p.id !== u.id) : [...picked, u];
    onPick(next);
    if (!next.some((p) => p.id === chairId)) onChair("");
  };
  return (
    <>
      <Field label="Find committee members" help="Active users with the committee member role.">
        {({ id, describedBy }) => (
          <input
            id={id}
            type="search"
            value={q}
            aria-describedby={describedBy}
            onChange={(e) => setQ(e.target.value)}
          />
        )}
      </Field>
      <fieldset style={{ border: 0, padding: 0, margin: "0 0 16px" }}>
        <legend className="label">Committee members (required)</legend>
        <div className="picker" role="group" aria-label="Users">
          {found.length === 0 && <p style={{ padding: 12 }}>No matching users.</p>}
          {found.map((u) => (
            <label key={u.id}>
              <input type="checkbox" checked={pickedIds.has(u.id)} onChange={() => toggle(u)} />
              {u.display_name}
            </label>
          ))}
        </div>
        {memberError && <span className="error">{memberError}</span>}
      </fieldset>
      <p aria-live="polite">
        {picked.length === 0
          ? "No members chosen yet."
          : `${picked.length} member${picked.length === 1 ? "" : "s"} chosen.`}
      </p>
      <Field label="Chair (required)" error={chairError} help="One of the chosen members.">
        {({ id, describedBy, invalid }) => (
          <select
            id={id}
            value={chairId}
            aria-invalid={invalid}
            aria-describedby={describedBy}
            onChange={(e) => onChair(e.target.value)}
          >
            <option value="">Choose the Chair</option>
            {picked.map((p) => (
              <option key={p.id} value={p.id}>
                {p.display_name}
              </option>
            ))}
          </select>
        )}
      </Field>
    </>
  );
}

function CommitteeEditor({
  tender,
  api,
  onChanged,
}: {
  tender: Tender;
  api: ReturnType<typeof useApi>;
  onChanged: () => void;
}) {
  const [removing, setRemoving] = useState<UserRef | null>(null);
  const [handling, setHandling] = useState<"kept_as_history" | "reassigned">("kept_as_history");
  const [target, setTarget] = useState("");
  const [note, setNote] = useState("");
  const [error, setError] = useState<Error | null>(null);
  const [adding, setAdding] = useState("");
  const [candidates, setCandidates] = useState<UserRef[]>([]);

  useEffect(() => {
    let live = true;
    unwrap(api.GET("/users/lookup", { params: { query: { limit: 50 } } })).then(
      (r) =>
        live &&
        setCandidates(
          r.items.filter((u) => !(tender.members ?? []).some((m) => m.user.id === u.id)),
        ),
      () => undefined,
    );
    return () => {
      live = false;
    };
  }, [api, tender.members]);

  const run = async (f: () => Promise<unknown>) => {
    setError(null);
    try {
      await f();
      onChanged();
    } catch (e) {
      setError(e as Error);
    }
  };

  return (
    <section className="card" aria-labelledby="committee-edit-h">
      <h2 id="committee-edit-h">Committee</h2>
      {error && <ErrorNotice error={error instanceof ApiError ? error : toApiError(0, null)} />}
      <div className="table-wrap">
        <table>
          <caption>Committee members</caption>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Role in this tender</th>
              <th scope="col">Actions</th>
            </tr>
          </thead>
          <tbody>
            {(tender.members ?? []).map((m) => (
              <tr key={m.user.id}>
                <th scope="row">{m.user.display_name}</th>
                <td>{m.is_chair ? <StatusChip label="Chair" tone="info" /> : "Member"}</td>
                <td>
                  {!m.is_chair && (
                    <>
                      <Button
                        onClick={() =>
                          run(() =>
                            unwrap(
                              api.PUT("/tenders/{tenderId}/chair", {
                                params: { path: { tenderId: tender.id } },
                                body: { user_id: m.user.id },
                              }),
                            ),
                          )
                        }
                      >
                        Make Chair
                      </Button>{" "}
                      <Button onClick={() => setRemoving(m.user)}>Remove</Button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="toolbar" style={{ marginTop: 16 }}>
        <div className="field" style={{ margin: 0 }}>
          <label htmlFor="add-member">Add a member</label>
          <select id="add-member" value={adding} onChange={(e) => setAdding(e.target.value)}>
            <option value="">Choose a person</option>
            {candidates.map((c) => (
              <option key={c.id} value={c.id}>
                {c.display_name}
              </option>
            ))}
          </select>
        </div>
        <Button
          disabled={!adding}
          onClick={() =>
            run(async () => {
              await unwrap(
                api.POST("/tenders/{tenderId}/members", {
                  params: { path: { tenderId: tender.id } },
                  body: { user_id: adding },
                }),
              );
              setAdding("");
            })
          }
        >
          Add member
        </Button>
      </div>

      <ConfirmDialog
        open={removing !== null}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={`Remove ${removing?.display_name ?? ""}`}
        description="Their work is kept as read-only history, or handed to another member."
        confirmLabel="Remove from committee"
        danger
        onConfirm={() => {
          const who = removing!;
          setRemoving(null);
          void run(() =>
            unwrap(
              api.POST("/tenders/{tenderId}/members/{userId}/remove", {
                params: { path: { tenderId: tender.id, userId: who.id } },
                body: {
                  handling,
                  reassign_to_user_id: handling === "reassigned" ? target : null,
                  note: note || null,
                },
              }),
            ),
          );
        }}
      >
        <Field label="What happens to their work">
          {({ id }) => (
            <select
              id={id}
              value={handling}
              onChange={(e) => setHandling(e.target.value as typeof handling)}
            >
              <option value="kept_as_history">Keep as read-only history</option>
              <option value="reassigned">Reassign to another member</option>
            </select>
          )}
        </Field>
        {handling === "reassigned" && (
          <Field label="Reassign to">
            {({ id }) => (
              <select id={id} value={target} onChange={(e) => setTarget(e.target.value)}>
                <option value="">Choose a member</option>
                {(tender.members ?? [])
                  .filter((m) => m.user.id !== removing?.id)
                  .map((m) => (
                    <option key={m.user.id} value={m.user.id}>
                      {m.user.display_name}
                    </option>
                  ))}
              </select>
            )}
          </Field>
        )}
        <Field label="Note">
          {({ id }) => <textarea id={id} value={note} onChange={(e) => setNote(e.target.value)} />}
        </Field>
      </ConfirmDialog>
    </section>
  );
}
