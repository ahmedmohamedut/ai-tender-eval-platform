import { Link, useParams } from "react-router";
import { EmptyState, ErrorNotice, Loading, StatusChip } from "../components/ui";
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
        <TenderHeader tender={tender.state.data} active="evaluation" />
      )}
      <h2>Your evaluations</h2>
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
          <table>
            <thead>
              <tr>
                <th scope="col">Vendor</th>
                <th scope="col">State</th>
                <th scope="col">Reviewed</th>
                <th scope="col">Outdated</th>
                <th scope="col">Flagged</th>
                <th scope="col">AI</th>
                <th scope="col">
                  <span className="visually-hidden">Open</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {progress.state.data.items.map((i) => (
                <tr key={i.tender_vendor_id}>
                  <th scope="row">{i.vendor_name}</th>
                  <td>
                    <StatusChip
                      label={evaluationStatusLabel[i.status] ?? i.status}
                      tone={evaluationStatusTone[i.status] ?? "neutral"}
                    />{" "}
                    {i.is_next && <StatusChip label="Next" tone="info" />}
                  </td>
                  <td>
                    {i.reviewed} of {i.total}
                  </td>
                  <td>{i.outdated}</td>
                  <td>{i.flagged}</td>
                  <td>
                    {i.job_status ? (jobStatusLabel[i.job_status] ?? i.job_status) : "Not started"}
                  </td>
                  <td>
                    <Link to={`/tenders/${tenderId}/vendors/${i.tender_vendor_id}/evaluation`}>
                      Open {i.vendor_name}
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ))}
    </>
  );
}
