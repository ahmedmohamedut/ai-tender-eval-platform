import { Card, Table, Typography } from "antd";
import { useParams } from "react-router";
import { EmptyState, ErrorNotice, Loading, rowHeader, rowHeaderComponents } from "../components/ui";
import { unwrap, useApi } from "../lib/api";
import { useAsync } from "../lib/useAsync";
import { useSession } from "../app/Session";
import { ChairPanel } from "./ChairPanel";
import { TenderHeader, useTender } from "./TenderHome";

/** PG-06 Tender progress board, first version: stage and members. It shows progress, never grades. */
export function TenderProgress() {
  const { tenderId } = useParams();
  const api = useApi();
  const tender = useTender(tenderId);
  const { me } = useSession();
  const board = useAsync(
    () =>
      unwrap(
        api.GET("/tenders/{tenderId}/progress", { params: { path: { tenderId: tenderId! } } }),
      ),
    [api, tenderId],
  );

  if (tender.state.status === "loading" || board.state.status === "loading") {
    return <Loading label="Loading progress" />;
  }
  if (tender.state.status === "error")
    return <ErrorNotice error={tender.state.error} onRetry={tender.reload} />;
  if (board.state.status === "error")
    return <ErrorNotice error={board.state.error} onRetry={board.reload} />;
  const b = board.state.data;
  return (
    <>
      <TenderHeader tender={tender.state.data} />
      <Typography.Title level={2} style={{ fontSize: 18 }}>
        Members
      </Typography.Title>
      {b.members.length === 0 ? (
        <EmptyState title="No activity yet">
          Progress appears here when the committee starts work.
        </EmptyState>
      ) : (
        <Table
          rowKey={(m) => m.user.id}
          components={rowHeaderComponents}
          dataSource={b.members}
          pagination={false}
          title={() => <Typography.Text strong>Committee progress</Typography.Text>}
          columns={[
            { title: "Member", onCell: rowHeader, render: (_, m) => m.user.display_name },
            {
              title: "Requirements reviewed",
              render: (_, m) => `${m.requirements_reviewed} of ${m.requirements_total}`,
            },
            {
              title: "Evaluations submitted",
              render: (_, m) => `${m.evaluations_submitted} of ${m.evaluations_total}`,
            },
          ]}
        />
      )}
      <Card title="Running jobs" style={{ margin: "16px 0" }}>
        {b.running_jobs.length === 0
          ? "No jobs are running."
          : `${b.running_jobs.length} jobs running.`}
      </Card>
      {(me.user.role === "admin" || tender.state.data.chair?.id === me.user.id) && (
        <ChairPanel tenderId={tenderId!} isChair={tender.state.data.chair?.id === me.user.id} />
      )}
    </>
  );
}
