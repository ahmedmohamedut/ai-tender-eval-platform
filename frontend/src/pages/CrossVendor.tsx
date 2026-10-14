import { Link, useParams } from "react-router";
import { EmptyState, ErrorNotice, Loading, StatusChip } from "../components/ui";
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
        <TenderHeader tender={tender.state.data} active="evaluation" />
      )}
      <p>
        <Link to={`/tenders/${tenderId}/evaluation`}>Back to your evaluations</Link>
      </p>
      {view.state.status === "loading" && <Loading label="Loading the comparison" />}
      {view.state.status === "error" && (
        <ErrorNotice error={view.state.error} onRetry={view.reload} />
      )}
      {view.state.status === "ready" && (
        <>
          <h2>
            {view.state.data.requirement.display_no}: {view.state.data.requirement.text}
          </h2>
          <nav aria-label="Requirements" className="actions">
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
            <table>
              <thead>
                <tr>
                  <th scope="col">Vendor</th>
                  <th scope="col">AI grade</th>
                  <th scope="col">Why</th>
                  <th scope="col">Your decision</th>
                </tr>
              </thead>
              <tbody>
                {view.state.data.vendors.map((v) => (
                  <tr key={v.tender_vendor_id}>
                    <th scope="row">{v.vendor_name}</th>
                    <td>
                      {v.ai?.grade ? (
                        <StatusChip
                          label={`${gradeIcon[v.ai.grade] ?? ""} ${gradeLabel[v.ai.grade] ?? v.ai.grade}`.trim()}
                          tone={gradeTone[v.ai.grade] ?? "neutral"}
                        />
                      ) : (
                        <span className="help">
                          {v.ai?.outcome === "failed" ? "Not evaluated" : "Not graded yet"}
                        </span>
                      )}
                    </td>
                    <td>{v.ai?.rationale_summary ?? ""}</td>
                    <td>
                      {v.my_decision?.grade
                        ? (gradeLabel[v.my_decision.grade] ?? v.my_decision.grade)
                        : memberStateLabel[v.my_decision?.review_state ?? "not_reviewed"]}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </>
  );
}
