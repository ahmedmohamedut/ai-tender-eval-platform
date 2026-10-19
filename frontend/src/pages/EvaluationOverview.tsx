import { Table, Typography } from "antd";
import { Link, useParams } from "react-router";
import {
  EmptyState,
  ErrorNotice,
  Loading,
  StatusChip,
  rowHeader,
  rowHeaderComponents,
} from "../components/ui";
import { unwrap, useApi } from "../lib/api";
import { evaluationStatusLabel, evaluationStatusTone, jobStatusLabel } from "../lib/labels";
import { useAsync } from "../lib/useAsync";
import { usePush } from "../lib/usePush";
import { TenderHeader, useTender } from "./TenderHome";

/** UX-EVA-17: where the member stands with each vendor, and which one is next. */
export function EvaluationOverview() {
  const { tenderId } = useParams();
  const api = useApi();
  const tender = useTender(tenderId);
  const progress = useAsync(
    () =>
      unwrap(
        api.GET("/tenders/{tenderId}/evaluations", { params: { path: { tenderId: tenderId! } } }),
      ),
    [api, tenderId],
  );
  usePush(tenderId, progress.reload, ["evaluation.progress", "job.updated", "tender.changed"]);

  return (
    <>
      {tender.state.status === "ready" && (
        <TenderHeader tender={tender.state.data} nextStep={false} />
      )}
      <Typography.Title level={2} style={{ fontSize: 20 }}>
        Your evaluations
      </Typography.Title>
      {progress.state.status === "loading" && <Loading label="Loading your evaluations" />}
      {progress.state.status === "error" && (
        <ErrorNotice error={progress.state.error} onRetry={progress.reload} />
      )}
      {progress.state.status === "ready" &&
        (progress.state.data.items.length === 0 ? (
          <EmptyState title="Nothing to evaluate yet">
            Vendors appear here once the Chair has accepted their proposal.
          </EmptyState>
        ) : (
          <Table
            rowKey="tender_vendor_id"
            components={rowHeaderComponents}
            dataSource={progress.state.data.items}
            pagination={false}
            scroll={{ x: "max-content" }}
            columns={[
              { title: "Vendor", onCell: rowHeader, render: (_, i) => i.vendor_name },
              {
                title: "State",
                render: (_, i) => (
                  <>
                    <StatusChip
                      label={evaluationStatusLabel[i.status] ?? i.status}
                      tone={evaluationStatusTone[i.status] ?? "neutral"}
                    />{" "}
                    {i.is_next && <StatusChip label="Next" tone="info" />}
                  </>
                ),
              },
              { title: "Reviewed", render: (_, i) => `${i.reviewed} of ${i.total}` },
              { title: "Outdated", dataIndex: "outdated" },
              { title: "Flagged", dataIndex: "flagged" },
              {
                title: "AI",
                render: (_, i) =>
                  i.job_status ? (jobStatusLabel[i.job_status] ?? i.job_status) : "Not started",
              },
              {
                title: <span className="visually-hidden">Open</span>,
                render: (_, i) => (
                  <Link to={`/tenders/${tenderId}/vendors/${i.tender_vendor_id}/evaluation`}>
                    Open {i.vendor_name}
                  </Link>
                ),
              },
            ]}
          />
        ))}
    </>
  );
}
