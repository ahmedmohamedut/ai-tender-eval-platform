import { Card, DatePicker, Flex, Select, Space, Table, Tabs, Typography } from "antd";
import dayjs from "dayjs";
import { useCallback, useEffect, useState } from "react";
import { ExportButton } from "../components/ExportButton";
import {
  Button,
  EmptyState,
  ErrorNotice,
  Field,
  Loading,
  rowHeader,
  rowHeaderComponents,
} from "../components/ui";
import { unwrap, useApi } from "../lib/api";
import type { components } from "../api/generated/schema";

type AuditEvent = components["schemas"]["AuditEvent"];
type AccessEvent = components["schemas"]["AccessEvent"];
type AuditAction = components["schemas"]["AuditAction"];

const ACTIONS: AuditAction[] = [
  "tender_created",
  "tender_updated",
  "tender_opened",
  "tender_archived",
  "committee_changed",
  "user_invited",
  "user_role_changed",
  "user_deactivated",
  "vendor_created",
  "vendor_updated",
  "document_uploaded",
  "document_version_uploaded",
  "proposal_version_uploaded",
  "conflict_declared",
  "requirement_created",
  "requirement_edited",
  "requirement_deleted",
  "requirement_restored",
  "requirement_merged",
  "requirement_split",
  "requirement_reviewed",
  "requirement_flag_resolved",
  "scoring_changed",
  "baseline_confirmed",
  "baseline_reopened",
  "screening_recommended",
  "screening_decided",
  "screening_reinstated",
  "grade_agreed",
  "grade_overridden",
  "row_flagged",
  "rows_marked_outdated",
  "evaluation_submitted",
  "evaluation_reopened",
  "member_excluded",
  "report_grade_recorded",
  "report_finalized",
  "report_reopened",
  "export_created",
  "reminders_sent",
  "ai_run_completed",
  "job_status_changed",
] as AuditAction[];

const actionText = (a: string) => {
  const t = a.replace(/_/g, " ");
  return t.charAt(0).toUpperCase() + t.slice(1);
};

const sourceText: Record<string, string> = { person: "Person", ai: "AI", system: "System" };

type Filters = {
  tenderId: string;
  action: string;
  from: string;
  to: string;
};
const NONE: Filters = { tenderId: "", action: "", from: "", to: "" };

/** Start of the chosen day and end of the chosen day, in the browser's time zone. */
const dayStart = (d: string) => (d ? new Date(`${d}T00:00:00`).toISOString() : undefined);
const dayEnd = (d: string) => (d ? new Date(`${d}T23:59:59.999`).toISOString() : undefined);

/** PG-20 Audit trail (UX-AUD-01, UX-AUD-03, UX-AUD-04) and the document access log (UX-NFR-07). */
export function Audit() {
  const api = useApi();
  const [tab, setTab] = useState<"trail" | "access">("trail");
  const [draft, setDraft] = useState<Filters>(NONE);
  const [applied, setApplied] = useState<Filters>(NONE);
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [access, setAccess] = useState<AccessEvent[]>([]);
  const [next, setNext] = useState<string | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState<Error | null>(null);
  const [tenders, setTenders] = useState<{ id: string; name: string }[]>([]);

  useEffect(() => {
    void unwrap(api.GET("/tenders", { params: { query: { limit: 200 } } }))
      .then((r) => setTenders(r.items.map((t) => ({ id: t.id, name: t.name }))))
      .catch(() => setTenders([]));
  }, [api]);

  const fetchPage = useCallback(
    async (cursor: string | null) => {
      const common = {
        limit: 50,
        cursor: cursor ?? undefined,
        tender_id: applied.tenderId || undefined,
        from: dayStart(applied.from),
        to: dayEnd(applied.to),
      };
      if (tab === "trail") {
        const page = await unwrap(
          api.GET("/audit-events", {
            params: { query: { ...common, action: (applied.action || undefined) as AuditAction } },
          }),
        );
        return { trail: page.items, access: null, next: page.next_cursor ?? null };
      }
      const page = await unwrap(api.GET("/document-access-events", { params: { query: common } }));
      return { trail: null, access: page.items, next: page.next_cursor ?? null };
    },
    [api, applied, tab],
  );

  const show = useCallback((r: Awaited<ReturnType<typeof fetchPage>>, append: boolean) => {
    if (r.trail) setEvents((prev) => (append ? [...prev, ...r.trail] : r.trail));
    if (r.access) setAccess((prev) => (append ? [...prev, ...r.access] : r.access));
    setNext(r.next);
    setState("ready");
  }, []);

  const fail = (e: unknown) => {
    setError(e instanceof Error ? e : new Error("The log could not be read."));
    setState("error");
  };

  useEffect(() => {
    let live = true;
    fetchPage(null)
      .then((r) => live && show(r, false))
      .catch((e) => live && fail(e));
    return () => {
      live = false;
    };
  }, [fetchPage, show]);

  const more = (cursor: string) => void fetchPage(cursor).then((r) => show(r, true), fail);

  const startExport = () =>
    unwrap(
      api.POST("/audit-events/export", {
        params: { header: { "Idempotency-Key": crypto.randomUUID() } },
        body: {
          tender_id: applied.tenderId || null,
          action: (applied.action || null) as AuditAction | null,
          from: dayStart(applied.from) ?? null,
          to: dayEnd(applied.to) ?? null,
        },
      }),
    );

  const rows = tab === "trail" ? events.length : access.length;
  return (
    <>
      <Typography.Title level={1} style={{ fontSize: 24, marginTop: 0 }}>
        Audit trail
      </Typography.Title>
      <Tabs
        aria-label="Log"
        activeKey={tab}
        onChange={(k) => setTab(k as "trail" | "access")}
        items={[
          { key: "trail", label: "Changes" },
          { key: "access", label: "Proposal access" },
        ]}
      />
      <Card style={{ marginBottom: 16 }}>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            setApplied(draft);
          }}
        >
          <Flex gap={16} wrap align="flex-end">
            <Field label="Tender">
              {(p) => (
                <Select
                  id={p.id}
                  value={draft.tenderId}
                  onChange={(v) => setDraft({ ...draft, tenderId: v })}
                  style={{ minWidth: 220 }}
                  showSearch={{ optionFilterProp: "label" }}
                  options={[
                    { value: "", label: "All tenders" },
                    ...tenders.map((t) => ({ value: t.id, label: t.name })),
                  ]}
                />
              )}
            </Field>
            {tab === "trail" && (
              <Field label="Action">
                {(p) => (
                  <Select
                    id={p.id}
                    value={draft.action}
                    onChange={(v) => setDraft({ ...draft, action: v })}
                    style={{ minWidth: 220 }}
                    showSearch={{ optionFilterProp: "label" }}
                    options={[
                      { value: "", label: "All actions" },
                      ...ACTIONS.map((a) => ({ value: a, label: actionText(a) })),
                    ]}
                  />
                )}
              </Field>
            )}
            <Field label="From" help="Without a start date the last 30 days are shown.">
              {(p) => (
                <DatePicker
                  id={p.id}
                  aria-describedby={p.describedBy}
                  value={draft.from ? dayjs(draft.from) : null}
                  onChange={(d) => setDraft({ ...draft, from: d ? d.format("YYYY-MM-DD") : "" })}
                />
              )}
            </Field>
            <Field label="To">
              {(p) => (
                <DatePicker
                  id={p.id}
                  value={draft.to ? dayjs(draft.to) : null}
                  onChange={(d) => setDraft({ ...draft, to: d ? d.format("YYYY-MM-DD") : "" })}
                />
              )}
            </Field>
          </Flex>
          <Space wrap>
            <Button type="submit" variant="primary">
              Search
            </Button>
            <Button
              type="button"
              onClick={() => {
                setDraft(NONE);
                setApplied(NONE);
              }}
            >
              Clear filters
            </Button>
            {tab === "trail" && <ExportButton label="Export CSV" start={startExport} />}
          </Space>
        </form>
      </Card>
      {state === "error" && error ? (
        <ErrorNotice error={error} onRetry={() => setApplied({ ...applied })} />
      ) : state === "loading" && rows === 0 ? (
        <Loading label="Loading the log" />
      ) : rows === 0 ? (
        <EmptyState title="Nothing found">No entries match these filters.</EmptyState>
      ) : tab === "trail" ? (
        <Table<AuditEvent>
          rowKey="id"
          components={rowHeaderComponents}
          dataSource={events}
          pagination={false}
          scroll={{ x: "max-content" }}
          title={() => <Typography.Text strong>Changes, newest first</Typography.Text>}
          columns={[
            { title: "When", render: (_, e) => new Date(e.occurred_at).toLocaleString() },
            {
              title: "Who",
              render: (_, e) => (
                <>
                  {e.actor?.display_name ?? "-"}
                  {e.actor_role ? (
                    <Typography.Text type="secondary"> ({e.actor_role})</Typography.Text>
                  ) : null}
                </>
              ),
            },
            { title: "Action", onCell: rowHeader, render: (_, e) => actionText(e.action) },
            { title: "Object", dataIndex: "object_type" },
            { title: "Source", render: (_, e) => sourceText[e.source] ?? e.source },
            { title: "Note", render: (_, e) => e.note ?? "" },
          ]}
        />
      ) : (
        <Table<AccessEvent>
          rowKey="id"
          components={rowHeaderComponents}
          dataSource={access}
          pagination={false}
          scroll={{ x: "max-content" }}
          title={() => (
            <Typography.Text strong>Proposal files opened, newest first</Typography.Text>
          )}
          columns={[
            { title: "When", render: (_, e) => new Date(e.opened_at).toLocaleString() },
            { title: "Who", onCell: rowHeader, render: (_, e) => e.user.display_name },
            { title: "Document version", dataIndex: "document_version_id" },
            {
              title: "Watermark",
              render: (_, e) => (e.watermark_shown ? "Shown" : "Not shown"),
            },
          ]}
        />
      )}
      {next && state === "ready" && (
        <p>
          <Button type="button" onClick={() => more(next)}>
            Show older entries
          </Button>
        </p>
      )}
    </>
  );
}
