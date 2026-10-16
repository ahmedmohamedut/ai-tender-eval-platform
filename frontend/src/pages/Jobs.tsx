import { Checkbox, Progress, Table, Typography } from "antd";
import { useState } from "react";
import { Link } from "react-router";
import {
  Button,
  EmptyState,
  ErrorNotice,
  Loading,
  StatusChip,
  rowHeader,
  rowHeaderComponents,
  useAnnounce,
} from "../components/ui";
import { unwrap, useApi } from "../lib/api";
import { failureText, jobStatusLabel, jobStatusTone, jobTypeLabel, type Tone } from "../lib/labels";
import { usePush } from "../lib/usePush";
import { useAsync } from "../lib/useAsync";

/** PG-17 Job center (T1 list): every committee member sees the same jobs (UX-JOB-01). */
export function Jobs() {
  const api = useApi();
  const announce = useAnnounce();
  const [activeOnly, setActiveOnly] = useState(false);
  const list = useAsync(
    () => unwrap(api.GET("/jobs", { params: { query: { active: activeOnly || undefined } } })),
    [api, activeOnly],
  );
  usePush(undefined, list.reload, ["job.updated"]);

  async function cancel(id: string) {
    try {
      await unwrap(api.POST("/jobs/{jobId}/cancel", { params: { path: { jobId: id } } }));
      announce("The job was cancelled");
      list.reload();
    } catch (e) {
      announce(e instanceof Error ? e.message : "The job could not be cancelled");
    }
  }

  if (list.state.status === "loading") return <Loading label="Loading jobs" />;
  if (list.state.status === "error")
    return <ErrorNotice error={list.state.error} onRetry={list.reload} />;
  const jobs = list.state.data.items;
  return (
    <>
      <Typography.Title level={1} style={{ fontSize: 24, marginTop: 0 }}>
        Jobs
      </Typography.Title>
      <Checkbox checked={activeOnly} onChange={(e) => setActiveOnly(e.target.checked)}>
        Only jobs that are waiting or running
      </Checkbox>
      {jobs.length === 0 ? (
        <EmptyState title="No jobs">Work that runs in the background appears here.</EmptyState>
      ) : (
        <Table
          style={{ marginTop: 16 }}
          rowKey="id"
          components={rowHeaderComponents}
          dataSource={jobs}
          pagination={false}
          scroll={{ x: "max-content" }}
          title={() => <Typography.Text strong>Background jobs</Typography.Text>}
          columns={[
            {
              title: "Work",
              onCell: rowHeader,
              render: (_, j) => jobTypeLabel[j.job_type] ?? j.job_type,
            },
            {
              title: "On",
              render: (_, j) => (
                <>
                  {j.target_label ?? "-"}{" "}
                  <Link to={`/tenders/${j.tender_id}/documents`}>Tender</Link>
                </>
              ),
            },
            {
              title: "Status",
              render: (_, j) => (
                <>
                  <StatusChip
                    label={jobStatusLabel[j.status] ?? j.status}
                    tone={(jobStatusTone[j.status] ?? "neutral") as Tone}
                  />
                  {j.failure && (
                    <div>
                      <Typography.Text type="secondary">
                        {failureText[j.failure.code] ?? j.failure.message}
                      </Typography.Text>
                    </div>
                  )}
                </>
              ),
            },
            {
              title: "Progress",
              render: (_, j) =>
                j.status === "running" || j.status === "queued" ? (
                  <>
                    <Progress
                      percent={j.progress_percent ?? 0}
                      size="small"
                      aria-label="Job progress"
                    />
                    {j.current_step ?? ""}
                  </>
                ) : (
                  "-"
                ),
            },
            { title: "Started by", render: (_, j) => j.started_by?.display_name ?? "-" },
            {
              title: "Actions",
              render: (_, j) =>
                j.can_cancel && <Button onClick={() => void cancel(j.id)}>Cancel</Button>,
            },
          ]}
        />
      )}
    </>
  );
}
