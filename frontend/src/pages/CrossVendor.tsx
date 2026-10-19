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
import { gradeIcon, gradeLabel, gradeTone, memberStateLabel } from "../lib/labels";
import { useAsync } from "../lib/useAsync";
import { TenderHeader, useTender } from "./TenderHome";

/** PG-16 Cross-vendor view (T5): one requirement, every vendor side by side (UX-EVA-11). */
export function CrossVendor() {
  const { tenderId, requirementId } = useParams();
  const api = useApi();
  const tender = useTender(tenderId);
  const view = useAsync(
    () =>
      unwrap(
        api.GET("/requirements/{requirementId}/cross-vendor", {
          params: { path: { requirementId: requirementId! } },
        }),
      ),
    [api, requirementId],
  );
  return (
    <>
      {tender.state.status === "ready" && (
        <TenderHeader tender={tender.state.data} nextStep={false} />
      )}
      <Typography.Paragraph>
        <Link to={`/tenders/${tenderId}/evaluation`}>Back to your evaluations</Link>
      </Typography.Paragraph>
      {view.state.status === "loading" && <Loading label="Loading the comparison" />}
      {view.state.status === "error" && (
        <ErrorNotice error={view.state.error} onRetry={view.reload} />
      )}
      {view.state.status === "ready" && (
        <>
          <Typography.Title level={2} style={{ fontSize: 20 }}>
            {view.state.data.requirement.display_no}: {view.state.data.requirement.text}
          </Typography.Title>
          <nav aria-label="Requirements" style={{ marginBottom: 16, display: "flex", gap: 16 }}>
            {view.state.data.previous_requirement_id ? (
              <Link
                to={`/tenders/${tenderId}/requirements/${view.state.data.previous_requirement_id}/compare`}
              >
                Previous requirement
              </Link>
            ) : null}{" "}
            {view.state.data.next_requirement_id ? (
              <Link
                to={`/tenders/${tenderId}/requirements/${view.state.data.next_requirement_id}/compare`}
              >
                Next requirement
              </Link>
            ) : null}
          </nav>
          {view.state.data.vendors.length === 0 ? (
            <EmptyState title="No vendors to compare">
              Vendors appear once their proposals are accepted.
            </EmptyState>
          ) : (
            <Table
              rowKey="tender_vendor_id"
              components={rowHeaderComponents}
              dataSource={view.state.data.vendors}
              pagination={false}
              scroll={{ x: "max-content" }}
              columns={[
                { title: "Vendor", onCell: rowHeader, render: (_, v) => v.vendor_name },
                {
                  title: "AI grade",
                  render: (_, v) =>
                    v.ai?.grade ? (
                      <StatusChip
                        label={`${gradeIcon[v.ai.grade] ?? ""} ${gradeLabel[v.ai.grade] ?? v.ai.grade}`.trim()}
                        tone={gradeTone[v.ai.grade] ?? "neutral"}
                      />
                    ) : (
                      <Typography.Text type="secondary">
                        {v.ai?.outcome === "failed" ? "Not evaluated" : "Not graded yet"}
                      </Typography.Text>
                    ),
                },
                { title: "Why", render: (_, v) => v.ai?.rationale_summary ?? "" },
                {
                  title: "Your decision",
                  render: (_, v) =>
                    v.my_decision?.grade
                      ? (gradeLabel[v.my_decision.grade] ?? v.my_decision.grade)
                      : memberStateLabel[v.my_decision?.review_state ?? "not_reviewed"],
                },
              ]}
            />
          )}
        </>
      )}
    </>
  );
}
