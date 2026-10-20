import { Checkbox, Descriptions, Modal, Progress, Space, Table, Tooltip, Typography } from "antd";
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
import type { components } from "../api/generated/schema";
import { jobStatusLabel, jobStatusTone, jobTypeLabel, type Tone } from "../lib/labels";
import { usePush } from "../lib/usePush";
import { useAsync } from "../lib/useAsync";

type JobT = components["schemas"]["Job"];

const levelTone: Record<string, Tone> = { info: "neutral", warning: "warning", error: "error" };
const stepTone: Record<string, Tone> = {
  pending: "neutral",
  running: "info",
  completed: "success",
  failed: "error",
};

function when(at?: string | null) {
  return at ? (
    <Tooltip title={new Date(at).toISOString()}>
      <span>{new Date(at).toLocaleString()}</span>
    </Tooltip>
  ) : (
    "-"
  );
}

function stepName(name: string) {
  const text = name.replace(/_/g, " ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}

function duration(seconds?: number | null) {
  if (seconds == null) return "-";
  if (seconds < 60) return `${seconds} s`;
  return `${Math.floor(seconds / 60)} min ${seconds % 60} s`;
}

/** The full record of one job: summary, cause of a failure, steps and the activity log. */
function JobDetails({ id, onClose }: { id: string | null; onClose: () => void }) {
  const api = useApi();
  const detail = useAsync(
    () =>
      id
        ? unwrap(api.GET("/jobs/{jobId}", { params: { path: { jobId: id } } }))
        : Promise.resolve(null),
    [api, id],
  );
  usePush(undefined, detail.reload, ["job.updated"]);
  const job: JobT | null =
    detail.state.status === "ready" ? (detail.state.data as JobT | null) : null;
  return (
    <Modal
      open={id !== null}
      title="Job details"
      width={760}
      destroyOnHidden
      onCancel={onClose}
      footer={<Button onClick={onClose}>Close</Button>}
    >
      {detail.state.status === "loading" && <Loading label="Loading job details" />}
      {detail.state.status === "error" && (
        <ErrorNotice error={detail.state.error} onRetry={detail.reload} />
      )}
      {job && (
        <Space direction="vertical" size={16} style={{ width: "100%" }}>
          <Descriptions size="small" column={2} bordered>
            <Descriptions.Item label="Work">
              {jobTypeLabel[job.job_type] ?? job.job_type}
            </Descriptions.Item>
            <Descriptions.Item label="Status">
              <StatusChip
                label={jobStatusLabel[job.status] ?? job.status}
                tone={(jobStatusTone[job.status] ?? "neutral") as Tone}
              />
            </Descriptions.Item>
            <Descriptions.Item label="On">{job.target_label ?? "-"}</Descriptions.Item>
            <Descriptions.Item label="Started by">
              {job.started_by?.display_name ?? "-"}
            </Descriptions.Item>
            <Descriptions.Item label="Queued">{when(job.queued_at)}</Descriptions.Item>
            <Descriptions.Item label="Started">{when(job.started_at)}</Descriptions.Item>
            <Descriptions.Item label="Last updated">{when(job.updated_at)}</Descriptions.Item>
            <Descriptions.Item label="Finished">{when(job.finished_at)}</Descriptions.Item>
            <Descriptions.Item label="Duration">{duration(job.duration_seconds)}</Descriptions.Item>
            <Descriptions.Item label="Progress">
              <Progress
                percent={job.progress_percent ?? 0}
                size="small"
                aria-label="Job progress"
              />
            </Descriptions.Item>
            {job.estimated_remaining_seconds != null && (
              <Descriptions.Item label="Time left" span={2}>
                About {duration(job.estimated_remaining_seconds)}
              </Descriptions.Item>
            )}
          </Descriptions>
          {job.failure && (
            <div role="alert">
              <Typography.Text strong>Why it failed</Typography.Text>
              <div>{job.failure.message}</div>
              {job.failure.detail && <div>{job.failure.detail}</div>}
            </div>
          )}
          <div>
            <Typography.Title level={5}>Steps</Typography.Title>
            {(job.steps ?? []).length === 0 ? (
              <Typography.Text type="secondary">No steps reported yet.</Typography.Text>
            ) : (
              <Table
                size="small"
                rowKey="step_no"
                pagination={false}
                dataSource={job.steps}
                columns={[
                  { title: "Step", render: (_, st) => stepName(st.name) },
                  {
                    title: "Status",
                    render: (_, st) => (
                      <StatusChip
                        label={stepName(st.status)}
                        tone={stepTone[st.status] ?? "neutral"}
                      />
                    ),
                  },
                  { title: "Started", width: "13%", render: (_, st) => when(st.started_at) },
                  { title: "Finished", render: (_, st) => when(st.finished_at) },
                ]}
              />
            )}
          </div>
          <div>
            <Typography.Title level={5}>Activity</Typography.Title>
            {(job.events ?? []).length === 0 ? (
              <Typography.Text type="secondary">No updates recorded yet.</Typography.Text>
            ) : (
              <Table
                size="small"
                rowKey={(e) => `${e.at}-${e.message}`}
                pagination={false}
                scroll={{ y: 220 }}
                dataSource={job.events}
                columns={[
                  { title: "Time", width: 190, render: (_, e) => when(e.at) },
                  {
                    title: "Level",
                    width: 100,
                    render: (_, e) => (
                      <StatusChip
                        label={stepName(e.level)}
                        tone={levelTone[e.level] ?? "neutral"}
                      />
                    ),
                  },
                  { title: "Update", dataIndex: "message" },
                ]}
              />
            )}
          </div>
        </Space>
      )}
    </Modal>
  );
}

/** PG-17 Job center (T1 list): every committee member sees the same jobs (UX-JOB-01). */
export function Jobs() {
  const api = useApi();
  const announce = useAnnounce();
  const [activeOnly, setActiveOnly] = useState(false);
  const [detailId, setDetailId] = useState<string | null>(null);
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

  async function retry(id: string) {
    try {
      await unwrap(
        api.POST("/jobs/{jobId}/retry", {
          params: { path: { jobId: id }, header: { "Idempotency-Key": crypto.randomUUID() } },
        }),
      );
      announce("The job was started again");
      list.reload();
    } catch (e) {
      announce(e instanceof Error ? e.message : "The job could not be retried");
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
          tableLayout="fixed"
          title={() => <Typography.Text strong>Background jobs</Typography.Text>}
          columns={[
            {
              title: "Work",
              width: "14%",
              onCell: rowHeader,
              render: (_, j) => jobTypeLabel[j.job_type] ?? j.job_type,
            },
            {
              title: "On",
              width: "22%",
              onCell: () => ({ style: { whiteSpace: "normal", wordBreak: "break-word" } }),
              render: (_, j) => (
                <>
                  {j.target_label ?? "-"}{" "}
                  <Link to={`/tenders/${j.tender_id}/documents`}>Tender</Link>
                </>
              ),
            },
            {
              title: "Status",
              width: "13%",
              render: (_, j) => (
                <>
                  <StatusChip
                    label={jobStatusLabel[j.status] ?? j.status}
                    tone={(jobStatusTone[j.status] ?? "neutral") as Tone}
                  />
                  {(j.status === "running" || j.status === "queued") && (
                    <Progress
                      percent={j.progress_percent ?? 0}
                      size="small"
                      aria-label="Job progress"
                    />
                  )}
                </>
              ),
            },
            { title: "Started", render: (_, j) => when(j.started_at ?? j.queued_at) },
            { title: "Last updated", width: "13%", render: (_, j) => when(j.updated_at) },
            {
              title: "Actions",
              render: (_, j) => (
                <Space wrap>
                  <Button onClick={() => setDetailId(j.id)}>View job details</Button>
                  {j.can_cancel && <Button onClick={() => void cancel(j.id)}>Cancel</Button>}
                  {j.can_retry &&
                    (j.job_type === "document_processing" ? (
                      <Link to={`/tenders/${j.tender_id}/documents`}>Retry from the document</Link>
                    ) : (
                      <Button onClick={() => void retry(j.id)}>Retry</Button>
                    ))}
                </Space>
              ),
            },
          ]}
        />
      )}
      <JobDetails id={detailId} onClose={() => setDetailId(null)} />
    </>
  );
}
