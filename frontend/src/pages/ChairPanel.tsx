import { useState } from "react";
import { ExportButton } from "../components/ExportButton";
import {
  Button,
  ErrorNotice,
  Loading,
  NoteDialog,
  StatusChip,
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

  return (
    <section aria-labelledby="chair-panel">
      <h2 id="chair-panel">Evaluations by member</h2>
      <div className="table-wrap">
        <table>
          <caption>Who has submitted which vendor</caption>
          <thead>
            <tr>
              <th scope="col">Member</th>
              {m.vendors.map((v) => (
                <th scope="col" key={v.tender_vendor_id}>
                  {v.name}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {m.members.map((mem) => (
              <tr key={mem.id}>
                <th scope="row">{mem.display_name}</th>
                {m.vendors.map((v) => {
                  const c = cell(v.tender_vendor_id, mem.id);
                  if (!c) return <td key={v.tender_vendor_id}>-</td>;
                  const label = c.excluded
                    ? "Excluded"
                    : (evaluationStatusLabel[c.status] ?? c.status);
                  const tone: Tone = c.excluded
                    ? "neutral"
                    : ((evaluationStatusTone[c.status] ?? "neutral") as Tone);
                  return (
                    <td key={v.tender_vendor_id}>
                      <StatusChip label={label} tone={tone} />
                      {isChair && c.member_evaluation_id && !c.excluded && (
                        <>
                          {c.status === "submitted" && (
                            <>
                              {" "}
                              <button
                                type="button"
                                className="link"
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
                              </button>
                            </>
                          )}{" "}
                          <button
                            type="button"
                            className="link"
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
                          </button>
                        </>
                      )}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {(isChair || me.user.role === "admin") && (
        <p>
          <Button type="button" onClick={() => void remind()}>
            Remind members who have not submitted
          </Button>
        </p>
      )}
      <h3>Clarification lists</h3>
      <ul className="plain">
        {m.vendors.map((v) => (
          <li key={v.tender_vendor_id}>
            {v.name}{" "}
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
          </li>
        ))}
      </ul>
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
    </section>
  );
}
