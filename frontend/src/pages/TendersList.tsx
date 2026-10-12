import { useState } from "react";
import { Link } from "react-router";
import { Button, EmptyState, ErrorNotice, LinkButton, Loading, StatusChip } from "../components/ui";
import { useApi, unwrap } from "../lib/api";
import { TENDER_STAGES, tenderStatusLabel, tenderStatusTone } from "../lib/labels";
import type { TenderStatus, TenderSummary } from "../lib/types";
import { useAsync } from "../lib/useAsync";
import { useSession } from "../app/Session";

/**
 * PG-02 All tenders (administrator, observer) and PG-08 My tenders (committee member) share one
 * list page (T1): search, a status filter, a table, one primary action (UX-TND-01).
 */
export function TendersList({ mine = false }: { mine?: boolean }) {
  const api = useApi();
  const { me } = useSession();
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<TenderStatus | "">("");
  const [extra, setExtra] = useState<TenderSummary[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);

  const { state, reload } = useAsync(async () => {
    const page = await unwrap(
      api.GET("/tenders", {
        params: {
          query: {
            q: q || undefined,
            status: status || undefined,
            mine: mine || undefined,
            limit: 50,
          },
        },
      }),
    );
    setExtra([]);
    setCursor(page.next_cursor ?? null);
    return page;
  }, [api, q, status, mine]);

  const loadMore = async () => {
    if (!cursor) return;
    const page = await unwrap(
      api.GET("/tenders", {
        params: {
          query: {
            q: q || undefined,
            status: status || undefined,
            mine: mine || undefined,
            limit: 50,
            cursor,
          },
        },
      }),
    );
    setExtra((prev) => [...prev, ...page.items]);
    setCursor(page.next_cursor ?? null);
  };

  const isAdmin = me.user.role === "admin";
  const title = mine ? "My tenders" : "All tenders";

  return (
    <>
      <h1>{title}</h1>
      <div className="toolbar" role="search">
        <div className="field grow" style={{ margin: 0 }}>
          <label htmlFor="tender-search">Search tenders</label>
          <input
            id="tender-search"
            type="search"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Name or reference"
          />
        </div>
        <div className="field" style={{ margin: 0 }}>
          <label htmlFor="tender-status">Status</label>
          <select
            id="tender-status"
            value={status}
            onChange={(e) => setStatus(e.target.value as TenderStatus | "")}
          >
            <option value="">All statuses</option>
            {TENDER_STAGES.map((s) => (
              <option key={s} value={s}>
                {tenderStatusLabel[s]}
              </option>
            ))}
          </select>
        </div>
        {isAdmin && (
          <LinkButton to="/tenders/new" variant="primary">
            Create tender
          </LinkButton>
        )}
      </div>

      {state.status === "loading" && <Loading label="Loading tenders" />}
      {state.status === "error" && <ErrorNotice error={state.error} onRetry={reload} />}
      {state.status === "ready" && (
        <TenderTable
          title={title}
          items={[...state.data.items, ...extra]}
          total={state.data.total ?? null}
          emptyFiltered={Boolean(q || status)}
          mine={mine}
          isAdmin={isAdmin}
          hasMore={Boolean(cursor)}
          onMore={loadMore}
        />
      )}
    </>
  );
}

function TenderTable({
  title,
  items,
  total,
  emptyFiltered,
  mine,
  isAdmin,
  hasMore,
  onMore,
}: {
  title: string;
  items: TenderSummary[];
  total: number | null;
  emptyFiltered: boolean;
  mine: boolean;
  isAdmin: boolean;
  hasMore: boolean;
  onMore: () => void;
}) {
  if (items.length === 0) {
    if (emptyFiltered) {
      return (
        <EmptyState title="No tenders match">Change the search or the status filter.</EmptyState>
      );
    }
    return mine ? (
      <EmptyState title="No tenders yet">
        You are not assigned to any tender yet. The administrator adds you when a tender opens.
      </EmptyState>
    ) : (
      <EmptyState
        title="No tenders yet"
        action={
          isAdmin ? (
            <LinkButton to="/tenders/new" variant="primary">
              Create tender
            </LinkButton>
          ) : undefined
        }
      >
        {isAdmin ? "Create a tender to start." : "There are no tenders to show."}
      </EmptyState>
    );
  }
  return (
    <>
      <div className="table-wrap">
        <table>
          <caption>
            {title}
            {total !== null ? ` (${total})` : ""}
          </caption>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Status</th>
              <th scope="col">Committee</th>
              <th scope="col">Vendors</th>
              <th scope="col">Chair</th>
              <th scope="col">Due date</th>
              <th scope="col">Last activity</th>
            </tr>
          </thead>
          <tbody>
            {items.map((t) => (
              <tr key={t.id}>
                <td>
                  <Link to={`/tenders/${t.id}`}>{t.name}</Link>
                  {t.reference_no && <div className="help mono">{t.reference_no}</div>}
                </td>
                <td>
                  <StatusChip
                    label={tenderStatusLabel[t.status]}
                    tone={tenderStatusTone[t.status]}
                  />
                  {t.archived && <span className="help"> Archived</span>}
                </td>
                <td>{t.committee_size}</td>
                <td>{t.vendor_count}</td>
                <td>{t.chair?.display_name ?? "—"}</td>
                <td>{t.due_date ?? "—"}</td>
                <td>{t.last_activity_at ? new Date(t.last_activity_at).toLocaleString() : "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {hasMore && (
        <p>
          <Button onClick={onMore}>Show more tenders</Button>
        </p>
      )}
    </>
  );
}
