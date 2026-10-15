import { useCallback, useEffect, useState } from "react";
import { ExportButton } from "../components/ExportButton";
import { Button, EmptyState, ErrorNotice, Field, Loading } from "../components/ui";
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
      <h1>Audit trail</h1>
      <div role="tablist" aria-label="Log">
        <Button
          type="button"
          role="tab"
          aria-selected={tab === "trail"}
          onClick={() => setTab("trail")}
        >
          Changes
        </Button>{" "}
        <Button
          type="button"
          role="tab"
          aria-selected={tab === "access"}
          onClick={() => setTab("access")}
        >
          Proposal access
        </Button>
      </div>
      <form
        className="filters"
        onSubmit={(e) => {
          e.preventDefault();
          setApplied(draft);
        }}
      >
        <Field label="Tender">
          {(p) => (
            <select
              id={p.id}
              value={draft.tenderId}
              onChange={(e) => setDraft({ ...draft, tenderId: e.target.value })}
            >
              <option value="">All tenders</option>
              {tenders.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </select>
          )}
        </Field>
        {tab === "trail" && (
          <Field label="Action">
            {(p) => (
              <select
                id={p.id}
                value={draft.action}
                onChange={(e) => setDraft({ ...draft, action: e.target.value })}
              >
                <option value="">All actions</option>
                {ACTIONS.map((a) => (
                  <option key={a} value={a}>
                    {actionText(a)}
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
        <Field label="From" help="Without a start date the last 30 days are shown.">
          {(p) => (
            <input
              id={p.id}
              type="date"
              value={draft.from}
              aria-describedby={p.describedBy}
              onChange={(e) => setDraft({ ...draft, from: e.target.value })}
            />
          )}
        </Field>
        <Field label="To">
          {(p) => (
            <input
              id={p.id}
              type="date"
              value={draft.to}
              onChange={(e) => setDraft({ ...draft, to: e.target.value })}
            />
          )}
        </Field>
        <Button type="submit" variant="primary">
          Search
        </Button>{" "}
        <Button
          type="button"
          onClick={() => {
            setDraft(NONE);
            setApplied(NONE);
          }}
        >
          Clear filters
        </Button>
        {tab === "trail" && (
          <>
            {" "}
            <ExportButton label="Export CSV" start={startExport} />
          </>
        )}
      </form>
      {state === "error" && error ? (
        <ErrorNotice error={error} onRetry={() => setApplied({ ...applied })} />
      ) : state === "loading" && rows === 0 ? (
        <Loading label="Loading the log" />
      ) : rows === 0 ? (
        <EmptyState title="Nothing found">No entries match these filters.</EmptyState>
      ) : tab === "trail" ? (
        <div className="table-wrap">
          <table>
            <caption>Changes, newest first</caption>
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">Who</th>
                <th scope="col">Action</th>
                <th scope="col">Object</th>
                <th scope="col">Source</th>
                <th scope="col">Note</th>
              </tr>
            </thead>
            <tbody>
              {events.map((e) => (
                <tr key={e.id}>
                  <td>{new Date(e.occurred_at).toLocaleString()}</td>
                  <td>
                    {e.actor?.display_name ?? "-"}
                    {e.actor_role ? <span className="help"> ({e.actor_role})</span> : null}
                  </td>
                  <th scope="row">{actionText(e.action)}</th>
                  <td>{e.object_type}</td>
                  <td>{sourceText[e.source] ?? e.source}</td>
                  <td>{e.note ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="table-wrap">
          <table>
            <caption>Proposal files opened, newest first</caption>
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">Who</th>
                <th scope="col">Document version</th>
                <th scope="col">Watermark</th>
              </tr>
            </thead>
            <tbody>
              {access.map((e) => (
                <tr key={e.id}>
                  <td>{new Date(e.opened_at).toLocaleString()}</td>
                  <th scope="row">{e.user.display_name}</th>
                  <td>{e.document_version_id}</td>
                  <td>{e.watermark_shown ? "Shown" : "Not shown"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
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
