import {
  ApartmentOutlined,
  ArrowRightOutlined,
  CheckOutlined,
  FileTextOutlined,
  OrderedListOutlined,
  TeamOutlined,
} from "@ant-design/icons";
import {
  Alert,
  Button,
  Card,
  Col,
  Flex,
  List,
  Row,
  Space,
  Statistic,
  Steps,
  Tag,
  Typography,
} from "antd";
import type { ReactNode } from "react";
import { useLocation, useNavigate, useParams } from "react-router";
import { usePush } from "../lib/usePush";
import { ChairPanel } from "./ChairPanel";
import { EmptyState, ErrorNotice, LinkButton, Loading, StatusChip } from "../components/ui";
import { ApiError, unwrap, useApi } from "../lib/api";
import { TENDER_STAGES, tenderStatusLabel, tenderStatusTone } from "../lib/labels";
import type { Tender } from "../lib/types";
import { useAsync } from "../lib/useAsync";
import { useSession } from "../app/Session";

/** The compact band on every screen inside a tender: title, status, facts and the next step. */
export function TenderHeader({ tender }: { tender: Tender }) {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const step = tender.next_step;
  const target = step?.target ? `/tenders/${tender.id}/${step.target}` : null;
  const report =
    tender.report_status === "finalized"
      ? "Report finalized"
      : tender.report_status === "in_progress"
        ? "Report in progress"
        : null;
  return (
    <header
      style={{
        marginBottom: 20,
        paddingBottom: 16,
        borderBottom: "1px solid var(--ant-color-split)",
      }}
    >
      <Flex justify="space-between" align="center" gap={16} wrap>
        <div>
          <Space size={12} align="center">
            <Typography.Title level={1} style={{ fontSize: 22, margin: 0 }}>
              {tender.name}
            </Typography.Title>
            <StatusChip
              label={tenderStatusLabel[tender.status]}
              tone={tenderStatusTone[tender.status]}
            />
          </Space>
          <div>
            <Space size="large" wrap>
              <Typography.Text type="secondary">
                <TeamOutlined /> Committee: {tender.committee_size} members
              </Typography.Text>
              {tender.baseline_version ? (
                <Typography.Text type="secondary">
                  <ApartmentOutlined /> Baseline v{tender.baseline_version}
                </Typography.Text>
              ) : null}
              {report && (
                <Typography.Text type="secondary">
                  <FileTextOutlined /> {report}
                </Typography.Text>
              )}
            </Space>
          </div>
        </div>
        {step && target && pathname !== target && pathname !== `/tenders/${tender.id}` && (
          <Button
            type="primary"
            icon={<ArrowRightOutlined />}
            iconPlacement="end"
            onClick={() => void navigate(target)}
          >
            {step.text}
          </Button>
        )}
      </Flex>
    </header>
  );
}

export function useTender(id: string | undefined) {
  const api = useApi();
  return useAsync(
    () => unwrap(api.GET("/tenders/{tenderId}", { params: { path: { tenderId: id! } } })),
    [api, id],
  );
}

/** PG-09 Tender home: where the tender stands and what happens next. */
export function TenderHome() {
  const { tenderId } = useParams();
  const api = useApi();
  const { state, reload } = useTender(tenderId);
  const { me } = useSession();
  const path = { path: { tenderId: tenderId! } };
  const docs = useAsync(
    () => unwrap(api.GET("/tenders/{tenderId}/documents", { params: path })).catch(() => null),
    [api, tenderId],
  );
  const vendors = useAsync(
    () => unwrap(api.GET("/tenders/{tenderId}/vendors", { params: path })).catch(() => null),
    [api, tenderId],
  );
  const reqs = useAsync(
    () =>
      unwrap(api.GET("/tenders/{tenderId}/requirements/summary", { params: path })).catch(
        () => null,
      ),
    [api, tenderId],
  );
  const board = useAsync(
    () =>
      unwrap(api.GET("/tenders/{tenderId}/report/progress", { params: path })).catch(() => null),
    [api, tenderId],
  );
  usePush(tenderId, () => {
    reload();
    board.reload();
    vendors.reload();
  }, ["tender.changed", "evaluation.updated", "job.updated"]);

  if (state.status === "loading") return <Loading label="Loading the tender" />;
  if (state.status === "error") {
    if (state.error instanceof ApiError && state.error.code === "no_access") {
      return <NoAccess contacts={state.error.problem.access_contacts ?? []} />;
    }
    if (state.error instanceof ApiError && state.error.status === 404) {
      return (
        <EmptyState title="Tender not found">This tender does not exist or was removed.</EmptyState>
      );
    }
    return <ErrorNotice error={state.error} onRetry={reload} />;
  }
  const t = state.data;
  const isAdmin = me.user.role === "admin";
  const isChair = t.chair?.id === me.user.id;
  const docCount = docs.state.status === "ready" ? (docs.state.data?.items.length ?? 0) : 0;
  const vendorItems = vendors.state.status === "ready" ? (vendors.state.data?.items ?? []) : [];
  const accepted = vendorItems.filter((v) => v.status === "accepted").length;
  const reqTotal = reqs.state.status === "ready" ? (reqs.state.data?.total ?? 0) : 0;
  const cells = board.state.status === "ready" ? (board.state.data?.cells ?? []) : null;
  const counted = cells ? cells.filter((c) => !c.excluded) : [];
  const done = counted.filter((c) => c.status === "submitted").length;
  const step = t.next_step;
  const stageIndex = TENDER_STAGES.indexOf(t.status);

  const stat = (title: string, value: string | number, hint: string, icon: ReactNode) => (
    <Col xs={24} sm={12} xl={6}>
      <Card>
        <Statistic title={title} value={value} prefix={icon} />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {hint}
        </Typography.Text>
      </Card>
    </Col>
  );

  return (
    <>
      <TenderHeader tender={t} />
      <Flex justify="space-between" align="flex-start" wrap gap={16} style={{ marginBottom: 16 }}>
        <div>
          <Typography.Title level={2} style={{ fontSize: 20, margin: 0 }}>
            Overview
          </Typography.Title>
          <Typography.Text type="secondary">
            Where this tender stands and what happens next.
          </Typography.Text>
        </div>
        {isAdmin && <LinkButton to={`/tenders/${t.id}/edit`}>Edit tender</LinkButton>}
      </Flex>
      <Row gutter={[16, 16]}>
        {stat("Documents", docCount, "RFP and requirement documents", <FileTextOutlined />)}
        {stat("Vendors", vendorItems.length, `${accepted} accepted at screening`, <TeamOutlined />)}
        {stat(
          "Requirements",
          reqTotal,
          t.baseline_version
            ? `Baseline v${t.baseline_version} confirmed`
            : "Baseline not confirmed",
          <OrderedListOutlined />,
        )}
        {stat(
          "Evaluations",
          cells ? `${done} of ${counted.length}` : "—",
          cells ? "Member submissions" : "Shown to the Chair and the administrator",
          <CheckOutlined />,
        )}
      </Row>
      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} xl={16}>
          <Card
            title="Next action"
            extra={
              <StatusChip label={tenderStatusLabel[t.status]} tone={tenderStatusTone[t.status]} />
            }
          >
            <Alert
              showIcon
              type={t.status === "finalized" ? "success" : "info"}
              role="note"
              title={step?.text ?? "Nothing is waiting"}
              action={
                step?.target ? (
                  <LinkButton variant="primary" to={`/tenders/${t.id}/${step.target}`}>
                    Open
                  </LinkButton>
                ) : undefined
              }
            />
          </Card>
          {(isAdmin || isChair) && (
            <div style={{ marginTop: 16 }}>
              <ChairPanel tenderId={t.id} isChair={isChair} />
            </div>
          )}
        </Col>
        <Col xs={24} xl={8}>
          <Card title="Committee" extra={<Tag>{t.committee_size} members</Tag>}>
            <List
              dataSource={t.members ?? []}
              renderItem={(m) => (
                <List.Item
                  extra={
                    <StatusChip
                      label={m.is_chair ? "Chair" : "Member"}
                      tone={m.is_chair ? "info" : "neutral"}
                    />
                  }
                >
                  {m.user.display_name}
                </List.Item>
              )}
            />
            {t.description && (
              <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
                {t.description}
              </Typography.Paragraph>
            )}
            {t.due_date && (
              <Typography.Text type="secondary">Evaluation due {t.due_date}</Typography.Text>
            )}
          </Card>
          <Card title="Workflow" style={{ marginTop: 16 }}>
            <div role="group" aria-label="Stage">
              <Steps
                orientation="vertical"
                size="small"
                current={stageIndex}
                items={TENDER_STAGES.map((s) => ({ title: tenderStatusLabel[s] }))}
              />
            </div>
          </Card>
        </Col>
      </Row>
    </>
  );
}

/** UX-ROL-04: names who to ask, never a blank page or an error code. */
export function NoAccess({ contacts }: { contacts: { name: string; email?: string | null }[] }) {
  return (
    <Card style={{ maxWidth: 560 }}>
      <Typography.Title level={1} style={{ fontSize: 24, marginTop: 0 }}>
        You do not have access to this tender
      </Typography.Title>
      <Typography.Paragraph>Ask the administrator to add you.</Typography.Paragraph>
      {contacts.length > 0 && (
        <ul>
          {contacts.map((c) => (
            <li key={`${c.name}-${c.email ?? ""}`}>
              {c.name}
              {c.email ? ` (${c.email})` : ""}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
