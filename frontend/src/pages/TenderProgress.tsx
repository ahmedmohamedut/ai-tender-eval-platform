import { useParams } from "react-router";
import { EmptyState, ErrorNotice, Loading } from "../components/ui";
import { unwrap, useApi } from "../lib/api";
import { useAsync } from "../lib/useAsync";
import { TenderHeader, useTender } from "./TenderHome";

/** PG-06 Tender progress board, first version: stage and members. It shows progress, never grades. */
export function TenderProgress() {
  const { tenderId } = useParams();
  const api = useApi();
  const tender = useTender(tenderId);
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
      <TenderHeader tender={tender.state.data} active="progress" />
      <h2>Members</h2>
      {b.members.length === 0 ? (
        <EmptyState title="No activity yet">
          Progress appears here when the committee starts work.
        </EmptyState>
      ) : (
        <div className="table-wrap">
          <table>
            <caption>Committee progress</caption>
            <thead>
              <tr>
                <th scope="col">Member</th>
                <th scope="col">Requirements reviewed</th>
                <th scope="col">Evaluations submitted</th>
              </tr>
            </thead>
            <tbody>
              {b.members.map((m) => (
                <tr key={m.user.id}>
                  <th scope="row">{m.user.display_name}</th>
                  <td>
                    {m.requirements_reviewed} of {m.requirements_total}
                  </td>
                  <td>
                    {m.evaluations_submitted} of {m.evaluations_total}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <h2>Running jobs</h2>
      <p>
        {b.running_jobs.length === 0
          ? "No jobs are running."
          : `${b.running_jobs.length} jobs running.`}
      </p>
    </>
  );
}
