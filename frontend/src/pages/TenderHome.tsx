import { Alert, Card, Space, Steps, Typography } from "antd";
import { useParams } from "react-router";
import { EmptyState, ErrorNotice, LinkButton, Loading, StatusChip } from "../components/ui";
import { ApiError, unwrap, useApi } from "../lib/api";
import { TENDER_STAGES, tenderStatusLabel, tenderStatusTone } from "../lib/labels";
import type { Tender } from "../lib/types";
import { useAsync } from "../lib/useAsync";
import { useSession } from "../app/Session";

/** The tender header band shared by every screen inside a tender (UX-GEN-08). */
export function TenderHeader({ tender }: { tender: Tender }) {
  const stageIndex = TENDER_STAGES.indexOf(tender.status);
  const step = tender.next_step;
  return (
    <header style={{ marginBottom: 16 }}>
      <Typography.Title level={1} style={{ fontSize: 24, marginTop: 0 }}>
        {tender.name}{" "}
        <StatusChip
          label={tenderStatusLabel[tender.status]}
          tone={tenderStatusTone[tender.status]}
        />
      </Typography.Title>
      <div role="group" aria-label="Stage" style={{ margin: "16px 0" }}>
        <Steps
          size="small"
          current={stageIndex}
          items={TENDER_STAGES.map((s) => ({ title: tenderStatusLabel[s] }))}
        />
      </div>
      {step && <Alert type="info" showIcon role="note" title={step.text} />}
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

/** PG-09 Tender home (T3). Documents, requirements and vendors arrive with their phases. */
export function TenderHome() {
  const { tenderId } = useParams();
  const { state, reload } = useTender(tenderId);
  const { me } = useSession();

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
  return (
    <>
      <TenderHeader tender={t} />
      <Card title="Committee" style={{ marginBottom: 16 }}>
        <Space direction="vertical" size={8} role="list">
          {(t.members ?? []).map((m) => (
            <div key={m.user.id} role="listitem">
              {m.user.display_name} {m.is_chair && <StatusChip label="Chair" tone="info" />}
            </div>
          ))}
        </Space>
        {t.description && (
          <Typography.Paragraph style={{ marginTop: 16 }}>{t.description}</Typography.Paragraph>
        )}
        {t.due_date && <Typography.Paragraph>Evaluation due {t.due_date}</Typography.Paragraph>}
      </Card>
      {isAdmin && (
        <p>
          <LinkButton to={`/tenders/${t.id}/edit`}>Edit tender</LinkButton>
        </p>
      )}
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
