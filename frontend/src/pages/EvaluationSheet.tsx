import { useState } from "react";
import { Link, useParams } from "react-router";
import {
  Button,
  ConfirmDialog,
  EmptyState,
  ErrorNotice,
  Field,
  Loading,
  NoteDialog,
  StatusChip,
  useAnnounce,
} from "../components/ui";
import { useSession } from "../app/Session";
import { ApiError, unwrap, useApi } from "../lib/api";
import {
  evaluationStatusLabel,
  evaluationStatusTone,
  gradeIcon,
  gradeLabel,
  gradeTone,
  importanceLabel,
  memberStateLabel,
  nonComplianceLabel,
  rowFlagText,
} from "../lib/labels";
import { usePush } from "../lib/usePush";
import { useAsync } from "../lib/useAsync";
import { TenderHeader, useTender } from "./TenderHome";
import type { components } from "../api/generated/schema";

type Row = components["schemas"]["EvaluationRow"];
type Grade = components["schemas"]["Grade"];

function GradeChip({ grade }: { grade: string | null | undefined }) {
  if (!grade) return <span className="help">None</span>;
  return (
    <StatusChip
      label={`${gradeIcon[grade] ?? ""} ${gradeLabel[grade] ?? grade}`.trim()}
      tone={gradeTone[grade] ?? "neutral"}
    />
  );
}

/** PG-15 Evaluation sheet (T5 split view): the member's own rows, the AI's evidence, the decision. */
export function EvaluationSheet() {
  const { tenderId, tenderVendorId } = useParams();
  const api = useApi();
  const announce = useAnnounce();
  const { me } = useSession();
  const minNote = me.client_config.min_note_length;
  const tender = useTender(tenderId);

  const [q, setQ] = useState("");
  const [grade, setGrade] = useState("");
  const [state, setState] = useState("");
  const [only, setOnly] = useState<"" | "overridden" | "flagged" | "outdated" | "not_evaluated">(
    "",
  );
  const [selected, setSelected] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | undefined>();
  const [overriding, setOverriding] = useState(false);
  const [overrideGrade, setOverrideGrade] = useState<Grade>("non_compliant");
  const [flagging, setFlagging] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [failures, setFailures] = useState<{ code: string; message: string }[]>([]);
  const [peers, setPeers] = useState<components["schemas"]["PeerRow"][] | null>(null);
  const [shown, setShown] = useState<{
    passage: string;
    text: string;
    page: number;
    doc: string;
  } | null>(null);

  const evaluation = useAsync(
    () =>
      unwrap(
        api.GET("/tender-vendors/{tenderVendorId}/evaluation", {
          params: { path: { tenderVendorId: tenderVendorId! } },
        }),
      ),
    [api, tenderVendorId],
  );
  const rows = useAsync(
    () =>
      unwrap(
        api.GET("/tender-vendors/{tenderVendorId}/evaluation/rows", {
          params: {
            path: { tenderVendorId: tenderVendorId! },
            query: {
              limit: 500,
              q: q || undefined,
              grade: (grade || undefined) as Grade | undefined,
              review_state: (state || undefined) as
                "not_reviewed" | "agreed" | "overridden" | undefined,
              overridden: only === "overridden" ? true : undefined,
              flagged: only === "flagged" ? true : undefined,
              outdated: only === "outdated" ? true : undefined,
              not_evaluated: only === "not_evaluated" ? true : undefined,
            },
          },
        }),
      ),
    [api, tenderVendorId, q, grade, state, only],
  );
  const reload = () => {
    evaluation.reload();
    rows.reload();
  };
  usePush(tenderId, reload, ["evaluation.progress", "job.updated", "tender.changed"]);

  const list = rows.state.status === "ready" ? rows.state.data.items : [];
  const current: Row | undefined =
    list.find((r) => r.requirement.id === selected) ?? list[0] ?? undefined;
  const history = useAsync(
    () =>
      current
        ? unwrap(
            api.GET("/tender-vendors/{tenderVendorId}/evaluation/rows/{requirementId}/history", {
              params: {
                path: { tenderVendorId: tenderVendorId!, requirementId: current.requirement.id },
              },
            }),
          )
        : Promise.resolve(null),
    [api, tenderVendorId, current?.requirement.id, current?.row_version],
  );

  if (evaluation.state.status === "loading") return <Loading label="Loading the evaluation" />;
  if (evaluation.state.status === "error") {
    const e = evaluation.state.error;
    if (e instanceof ApiError && e.code === "declaration_required") {
      return (
        <EmptyState title="Declare before you evaluate">
          Open the vendor&rsquo;s proposal from the vendors page and declare that you have no
          conflict. Then come back here.
        </EmptyState>
      );
    }
    return <ErrorNotice error={e} onRetry={evaluation.reload} />;
  }
  const ev = evaluation.state.data;
  const actions = ev.actions ?? {};
  const decide = actions.decide_rows?.allowed === true;
  const summary = ev.summary;

  async function run(label: string, fn: () => Promise<unknown>) {
    setBusy(true);
    setError(undefined);
    try {
      await fn();
      announce(label);
      reload();
    } catch (e) {
      setError(e instanceof Error ? e : new Error("The change was not saved"));
    } finally {
      setBusy(false);
    }
  }

  function patch(row: Row, body: components["schemas"]["RowUpdate"], label: string) {
    return run(label, () =>
      unwrap(
        api.PATCH("/tender-vendors/{tenderVendorId}/evaluation/rows/{requirementId}", {
          params: {
            path: { tenderVendorId: tenderVendorId!, requirementId: row.requirement.id },
            header: { "If-Match": `"${row.row_version}"` },
          },
          body,
        }),
      ),
    );
  }

  async function showPassage(id: string) {
    try {
      const p = await unwrap(
        api.GET("/passages/{passageId}", { params: { path: { passageId: id } } }),
      );
      setShown({ passage: p.id, text: p.text, page: p.page_no, doc: p.document_version_id });
    } catch (e) {
      setError(e instanceof Error ? e : new Error("The passage could not be loaded"));
    }
  }

  async function submit() {
    setBusy(true);
    setError(undefined);
    try {
      await unwrap(
        api.POST("/tender-vendors/{tenderVendorId}/evaluation/submit", {
          params: { path: { tenderVendorId: tenderVendorId! } },
          headers: { "Idempotency-Key": crypto.randomUUID() },
        }),
      );
      announce("Evaluation submitted");
      setSubmitting(false);
      setFailures([]);
      reload();
    } catch (e) {
      if (e instanceof ApiError && e.code === "precheck_failed") {
        const f = (e.problem as { failures?: { code: string; message: string }[] }).failures ?? [];
        setFailures(f);
      } else {
        setError(e instanceof Error ? e : new Error("The evaluation was not submitted"));
      }
      setSubmitting(false);
    } finally {
      setBusy(false);
    }
  }

  const total = summary?.total ?? 0;
  const reviewed = summary?.reviewed ?? 0;
  const running = ev.job?.status === "queued" || ev.job?.status === "running";

  return (
    <>
      {tender.state.status === "ready" && (
        <TenderHeader tender={tender.state.data} active="evaluation" />
      )}
      <p>
        <Link to={`/tenders/${tenderId}/evaluation`}>Back to your evaluations</Link>
      </p>
      <h2>
        {ev.vendor_name}{" "}
        <StatusChip
          label={evaluationStatusLabel[ev.status] ?? ev.status}
          tone={evaluationStatusTone[ev.status] ?? "neutral"}
        />
      </h2>
      <p className="help" role="note">
        Your grades and notes are private. Other members&rsquo; grades open when you submit this
        vendor.
      </p>
      {running && (
        <p role="status">
          The AI is still grading this proposal. Rows appear as they are ready
          {ev.job?.progress_percent != null ? ` (${ev.job.progress_percent} %)` : ""}.
        </p>
      )}
      {ev.excluded && <p role="alert">You are excluded from this vendor.</p>}
      {error && <ErrorNotice error={error} />}
      {failures.length > 0 && (
        <div role="alert" className="card">
          <h3>Not ready to submit</h3>
          <ul>
            {failures.map((f) => (
              <li key={f.code}>{f.message}</li>
            ))}
          </ul>
        </div>
      )}

      <div className="card">
        <p aria-live="polite">
          <strong>
            {reviewed} of {total} reviewed
          </strong>
          {summary?.compliance_percent != null && (
            <>
              {" "}
              · Your score so far: {Math.round(Number(summary.compliance_percent))} %
              {summary.mandatory_failures > 0 &&
                ` · ${summary.mandatory_failures} mandatory not met`}
            </>
          )}
        </p>
        {summary?.explanation && (
          <details>
            <summary>How the score is worked out</summary>
            <p className="help">
              {String(summary.explanation.basis ?? "")} Fully compliant counts{" "}
              {String(summary.explanation.points_fully)}, partially{" "}
              {String(summary.explanation.points_partially)}, non-compliant{" "}
              {String(summary.explanation.points_non)}.
            </p>
          </details>
        )}
        <Button
          onClick={() => setSubmitting(true)}
          disabled={busy || actions.submit?.allowed !== true}
          disabledReason={
            actions.submit?.allowed === true ? undefined : (actions.submit?.reason ?? undefined)
          }
        >
          Submit this vendor
        </Button>
      </div>

      <div className="filters" role="search">
        <Field label="Search requirements">
          {({ id }) => <input id={id} value={q} onChange={(e) => setQ(e.target.value)} />}
        </Field>
        <Field label="Grade">
          {({ id }) => (
            <select id={id} value={grade} onChange={(e) => setGrade(e.target.value)}>
              <option value="">Any grade</option>
              {Object.entries(gradeLabel).map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Review state">
          {({ id }) => (
            <select id={id} value={state} onChange={(e) => setState(e.target.value)}>
              <option value="">Any state</option>
              {Object.entries(memberStateLabel).map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Show only">
          {({ id }) => (
            <select id={id} value={only} onChange={(e) => setOnly(e.target.value as typeof only)}>
              <option value="">All rows</option>
              <option value="overridden">Overridden</option>
              <option value="flagged">Needs clarification</option>
              <option value="outdated">Outdated</option>
              <option value="not_evaluated">Not evaluated</option>
            </select>
          )}
        </Field>
      </div>

      {rows.state.status === "loading" && <Loading label="Loading rows" />}
      {rows.state.status === "error" && (
        <ErrorNotice error={rows.state.error} onRetry={rows.reload} />
      )}
      {rows.state.status === "ready" && list.length === 0 && (
        <EmptyState title="No rows">
          {running ? "Rows appear as the AI grades them." : "No requirement matches these filters."}
        </EmptyState>
      )}
      {list.length > 0 && (
        <div className="split">
          <table>
            <thead>
              <tr>
                <th scope="col">Requirement</th>
                <th scope="col">AI</th>
                <th scope="col">Mine</th>
                <th scope="col">Notes</th>
              </tr>
            </thead>
            <tbody>
              {list.map((r) => (
                <tr
                  key={r.requirement.id}
                  aria-selected={current?.requirement.id === r.requirement.id}
                >
                  <th scope="row">
                    <button
                      type="button"
                      className="linklike"
                      onClick={() => {
                        setSelected(r.requirement.id);
                        setShown(null);
                      }}
                    >
                      {r.requirement.display_no}
                    </button>{" "}
                    {r.requirement.text.length > 90
                      ? r.requirement.text.slice(0, 90) + "…"
                      : r.requirement.text}
                  </th>
                  <td>
                    {r.ai ? (
                      <GradeChip grade={r.ai.grade} />
                    ) : (
                      <span className="help">Waiting</span>
                    )}
                  </td>
                  <td>
                    {r.member.grade ? (
                      <GradeChip grade={r.member.grade} />
                    ) : (
                      <span className="help">{memberStateLabel[r.member.review_state]}</span>
                    )}
                  </td>
                  <td>
                    {r.flags.map((f) => (
                      <StatusChip
                        key={f}
                        label={rowFlagText[f] ?? f}
                        tone={
                          f === "outdated" || f === "needs_clarification" ? "warning" : "neutral"
                        }
                      />
                    ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {current && (
            <aside className="card" aria-label={`Detail of ${current.requirement.display_no}`}>
              <h3>
                {current.requirement.display_no}{" "}
                <StatusChip
                  label={
                    importanceLabel[current.requirement.importance] ??
                    current.requirement.importance
                  }
                  tone="neutral"
                />
              </h3>
              <p>{current.requirement.text}</p>
              {current.requirement.acceptance_criteria && (
                <p className="help">
                  Acceptance criteria: {current.requirement.acceptance_criteria}
                </p>
              )}
              <h4>AI assessment</h4>
              {!current.ai ? (
                <p className="help">The AI has not graded this requirement yet.</p>
              ) : current.ai.outcome === "failed" ? (
                <p>
                  Not evaluated: the AI could not give a grade with evidence. Grade it yourself.
                </p>
              ) : (
                <>
                  <p>
                    <GradeChip grade={current.ai.grade} />{" "}
                    {current.ai.non_compliance_reason &&
                      nonComplianceLabel[current.ai.non_compliance_reason]}
                    {current.ai.confidence != null &&
                      ` · confidence ${Math.round(Number(current.ai.confidence) * 100)} %`}
                  </p>
                  <p>{current.ai.rationale}</p>
                  {(current.ai.missing_elements?.length ?? 0) > 0 && (
                    <ul>
                      {(current.ai.missing_elements ?? []).map((m) => (
                        <li key={m}>Missing: {m}</li>
                      ))}
                    </ul>
                  )}
                  {current.ai.citations.length === 0 ? (
                    <p className="help">No evidence found.</p>
                  ) : (
                    <ul>
                      {current.ai.citations.map((c) => (
                        <li key={c.citation_no}>
                          &ldquo;{c.excerpt}&rdquo; (page {c.page_no}){" "}
                          <button
                            type="button"
                            className="linklike"
                            onClick={() => void showPassage(c.passage_id)}
                          >
                            Show in context
                          </button>{" "}
                          <Link
                            to={`/documents/${c.document_version_id}/view?passage=${c.passage_id}`}
                          >
                            Open page {c.page_no}
                          </Link>
                        </li>
                      ))}
                    </ul>
                  )}
                </>
              )}
              {shown && (
                <blockquote aria-label="The passage in context">
                  <p className="help">Page {shown.page}</p>
                  {shown.text}
                </blockquote>
              )}

              <h4>Your decision</h4>
              <p>
                <StatusChip label={memberStateLabel[current.member.review_state]} tone="neutral" />{" "}
                {current.member.grade && <GradeChip grade={current.member.grade} />}
              </p>
              {current.member.note && <p>Your note: {current.member.note}</p>}
              <div className="actions">
                <Button
                  onClick={() =>
                    void patch(current, { action: "agree" }, "Agreed with the AI grade")
                  }
                  disabled={busy || !decide || !current.ai || current.ai.outcome === "failed"}
                  disabledReason={decide ? undefined : (actions.decide_rows?.reason ?? undefined)}
                >
                  Agree with AI
                </Button>
                <Button onClick={() => setOverriding(true)} disabled={busy || !decide}>
                  Override
                </Button>
                {current.member.needs_clarification ? (
                  <Button
                    onClick={() =>
                      void patch(current, { action: "clear_clarification" }, "Flag cleared")
                    }
                    disabled={busy || !decide}
                  >
                    Clear flag
                  </Button>
                ) : (
                  <Button onClick={() => setFlagging(true)} disabled={busy || !decide}>
                    Needs clarification
                  </Button>
                )}
              </div>
              {current.ai && current.ai.outcome === "graded" && (
                <p className="help">
                  Was the AI grade useful?{" "}
                  <button
                    type="button"
                    className="linklike"
                    onClick={() =>
                      void run("Thanks for the feedback", () =>
                        unwrap(
                          api.POST(
                            "/tender-vendors/{tenderVendorId}/evaluation/rows/{requirementId}/feedback",
                            {
                              params: {
                                path: {
                                  tenderVendorId: tenderVendorId!,
                                  requirementId: current.requirement.id,
                                },
                              },
                              body: { is_useful: true },
                            },
                          ),
                        ),
                      )
                    }
                  >
                    Yes
                  </button>{" "}
                  <button
                    type="button"
                    className="linklike"
                    onClick={() =>
                      void run("Thanks for the feedback", () =>
                        unwrap(
                          api.POST(
                            "/tender-vendors/{tenderVendorId}/evaluation/rows/{requirementId}/feedback",
                            {
                              params: {
                                path: {
                                  tenderVendorId: tenderVendorId!,
                                  requirementId: current.requirement.id,
                                },
                              },
                              body: { is_useful: false },
                            },
                          ),
                        ),
                      )
                    }
                  >
                    No
                  </button>
                </p>
              )}
              <p>
                <Link to={`/tenders/${tenderId}/requirements/${current.requirement.id}/compare`}>
                  Compare this requirement across vendors
                </Link>
              </p>
              {ev.first_submitted_at && (
                <div>
                  <Button
                    onClick={() =>
                      void (async () => {
                        try {
                          const p = await unwrap(
                            api.GET("/tender-vendors/{tenderVendorId}/evaluation/peers", {
                              params: {
                                path: { tenderVendorId: tenderVendorId! },
                                query: { requirement_id: current.requirement.id },
                              },
                            }),
                          );
                          setPeers(p.items);
                        } catch (e) {
                          setError(e instanceof Error ? e : new Error("Not available"));
                        }
                      })()
                    }
                  >
                    Show other members&rsquo; decisions
                  </Button>
                  {peers && (
                    <ul aria-label="Other members' decisions">
                      {peers.length === 0 && (
                        <li className="help">No other member has submitted this vendor yet.</li>
                      )}
                      {peers.map((p) => (
                        <li key={`${p.member.id}`}>
                          {p.member.display_name}:{" "}
                          {p.grade
                            ? (gradeLabel[p.grade] ?? p.grade)
                            : memberStateLabel[p.review_state]}
                          {p.note ? ` (${p.note})` : ""}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
              {history.state.status === "ready" && history.state.data && (
                <details>
                  <summary>Your history of this row</summary>
                  {history.state.data.events.length === 0 ? (
                    <p className="help">No decision yet.</p>
                  ) : (
                    <ul>
                      {history.state.data.events.map((e) => (
                        <li key={e.id}>
                          {new Date(e.occurred_at).toLocaleString()}: {e.action.replace(/_/g, " ")}
                          {e.note ? ` (${e.note})` : ""}
                        </li>
                      ))}
                    </ul>
                  )}
                </details>
              )}
            </aside>
          )}
        </div>
      )}

      <ConfirmDialog
        open={submitting}
        onOpenChange={setSubmitting}
        title={`Submit ${ev.vendor_name}?`}
        description="After you submit you cannot change your grades unless the Chair reopens your evaluation. Other members' grades then open to you."
        confirmLabel="Submit"
        onConfirm={() => void submit()}
      />
      <NoteDialog
        open={overriding}
        onOpenChange={setOverriding}
        title="Override the AI grade"
        description="Choose your grade and say why. The AI grade stays visible next to yours."
        confirmLabel="Save override"
        minLength={minNote}
        busy={busy}
        onConfirm={(note) => {
          if (current)
            void patch(
              current,
              { action: "override", grade: overrideGrade, note },
              "Override saved",
            );
          setOverriding(false);
        }}
      >
        <Field label="Your grade">
          {({ id }) => (
            <select
              id={id}
              value={overrideGrade}
              onChange={(e) => setOverrideGrade(e.target.value as Grade)}
            >
              {Object.entries(gradeLabel).map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          )}
        </Field>
      </NoteDialog>
      <NoteDialog
        open={flagging}
        onOpenChange={setFlagging}
        title="Needs clarification"
        description="Say what is unclear. The row goes on the clarification list for the vendor."
        confirmLabel="Flag the row"
        minLength={1}
        busy={busy}
        onConfirm={(note) => {
          if (current) void patch(current, { action: "flag_clarification", note }, "Row flagged");
          setFlagging(false);
        }}
      />
    </>
  );
}
