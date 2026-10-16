import {
  CheckOutlined,
  LockOutlined,
  TeamOutlined,
  UnorderedListOutlined,
} from "@ant-design/icons";
import {
  Alert,
  Avatar,
  Button as AntButton,
  Card,
  Checkbox,
  Col,
  Collapse,
  Descriptions,
  Drawer,
  Flex,
  Input,
  Modal,
  Progress,
  Radio,
  Row,
  Segmented,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from "antd";
import { useState, type ReactNode } from "react";
import { useParams, useSearchParams } from "react-router";
import { ExportButton } from "../components/ExportButton";
import {
  Button,
  ConfirmDialog,
  EmptyState,
  ErrorNotice,
  LinkButton,
  Loading,
  NoteDialog,
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
import { useViewer } from "../app/ViewerHost";
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
  const [vendorPanel, setVendorPanel] = useState<string | null>(null);
  const [method, setMethod] = useState(false);

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
  // One column per vendor that has a cell in the matrix, even when the server's vendor list
  // leaves it out (a proposal that is not yet marked evaluated); the name comes from the ranking.
  const vendorOptions: { tender_vendor_id: string; name: string }[] = [...(m?.vendors ?? [])];
  for (const row of m?.rows ?? []) {
    for (const c of row.cells) {
      if (!vendorOptions.some((v) => v.tender_vendor_id === c.tender_vendor_id)) {
        const known = ranking.find((r) => r.tender_vendor_id === c.tender_vendor_id);
        vendorOptions.push({
          tender_vendor_id: c.tender_vendor_id,
          name: known?.vendor_name ?? "Vendor",
        });
      }
    }
  }

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

  const ranked = ranking.filter((v) => v.available && v.outcome !== "rejected_at_screening");
  const waiting = ranking.filter((v) => !v.available && v.outcome !== "rejected_at_screening");
  const rejected = ranking.filter((v) => v.outcome === "rejected_at_screening");
  const failing = ranked.filter((v) => v.fails_mandatory);
  const panel = ranking.find((v) => v.tender_vendor_id === vendorPanel) ?? null;
  const jump = (id: string) =>
    document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
  const locked = h.status !== "in_progress";
  const versionList = versions.state.status === "ready" ? versions.state.data : [];
  const grades = (v: (typeof ranking)[number]) =>
    `${String(v.counts?.fully_compliant ?? 0)} full · ${String(
      v.counts?.partially_compliant ?? 0,
    )} partial · ${String(v.counts?.non_compliant ?? 0)} non`;

  return (
    <div className="report">
      <TenderHeader tender={tender.state.data} />
      <Flex justify="space-between" align="flex-start" wrap gap={16} style={{ marginBottom: 16 }}>
        <div aria-label="Report status">
          <Typography.Title level={2} style={{ fontSize: 22, margin: 0 }}>
            Final evaluation report{" "}
            <StatusChip
              label={statusLabel[h.status] ?? h.status}
              tone={
                h.status === "finalized"
                  ? "success"
                  : h.status === "superseded"
                    ? "neutral"
                    : "info"
              }
            />
          </Typography.Title>
          <Typography.Text type="secondary">
            <span>Report version {h.version_no}</span>
            {h.finalized_at
              ? ` · Finalized ${new Date(h.finalized_at).toLocaleString()}${
                  h.finalized_by ? ` · By ${h.finalized_by.display_name}` : ""
                }`
              : " · In progress"}
          </Typography.Text>
        </div>
        <Space wrap className="report-actions">
          {versionList.length > 1 && (
            <>
              <label htmlFor="report-version">Version</label>
              <Select
                id="report-version"
                value={h.version_no}
                onChange={(v) => setParams({ version: String(v) })}
                style={{ minWidth: 190 }}
                options={versionList.map((v) => ({
                  value: v.version_no,
                  label: `${v.version_no} · ${statusLabel[v.status] ?? v.status}`,
                }))}
              />
            </>
          )}
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
      </Flex>

      <div
        style={{
          position: "sticky",
          top: 56,
          zIndex: 5,
          background: "var(--ant-color-bg-layout)",
          padding: "8px 0",
          marginBottom: 12,
        }}
      >
        <Segmented
          aria-label="Report sections"
          onChange={(v) => jump(String(v))}
          options={[
            { value: "summary", label: "Summary" },
            { value: "ranking", label: "Vendors" },
            { value: "matrix", label: "Requirements" },
            { value: "governance", label: "Governance & audit" },
          ]}
        />
      </div>

      <div id="summary">
        {h.status === "in_progress" && (
          <Alert
            showIcon
            type="info"
            role="note"
            style={{ marginBottom: 12 }}
            title={
              h.disagreement_count === 0
                ? "No disagreements are waiting for a final grade."
                : `${h.disagreement_count} disagreements still need a final grade.`
            }
          />
        )}
        {missing.length > 0 && (
          <Alert
            type="error"
            showIcon
            role="alert"
            style={{ marginBottom: 12 }}
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
        {failing.length > 0 && (
          <Alert
            showIcon
            type="warning"
            title="Mandatory requirement failures · Review before procurement decision"
            description={`${failing
              .map((v) => `${v.vendor_name}: ${v.mandatory_failures}`)
              .join(
                "; ",
              )}. A failure is flagged, not automatically excluded from the weighted ranking. The final score is not a procurement award decision.`}
          />
        )}
      </div>
      <Row gutter={[16, 16]} style={{ margin: "16px 0" }}>
        {(
          [
            [
              "Ranked vendors",
              String(ranked.length),
              `${rejected.length} rejected at screening`,
              <TeamOutlined key="i" />,
            ],
            [
              "Requirements",
              String(m?.rows.length ?? 0),
              `Baseline v${h.baseline_version}`,
              <UnorderedListOutlined key="i" />,
            ],
            [
              "Final disagreements",
              String(h.disagreement_count),
              locked ? "Resolved by Chair" : "Waiting for the Chair",
              <CheckOutlined key="i" />,
            ],
            [
              "Report version",
              `v${h.version_no}`,
              locked ? "Locked record" : "In progress",
              <LockOutlined key="i" />,
            ],
          ] as [string, string, string, ReactNode][]
        ).map(([t, v, hint, icon]) => (
          <Col key={t} xs={24} sm={12} xl={6}>
            <Card>
              <Statistic title={t} value={v} prefix={icon} />
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {hint}
              </Typography.Text>
            </Card>
          </Col>
        ))}
      </Row>

      {(me.user.role === "admin" || isChair) && h.status === "in_progress" && (
        <ChairPanel tenderId={tenderId!} isChair={isChair} />
      )}

      <div id="ranking">
        <Card
          title="Vendor comparison"
          style={{ marginBottom: 16 }}
          extra={<Button onClick={() => setMethod(true)}>How scoring works</Button>}
        >
          {ranking.length === 0 ? (
            <EmptyState title="No results yet">
              Results open for a vendor once every member has submitted it.
            </EmptyState>
          ) : (
            <>
              <Typography.Paragraph type="secondary">
                Final scores ranked by the configured weighted methodology.
              </Typography.Paragraph>
              <Row gutter={[16, 16]}>
                <Col xs={24} lg={12}>
                  <Flex vertical gap={12}>
                    {ranked.map((v, i) => (
                      <Card key={v.tender_vendor_id} size="small">
                        <Flex justify="space-between" align="center" gap={12}>
                          <Space>
                            <Avatar
                              style={{
                                background: i
                                  ? "var(--ant-color-fill-secondary)"
                                  : "var(--ant-color-primary-bg)",
                                color: "var(--ant-color-text)",
                              }}
                            >
                              {v.rank ?? i + 1}
                            </Avatar>
                            <div>
                              <Typography.Text strong>{v.vendor_name}</Typography.Text>
                              <br />
                              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                Rank {v.rank ?? i + 1} · {v.mandatory_failures ?? 0} mandatory{" "}
                                {v.mandatory_failures === 1 ? "failure" : "failures"}
                              </Typography.Text>
                            </div>
                          </Space>
                          <Space>
                            <Typography.Text strong style={{ fontSize: 20 }}>
                              {v.final_score ?? "-"}%
                            </Typography.Text>
                            <AntButton
                              size="small"
                              aria-label={`Details of ${v.vendor_name}`}
                              onClick={() => setVendorPanel(v.tender_vendor_id)}
                            >
                              Details
                            </AntButton>
                          </Space>
                        </Flex>
                        <Progress
                          percent={Number(v.final_score ?? 0)}
                          showInfo={false}
                          style={{ marginBottom: 0 }}
                          aria-label={`Final score of ${v.vendor_name}`}
                        />
                      </Card>
                    ))}
                    {waiting.map((v) => (
                      <Card key={v.tender_vendor_id} size="small">
                        <Typography.Text strong>{v.vendor_name}</Typography.Text>
                        <br />
                        <Typography.Text type="secondary">
                          Results open once all members submit.
                          {v.awaiting && v.awaiting.length > 0
                            ? ` Waiting for ${v.awaiting.map((a) => a.display_name).join(", ")}.`
                            : ""}
                        </Typography.Text>
                      </Card>
                    ))}
                  </Flex>
                </Col>
                <Col xs={24} lg={12}>
                  <Typography.Text strong>Score comparison</Typography.Text>
                  {ranked.map((v) => (
                    <Flex key={v.tender_vendor_id} align="center" gap={12}>
                      <span style={{ width: 120 }}>{v.vendor_name}</span>
                      <Progress
                        percent={Number(v.final_score ?? 0)}
                        style={{ flex: 1 }}
                        aria-label={`Score of ${v.vendor_name}`}
                      />
                    </Flex>
                  ))}
                  <Descriptions
                    size="small"
                    column={1}
                    style={{ marginTop: 8 }}
                    items={ranked.map((v) => ({
                      key: v.tender_vendor_id,
                      label: `Compliance · ${v.vendor_name}`,
                      children: (
                        <>
                          <b>{v.compliance_percent ?? "-"}%</b>
                          {overrideBy.has(v.tender_vendor_id)
                            ? ` · AI overridden ${overrideBy.get(v.tender_vendor_id)}%`
                            : ""}
                        </>
                      ),
                    }))}
                  />
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    Rank and compliance are different metrics; failure counts remain visible.
                  </Typography.Text>
                </Col>
              </Row>
            </>
          )}
        </Card>
      </div>

      <div id="matrix">
        <Card
          title="Requirements compliance · head to head"
          extra={<Tag>{m?.rows.length ?? 0} shown</Tag>}
          style={{ marginBottom: 16 }}
        >
          <Typography.Paragraph type="secondary">
            Select any grade to inspect the AI suggestion, human review, final decision and
            citation.
          </Typography.Paragraph>
          <Flex
            gap={12}
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
              style={{ minWidth: 190 }}
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
            <Button
              onClick={() =>
                setFilters({ tenderVendorId: "", importance: "", grade: "", disagreement: false })
              }
            >
              Clear filters
            </Button>
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
                      {r.requirement.text}
                      <br />
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {importanceLabel[r.requirement.importance] ?? r.requirement.importance}
                      </Typography.Text>
                    </>
                  ),
                },
                ...vendorOptions.map((v) => ({
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
        </Card>
      </div>

      <div id="governance">
        <Typography.Title level={3} style={{ fontSize: 18 }}>
          Governance & audit
        </Typography.Title>
        <Collapse
          items={[
            {
              key: "scr",
              label: (
                <Space>
                  Proposal screening outcomes{" "}
                  <Tag color={rejected.length ? "warning" : "success"}>
                    {ranking.length - rejected.length} accepted
                    {rejected.length ? ` · ${rejected.length} rejected` : ""}
                  </Tag>
                </Space>
              ),
              children: (
                <>
                  {outcomes.state.status === "ready" && outcomes.state.data.length > 0 ? (
                    <ul>
                      {outcomes.state.data.map((o) => (
                        <li key={o.tender_vendor_id}>
                          <strong>{o.vendor_name}</strong> · Rejected at screening
                          {o.reason ? `: ${o.reason}` : ""}
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <Typography.Paragraph>
                      Every vendor passed proposal screening, with the committee recommendations and
                      the Chair decision on record.
                    </Typography.Paragraph>
                  )}
                  <LinkButton to={`/tenders/${tenderId}/screening`}>
                    Review screening records
                  </LinkButton>
                </>
              ),
            },
            {
              key: "his",
              label: (
                <Space>
                  Committee sign-off and decision history <Tag>{h.committee.length} members</Tag>
                </Space>
              ),
              children: (
                <>
                  <Typography.Paragraph>
                    Committee: {h.committee.map((c) => c.display_name).join(", ")}.{" "}
                    {h.disagreement_count} final disagreements
                    {locked ? " were resolved by the Chair" : " wait for the Chair"}. Protected
                    grades are never shown to unauthorised roles.
                  </Typography.Paragraph>
                  {me.user.role === "admin" && (
                    <LinkButton to="/audit">Open audit trail</LinkButton>
                  )}
                </>
              ),
            },
            {
              key: "met",
              label: (
                <Space>
                  Scoring methodology and report metadata <Tag>Baseline v{h.baseline_version}</Tag>
                </Space>
              ),
              children: (
                <Descriptions
                  size="small"
                  column={{ xs: 1, md: 2 }}
                  items={[
                    {
                      key: "w",
                      label: "Weighted scoring",
                      children: `Fully compliant = ${h.scoring_model.points_fully}, partially = ${h.scoring_model.points_partially}, non-compliant = ${h.scoring_model.points_non}. Mandatory failures remain flagged.`,
                    },
                    {
                      key: "v",
                      label: "Source and version",
                      children: `Baseline v${h.baseline_version} · Report v${h.version_no}${
                        h.ai_model_versions.length
                          ? ` · AI model ${h.ai_model_versions.join(", ")}`
                          : ""
                      }`,
                    },
                  ]}
                />
              ),
            },
          ]}
        />
      </div>

      {locked ? (
        <Alert
          style={{ marginTop: 16 }}
          showIcon
          type="success"
          icon={<LockOutlined />}
          title={`${h.status === "superseded" ? "Superseded" : "Finalized"} record · Read-only`}
          description="The report is an immutable version. Reopening requires a reason and produces a new report version. Export is allowed; direct editing is not."
          action={
            reopen?.allowed ? (
              <Button type="button" onClick={() => setReopening(true)}>
                Reopen report
              </Button>
            ) : undefined
          }
        />
      ) : null}

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

      <Drawer
        open={panel !== null}
        onClose={() => setVendorPanel(null)}
        size={460}
        title={panel ? `${panel.vendor_name} · Vendor details` : ""}
      >
        {panel && (
          <Flex vertical gap={16}>
            {panel.fails_mandatory ? (
              <Alert
                showIcon
                type="warning"
                title="Mandatory failures"
                description={`${panel.mandatory_failures} mandatory requirement${
                  (panel.mandatory_failures ?? 0) > 1 ? "s" : ""
                } failed. This does not automatically exclude the vendor.`}
              />
            ) : (
              <Alert showIcon type="success" title="No mandatory requirement failed" />
            )}
            <Row gutter={12}>
              <Col span={12}>
                <Card size="small">
                  <Statistic title="Final score" value={`${panel.final_score ?? "-"}%`} />
                </Card>
              </Col>
              <Col span={12}>
                <Card size="small">
                  <Statistic title="Compliance" value={`${panel.compliance_percent ?? "-"}%`} />
                </Card>
              </Col>
            </Row>
            <Descriptions
              bordered
              size="small"
              column={1}
              items={[
                { key: "d", label: "Grade distribution", children: grades(panel) },
                { key: "a", label: "Agreement", children: `${panel.agreement_level ?? "-"}%` },
              ]}
            />
            <Button
              variant="primary"
              onClick={() => {
                setFilters({ ...filters, tenderVendorId: panel.tender_vendor_id });
                setVendorPanel(null);
                jump("matrix");
              }}
            >
              View requirement results
            </Button>
          </Flex>
        )}
      </Drawer>

      <Modal
        open={method}
        onCancel={() => setMethod(false)}
        footer={<Button onClick={() => setMethod(false)}>Close</Button>}
        title="Scoring methodology"
      >
        <Typography.Paragraph>
          Fully compliant = {h.scoring_model.points_fully}, partially compliant ={" "}
          {h.scoring_model.points_partially}, non-compliant = {h.scoring_model.points_non}.
          Mandatory failures remain visible and do not automatically remove a vendor from the
          ranking.
        </Typography.Paragraph>
      </Modal>

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
  const viewer = useViewer();
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
                      <AntButton
                        type="link"
                        style={{ padding: 0 }}
                        onClick={() => viewer.open(c.document_version_id, c.passage_id)}
                      >
                        Page {c.page_no}
                      </AntButton>
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
