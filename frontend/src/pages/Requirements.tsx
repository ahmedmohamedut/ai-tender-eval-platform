import { useState } from "react";
import { Link, useParams } from "react-router";
import {
  Button,
  ConfirmDialog,
  EmptyState,
  ErrorNotice,
  Field,
  Loading,
  NoteDialog,
  StatusChip,
  useAnnounce,
} from "../components/ui";
import { useSession } from "../app/Session";
import { ApiError, unwrap, useApi } from "../lib/api";
import {
  deleteReasonLabel,
  flagLabel,
  importanceLabel,
  originLabel,
  reviewStateLabel,
  reviewStateTone,
  type Tone,
} from "../lib/labels";
import { usePush } from "../lib/usePush";
import { useAsync } from "../lib/useAsync";
import { TenderHeader, useTender } from "./TenderHome";
import type { components } from "../api/generated/schema";

type Req = components["schemas"]["Requirement"];
type Category = components["schemas"]["Category"];

interface Draft {
  text: string;
  category_id: string;
  importance: "mandatory" | "optional";
  acceptance_criteria: string;
}

function toDraft(r: Req): Draft {
  return {
    text: r.text,
    category_id: r.category?.id ?? "",
    importance: r.importance,
    acceptance_criteria: r.acceptance_criteria ?? "",
  };
}

/** PG-11 Requirements (T4 review table with a side panel). */
export function Requirements() {
  const { tenderId } = useParams();
  const api = useApi();
  const announce = useAnnounce();
  const { me } = useSession();
  const minNote = me.client_config.min_note_length;
  const tender = useTender(tenderId);

  const [q, setQ] = useState("");
  const [category, setCategory] = useState("");
  const [importance, setImportance] = useState("");
  const [state, setState] = useState("");
  const [flagged, setFlagged] = useState(false);
  const [deleted, setDeleted] = useState(false);
  const [openId, setOpenId] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const list = useAsync(
    () =>
      unwrap(
        api.GET("/tenders/{tenderId}/requirements", {
          params: {
            path: { tenderId: tenderId! },
            query: {
              limit: 200,
              q: q || undefined,
              category_id: category || undefined,
              importance: (importance || undefined) as "mandatory" | "optional" | undefined,
              review_state: (state || undefined) as "unreviewed" | "reviewed" | undefined,
              flagged: flagged || undefined,
              deleted: deleted || undefined,
            },
          },
        }),
      ),
    [api, tenderId, q, category, importance, state, flagged, deleted],
  );
  const summary = useAsync(
    () =>
      unwrap(
        api.GET("/tenders/{tenderId}/requirements/summary", {
          params: { path: { tenderId: tenderId! } },
        }),
      ),
    [api, tenderId],
  );
  const categories = useAsync(
    () => unwrap(api.GET("/categories", { params: { query: {} } })),
    [api],
  );
  const refresh = () => {
    list.reload();
    summary.reload();
  };
  usePush(tenderId, refresh, ["requirements.changed", "baseline.changed", "job.updated"]);

  const [adding, setAdding] = useState(false);
  const [bulkNote, setBulkNote] = useState<
    null | "mark_reviewed" | "change_category" | "change_importance"
  >(null);
  const [bulkCategory, setBulkCategory] = useState("");
  const [bulkImportance, setBulkImportance] = useState<"mandatory" | "optional">("mandatory");

  if (tender.state.status === "loading" || list.state.status === "loading") {
    return <Loading label="Loading requirements" />;
  }
  if (tender.state.status === "error")
    return <ErrorNotice error={tender.state.error} onRetry={tender.reload} />;
  if (list.state.status === "error")
    return <ErrorNotice error={list.state.error} onRetry={list.reload} />;
  const items: Req[] = list.state.data.items;
  const cats: Category[] = categories.state.status === "ready" ? categories.state.data : [];
  const sum = summary.state.status === "ready" ? summary.state.data : null;
  const open = items.find((r) => r.id === openId) ?? null;
  const locked = items.length > 0 && items.every((r) => !r.actions?.edit?.allowed && !r.deleted);
  const canEdit = items.some((r) => r.actions?.edit?.allowed);
  const isMember = me.user.role === "member";

  async function bulk(note: string) {
    const action = bulkNote!;
    try {
      const result = await unwrap(
        api.POST("/tenders/{tenderId}/requirements/bulk", {
          params: { path: { tenderId: tenderId! } },
          body: {
            ids: [...selected],
            action,
            note,
            ...(action === "change_category" ? { category_id: bulkCategory } : {}),
            ...(action === "change_importance" ? { importance: bulkImportance } : {}),
          },
        }),
      );
      announce(
        `${result.updated} updated${result.skipped.length ? `, ${result.skipped.length} skipped` : ""}`,
      );
      setSelected(new Set());
      setBulkNote(null);
      refresh();
    } catch (e) {
      announce(e instanceof Error ? e.message : "The change failed");
    }
  }

  return (
    <>
      <TenderHeader tender={tender.state.data} active="requirements" />
      <h2>Requirements</h2>
      {locked && (
        <p className="banner" role="note">
          <span aria-hidden="true">🔒 </span>The baseline is confirmed, so the list is read only.{" "}
          <Link to={`/tenders/${tenderId}/baseline`}>Open baseline and scoring</Link>
        </p>
      )}
      {sum && (
        <div className="summary-strip" role="group" aria-label="Summary">
          <button
            type="button"
            aria-pressed={!state && !flagged && !importance}
            onClick={() => {
              setState("");
              setFlagged(false);
              setImportance("");
            }}
          >
            {sum.total} requirements
          </button>
          <button
            type="button"
            aria-pressed={importance === "mandatory"}
            onClick={() => setImportance(importance === "mandatory" ? "" : "mandatory")}
          >
            {sum.mandatory} mandatory
          </button>
          <button
            type="button"
            aria-pressed={importance === "optional"}
            onClick={() => setImportance(importance === "optional" ? "" : "optional")}
          >
            {sum.optional} optional
          </button>
          <span role="status" aria-live="polite" className="chip">
            {sum.reviewed} of {sum.total} reviewed
          </span>
          <button type="button" aria-pressed={flagged} onClick={() => setFlagged(!flagged)}>
            {sum.flagged} flagged
          </button>
        </div>
      )}
      <div className="filter-bar">
        <Field label="Search requirements">
          {({ id }) => <input id={id} value={q} onChange={(e) => setQ(e.target.value)} />}
        </Field>
        <Field label="Category">
          {({ id }) => (
            <select id={id} value={category} onChange={(e) => setCategory(e.target.value)}>
              <option value="">All categories</option>
              {cats.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="State">
          {({ id }) => (
            <select id={id} value={state} onChange={(e) => setState(e.target.value)}>
              <option value="">Any state</option>
              <option value="unreviewed">Not reviewed</option>
              <option value="reviewed">Reviewed</option>
            </select>
          )}
        </Field>
        <label>
          <input type="checkbox" checked={deleted} onChange={(e) => setDeleted(e.target.checked)} />{" "}
          Show deleted
        </label>
        {isMember && (
          <Button
            variant="primary"
            disabledReason={locked ? "The baseline is confirmed" : undefined}
            onClick={() => setAdding(true)}
          >
            Add requirement
          </Button>
        )}
        <Link to={`/tenders/${tenderId}/baseline`} className="btn">
          Baseline and scoring
        </Link>
      </div>
      {selected.size > 0 && canEdit && (
        <div className="bulk-bar" role="region" aria-label="Bulk actions">
          <span>{selected.size} selected</span>
          <Button onClick={() => setBulkNote("mark_reviewed")}>Mark reviewed</Button>
          <select
            aria-label="Category for the selection"
            value={bulkCategory}
            onChange={(e) => setBulkCategory(e.target.value)}
          >
            <option value="">Change category to…</option>
            {cats.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
          <Button disabled={!bulkCategory} onClick={() => setBulkNote("change_category")}>
            Apply category
          </Button>
          <select
            aria-label="Importance for the selection"
            value={bulkImportance}
            onChange={(e) => setBulkImportance(e.target.value as "mandatory" | "optional")}
          >
            <option value="mandatory">Mandatory</option>
            <option value="optional">Optional</option>
          </select>
          <Button onClick={() => setBulkNote("change_importance")}>Apply importance</Button>
          <Button onClick={() => setSelected(new Set())}>Clear</Button>
        </div>
      )}
      <div className="req-layout">
        <div>
          {items.length === 0 ? (
            <EmptyState title="No requirements yet">
              Select sections in the RFP to extract them.{" "}
              <Link to={`/tenders/${tenderId}/documents`}>Open the documents</Link>
            </EmptyState>
          ) : (
            <div className="table-wrap">
              <table>
                <caption>Requirements of this tender</caption>
                <thead>
                  <tr>
                    <th scope="col">
                      <span className="visually-hidden">Select</span>
                    </th>
                    <th scope="col">ID</th>
                    <th scope="col">Requirement</th>
                    <th scope="col">Category</th>
                    <th scope="col">Importance</th>
                    <th scope="col">State</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((r) => (
                    <tr key={r.id} aria-selected={r.id === openId}>
                      <td>
                        <input
                          type="checkbox"
                          aria-label={`Select ${r.display_no}`}
                          checked={selected.has(r.id)}
                          onChange={(e) => {
                            const next = new Set(selected);
                            if (e.target.checked) next.add(r.id);
                            else next.delete(r.id);
                            setSelected(next);
                          }}
                        />
                      </td>
                      <th scope="row">
                        <button type="button" className="linklike" onClick={() => setOpenId(r.id)}>
                          {r.display_no}
                        </button>{" "}
                        <StatusChip
                          label={originLabel[r.origin]}
                          tone={r.origin === "ai" ? "ai" : "neutral"}
                        />
                      </th>
                      <td>
                        {r.text}
                        {r.flags
                          .filter((f) => !f.resolved_at)
                          .map((f) => (
                            <span key={f.id} title={f.detail ?? undefined}>
                              {" "}
                              <StatusChip
                                label={flagLabel[f.flag_type] ?? f.flag_type}
                                tone="warning"
                              />
                            </span>
                          ))}
                      </td>
                      <td>{r.category?.name ?? "-"}</td>
                      <td>{importanceLabel[r.importance]}</td>
                      <td>
                        {r.deleted ? (
                          <StatusChip label="Deleted" tone="error" />
                        ) : (
                          <StatusChip
                            label={reviewStateLabel[r.review_state]}
                            tone={(reviewStateTone[r.review_state] ?? "neutral") as Tone}
                          />
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
        {open && (
          <RequirementPanel
            key={open.id + ":" + open.row_version}
            req={open}
            categories={cats}
            others={items.filter((r) => r.id !== open.id && !r.deleted)}
            minNote={minNote}
            onChanged={refresh}
            onClose={() => setOpenId(null)}
          />
        )}
      </div>

      <AddDialog
        open={adding}
        onOpenChange={setAdding}
        tenderId={tenderId!}
        categories={cats}
        onAdded={() => {
          setAdding(false);
          refresh();
        }}
      />
      <NoteDialog
        open={bulkNote !== null}
        onOpenChange={(o) => !o && setBulkNote(null)}
        title="Change selected requirements"
        description={`${selected.size} requirements will change. The note is kept in each history.`}
        confirmLabel="Apply"
        minLength={minNote}
        onConfirm={(note) => void bulk(note)}
      />
    </>
  );
}

function RequirementPanel({
  req,
  categories,
  others,
  minNote,
  onChanged,
  onClose,
}: {
  req: Req;
  categories: Category[];
  others: Req[];
  minNote: number;
  onChanged: () => void;
  onClose: () => void;
}) {
  const api = useApi();
  const announce = useAnnounce();
  const [draft, setDraft] = useState<Draft>(toDraft(req));
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [reason, setReason] = useState<
    "duplicate" | "out_of_scope" | "not_a_requirement" | "other"
  >("out_of_scope");
  const [duplicateOf, setDuplicateOf] = useState("");
  const [restoring, setRestoring] = useState(false);
  const [conflict, setConflict] = useState<null | {
    current: Req;
    by: string;
    at: string;
    fields: string[];
    mine: Draft;
    note: string;
  }>(null);
  const [showHistory, setShowHistory] = useState(false);
  const canEdit = req.actions?.edit?.allowed === true;
  const dirty =
    draft.text !== req.text ||
    draft.category_id !== (req.category?.id ?? "") ||
    draft.importance !== req.importance ||
    draft.acceptance_criteria !== (req.acceptance_criteria ?? "");

  const history = useAsync(
    () =>
      showHistory
        ? unwrap(
            api.GET("/requirements/{requirementId}/history", {
              params: { path: { requirementId: req.id } },
            }),
          )
        : Promise.resolve(null),
    [api, req.id, req.row_version, showHistory],
  );

  async function save(note: string, over?: { draft: Draft; version: number }) {
    const d = over?.draft ?? draft;
    try {
      await unwrap(
        api.PATCH("/requirements/{requirementId}", {
          params: {
            path: { requirementId: req.id },
            header: { "If-Match": `"${over?.version ?? req.row_version}"` },
          },
          body: {
            note,
            text: d.text,
            category_id: d.category_id || undefined,
            importance: d.importance,
            acceptance_criteria: d.acceptance_criteria,
          },
        }),
      );
      announce(`${req.display_no} saved`);
      setSaving(false);
      setConflict(null);
      onChanged();
    } catch (e) {
      setSaving(false);
      if (e instanceof ApiError && e.status === 412) {
        const p = e.problem as unknown as {
          current: Req;
          changed_by?: { display_name: string };
          changed_at?: string;
          changed_fields?: string[];
        };
        setConflict({
          current: p.current,
          by: p.changed_by?.display_name ?? "Another member",
          at: p.changed_at ?? "",
          fields: p.changed_fields ?? [],
          mine: d,
          note,
        });
      } else {
        announce(e instanceof Error ? e.message : "The change could not be saved");
      }
    }
  }

  async function act(path: "review", note?: string) {
    try {
      await unwrap(
        api.POST("/requirements/{requirementId}/review", {
          params: { path: { requirementId: req.id } },
        }),
      );
      announce(`${req.display_no} marked reviewed`);
      onChanged();
    } catch (e) {
      announce(e instanceof Error ? e.message : "The change failed");
    }
    void path;
    void note;
  }

  async function remove(note: string) {
    try {
      await unwrap(
        api.POST("/requirements/{requirementId}/delete", {
          params: { path: { requirementId: req.id } },
          body: {
            reason,
            note,
            duplicate_of_id: reason === "duplicate" ? duplicateOf || null : null,
          },
        }),
      );
      announce(`${req.display_no} deleted`);
      setDeleting(false);
      onChanged();
    } catch (e) {
      announce(e instanceof Error ? e.message : "The requirement could not be deleted");
    }
  }

  async function restore(note: string) {
    try {
      await unwrap(
        api.POST("/requirements/{requirementId}/restore", {
          params: { path: { requirementId: req.id } },
          body: { note },
        }),
      );
      announce(`${req.display_no} restored`);
      setRestoring(false);
      onChanged();
    } catch (e) {
      announce(e instanceof Error ? e.message : "The requirement could not be restored");
    }
  }

  async function resolve(flagId: string) {
    try {
      await unwrap(
        api.POST("/requirements/{requirementId}/flags/{flagId}/resolve", {
          params: { path: { requirementId: req.id, flagId } },
          body: { note: "Checked" },
        }),
      );
      announce("Flag resolved");
      onChanged();
    } catch (e) {
      announce(e instanceof Error ? e.message : "The flag could not be resolved");
    }
  }

  return (
    <aside className="req-panel" aria-label={`Details of ${req.display_no}`}>
      <h3>
        {req.display_no}{" "}
        <StatusChip label={originLabel[req.origin]} tone={req.origin === "ai" ? "ai" : "neutral"} />
      </h3>
      <Button onClick={onClose}>Close panel</Button>
      <Field label="Requirement text">
        {({ id }) => (
          <textarea
            id={id}
            rows={5}
            value={draft.text}
            disabled={!canEdit}
            onChange={(e) => setDraft({ ...draft, text: e.target.value })}
          />
        )}
      </Field>
      <Field label="Category">
        {({ id }) => (
          <select
            id={id}
            value={draft.category_id}
            disabled={!canEdit}
            onChange={(e) => setDraft({ ...draft, category_id: e.target.value })}
          >
            <option value="">No category</option>
            {categories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        )}
      </Field>
      <Field label="Importance">
        {({ id }) => (
          <select
            id={id}
            value={draft.importance}
            disabled={!canEdit}
            onChange={(e) =>
              setDraft({ ...draft, importance: e.target.value as "mandatory" | "optional" })
            }
          >
            <option value="mandatory">Mandatory</option>
            <option value="optional">Optional</option>
          </select>
        )}
      </Field>
      <Field label="Acceptance criteria">
        {({ id }) => (
          <textarea
            id={id}
            rows={3}
            value={draft.acceptance_criteria}
            disabled={!canEdit}
            onChange={(e) => setDraft({ ...draft, acceptance_criteria: e.target.value })}
          />
        )}
      </Field>
      {!canEdit && (
        <p className="help">{req.actions?.edit?.reason ?? "You cannot edit this requirement."}</p>
      )}
      {req.flags.length > 0 && (
        <section aria-label="Flags">
          <h4>Flags</h4>
          <ul>
            {req.flags.map((f) => (
              <li key={f.id}>
                <StatusChip
                  label={flagLabel[f.flag_type] ?? f.flag_type}
                  tone={f.resolved_at ? "neutral" : "warning"}
                />{" "}
                {f.detail}
                {f.resolved_at
                  ? " (resolved)"
                  : canEdit && <Button onClick={() => void resolve(f.id)}>Resolve</Button>}
              </li>
            ))}
          </ul>
        </section>
      )}
      {req.sources.length > 0 && (
        <section aria-label="Sources">
          <h4>Sources</h4>
          <ul>
            {req.sources.map((s, i) => (
              <li key={i}>
                <Link
                  to={`/documents/${s.document_version_id}/view${s.passage_id ? `?passage=${s.passage_id}` : ""}`}
                >
                  Page {s.page_no ?? "?"}
                </Link>
                {s.quote ? <blockquote>{s.quote}</blockquote> : null}
              </li>
            ))}
          </ul>
        </section>
      )}
      <div className="dialog-actions">
        {canEdit && (
          <Button
            variant="primary"
            disabledReason={dirty ? undefined : "Nothing has changed"}
            onClick={() => setSaving(true)}
          >
            Save changes
          </Button>
        )}
        {canEdit && req.review_state === "unreviewed" && (
          <Button onClick={() => void act("review")}>Mark reviewed</Button>
        )}
        {canEdit && <Button onClick={() => setDeleting(true)}>Delete</Button>}
        {req.deleted && req.actions?.restore?.allowed && (
          <Button onClick={() => setRestoring(true)}>Restore</Button>
        )}
        <Button onClick={() => setShowHistory(!showHistory)}>
          {showHistory ? "Hide history" : "History"}
        </Button>
      </div>
      {showHistory && history.state.status === "ready" && history.state.data && (
        <section aria-label="History">
          <h4>History</h4>
          {history.state.data.ai_original && (
            <p className="help">
              The AI suggested: {String(history.state.data.ai_original.text ?? "")}
            </p>
          )}
          <ol>
            {history.state.data.events.map((e) => (
              <li key={e.id}>
                {new Date(e.occurred_at).toLocaleString()} ·{" "}
                {e.actor?.display_name ?? "The platform"} · {e.action.replace(/_/g, " ")}
                {e.note ? `: ${e.note}` : ""}
              </li>
            ))}
          </ol>
        </section>
      )}

      <NoteDialog
        open={saving}
        onOpenChange={setSaving}
        title={`Save changes to ${req.display_no}`}
        description="Say why, so the history explains the change."
        confirmLabel="Save"
        minLength={minNote}
        onConfirm={(note) => void save(note)}
      />
      <NoteDialog
        open={deleting}
        onOpenChange={setDeleting}
        title={`Delete ${req.display_no}`}
        description="The requirement stays in the history and can be restored."
        confirmLabel="Delete"
        danger
        minLength={minNote}
        onConfirm={(note) => void remove(note)}
      >
        <Field label="Reason">
          {({ id }) => (
            <select
              id={id}
              value={reason}
              onChange={(e) => setReason(e.target.value as typeof reason)}
            >
              {Object.entries(deleteReasonLabel).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          )}
        </Field>
        {reason === "duplicate" && (
          <Field label="Requirement that is kept">
            {({ id }) => (
              <select id={id} value={duplicateOf} onChange={(e) => setDuplicateOf(e.target.value)}>
                <option value="">Choose the requirement that is kept</option>
                {others.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.display_no}: {o.text.slice(0, 70)}
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
      </NoteDialog>
      <NoteDialog
        open={restoring}
        onOpenChange={setRestoring}
        title={`Restore ${req.display_no}`}
        description="The requirement returns to the list."
        confirmLabel="Restore"
        minLength={minNote}
        onConfirm={(note) => void restore(note)}
      />
      <ConfirmDialog
        open={conflict !== null}
        onOpenChange={(o) => !o && setConflict(null)}
        title="This requirement was changed"
        description={
          conflict
            ? `${conflict.by} changed this requirement while you were editing it${
                conflict.fields.length ? ` (${conflict.fields.join(", ")})` : ""
              }. Choose which version to keep.`
            : ""
        }
        confirmLabel="Keep mine"
        onConfirm={() =>
          conflict &&
          void save(conflict.note, { draft: conflict.mine, version: conflict.current.row_version })
        }
      >
        {conflict && (
          <div className="conflict" role="group" aria-label="The two versions">
            <p>
              <strong>Theirs:</strong> {conflict.current.text}
            </p>
            <p>
              <strong>Yours:</strong> {conflict.mine.text}
            </p>
            <Button
              onClick={() => {
                setDraft(toDraft(conflict.current));
                setConflict(null);
                onChanged();
              }}
            >
              Keep theirs
            </Button>
          </div>
        )}
      </ConfirmDialog>
    </aside>
  );
}

function AddDialog({
  open,
  onOpenChange,
  tenderId,
  categories,
  onAdded,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  tenderId: string;
  categories: Category[];
  onAdded: () => void;
}) {
  const api = useApi();
  const announce = useAnnounce();
  const [text, setText] = useState("");
  const [category, setCategory] = useState("");
  const [importance, setImportance] = useState<"mandatory" | "optional">("mandatory");
  const [criteria, setCriteria] = useState("");
  const [error, setError] = useState<string | undefined>();
  async function add() {
    try {
      await unwrap(
        api.POST("/tenders/{tenderId}/requirements", {
          params: { path: { tenderId } },
          body: {
            text,
            category_id: category,
            importance,
            acceptance_criteria: criteria,
          },
        }),
      );
      announce("Requirement added");
      setText("");
      setCriteria("");
      onAdded();
    } catch (e) {
      setError(e instanceof Error ? e.message : "The requirement could not be added");
    }
  }
  return (
    <ConfirmDialog
      open={open}
      onOpenChange={onOpenChange}
      title="Add requirement"
      description="A requirement you add is marked Manual."
      confirmLabel="Add requirement"
      busy={!text.trim() || !category}
      onConfirm={() => void add()}
    >
      <Field label="Requirement text" error={error}>
        {({ id }) => (
          <textarea id={id} rows={4} value={text} onChange={(e) => setText(e.target.value)} />
        )}
      </Field>
      <Field label="Category">
        {({ id }) => (
          <select id={id} value={category} onChange={(e) => setCategory(e.target.value)}>
            <option value="">Choose a category</option>
            {categories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        )}
      </Field>
      <Field label="Importance">
        {({ id }) => (
          <select
            id={id}
            value={importance}
            onChange={(e) => setImportance(e.target.value as "mandatory" | "optional")}
          >
            <option value="mandatory">Mandatory</option>
            <option value="optional">Optional</option>
          </select>
        )}
      </Field>
      <Field label="Acceptance criteria">
        {({ id }) => (
          <textarea
            id={id}
            rows={2}
            value={criteria}
            onChange={(e) => setCriteria(e.target.value)}
          />
        )}
      </Field>
    </ConfirmDialog>
  );
}
