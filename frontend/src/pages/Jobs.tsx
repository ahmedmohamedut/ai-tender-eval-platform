import { useState } from "react";
import { Link } from "react-router";
import {
  Button,
  EmptyState,
  ErrorNotice,
  Loading,
  StatusChip,
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
      <h1>Jobs</h1>
      <label>
        <input
          type="checkbox"
          checked={activeOnly}
          onChange={(e) => setActiveOnly(e.target.checked)}
        />{" "}
        Only jobs that are waiting or running
      </label>
      {jobs.length === 0 ? (
        <EmptyState title="No jobs">Work that runs in the background appears here.</EmptyState>
      ) : (
        <div className="table-wrap">
          <table>
            <caption>Background jobs</caption>
            <thead>
              <tr>
                <th scope="col">Work</th>
                <th scope="col">On</th>
                <th scope="col">Status</th>
                <th scope="col">Progress</th>
                <th scope="col">Started by</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((j) => (
                <tr key={j.id}>
                  <th scope="row">{jobTypeLabel[j.job_type] ?? j.job_type}</th>
                  <td>
                    {j.target_label ?? "-"}{" "}
                    <Link to={`/tenders/${j.tender_id}/documents`}>Tender</Link>
                  </td>
                  <td>
                    <StatusChip
                      label={jobStatusLabel[j.status] ?? j.status}
                      tone={(jobStatusTone[j.status] ?? "neutral") as Tone}
                    />
                    {j.failure && (
                      <p className="help">{failureText[j.failure.code] ?? j.failure.message}</p>
                    )}
                  </td>
                  <td>
                    {j.status === "running" || j.status === "queued" ? (
                      <>
                        <progress
                          max={100}
                          value={j.progress_percent ?? 0}
                          aria-label="Job progress"
                        />{" "}
                        {j.current_step ?? ""}
                      </>
                    ) : (
                      "-"
                    )}
                  </td>
                  <td>{j.started_by?.display_name ?? "-"}</td>
                  <td>
                    {j.can_cancel && <Button onClick={() => void cancel(j.id)}>Cancel</Button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
