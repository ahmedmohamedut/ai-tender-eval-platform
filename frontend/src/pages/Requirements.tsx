import {
  Alert,
  Button as AntButton,
  Card,
  Checkbox,
  Col,
  Flex,
  Input,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
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
  LinkButton,
  StatusChip,
  rowHeader,
  rowHeaderComponents,
  useAnnounce,
} from "../components/ui";
import { useSession } from "../app/Session";
import { useViewer } from "../app/ViewerHost";
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
      <TenderHeader tender={tender.state.data} />
      <Typography.Title level={2} style={{ fontSize: 20 }}>
        Requirements
      </Typography.Title>
      {locked && (
        <Alert
          type="info"
          showIcon
          role="note"
          style={{ marginBottom: 16 }}
          title={
            <>
              <span aria-hidden="true">🔒 </span>The baseline is confirmed, so the list is read
              only. <Link to={`/tenders/${tenderId}/baseline`}>Open baseline and scoring</Link>
            </>
          }
        />
      )}
      {sum && (
        <Space role="group" aria-label="Summary" wrap style={{ marginBottom: 16 }}>
          <AntButton
            aria-pressed={!state && !flagged && !importance}
            type={!state && !flagged && !importance ? "primary" : "default"}
            onClick={() => {
              setState("");
              setFlagged(false);
              setImportance("");
            }}
          >
            {sum.total} requirements
          </AntButton>
          <AntButton
            aria-pressed={importance === "mandatory"}
            type={importance === "mandatory" ? "primary" : "default"}
            onClick={() => setImportance(importance === "mandatory" ? "" : "mandatory")}
          >
            {sum.mandatory} mandatory
          </AntButton>
          <AntButton
            aria-pressed={importance === "optional"}
            type={importance === "optional" ? "primary" : "default"}
            onClick={() => setImportance(importance === "optional" ? "" : "optional")}
          >
            {sum.optional} optional
          </AntButton>
          <span role="status" aria-live="polite">
            <Tag style={{ margin: 0 }}>
              {sum.reviewed} of {sum.total} reviewed
            </Tag>
          </span>
          <AntButton
            aria-pressed={flagged}
            type={flagged ? "primary" : "default"}
            onClick={() => setFlagged(!flagged)}
          >
            {sum.flagged} flagged
          </AntButton>
        </Space>
      )}
      <Card style={{ marginBottom: 16 }}>
        <Flex gap={16} wrap align="flex-end">
          <Field label="Search requirements">
            {({ id }) => (
              <Input.Search
                id={id}
                allowClear
                value={q}
                onChange={(e) => setQ(e.target.value)}
                style={{ minWidth: 240 }}
              />
            )}
          </Field>
          <Field label="Category">
            {({ id }) => (
              <Select
                id={id}
                value={category}
                onChange={(v) => setCategory(v)}
                style={{ minWidth: 200 }}
                options={[
                  { value: "", label: "All categories" },
                  ...cats.map((c) => ({ value: c.id, label: c.name })),
                ]}
              />
            )}
          </Field>
          <Field label="State">
            {({ id }) => (
              <Select
                id={id}
                value={state}
                onChange={(v) => setState(v)}
                style={{ minWidth: 160 }}
                options={[
                  { value: "", label: "Any state" },
                  { value: "unreviewed", label: "Not reviewed" },
                  { value: "reviewed", label: "Reviewed" },
                ]}
              />
            )}
          </Field>
          <div style={{ marginBottom: 16 }}>
            <Checkbox checked={deleted} onChange={(e) => setDeleted(e.target.checked)}>
              Show deleted
            </Checkbox>
          </div>
          <Space style={{ marginBottom: 16 }}>
            {isMember && (
              <Button
                variant="primary"
                disabledReason={locked ? "The baseline is confirmed" : undefined}
                onClick={() => setAdding(true)}
              >
                Add requirement
              </Button>
            )}
            <LinkButton to={`/tenders/${tenderId}/baseline`}>Baseline and scoring</LinkButton>
          </Space>
        </Flex>
      </Card>
      {selected.size > 0 && canEdit && (
        <Card size="small" role="region" aria-label="Bulk actions" style={{ marginBottom: 16 }}>
          <Flex gap={8} wrap align="center">
            <span>{selected.size} selected</span>
            <Button onClick={() => setBulkNote("mark_reviewed")}>Mark reviewed</Button>
            <Select
              aria-label="Category for the selection"
              value={bulkCategory}
              onChange={(v) => setBulkCategory(v)}
              style={{ minWidth: 200 }}
              options={[
                { value: "", label: "Change category to…" },
                ...cats.map((c) => ({ value: c.id, label: c.name })),
              ]}
            />
            <Button disabled={!bulkCategory} onClick={() => setBulkNote("change_category")}>
              Apply category
            </Button>
            <Select
              aria-label="Importance for the selection"
              value={bulkImportance}
              onChange={(v) => setBulkImportance(v)}
              style={{ minWidth: 140 }}
              options={[
                { value: "mandatory", label: "Mandatory" },
                { value: "optional", label: "Optional" },
              ]}
            />
            <Button onClick={() => setBulkNote("change_importance")}>Apply importance</Button>
            <Button onClick={() => setSelected(new Set())}>Clear</Button>
          </Flex>
        </Card>
      )}
      <Row gutter={24}>
        <Col xs={24} xl={open ? 14 : 24}>
          {items.length === 0 ? (
            <EmptyState title="No requirements yet">
              Select sections in the RFP to extract them.{" "}
              <Link to={`/tenders/${tenderId}/documents`}>Open the documents</Link>
            </EmptyState>
          ) : (
            <Table<Req>
              rowKey="id"
              components={rowHeaderComponents}
              dataSource={items}
              pagination={false}
              scroll={{ x: "max-content" }}
              title={() => <Typography.Text strong>Requirements of this tender</Typography.Text>}
              onRow={(r) => ({ "aria-selected": r.id === openId })}
              columns={[
                {
                  title: <span className="visually-hidden">Select</span>,
                  render: (_, r) => (
                    <Checkbox
                      aria-label={`Select ${r.display_no}`}
                      checked={selected.has(r.id)}
                      onChange={(e) => {
                        const next = new Set(selected);
                        if (e.target.checked) next.add(r.id);
                        else next.delete(r.id);
                        setSelected(next);
                      }}
                    />
                  ),
                },
                {
                  title: "ID",
                  onCell: rowHeader,
                  render: (_, r) => (
                    <Space size={4} wrap>
                      <AntButton type="link" style={{ padding: 0 }} onClick={() => setOpenId(r.id)}>
                        {r.display_no}
                      </AntButton>
                      <StatusChip
                        label={originLabel[r.origin]}
                        tone={r.origin === "ai" ? "ai" : "neutral"}
                      />
                    </Space>
                  ),
                },
                {
                  title: "Requirement",
                  render: (_, r) => (
                    <>
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
                    </>
                  ),
                },
                { title: "Category", render: (_, r) => r.category?.name ?? "-" },
                { title: "Importance", render: (_, r) => importanceLabel[r.importance] },
                {
                  title: "State",
                  render: (_, r) =>
                    r.deleted ? (
                      <StatusChip label="Deleted" tone="error" />
                    ) : (
                      <StatusChip
                        label={reviewStateLabel[r.review_state]}
                        tone={(reviewStateTone[r.review_state] ?? "neutral") as Tone}
                      />
                    ),
                },
              ]}
            />
          )}
        </Col>
        {open && (
          <Col xs={24} xl={10}>
            <RequirementPanel
              key={open.id + ":" + open.row_version}
              req={open}
              categories={cats}
              others={items.filter((r) => r.id !== open.id && !r.deleted)}
              minNote={minNote}
              onChanged={refresh}
              onClose={() => setOpenId(null)}
            />
          </Col>
        )}
      </Row>

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
  const viewer = useViewer();
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
    <aside aria-label={`Details of ${req.display_no}`}>
      <Card
        title={
          <Typography.Title level={3} style={{ fontSize: 16, margin: 0 }}>
            {req.display_no}{" "}
            <StatusChip
              label={originLabel[req.origin]}
              tone={req.origin === "ai" ? "ai" : "neutral"}
            />
          </Typography.Title>
        }
        extra={<Button onClick={onClose}>Close panel</Button>}
      >
        <Field label="Requirement text">
          {({ id }) => (
            <Input.TextArea
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
            <Select
              id={id}
              value={draft.category_id}
              disabled={!canEdit}
              onChange={(v) => setDraft({ ...draft, category_id: v })}
              style={{ width: "100%" }}
              options={[
                { value: "", label: "No category" },
                ...categories.map((c) => ({ value: c.id, label: c.name })),
              ]}
            />
          )}
        </Field>
        <Field label="Importance">
          {({ id }) => (
            <Select
              id={id}
              value={draft.importance}
              disabled={!canEdit}
              onChange={(v) => setDraft({ ...draft, importance: v })}
              style={{ width: "100%" }}
              options={[
                { value: "mandatory", label: "Mandatory" },
                { value: "optional", label: "Optional" },
              ]}
            />
          )}
        </Field>
        <Field label="Acceptance criteria">
          {({ id }) => (
            <Input.TextArea
              id={id}
              rows={3}
              value={draft.acceptance_criteria}
              disabled={!canEdit}
              onChange={(e) => setDraft({ ...draft, acceptance_criteria: e.target.value })}
            />
          )}
        </Field>
        {!canEdit && (
          <Typography.Paragraph type="secondary">
            {req.actions?.edit?.reason ?? "You cannot edit this requirement."}
          </Typography.Paragraph>
        )}
        {req.flags.length > 0 && (
          <section aria-label="Flags">
            <Typography.Title level={4} style={{ fontSize: 14 }}>
              Flags
            </Typography.Title>
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
            <Typography.Title level={4} style={{ fontSize: 14 }}>
              Sources
            </Typography.Title>
            <ul>
              {req.sources.map((s, i) => (
                <li key={i}>
                  <AntButton
                    type="link"
                    style={{ padding: 0 }}
                    onClick={() => viewer.open(s.document_version_id, s.passage_id ?? null)}
                  >
                    Page {s.page_no ?? "?"}
                  </AntButton>
                  {s.quote ? (
                    <Typography.Paragraph type="secondary" italic>
                      “{s.quote}”
                    </Typography.Paragraph>
                  ) : null}
                </li>
              ))}
            </ul>
          </section>
        )}
        <Space wrap style={{ marginTop: 16 }}>
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
        </Space>
        {showHistory && history.state.status === "ready" && history.state.data && (
          <section aria-label="History">
            <Typography.Title level={4} style={{ fontSize: 14 }}>
              History
            </Typography.Title>
            {history.state.data.ai_original && (
              <Typography.Paragraph type="secondary">
                The AI suggested: {String(history.state.data.ai_original.text ?? "")}
              </Typography.Paragraph>
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
              <Select
                id={id}
                value={reason}
                onChange={(v) => setReason(v)}
                style={{ width: "100%" }}
                options={Object.entries(deleteReasonLabel).map(([k, v]) => ({
                  value: k,
                  label: v,
                }))}
              />
            )}
          </Field>
          {reason === "duplicate" && (
            <Field label="Requirement that is kept">
              {({ id }) => (
                <Select
                  id={id}
                  value={duplicateOf}
                  onChange={(v) => setDuplicateOf(v)}
                  style={{ width: "100%" }}
                  options={[
                    { value: "", label: "Choose the requirement that is kept" },
                    ...others.map((o) => ({
                      value: o.id,
                      label: `${o.display_no}: ${o.text.slice(0, 70)}`,
                    })),
                  ]}
                />
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
            void save(conflict.note, {
              draft: conflict.mine,
              version: conflict.current.row_version,
            })
          }
        >
          {conflict && (
            <div role="group" aria-label="The two versions">
              <Typography.Paragraph>
                <strong>Theirs:</strong> {conflict.current.text}
              </Typography.Paragraph>
              <Typography.Paragraph>
                <strong>Yours:</strong> {conflict.mine.text}
              </Typography.Paragraph>
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
      </Card>
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
          <Input.TextArea id={id} rows={4} value={text} onChange={(e) => setText(e.target.value)} />
        )}
      </Field>
      <Field label="Category">
        {({ id }) => (
          <Select
            id={id}
            value={category}
            onChange={(v) => setCategory(v)}
            style={{ width: "100%" }}
            options={[
              { value: "", label: "Choose a category" },
              ...categories.map((c) => ({ value: c.id, label: c.name })),
            ]}
          />
        )}
      </Field>
      <Field label="Importance">
        {({ id }) => (
          <Select
            id={id}
            value={importance}
            onChange={(v) => setImportance(v)}
            style={{ width: "100%" }}
            options={[
              { value: "mandatory", label: "Mandatory" },
              { value: "optional", label: "Optional" },
            ]}
          />
        )}
      </Field>
      <Field label="Acceptance criteria">
        {({ id }) => (
          <Input.TextArea
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
