import {
  Alert,
  Button as AntButton,
  Card,
  Col,
  Flex,
  Input,
  Row,
  Select,
  Space,
  Table,
  Typography,
} from "antd";
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
  rowHeader,
  rowHeaderComponents,
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
  if (!grade) return <Typography.Text type="secondary">None</Typography.Text>;
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
      {tender.state.status === "ready" && <TenderHeader tender={tender.state.data} />}
      <Typography.Paragraph>
        <Link to={`/tenders/${tenderId}/evaluation`}>Back to your evaluations</Link>
      </Typography.Paragraph>
      <Typography.Title level={2} style={{ fontSize: 20 }}>
        {ev.vendor_name}{" "}
        <StatusChip
          label={evaluationStatusLabel[ev.status] ?? ev.status}
          tone={evaluationStatusTone[ev.status] ?? "neutral"}
        />
      </Typography.Title>
      <Typography.Paragraph type="secondary" role="note">
        Your grades and notes are private. Other members&rsquo; grades open when you submit this
        vendor.
      </Typography.Paragraph>
      {running && (
        <Alert
          type="info"
          showIcon
          role="status"
          style={{ marginBottom: 16 }}
          title={`The AI is still grading this proposal. Rows appear as they are ready${
            ev.job?.progress_percent != null ? ` (${ev.job.progress_percent} %)` : ""
          }.`}
        />
      )}
      {ev.excluded && (
        <Alert
          type="warning"
          showIcon
          role="alert"
          style={{ marginBottom: 16 }}
          title="You are excluded from this vendor."
        />
      )}
      {error && <ErrorNotice error={error} />}
      {failures.length > 0 && (
        <Alert
          type="error"
          showIcon
          role="alert"
          style={{ marginBottom: 16 }}
          title="Not ready to submit"
          description={
            <ul>
              {failures.map((f) => (
                <li key={f.code}>{f.message}</li>
              ))}
            </ul>
          }
        />
      )}

      <Card style={{ marginBottom: 16 }}>
        <p aria-live="polite" style={{ marginTop: 0 }}>
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
          <details style={{ marginBottom: 12 }}>
            <summary>How the score is worked out</summary>
            <Typography.Paragraph type="secondary">
              {String(summary.explanation.basis ?? "")} Fully compliant counts{" "}
              {String(summary.explanation.points_fully)}, partially{" "}
              {String(summary.explanation.points_partially)}, non-compliant{" "}
              {String(summary.explanation.points_non)}.
            </Typography.Paragraph>
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
      </Card>

      <Card style={{ marginBottom: 16 }}>
        <Flex gap={16} wrap align="flex-end" role="search">
          <Field label="Search requirements">
            {({ id }) => (
              <Input.Search
                id={id}
                allowClear
                value={q}
                onChange={(e) => setQ(e.target.value)}
                style={{ minWidth: 240 }}
              />
            )}
          </Field>
          <Field label="Grade">
            {({ id }) => (
              <Select
                id={id}
                value={grade}
                onChange={(v) => setGrade(v)}
                style={{ minWidth: 200 }}
                options={[
                  { value: "", label: "Any grade" },
                  ...Object.entries(gradeLabel).map(([v, l]) => ({ value: v, label: l })),
                ]}
              />
            )}
          </Field>
          <Field label="Review state">
            {({ id }) => (
              <Select
                id={id}
                value={state}
                onChange={(v) => setState(v)}
                style={{ minWidth: 180 }}
                options={[
                  { value: "", label: "Any state" },
                  ...Object.entries(memberStateLabel).map(([v, l]) => ({ value: v, label: l })),
                ]}
              />
            )}
          </Field>
          <Field label="Show only">
            {({ id }) => (
              <Select
                id={id}
                value={only}
                onChange={(v) => setOnly(v)}
                style={{ minWidth: 200 }}
                options={[
                  { value: "", label: "All rows" },
                  { value: "overridden", label: "Overridden" },
                  { value: "flagged", label: "Needs clarification" },
                  { value: "outdated", label: "Outdated" },
                  { value: "not_evaluated", label: "Not evaluated" },
                ]}
              />
            )}
          </Field>
        </Flex>
      </Card>

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
        <Row gutter={24}>
          <Col xs={24} xl={12}>
            <Table<Row>
              rowKey={(r) => r.requirement.id}
              components={rowHeaderComponents}
              dataSource={list}
              pagination={false}
              scroll={{ x: "max-content" }}
              onRow={(r) => ({ "aria-selected": current?.requirement.id === r.requirement.id })}
              columns={[
                {
                  title: "Requirement",
                  onCell: rowHeader,
                  render: (_, r) => (
                    <>
                      <AntButton
                        type="link"
                        style={{ padding: 0 }}
                        onClick={() => {
                          setSelected(r.requirement.id);
                          setShown(null);
                        }}
                      >
                        {r.requirement.display_no}
                      </AntButton>{" "}
                      {r.requirement.text.length > 90
                        ? r.requirement.text.slice(0, 90) + "…"
                        : r.requirement.text}
                    </>
                  ),
                },
                {
                  title: "AI",
                  render: (_, r) =>
                    r.ai ? (
                      <GradeChip grade={r.ai.grade} />
                    ) : (
                      <Typography.Text type="secondary">Waiting</Typography.Text>
                    ),
                },
                {
                  title: "Mine",
                  render: (_, r) =>
                    r.member.grade ? (
                      <GradeChip grade={r.member.grade} />
                    ) : (
                      <Typography.Text type="secondary">
                        {memberStateLabel[r.member.review_state]}
                      </Typography.Text>
                    ),
                },
                {
                  title: "Notes",
                  render: (_, r) => (
                    <Space size={4} wrap>
                      {r.flags.map((f) => (
                        <StatusChip
                          key={f}
                          label={rowFlagText[f] ?? f}
                          tone={
                            f === "outdated" || f === "needs_clarification" ? "warning" : "neutral"
                          }
                        />
                      ))}
                    </Space>
                  ),
                },
              ]}
            />
          </Col>
          <Col xs={24} xl={12}>
            {current && (
              <aside aria-label={`Detail of ${current.requirement.display_no}`}>
                <Card>
                  <Typography.Title level={3} style={{ fontSize: 16, marginTop: 0 }}>
                    {current.requirement.display_no}{" "}
                    <StatusChip
                      label={
                        importanceLabel[current.requirement.importance] ??
                        current.requirement.importance
                      }
                      tone="neutral"
                    />
                  </Typography.Title>
                  <Typography.Paragraph>{current.requirement.text}</Typography.Paragraph>
                  {current.requirement.acceptance_criteria && (
                    <Typography.Paragraph type="secondary">
                      Acceptance criteria: {current.requirement.acceptance_criteria}
                    </Typography.Paragraph>
                  )}
                  <Typography.Title level={4} style={{ fontSize: 14 }}>
                    AI assessment
                  </Typography.Title>
                  {!current.ai ? (
                    <Typography.Paragraph type="secondary">
                      The AI has not graded this requirement yet.
                    </Typography.Paragraph>
                  ) : current.ai.outcome === "failed" ? (
                    <Typography.Paragraph>
                      Not evaluated: the AI could not give a grade with evidence. Grade it yourself.
                    </Typography.Paragraph>
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
                        <Typography.Paragraph type="secondary">
                          No evidence found.
                        </Typography.Paragraph>
                      ) : (
                        <ul>
                          {current.ai.citations.map((c) => (
                            <li key={c.citation_no}>
                              &ldquo;{c.excerpt}&rdquo; (page {c.page_no}){" "}
                              <AntButton
                                type="link"
                                style={{ padding: 0 }}
                                onClick={() => void showPassage(c.passage_id)}
                              >
                                Show in context
                              </AntButton>{" "}
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
                      <Typography.Paragraph type="secondary">
                        Page {shown.page}
                      </Typography.Paragraph>
                      {shown.text}
                    </blockquote>
                  )}

                  <Typography.Title level={4} style={{ fontSize: 14 }}>
                    Your decision
                  </Typography.Title>
                  <p>
                    <StatusChip
                      label={memberStateLabel[current.member.review_state]}
                      tone="neutral"
                    />{" "}
                    {current.member.grade && <GradeChip grade={current.member.grade} />}
                  </p>
                  {current.member.note && <p>Your note: {current.member.note}</p>}
                  <Space wrap style={{ marginBottom: 12 }}>
                    <Button
                      onClick={() =>
                        void patch(current, { action: "agree" }, "Agreed with the AI grade")
                      }
                      disabled={busy || !decide || !current.ai || current.ai.outcome === "failed"}
                      disabledReason={
                        decide ? undefined : (actions.decide_rows?.reason ?? undefined)
                      }
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
                  </Space>
                  {current.ai && current.ai.outcome === "graded" && (
                    <Typography.Paragraph type="secondary">
                      Was the AI grade useful?{" "}
                      <AntButton
                        type="link"
                        style={{ padding: 0 }}
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
                      </AntButton>{" "}
                      <AntButton
                        type="link"
                        style={{ padding: 0 }}
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
                      </AntButton>
                    </Typography.Paragraph>
                  )}
                  <p>
                    <Link
                      to={`/tenders/${tenderId}/requirements/${current.requirement.id}/compare`}
                    >
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
                            <li>
                              <Typography.Text type="secondary">
                                No other member has submitted this vendor yet.
                              </Typography.Text>
                            </li>
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
                        <Typography.Paragraph type="secondary">
                          No decision yet.
                        </Typography.Paragraph>
                      ) : (
                        <ul>
                          {history.state.data.events.map((e) => (
                            <li key={e.id}>
                              {new Date(e.occurred_at).toLocaleString()}:{" "}
                              {e.action.replace(/_/g, " ")}
                              {e.note ? ` (${e.note})` : ""}
                            </li>
                          ))}
                        </ul>
                      )}
                    </details>
                  )}
                </Card>
              </aside>
            )}
          </Col>
        </Row>
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
            <Select
              id={id}
              value={overrideGrade}
              onChange={(v) => setOverrideGrade(v)}
              style={{ width: "100%" }}
              options={Object.entries(gradeLabel).map(([v, l]) => ({ value: v, label: l }))}
            />
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
