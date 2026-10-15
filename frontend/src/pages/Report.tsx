import * as Dialog from "@radix-ui/react-dialog";
import { useState } from "react";
import { Link, useParams, useSearchParams } from "react-router";
import { ExportButton } from "../components/ExportButton";
import {
  Button,
  ConfirmDialog,
  EmptyState,
  ErrorNotice,
  Loading,
  NoteDialog,
  StatusChip,
  useAnnounce,
} from "../components/ui";
import { ApiError, unwrap, useApi } from "../lib/api";
import {
  gradeIcon,
  gradeLabel,
  gradeTone,
  importanceLabel,
  reviewStateLabel,
  type Tone,
} from "../lib/labels";
import type { components } from "../api/generated/schema";
import { useAsync } from "../lib/useAsync";
import { usePush } from "../lib/usePush";
import { useSession } from "../app/Session";
import { ChairPanel } from "./ChairPanel";
import { TenderHeader, useTender } from "./TenderHome";

type S = components["schemas"];

const statusLabel: Record<string, string> = {
  in_progress: "In progress",
  finalized: "Finalized",
  superseded: "Superseded",
};

function Grade({ grade }: { grade: string | null | undefined }) {
  if (!grade) return <span className="help">No final grade</span>;
  return (
    <StatusChip
      label={`${gradeIcon[grade] ?? ""} ${gradeLabel[grade] ?? grade}`.trim()}
      tone={(gradeTone[grade] ?? "neutral") as Tone}
    />
  );
}

/** PG-19 Consolidated evaluation report (T6): status, overview, matrix, disagreements. */
export function Report() {
  const { tenderId } = useParams();
  const [params, setParams] = useSearchParams();
  const version = params.get("version") ? Number(params.get("version")) : undefined;
  const api = useApi();
  const announce = useAnnounce();
  const { me } = useSession();
  const tender = useTender(tenderId);
  const [filters, setFilters] = useState<{
    tenderVendorId: string;
    importance: string;
    grade: string;
    disagreement: boolean;
  }>({ tenderVendorId: "", importance: "", grade: "", disagreement: false });
  const [cellOf, setCellOf] = useState<{ requirementId: string; tenderVendorId: string } | null>(
    null,
  );
  const [confirmFinalize, setConfirmFinalize] = useState(false);
  const [reopening, setReopening] = useState(false);
  const [missing, setMissing] = useState<{ code: string; message: string }[]>([]);
  const [busy, setBusy] = useState(false);

  const header = useAsync(
    () =>
      unwrap(
        api.GET("/tenders/{tenderId}/report", {
          params: { path: { tenderId: tenderId! }, query: { version } },
        }),
      ),
    [api, tenderId, version],
  );
  const overview = useAsync(
    () =>
      unwrap(
        api.GET("/tenders/{tenderId}/report/overview", {
          params: { path: { tenderId: tenderId! }, query: { version } },
        }),
      ).catch(() => null),
    [api, tenderId, version],
  );
  const matrix = useAsync(
    () =>
      unwrap(
        api.GET("/tenders/{tenderId}/report/matrix", {
          params: {
            path: { tenderId: tenderId! },
            query: {
              version,
              limit: 500,
              importance: (filters.importance || undefined) as S["Importance"] | undefined,
              grade: (filters.grade || undefined) as S["Grade"] | undefined,
              tender_vendor_id: filters.tenderVendorId || undefined,
              disagreement: filters.disagreement || undefined,
            },
          },
        }),
      ).catch(() => null),
    [
      api,
      tenderId,
      version,
      filters.importance,
      filters.grade,
      filters.tenderVendorId,
      filters.disagreement,
    ],
  );
  const versions = useAsync(
    () =>
      unwrap(
        api.GET("/tenders/{tenderId}/report/versions", {
          params: { path: { tenderId: tenderId! } },
        }),
      ).catch(() => []),
    [api, tenderId],
  );
  const outcomes = useAsync(
    () =>
      unwrap(
        api.GET("/tenders/{tenderId}/report/screening-outcomes", {
          params: { path: { tenderId: tenderId! }, query: { version } },
        }),
      ).catch(() => []),
    [api, tenderId, version],
  );
  const appendix = useAsync(
    () =>
      unwrap(
        api.GET("/tenders/{tenderId}/report/appendix", {
          params: { path: { tenderId: tenderId! }, query: { version } },
        }),
      ).catch(() => null),
    [api, tenderId, version],
  );
  const refreshAll = () => {
    header.reload();
    overview.reload();
    matrix.reload();
    versions.reload();
    appendix.reload();
  };
  usePush(tenderId, refreshAll, ["evaluation.updated", "tender.changed", "report.changed"]);

  if (tender.state.status === "loading" || header.state.status === "loading") {
    return <Loading label="Loading the report" />;
  }
  if (tender.state.status === "error")
    return <ErrorNotice error={tender.state.error} onRetry={tender.reload} />;
  if (header.state.status === "error") {
    const e = header.state.error;
    if (e instanceof ApiError && e.status === 404) {
      return (
        <>
          <TenderHeader tender={tender.state.data} active="report" />
          <h2>Report</h2>
          <EmptyState title="The report is not available yet">
            It opens when the Chair finalizes it. Until then the committee works on the evaluations.
          </EmptyState>
        </>
      );
    }
    return <ErrorNotice error={e} onRetry={header.reload} />;
  }
  const h = header.state.data;
  const isChair = tender.state.data.chair?.id === me.user.id;
  const decide = isChair && h.status === "in_progress";
  const finalize = h.actions?.finalize;
  const reopen = h.actions?.reopen;
  const ranking = overview.state.status === "ready" ? (overview.state.data?.vendors ?? []) : [];
  const overrideBy = new Map(
    (appendix.state.status === "ready" ? (appendix.state.data?.override_stats ?? []) : []).map(
      (s) => [s.tender_vendor_id, s.percent] as const,
    ),
  );
  const m = matrix.state.status === "ready" ? matrix.state.data : null;
  const vendorOptions = m?.vendors ?? [];

  async function doFinalize() {
    setBusy(true);
    try {
      await unwrap(
        api.POST("/tenders/{tenderId}/report/finalize", {
          params: {
            path: { tenderId: tenderId! },
            header: { "Idempotency-Key": crypto.randomUUID() },
          },
        }),
      );
      setConfirmFinalize(false);
      setMissing([]);
      announce("The report was finalized");
      refreshAll();
    } catch (e) {
      setConfirmFinalize(false);
      const failures = (e as ApiError).problem as unknown as {
        failures?: { code: string; message: string }[];
      };
      setMissing(failures?.failures ?? []);
      announce(e instanceof Error ? e.message : "The report could not be finalized");
    } finally {
      setBusy(false);
    }
  }

  async function doReopen(reason: string) {
    setBusy(true);
    try {
      await unwrap(
        api.POST("/tenders/{tenderId}/report/reopen", {
          params: { path: { tenderId: tenderId! } },
          body: { reason },
        }),
      );
      setReopening(false);
      setParams({});
      announce("The report was reopened as a new version");
      refreshAll();
    } catch (e) {
      announce(e instanceof Error ? e.message : "The report could not be reopened");
    } finally {
      setBusy(false);
    }
  }

  const startExport = (format: "pdf" | "xlsx") => () =>
    unwrap(
      api.POST("/tenders/{tenderId}/report/exports", {
        params: {
          path: { tenderId: tenderId! },
          header: { "Idempotency-Key": crypto.randomUUID() },
        },
        body: { format, version_no: h.version_no },
      }),
    );

  return (
    <div className="report">
      <TenderHeader tender={tender.state.data} active="report" />
      <section className="report-status" aria-label="Report status">
        <h2>
          Report version {h.version_no}{" "}
          <StatusChip
            label={statusLabel[h.status] ?? h.status}
            tone={
              h.status === "finalized" ? "success" : h.status === "superseded" ? "neutral" : "info"
            }
          />
        </h2>
        <p>
          Baseline version {h.baseline_version} · Weighted scoring (fully{" "}
          {h.scoring_model.points_fully}, partially {h.scoring_model.points_partially}, non{" "}
          {h.scoring_model.points_non}; a mandatory failure is flagged, not removed)
          {h.finalized_at && (
            <>
              {" "}
              · Finalized {new Date(h.finalized_at).toLocaleString()}
              {h.finalized_by ? ` by ${h.finalized_by.display_name}` : ""}
            </>
          )}
        </p>
        {h.ai_model_versions.length > 0 && (
          <p className="help">AI model: {h.ai_model_versions.join(", ")}</p>
        )}
        <p className="help">Committee: {h.committee.map((c) => c.display_name).join(", ")}</p>
        {(versions.state.status === "ready" ? versions.state.data : []).length > 1 && (
          <p>
            <label>
              Version{" "}
              <select value={h.version_no} onChange={(e) => setParams({ version: e.target.value })}>
                {(versions.state.status === "ready" ? versions.state.data : []).map((v) => (
                  <option key={v.version_no} value={v.version_no}>
                    {v.version_no} · {statusLabel[v.status] ?? v.status}
                  </option>
                ))}
              </select>
            </label>
          </p>
        )}
        <p className="report-actions">
          {finalize && (
            <>
              <Button
                type="button"
                variant="primary"
                disabled={!finalize.allowed || busy}
                onClick={() => setConfirmFinalize(true)}
              >
                Finalize report
              </Button>{" "}
              {!finalize.allowed && finalize.reason && h.disagreement_count === 0 && (
                <span className="help">{finalize.reason}</span>
              )}
            </>
          )}
          {reopen?.allowed && (
            <Button type="button" onClick={() => setReopening(true)}>
              Reopen report
            </Button>
          )}{" "}
          {h.actions?.export?.allowed && (
            <>
              <ExportButton label="Export PDF" start={startExport("pdf")} />{" "}
              <ExportButton label="Export Excel" start={startExport("xlsx")} />
            </>
          )}{" "}
          <Button type="button" onClick={() => window.print()}>
            Print
          </Button>
        </p>
        {h.status === "in_progress" && (
          <p role="note">
            {h.disagreement_count === 0
              ? "No disagreements are waiting for a final grade."
              : `${h.disagreement_count} disagreements still need a final grade.`}
          </p>
        )}
        {missing.length > 0 && (
          <div role="alert" className="notice">
            <strong>The report is not ready to finalize</strong>
            <ul>
              {missing.map((f, i) => (
                <li key={`${f.code}-${i}`}>{f.message}</li>
              ))}
            </ul>
          </div>
        )}
      </section>

      {(me.user.role === "admin" || isChair) && h.status === "in_progress" && (
        <ChairPanel tenderId={tenderId!} isChair={isChair} />
      )}

      <section aria-labelledby="overview-h">
        <h2 id="overview-h">Overview</h2>
        {ranking.length === 0 ? (
          <EmptyState title="No results yet">
            Results open for a vendor once every member has submitted it.
          </EmptyState>
        ) : (
          <div className="table-wrap">
            <table>
              <caption>Vendors ranked by final score</caption>
              <thead>
                <tr>
                  <th scope="col">Rank</th>
                  <th scope="col">Vendor</th>
                  <th scope="col">Final score</th>
                  <th scope="col">Compliance</th>
                  <th scope="col">Mandatory</th>
                  <th scope="col">Grades (full / partial / non)</th>
                  <th scope="col">Agreement</th>
                  <th scope="col">AI overridden</th>
                </tr>
              </thead>
              <tbody>
                {ranking.map((v) =>
                  v.outcome === "rejected_at_screening" ? (
                    <tr key={v.tender_vendor_id}>
                      <td>-</td>
                      <th scope="row">{v.vendor_name}</th>
                      <td colSpan={6}>Rejected at screening{v.reason ? `: ${v.reason}` : ""}</td>
                    </tr>
                  ) : !v.available ? (
                    <tr key={v.tender_vendor_id}>
                      <td>-</td>
                      <th scope="row">{v.vendor_name}</th>
                      <td colSpan={6}>
                        Results open once all members submit.
                        {v.awaiting && v.awaiting.length > 0
                          ? ` Waiting for ${v.awaiting.map((a) => a.display_name).join(", ")}.`
                          : ""}
                      </td>
                    </tr>
                  ) : (
                    <tr key={v.tender_vendor_id}>
                      <td>{v.rank ?? "-"}</td>
                      <th scope="row">{v.vendor_name}</th>
                      <td>{v.final_score ?? "-"}%</td>
                      <td>{v.compliance_percent ?? "-"}%</td>
                      <td>
                        {v.fails_mandatory ? (
                          <StatusChip
                            tone="error"
                            label={`✕ ${v.mandatory_failures} failed (flag)`}
                          />
                        ) : (
                          "None failed"
                        )}
                      </td>
                      <td>
                        {String(v.counts?.fully_compliant ?? 0)} /{" "}
                        {String(v.counts?.partially_compliant ?? 0)} /{" "}
                        {String(v.counts?.non_compliant ?? 0)}
                      </td>
                      <td>{v.agreement_level ?? "-"}%</td>
                      <td>
                        {overrideBy.has(v.tender_vendor_id)
                          ? `${overrideBy.get(v.tender_vendor_id)}%`
                          : "-"}
                      </td>
                    </tr>
                  ),
                )}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section aria-labelledby="matrix-h">
        <h2 id="matrix-h">Matrix</h2>
        <div className="filter-bar" role="search" aria-label="Matrix filters">
          <label>
            Vendor{" "}
            <select
              value={filters.tenderVendorId}
              onChange={(e) => setFilters({ ...filters, tenderVendorId: e.target.value })}
            >
              <option value="">All vendors</option>
              {vendorOptions.map((v) => (
                <option key={v.tender_vendor_id} value={v.tender_vendor_id}>
                  {v.name}
                </option>
              ))}
            </select>
          </label>{" "}
          <label>
            Importance{" "}
            <select
              value={filters.importance}
              onChange={(e) => setFilters({ ...filters, importance: e.target.value })}
            >
              <option value="">Any</option>
              {Object.entries(importanceLabel).map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          </label>{" "}
          <label>
            Grade{" "}
            <select
              value={filters.grade}
              onChange={(e) => setFilters({ ...filters, grade: e.target.value })}
            >
              <option value="">Any</option>
              {Object.entries(gradeLabel).map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          </label>{" "}
          <label>
            <input
              type="checkbox"
              checked={filters.disagreement}
              onChange={(e) => setFilters({ ...filters, disagreement: e.target.checked })}
            />{" "}
            Disagreements only ({h.disagreement_count})
          </label>
        </div>
        {matrix.state.status === "loading" ? (
          <Loading label="Loading the matrix" />
        ) : !m || m.rows.length === 0 ? (
          <EmptyState title="No rows">Nothing matches these filters.</EmptyState>
        ) : (
          <div className="table-wrap">
            <table>
              <caption>Final grade of each requirement for each vendor</caption>
              <thead>
                <tr>
                  <th scope="col">No</th>
                  <th scope="col">Requirement</th>
                  {m.vendors.map((v) => (
                    <th scope="col" key={v.tender_vendor_id}>
                      {v.name}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {m.rows.map((r) => (
                  <tr key={r.requirement.id}>
                    <th scope="row">{r.requirement.display_no}</th>
                    <td>
                      {r.requirement.text}{" "}
                      <span className="help">
                        {importanceLabel[r.requirement.importance] ?? r.requirement.importance}
                      </span>
                    </td>
                    {r.cells.map((c) => (
                      <td key={c.tender_vendor_id}>
                        {!c.available ? (
                          <span className="help">Not available yet</span>
                        ) : (
                          <button
                            type="button"
                            className="link"
                            onClick={() =>
                              setCellOf({
                                requirementId: r.requirement.id,
                                tenderVendorId: c.tender_vendor_id,
                              })
                            }
                          >
                            <span className="visually-hidden">
                              Open {r.requirement.display_no} for{" "}
                              {
                                m.vendors.find((v) => v.tender_vendor_id === c.tender_vendor_id)
                                  ?.name
                              }
                              :{" "}
                            </span>
                            {c.has_disagreement && (
                              <StatusChip tone="warning" label="Disagreement" />
                            )}{" "}
                            <Grade grade={c.final_grade} />
                          </button>
                        )}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {outcomes.state.status === "ready" && outcomes.state.data.length > 0 && (
        <section aria-labelledby="screen-h">
          <h2 id="screen-h">Rejected at screening</h2>
          <ul>
            {outcomes.state.data.map((o) => (
              <li key={o.tender_vendor_id}>
                <strong>{o.vendor_name}</strong>
                {o.reason ? `: ${o.reason}` : ""}
              </li>
            ))}
          </ul>
        </section>
      )}

      {cellOf && (
        <CellDialog
          tenderId={tenderId!}
          version={version}
          cell={cellOf}
          canDecide={decide}
          onClose={() => setCellOf(null)}
          onDecided={() => {
            refreshAll();
          }}
        />
      )}
      <ConfirmDialog
        open={confirmFinalize}
        onOpenChange={setConfirmFinalize}
        title="Finalize the report"
        description="This locks the report as a version. Changing it later needs a reopen with a reason, which creates a new version."
        confirmLabel="Finalize report"
        busy={busy}
        onConfirm={() => void doFinalize()}
      />
      <NoteDialog
        open={reopening}
        onOpenChange={setReopening}
        title="Reopen the report"
        description="A new version opens with the final grades copied. The finalized version stays readable."
        confirmLabel="Reopen report"
        minLength={me.client_config.min_note_length}
        busy={busy}
        onConfirm={(r) => void doReopen(r)}
      />
    </div>
  );
}

function CellDialog({
  tenderId,
  version,
  cell,
  canDecide,
  onClose,
  onDecided,
}: {
  tenderId: string;
  version: number | undefined;
  cell: { requirementId: string; tenderVendorId: string };
  canDecide: boolean;
  onClose: () => void;
  onDecided: () => void;
}) {
  const api = useApi();
  const announce = useAnnounce();
  const { me } = useSession();
  const [grade, setGrade] = useState("");
  const [why, setWhy] = useState("");
  const [busy, setBusy] = useState(false);
  const detail = useAsync(
    () =>
      unwrap(
        api.GET("/tenders/{tenderId}/report/matrix/{requirementId}/{tenderVendorId}", {
          params: {
            path: {
              tenderId,
              requirementId: cell.requirementId,
              tenderVendorId: cell.tenderVendorId,
            },
            query: { version },
          },
        }),
      ),
    [api, tenderId, cell.requirementId, cell.tenderVendorId, version],
  );
  const min = me.client_config.min_note_length;
  const d = detail.state.status === "ready" ? detail.state.data : null;
  const differs =
    d !== null && new Set(d.member_grades.map((g) => g.grade).filter((g) => g)).size > 1;
  const settled = d?.basis === "chair_decision";

  async function record() {
    setBusy(true);
    try {
      await unwrap(
        api.PUT("/tenders/{tenderId}/report/grades/{tenderVendorId}/{requirementId}", {
          params: {
            path: {
              tenderId,
              tenderVendorId: cell.tenderVendorId,
              requirementId: cell.requirementId,
            },
          },
          body: { final_grade: grade as S["Grade"], justification: why.trim() },
        }),
      );
      announce("The final grade was recorded");
      onDecided();
      onClose();
    } catch (e) {
      announce(e instanceof Error ? e.message : "The final grade could not be recorded");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog.Root open onOpenChange={(o) => !o && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog wide">
          <Dialog.Title asChild>
            <h2>{d ? `${d.requirement.display_no}: ${d.requirement.text}` : "Requirement"}</h2>
          </Dialog.Title>
          <Dialog.Description>
            Each member's grade and note, the AI's grade and the evidence behind it.
          </Dialog.Description>
          {detail.state.status === "loading" && <Loading label="Loading the cell" />}
          {detail.state.status === "error" && (
            <ErrorNotice error={detail.state.error} onRetry={detail.reload} />
          )}
          {d && (
            <>
              <p>
                Final grade: <Grade grade={d.final_grade} />{" "}
                {d.basis && (
                  <span className="help">
                    ({d.basis === "all_agree" ? "all members agree" : "Chair's decision"})
                  </span>
                )}
              </p>
              {d.justification && (
                <p>
                  <strong>Chair's justification:</strong> {d.justification}
                  {d.decided_by ? ` (${d.decided_by.display_name})` : ""}
                </p>
              )}
              {d.ai && (
                <section aria-label="AI grade">
                  <h3>AI</h3>
                  <p>
                    <Grade grade={d.ai.grade} /> {d.ai.rationale_summary}
                  </p>
                  {d.ai.citations.length > 0 && (
                    <ul>
                      {d.ai.citations.map((c) => (
                        <li key={c.citation_no}>
                          <Link
                            to={`/documents/${c.document_version_id}/view?passage=${c.passage_id}`}
                          >
                            Page {c.page_no}
                          </Link>
                          : {c.excerpt}
                        </li>
                      ))}
                    </ul>
                  )}
                </section>
              )}
              <h3>Members</h3>
              <ul>
                {d.member_grades.map((g) => (
                  <li key={g.member.id}>
                    <strong>{g.member.display_name}</strong>: <Grade grade={g.grade} />{" "}
                    <span className="help">
                      {reviewStateLabel[g.review_state ?? "not_reviewed"] ?? ""}
                    </span>
                    {g.note && <div>{g.note}</div>}
                  </li>
                ))}
              </ul>
              {canDecide && (differs || settled) && (
                <section aria-label="Chair's final grade">
                  <h3>Chair's final grade</h3>
                  <fieldset>
                    <legend>Final grade</legend>
                    {Object.entries(gradeLabel).map(([v, l]) => (
                      <label key={v}>
                        <input
                          type="radio"
                          name="final-grade"
                          value={v}
                          checked={grade === v}
                          onChange={() => setGrade(v)}
                        />{" "}
                        {l}
                      </label>
                    ))}
                  </fieldset>
                  <div className="field">
                    <label htmlFor="why">Justification (required)</label>
                    <textarea
                      id="why"
                      rows={3}
                      value={why}
                      onChange={(e) => setWhy(e.target.value)}
                    />
                    <span className="help">At least {min} characters, so the report says why.</span>
                  </div>
                  <Button
                    type="button"
                    variant="primary"
                    disabled={busy || !grade || why.trim().length < min}
                    onClick={() => void record()}
                  >
                    Record final grade
                  </Button>
                </section>
              )}
            </>
          )}
          <div className="dialog-actions">
            <Dialog.Close asChild>
              <button type="button" className="btn">
                Close
              </button>
            </Dialog.Close>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
