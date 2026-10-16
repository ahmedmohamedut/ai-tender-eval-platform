import { Card, Flex, Input, Select, Table, Typography } from "antd";
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
      <Typography.Title level={1} style={{ fontSize: 24, marginTop: 0 }}>
        {title}
      </Typography.Title>
      <Card style={{ marginBottom: 16 }}>
        <Flex gap={16} wrap align="flex-end" role="search">
          <div style={{ flex: 1, minWidth: 240 }}>
            <label htmlFor="tender-search" style={{ display: "block", marginBottom: 4 }}>
              Search tenders
            </label>
            <Input.Search
              id="tender-search"
              allowClear
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="Name or reference"
            />
          </div>
          <div>
            <label htmlFor="tender-status" style={{ display: "block", marginBottom: 4 }}>
              Status
            </label>
            <Select
              id="tender-status"
              value={status}
              onChange={(v) => setStatus(v as TenderStatus | "")}
              style={{ minWidth: 180 }}
              options={[
                { value: "", label: "All statuses" },
                ...TENDER_STAGES.map((s) => ({ value: s, label: tenderStatusLabel[s] })),
              ]}
            />
          </div>
          {isAdmin && (
            <LinkButton to="/tenders/new" variant="primary">
              Create tender
            </LinkButton>
          )}
        </Flex>
      </Card>

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
      <Table<TenderSummary>
        rowKey="id"
        dataSource={items}
        pagination={false}
        scroll={{ x: "max-content" }}
        title={() => (
          <Typography.Text strong>
            {title}
            {total !== null ? ` (${total})` : ""}
          </Typography.Text>
        )}
        columns={[
          {
            title: "Name",
            render: (_, t) => (
              <>
                <Link to={`/tenders/${t.id}`}>{t.name}</Link>
                {t.reference_no && (
                  <div>
                    <Typography.Text type="secondary" className="mono">
                      {t.reference_no}
                    </Typography.Text>
                  </div>
                )}
              </>
            ),
          },
          {
            title: "Status",
            render: (_, t) => (
              <>
                <StatusChip label={tenderStatusLabel[t.status]} tone={tenderStatusTone[t.status]} />
                {t.archived && <Typography.Text type="secondary"> Archived</Typography.Text>}
              </>
            ),
          },
          { title: "Committee", dataIndex: "committee_size" },
          { title: "Vendors", dataIndex: "vendor_count" },
          { title: "Chair", render: (_, t) => t.chair?.display_name ?? "—" },
          { title: "Due date", render: (_, t) => t.due_date ?? "—" },
          {
            title: "Last activity",
            render: (_, t) =>
              t.last_activity_at ? new Date(t.last_activity_at).toLocaleString() : "—",
          },
        ]}
      />
      {hasMore && (
        <p>
          <Button onClick={onMore}>Show more tenders</Button>
        </p>
      )}
    </>
  );
}
