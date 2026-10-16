import {
  Alert,
  Button as AntButton,
  Card,
  Checkbox,
  Drawer,
  Flex,
  Input,
  Radio,
  Select,
  Space,
  Table,
  Typography,
} from "antd";
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
  Section,
  StatusChip,
  rowHeader,
  rowHeaderComponents,
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
  if (!grade) return <Typography.Text type="secondary">No final grade</Typography.Text>;
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
          <TenderHeader tender={tender.state.data} />
          <Typography.Title level={2} style={{ fontSize: 20 }}>
            Report
          </Typography.Title>
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
      <TenderHeader tender={tender.state.data} />
      <Card aria-label="Report status" style={{ marginBottom: 16 }}>
        <Typography.Title level={2} style={{ fontSize: 20, marginTop: 0 }}>
          Report version {h.version_no}{" "}
          <StatusChip
            label={statusLabel[h.status] ?? h.status}
            tone={
              h.status === "finalized" ? "success" : h.status === "superseded" ? "neutral" : "info"
            }
          />
        </Typography.Title>
        <Typography.Paragraph>
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
        </Typography.Paragraph>
        {h.ai_model_versions.length > 0 && (
          <Typography.Paragraph type="secondary">
            AI model: {h.ai_model_versions.join(", ")}
          </Typography.Paragraph>
        )}
        <Typography.Paragraph type="secondary">
          Committee: {h.committee.map((c) => c.display_name).join(", ")}
        </Typography.Paragraph>
        {(versions.state.status === "ready" ? versions.state.data : []).length > 1 && (
          <Flex gap={8} align="center" style={{ marginBottom: 16 }}>
            <label htmlFor="report-version">Version</label>
            <Select
              id="report-version"
              value={h.version_no}
              onChange={(v) => setParams({ version: String(v) })}
              style={{ minWidth: 200 }}
              options={(versions.state.status === "ready" ? versions.state.data : []).map((v) => ({
                value: v.version_no,
                label: `${v.version_no} · ${statusLabel[v.status] ?? v.status}`,
              }))}
            />
          </Flex>
        )}
        <Space wrap className="report-actions">
          {finalize && (
            <>
              <Button
                type="button"
                variant="primary"
                disabled={!finalize.allowed || busy}
                onClick={() => setConfirmFinalize(true)}
              >
                Finalize report
              </Button>
              {!finalize.allowed && finalize.reason && h.disagreement_count === 0 && (
                <Typography.Text type="secondary">{finalize.reason}</Typography.Text>
              )}
            </>
          )}
          {reopen?.allowed && (
            <Button type="button" onClick={() => setReopening(true)}>
              Reopen report
            </Button>
          )}
          {h.actions?.export?.allowed && (
            <>
              <ExportButton label="Export PDF" start={startExport("pdf")} />
              <ExportButton label="Export Excel" start={startExport("xlsx")} />
            </>
          )}
          <Button type="button" onClick={() => window.print()}>
            Print
          </Button>
        </Space>
        {h.status === "in_progress" && (
          <Typography.Paragraph role="note" style={{ marginTop: 16, marginBottom: 0 }}>
            {h.disagreement_count === 0
              ? "No disagreements are waiting for a final grade."
              : `${h.disagreement_count} disagreements still need a final grade.`}
          </Typography.Paragraph>
        )}
        {missing.length > 0 && (
          <Alert
            type="error"
            showIcon
            role="alert"
            style={{ marginTop: 16 }}
            title="The report is not ready to finalize"
            description={
              <ul>
                {missing.map((f, i) => (
                  <li key={`${f.code}-${i}`}>{f.message}</li>
                ))}
              </ul>
            }
          />
        )}
      </Card>

      {(me.user.role === "admin" || isChair) && h.status === "in_progress" && (
        <ChairPanel tenderId={tenderId!} isChair={isChair} />
      )}

      <Section title="Overview">
        {ranking.length === 0 ? (
          <EmptyState title="No results yet">
            Results open for a vendor once every member has submitted it.
          </EmptyState>
        ) : (
          <Table<(typeof ranking)[number]>
            rowKey="tender_vendor_id"
            components={rowHeaderComponents}
            dataSource={ranking}
            pagination={false}
            scroll={{ x: "max-content" }}
            title={() => <Typography.Text strong>Vendors ranked by final score</Typography.Text>}
            columns={[
              {
                title: "Rank",
                render: (_, v) =>
                  v.outcome === "rejected_at_screening" || !v.available ? "-" : (v.rank ?? "-"),
              },
              { title: "Vendor", onCell: rowHeader, render: (_, v) => v.vendor_name },
              {
                title: "Final score",
                onCell: (v) => ({
                  colSpan: v.outcome === "rejected_at_screening" || !v.available ? 6 : 1,
                }),
                render: (_, v) =>
                  v.outcome === "rejected_at_screening"
                    ? `Rejected at screening${v.reason ? `: ${v.reason}` : ""}`
                    : !v.available
                      ? `Results open once all members submit.${
                          v.awaiting && v.awaiting.length > 0
                            ? ` Waiting for ${v.awaiting.map((a) => a.display_name).join(", ")}.`
                            : ""
                        }`
                      : `${v.final_score ?? "-"}%`,
              },
              {
                title: "Compliance",
                onCell: (v) => ({
                  colSpan: v.outcome === "rejected_at_screening" || !v.available ? 0 : 1,
                }),
                render: (_, v) => `${v.compliance_percent ?? "-"}%`,
              },
              {
                title: "Mandatory",
                onCell: (v) => ({
                  colSpan: v.outcome === "rejected_at_screening" || !v.available ? 0 : 1,
                }),
                render: (_, v) =>
                  v.fails_mandatory ? (
                    <StatusChip tone="error" label={`✕ ${v.mandatory_failures} failed (flag)`} />
                  ) : (
                    "None failed"
                  ),
              },
              {
                title: "Grades (full / partial / non)",
                onCell: (v) => ({
                  colSpan: v.outcome === "rejected_at_screening" || !v.available ? 0 : 1,
                }),
                render: (_, v) =>
                  `${String(v.counts?.fully_compliant ?? 0)} / ${String(
                    v.counts?.partially_compliant ?? 0,
                  )} / ${String(v.counts?.non_compliant ?? 0)}`,
              },
              {
                title: "Agreement",
                onCell: (v) => ({
                  colSpan: v.outcome === "rejected_at_screening" || !v.available ? 0 : 1,
                }),
                render: (_, v) => `${v.agreement_level ?? "-"}%`,
              },
              {
                title: "AI overridden",
                onCell: (v) => ({
                  colSpan: v.outcome === "rejected_at_screening" || !v.available ? 0 : 1,
                }),
                render: (_, v) =>
                  overrideBy.has(v.tender_vendor_id)
                    ? `${overrideBy.get(v.tender_vendor_id)}%`
                    : "-",
              },
            ]}
          />
        )}
      </Section>

      <Section title="Matrix">
        <Flex
          gap={16}
          wrap
          align="center"
          role="search"
          aria-label="Matrix filters"
          style={{ marginBottom: 16 }}
        >
          <label htmlFor="matrix-vendor">Vendor</label>
          <Select
            id="matrix-vendor"
            value={filters.tenderVendorId}
            onChange={(v) => setFilters({ ...filters, tenderVendorId: v })}
            style={{ minWidth: 180 }}
            options={[
              { value: "", label: "All vendors" },
              ...vendorOptions.map((v) => ({ value: v.tender_vendor_id, label: v.name })),
            ]}
          />
          <label htmlFor="matrix-importance">Importance</label>
          <Select
            id="matrix-importance"
            value={filters.importance}
            onChange={(v) => setFilters({ ...filters, importance: v })}
            style={{ minWidth: 140 }}
            options={[
              { value: "", label: "Any" },
              ...Object.entries(importanceLabel).map(([v, l]) => ({ value: v, label: l })),
            ]}
          />
          <label htmlFor="matrix-grade">Grade</label>
          <Select
            id="matrix-grade"
            value={filters.grade}
            onChange={(v) => setFilters({ ...filters, grade: v })}
            style={{ minWidth: 180 }}
            options={[
              { value: "", label: "Any" },
              ...Object.entries(gradeLabel).map(([v, l]) => ({ value: v, label: l })),
            ]}
          />
          <Checkbox
            checked={filters.disagreement}
            onChange={(e) => setFilters({ ...filters, disagreement: e.target.checked })}
          >
            Disagreements only ({h.disagreement_count})
          </Checkbox>
        </Flex>
        {matrix.state.status === "loading" ? (
          <Loading label="Loading the matrix" />
        ) : !m || m.rows.length === 0 ? (
          <EmptyState title="No rows">Nothing matches these filters.</EmptyState>
        ) : (
          <Table<(typeof m.rows)[number]>
            rowKey={(r) => r.requirement.id}
            components={rowHeaderComponents}
            dataSource={m.rows}
            pagination={false}
            scroll={{ x: "max-content" }}
            title={() => (
              <Typography.Text strong>
                Final grade of each requirement for each vendor
              </Typography.Text>
            )}
            columns={[
              { title: "No", onCell: rowHeader, render: (_, r) => r.requirement.display_no },
              {
                title: "Requirement",
                render: (_, r) => (
                  <>
                    {r.requirement.text}{" "}
                    <Typography.Text type="secondary">
                      {importanceLabel[r.requirement.importance] ?? r.requirement.importance}
                    </Typography.Text>
                  </>
                ),
              },
              ...m.vendors.map((v) => ({
                title: v.name,
                key: v.tender_vendor_id,
                render: (_: unknown, r: (typeof m.rows)[number]) => {
                  const c = r.cells.find((x) => x.tender_vendor_id === v.tender_vendor_id);
                  if (!c) return null;
                  return !c.available ? (
                    <Typography.Text type="secondary">Not available yet</Typography.Text>
                  ) : (
                    <AntButton
                      type="link"
                      style={{ padding: 0, height: "auto" }}
                      onClick={() =>
                        setCellOf({
                          requirementId: r.requirement.id,
                          tenderVendorId: c.tender_vendor_id,
                        })
                      }
                    >
                      <span className="visually-hidden">
                        Open {r.requirement.display_no} for {v.name}:{" "}
                      </span>
                      {c.has_disagreement && <StatusChip tone="warning" label="Disagreement" />}{" "}
                      <Grade grade={c.final_grade} />
                    </AntButton>
                  );
                },
              })),
            ]}
          />
        )}
      </Section>

      {outcomes.state.status === "ready" && outcomes.state.data.length > 0 && (
        <Section title="Rejected at screening">
          <ul>
            {outcomes.state.data.map((o) => (
              <li key={o.tender_vendor_id}>
                <strong>{o.vendor_name}</strong>
                {o.reason ? `: ${o.reason}` : ""}
              </li>
            ))}
          </ul>
        </Section>
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
            // Changing a recorded decision names the version that was read (G15).
            header: d?.row_version != null ? { "If-Match": `"${d.row_version}"` } : {},
          },
          body: { final_grade: grade as S["Grade"], justification: why.trim() },
        }),
      );
      announce("The final grade was recorded");
      onDecided();
      onClose();
    } catch (e) {
      if (e instanceof ApiError && (e.status === 412 || e.status === 428)) {
        announce("Someone else changed this grade. It was reloaded; check it and record again.");
        detail.reload();
      } else {
        announce(e instanceof Error ? e.message : "The final grade could not be recorded");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Drawer
      open
      size={640}
      onClose={onClose}
      title={d ? `${d.requirement.display_no}: ${d.requirement.text}` : "Requirement"}
      extra={<Button onClick={onClose}>Close</Button>}
    >
      <Typography.Paragraph type="secondary">
        Each member&apos;s grade and note, the AI&apos;s grade and the evidence behind it.
      </Typography.Paragraph>
      {detail.state.status === "loading" && <Loading label="Loading the cell" />}
      {detail.state.status === "error" && (
        <ErrorNotice error={detail.state.error} onRetry={detail.reload} />
      )}
      {d && (
        <>
          <Typography.Paragraph>
            Final grade: <Grade grade={d.final_grade} />{" "}
            {d.basis && (
              <Typography.Text type="secondary">
                ({d.basis === "all_agree" ? "all members agree" : "Chair's decision"})
              </Typography.Text>
            )}
          </Typography.Paragraph>
          {d.justification && (
            <Typography.Paragraph>
              <strong>Chair&apos;s justification:</strong> {d.justification}
              {d.decided_by ? ` (${d.decided_by.display_name}` : ""}
              {d.decided_by && d.decided_at
                ? `, ${new Date(d.decided_at).toLocaleString()})`
                : d.decided_by
                  ? ")"
                  : ""}
            </Typography.Paragraph>
          )}
          {(d.history?.length ?? 0) > 0 && (
            <section aria-label="Earlier decisions">
              <Typography.Title level={3} style={{ fontSize: 16 }}>
                Earlier decisions
              </Typography.Title>
              <ul>
                {d.history!.map((h, i) => (
                  <li key={i}>
                    <Grade grade={h.final_grade} /> {h.justification}{" "}
                    <Typography.Text type="secondary">
                      {h.decided_by ? `${h.decided_by.display_name}, ` : ""}
                      {h.decided_at ? `decided ${new Date(h.decided_at).toLocaleString()}, ` : ""}
                      replaced {new Date(h.superseded_at).toLocaleString()}
                    </Typography.Text>
                  </li>
                ))}
              </ul>
            </section>
          )}
          {d.ai && (
            <section aria-label="AI grade">
              <Typography.Title level={3} style={{ fontSize: 16 }}>
                AI
              </Typography.Title>
              <Typography.Paragraph>
                <Grade grade={d.ai.grade} /> {d.ai.rationale_summary}
              </Typography.Paragraph>
              {d.ai.citations.length > 0 && (
                <ul>
                  {d.ai.citations.map((c) => (
                    <li key={c.citation_no}>
                      <Link to={`/documents/${c.document_version_id}/view?passage=${c.passage_id}`}>
                        Page {c.page_no}
                      </Link>
                      : {c.excerpt}
                    </li>
                  ))}
                </ul>
              )}
            </section>
          )}
          <Typography.Title level={3} style={{ fontSize: 16 }}>
            Members
          </Typography.Title>
          <ul>
            {d.member_grades.map((g) => (
              <li key={g.member.id}>
                <strong>{g.member.display_name}</strong>: <Grade grade={g.grade} />{" "}
                <Typography.Text type="secondary">
                  {reviewStateLabel[g.review_state ?? "not_reviewed"] ?? ""}
                </Typography.Text>
                {g.note && <div>{g.note}</div>}
              </li>
            ))}
          </ul>
          {canDecide && (differs || settled) && (
            <section aria-label="Chair's final grade">
              <Typography.Title level={3} style={{ fontSize: 16 }}>
                Chair&apos;s final grade
              </Typography.Title>
              <fieldset style={{ border: 0, padding: 0 }}>
                <legend>Final grade</legend>
                <Radio.Group
                  value={grade || undefined}
                  onChange={(e) => setGrade(e.target.value as string)}
                  style={{ display: "flex", flexDirection: "column", gap: 4, marginBottom: 16 }}
                >
                  {Object.entries(gradeLabel).map(([v, l]) => (
                    <Radio key={v} value={v}>
                      {l}
                    </Radio>
                  ))}
                </Radio.Group>
              </fieldset>
              <label htmlFor="why" style={{ display: "block", fontWeight: 500, marginBottom: 4 }}>
                Justification (required)
              </label>
              <Input.TextArea
                id="why"
                rows={3}
                value={why}
                onChange={(e) => setWhy(e.target.value)}
              />
              <Typography.Paragraph type="secondary">
                At least {min} characters, so the report says why.
              </Typography.Paragraph>
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
    </Drawer>
  );
}
