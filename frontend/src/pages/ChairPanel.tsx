import { Button as AntButton, Space, Table, Typography } from "antd";
import { useState } from "react";
import { ExportButton } from "../components/ExportButton";
import {
  Button,
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
import { evaluationStatusLabel, evaluationStatusTone, type Tone } from "../lib/labels";
import { useAsync } from "../lib/useAsync";
import { usePush } from "../lib/usePush";
import { useSession } from "../app/Session";

type Action = { kind: "reopen" | "exclude"; evaluationId: string; who: string; vendor: string };

/**
 * The Chair's panel on the progress board (UX-RPT-02, UX-EVA-13, UX-EVA-06): who has submitted
 * which vendor, reopen or exclude an evaluation with a reason, remind those who are late, and
 * export a vendor's clarification list. The administrator sees the same matrix without the
 * controls. It shows status only, never grades.
 */
export function ChairPanel({ tenderId, isChair }: { tenderId: string; isChair: boolean }) {
  const api = useApi();
  const announce = useAnnounce();
  const { me } = useSession();
  const [action, setAction] = useState<Action | null>(null);
  const [busy, setBusy] = useState(false);
  const matrix = useAsync(
    () =>
      unwrap(api.GET("/tenders/{tenderId}/report/progress", { params: { path: { tenderId } } })),
    [api, tenderId],
  );
  usePush(tenderId, matrix.reload, [
    "evaluation.updated",
    "tender.changed",
    "notification.created",
  ]);

  if (matrix.state.status === "loading")
    return <Loading label="Loading the committee's progress" />;
  if (matrix.state.status === "error") {
    const e = matrix.state.error;
    if (e instanceof ApiError && (e.status === 403 || e.status === 404)) return null;
    return <ErrorNotice error={e} onRetry={matrix.reload} />;
  }
  const m = matrix.state.data;
  if (m.vendors.length === 0) return null;

  async function confirm(note: string) {
    if (!action) return;
    setBusy(true);
    try {
      await unwrap(
        api.POST(
          action.kind === "reopen"
            ? "/member-evaluations/{memberEvaluationId}/reopen"
            : "/member-evaluations/{memberEvaluationId}/exclude",
          {
            params: { path: { memberEvaluationId: action.evaluationId } },
            body: { reason: note },
          },
        ),
      );
      announce(
        action.kind === "reopen" ? "The evaluation was reopened" : "The member was excluded",
      );
      setAction(null);
      matrix.reload();
    } catch (e) {
      announce(e instanceof Error ? e.message : "The change could not be saved");
    } finally {
      setBusy(false);
    }
  }

  async function remind() {
    try {
      await unwrap(
        api.POST("/tenders/{tenderId}/report/reminders", {
          params: { path: { tenderId } },
          body: {},
        }),
      );
      announce("Reminders were sent to the members who have not submitted");
    } catch (e) {
      announce(e instanceof Error ? e.message : "Reminders could not be sent");
    }
  }

  const cell = (tv: string, user: string) =>
    m.cells.find((c) => c.tender_vendor_id === tv && c.user_id === user);

  type Member = (typeof m.members)[number];
  return (
    <Section title="Evaluations by member">
      <Table<Member>
        rowKey="id"
        components={rowHeaderComponents}
        dataSource={m.members}
        pagination={false}
        scroll={{ x: "max-content" }}
        title={() => <Typography.Text strong>Who has submitted which vendor</Typography.Text>}
        columns={[
          { title: "Member", onCell: rowHeader, render: (_, mem) => mem.display_name },
          ...m.vendors.map((v) => ({
            title: v.name,
            key: v.tender_vendor_id,
            render: (_: unknown, mem: Member) => {
              const c = cell(v.tender_vendor_id, mem.id);
              if (!c) return "-";
              const label = c.excluded ? "Excluded" : (evaluationStatusLabel[c.status] ?? c.status);
              const tone: Tone = c.excluded
                ? "neutral"
                : ((evaluationStatusTone[c.status] ?? "neutral") as Tone);
              return (
                <Space size={4} wrap>
                  <StatusChip label={label} tone={tone} />
                  {isChair && c.member_evaluation_id && !c.excluded && (
                    <>
                      {c.status === "submitted" && (
                        <AntButton
                          type="link"
                          size="small"
                          aria-label={`Reopen ${mem.display_name}'s evaluation of ${v.name}`}
                          onClick={() =>
                            setAction({
                              kind: "reopen",
                              evaluationId: c.member_evaluation_id!,
                              who: mem.display_name,
                              vendor: v.name,
                            })
                          }
                        >
                          Reopen
                        </AntButton>
                      )}
                      <AntButton
                        type="link"
                        size="small"
                        aria-label={`Exclude ${mem.display_name} from ${v.name}`}
                        onClick={() =>
                          setAction({
                            kind: "exclude",
                            evaluationId: c.member_evaluation_id!,
                            who: mem.display_name,
                            vendor: v.name,
                          })
                        }
                      >
                        Exclude
                      </AntButton>
                    </>
                  )}
                </Space>
              );
            },
          })),
        ]}
      />
      {(isChair || me.user.role === "admin") && (
        <Typography.Paragraph style={{ marginTop: 16 }}>
          <Button type="button" onClick={() => void remind()}>
            Remind members who have not submitted
          </Button>
        </Typography.Paragraph>
      )}
      <Typography.Title level={3} style={{ fontSize: 16 }}>
        Clarification lists
      </Typography.Title>
      <Space direction="vertical">
        {m.vendors.map((v) => (
          <Space key={v.tender_vendor_id} wrap>
            {v.name}
            <ExportButton
              label="Export clarifications"
              start={() =>
                unwrap(
                  api.POST("/tender-vendors/{tenderVendorId}/evaluation/clarifications/export", {
                    params: {
                      path: { tenderVendorId: v.tender_vendor_id },
                      header: { "Idempotency-Key": crypto.randomUUID() },
                    },
                  }),
                )
              }
            />
          </Space>
        ))}
      </Space>
      {action && (
        <NoteDialog
          open
          onOpenChange={(o) => {
            if (!o) setAction(null);
          }}
          title={action.kind === "reopen" ? "Reopen this evaluation" : "Exclude this member"}
          description={
            action.kind === "reopen"
              ? `${action.who} can change the evaluation of ${action.vendor} again and submits it again.`
              : `${action.who}'s evaluation of ${action.vendor} no longer counts in the report.`
          }
          confirmLabel={action.kind === "reopen" ? "Reopen evaluation" : "Exclude member"}
          minLength={me.client_config.min_note_length}
          busy={busy}
          danger={action.kind === "exclude"}
          onConfirm={(note) => void confirm(note)}
        />
      )}
    </Section>
  );
}
