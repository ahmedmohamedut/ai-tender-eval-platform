import { Checkbox, Radio, Space, Table, Typography, Input } from "antd";
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
  Section,
  StatusChip,
  rowHeader,
  rowHeaderComponents,
  useAnnounce,
} from "../components/ui";
import { OpenProposalFile } from "../components/OpenProposalFile";
import { useSession } from "../app/Session";
import { ApiError, unwrap, useApi } from "../lib/api";
import {
  proposalStatusLabel,
  proposalStatusTone,
  recommendationLabel,
  recommendationTone,
} from "../lib/labels";
import { usePush } from "../lib/usePush";
import { useAsync } from "../lib/useAsync";
import { TenderHeader, useTender } from "./TenderHome";
import type { components } from "../api/generated/schema";

type State = components["schemas"]["ScreeningState"];
type Recommendation = components["schemas"]["Recommendation"];

/** PG-14 Proposal screening (T4 review table with a side panel): UX-SCR-01..07. */
export function Screening() {
  const { tenderId, tenderVendorId } = useParams();
  const api = useApi();
  const announce = useAnnounce();
  const { me } = useSession();
  const minNote = me.client_config.min_note_length;
  const tender = useTender(tenderId);
  const vendor = useAsync(
    () =>
      unwrap(
        api.GET("/tenders/{tenderId}/vendors", {
          params: { path: { tenderId: tenderId! } },
        }),
      ),
    [api, tenderId],
  );
  const screening = useAsync(
    () =>
      unwrap(
        api.GET("/tender-vendors/{tenderVendorId}/screening", {
          params: { path: { tenderVendorId: tenderVendorId! } },
        }),
      ),
    [api, tenderVendorId],
  );
  usePush(tenderId, screening.reload, ["tender.changed", "job.updated"]);

  const [checks, setChecks] = useState<Record<string, { checked: boolean; note: string }>>({});
  const [rec, setRec] = useState<Recommendation | "">("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | undefined>();
  const [deciding, setDeciding] = useState<Recommendation | null>(null);
  const [reinstating, setReinstating] = useState(false);

  const data: State | undefined =
    screening.state.status === "ready" ? screening.state.data : undefined;

  // The form follows the server state: when a new state arrives it is copied into the fields.
  const [syncedFor, setSyncedFor] = useState<State | undefined>();
  if (data && data !== syncedFor) {
    setSyncedFor(data);
    setChecks(
      Object.fromEntries(
        data.checklist.map((c) => [
          c.item.id,
          { checked: c.my_check === true, note: c.my_note ?? "" },
        ]),
      ),
    );
    setRec(data.my_recommendation?.recommendation ?? "");
    setReason(data.my_recommendation?.reason ?? "");
  }

  if (screening.state.status === "loading") return <Loading label="Loading screening" />;
  if (screening.state.status === "error") {
    const e = screening.state.error;
    if (e instanceof ApiError && e.status === 409) {
      return (
        <EmptyState title="Nothing to screen yet">
          This vendor has no proposal yet. Upload it on the vendors page first.
        </EmptyState>
      );
    }
    return <ErrorNotice error={e} onRetry={screening.reload} />;
  }
  const s = screening.state.data;
  const version = s.proposal_version;
  const vendorName =
    vendor.state.status === "ready"
      ? (vendor.state.data.items.find((v) => v.id === tenderVendorId)?.vendor.name ?? "Vendor")
      : "Vendor";
  const actions = s.actions ?? {};
  const can = (name: string) => actions[name]?.allowed === true;
  const why = (name: string) => actions[name]?.reason ?? undefined;

  async function run(label: string, fn: () => Promise<unknown>) {
    setBusy(true);
    setError(undefined);
    try {
      await fn();
      announce(label);
      screening.reload();
    } catch (e) {
      setError(e instanceof Error ? e : new Error("The change was not saved"));
    } finally {
      setBusy(false);
    }
  }

  function saveChecks() {
    void run("Checklist saved", () =>
      unwrap(
        api.PUT("/tender-vendors/{tenderVendorId}/screening/checks", {
          params: { path: { tenderVendorId: tenderVendorId! } },
          body: {
            checks: Object.entries(checks).map(([id, c]) => ({
              checklist_item_id: id,
              is_checked: c.checked,
              note: c.note.trim() ? c.note.trim() : null,
            })),
          },
        }),
      ),
    );
  }

  function saveRecommendation() {
    if (!rec) return;
    void run("Recommendation saved", () =>
      unwrap(
        api.PUT("/tender-vendors/{tenderVendorId}/screening/recommendation", {
          params: { path: { tenderVendorId: tenderVendorId! } },
          body: { recommendation: rec, reason: rec === "accept" ? null : reason.trim() },
        }),
      ),
    );
  }

  function startPrecheck() {
    void run("AI check started", () =>
      unwrap(
        api.POST("/tender-vendors/{tenderVendorId}/screening/precheck", {
          params: { path: { tenderVendorId: tenderVendorId! } },
          headers: { "Idempotency-Key": crypto.randomUUID() },
        }),
      ),
    );
  }

  function decide(decision: Recommendation, note: string | null) {
    void run(`Decision confirmed: ${recommendationLabel[decision]}`, () =>
      unwrap(
        api.POST("/tender-vendors/{tenderVendorId}/screening/decision", {
          params: { path: { tenderVendorId: tenderVendorId! } },
          headers: { "Idempotency-Key": crypto.randomUUID() },
          body: { decision, reason: note },
        }),
      ),
    ).then(() => setDeciding(null));
  }

  function reinstate(note: string) {
    void run("Vendor reinstated", () =>
      unwrap(
        api.POST("/tender-vendors/{tenderVendorId}/screening/reinstate", {
          params: { path: { tenderVendorId: tenderVendorId! } },
          body: { note },
        }),
      ),
    ).then(() => setReinstating(false));
  }

  const tone = proposalStatusTone[version.status] ?? "neutral";
  const needsReason = rec !== "" && rec !== "accept" && reason.trim().length < 1;

  return (
    <>
      {tender.state.status === "ready" && <TenderHeader tender={tender.state.data} />}
      <Typography.Paragraph>
        <Link to={`/tenders/${tenderId}/vendors`}>Back to vendors</Link>
      </Typography.Paragraph>
      <Typography.Title level={2} style={{ fontSize: 20 }}>
        Screening: {vendorName}{" "}
        <StatusChip label={proposalStatusLabel[version.status] ?? version.status} tone={tone} />
      </Typography.Title>
      <Typography.Paragraph type="secondary">
        Version {version.version_no}. Members tick the checklist and recommend; the Chair decides.
      </Typography.Paragraph>
      {error && <ErrorNotice error={error} />}

      {s.decision && (
        <Section title="Decision">
          <Typography.Paragraph>
            <StatusChip
              label={
                s.decision.is_reinstatement
                  ? "Reinstated"
                  : (recommendationLabel[s.decision.decision] ?? s.decision.decision)
              }
              tone={recommendationTone[s.decision.decision] ?? "neutral"}
            />{" "}
            by {s.decision.decided_by.display_name} on{" "}
            {new Date(s.decision.decided_at).toLocaleDateString()}.
          </Typography.Paragraph>
          {s.decision.reason && <Typography.Paragraph>{s.decision.reason}</Typography.Paragraph>}
          {version.status === "accepted" && (
            <Typography.Paragraph type="secondary">
              Accepted. The evaluation starts once the requirements baseline is confirmed.
            </Typography.Paragraph>
          )}
          {version.status === "rejected" && (
            <Typography.Paragraph type="secondary">
              This vendor stays in the report as &ldquo;Not evaluated: rejected at screening&rdquo;.
            </Typography.Paragraph>
          )}
          {version.status === "resubmission_requested" && (
            <Typography.Paragraph type="secondary">
              A new proposal version is screened again when the administrator uploads it.
            </Typography.Paragraph>
          )}
          {can("reinstate") && (
            <Button onClick={() => setReinstating(true)}>Reinstate this vendor</Button>
          )}
        </Section>
      )}

      <Section title="Proposal files">
        <Space direction="vertical">
          {version.files.map((f) => (
            <Space key={f.document_version_id} wrap>
              <span>
                {f.file_name} ({f.role === "main" ? "main file" : "annex"})
              </span>
              <OpenProposalFile
                tenderVendorId={tenderVendorId!}
                vendorName={vendorName}
                documentVersionId={f.document_version_id}
                fileName={f.file_name}
                onDeclared={screening.reload}
              />
            </Space>
          ))}
        </Space>
      </Section>

      <Section title="Checklist">
        <Space direction="vertical" style={{ marginBottom: 16 }}>
          {s.checklist.map((c) => {
            const v = checks[c.item.id] ?? { checked: false, note: "" };
            return (
              <Checkbox
                key={c.item.id}
                checked={v.checked}
                disabled={!can("save_checks")}
                onChange={(e) =>
                  setChecks({ ...checks, [c.item.id]: { ...v, checked: e.target.checked } })
                }
              >
                {c.item.label}
              </Checkbox>
            );
          })}
        </Space>
        <Button
          onClick={saveChecks}
          disabled={busy || !can("save_checks")}
          disabledReason={can("save_checks") ? undefined : why("save_checks")}
        >
          Save checklist
        </Button>
      </Section>

      <Section title="AI structure check">
        <Typography.Paragraph type="secondary">
          Compares the proposal with the sections the request for proposal asks for. It only
          suggests; nobody decides from it.
        </Typography.Paragraph>
        {s.prechecks.length === 0 ? (
          <Typography.Paragraph type="secondary">
            No AI check has run for this version.
          </Typography.Paragraph>
        ) : (
          <Table<State["prechecks"][number]>
            rowKey="expected_section"
            components={rowHeaderComponents}
            dataSource={s.prechecks}
            pagination={false}
            style={{ marginBottom: 16 }}
            columns={[
              {
                title: "Expected section",
                onCell: rowHeader,
                render: (_, f) => f.expected_section,
              },
              {
                title: "Result",
                render: (_, f) => (
                  <StatusChip
                    label={f.found ? "Found" : "Not found"}
                    tone={f.found ? "success" : "warning"}
                  />
                ),
              },
              { title: "Where", render: (_, f) => f.note },
            ]}
          />
        )}
        <Button
          onClick={startPrecheck}
          disabled={busy || !can("start_precheck")}
          disabledReason={can("start_precheck") ? undefined : why("start_precheck")}
        >
          {s.prechecks.length === 0 ? "Run the AI check" : "Run the AI check again"}
        </Button>
      </Section>

      <Section title="Your recommendation">
        <fieldset disabled={!can("save_recommendation")} style={{ border: 0, padding: 0 }}>
          <legend className="visually-hidden">Recommendation</legend>
          <Radio.Group
            value={rec || undefined}
            onChange={(e) => setRec(e.target.value as Recommendation)}
            disabled={!can("save_recommendation")}
            style={{ display: "flex", flexDirection: "column", gap: 4, marginBottom: 16 }}
          >
            {(["accept", "reject", "request_resubmission"] as Recommendation[]).map((r) => (
              <Radio key={r} value={r}>
                {recommendationLabel[r]}
              </Radio>
            ))}
          </Radio.Group>
        </fieldset>
        {rec !== "" && rec !== "accept" && (
          <Field label="Reason (required)">
            {({ id, describedBy, invalid }) => (
              <Input.TextArea
                id={id}
                aria-describedby={describedBy}
                aria-invalid={invalid}
                value={reason}
                rows={3}
                onChange={(e) => setReason(e.target.value)}
              />
            )}
          </Field>
        )}
        <Button
          onClick={saveRecommendation}
          disabled={busy || rec === "" || needsReason || !can("save_recommendation")}
          disabledReason={can("save_recommendation") ? undefined : why("save_recommendation")}
        >
          Save recommendation
        </Button>
      </Section>

      <Section title={<>The committee&rsquo;s recommendations</>}>
        {s.recommendations.length === 0 ? (
          <Typography.Paragraph type="secondary">Nobody has recommended yet.</Typography.Paragraph>
        ) : (
          <Space direction="vertical">
            {s.recommendations.map((r) => (
              <div key={r.user.id}>
                {r.user.display_name}:{" "}
                <StatusChip
                  label={recommendationLabel[r.recommendation] ?? r.recommendation}
                  tone={recommendationTone[r.recommendation] ?? "neutral"}
                />
                {r.reason ? <> {r.reason}</> : null}
              </div>
            ))}
          </Space>
        )}
      </Section>

      {can("confirm_decision") && (
        <Section title="Confirm the decision">
          <Typography.Paragraph type="secondary">
            Accepting queues the evaluation. Rejecting and requesting a resubmission need a reason.
          </Typography.Paragraph>
          <Space wrap>
            <Button onClick={() => setDeciding("accept")} disabled={busy}>
              Accept
            </Button>
            <Button onClick={() => setDeciding("request_resubmission")} disabled={busy}>
              Request resubmission
            </Button>
            <Button variant="danger" onClick={() => setDeciding("reject")} disabled={busy}>
              Reject
            </Button>
          </Space>
        </Section>
      )}

      <ConfirmDialog
        open={deciding === "accept"}
        onOpenChange={(o) => !o && setDeciding(null)}
        title={`Accept ${vendorName}?`}
        description="The vendor goes on to evaluation. The decision is recorded with your name."
        confirmLabel="Accept the vendor"
        onConfirm={() => decide("accept", null)}
      />
      <NoteDialog
        open={deciding === "reject" || deciding === "request_resubmission"}
        onOpenChange={(o) => !o && setDeciding(null)}
        title={deciding === "reject" ? `Reject ${vendorName}?` : `Ask ${vendorName} to resubmit?`}
        description="The reason is recorded with the decision and shown to the committee."
        confirmLabel={deciding === "reject" ? "Reject the vendor" : "Request resubmission"}
        minLength={Math.max(10, minNote)}
        danger={deciding === "reject"}
        busy={busy}
        onConfirm={(note) => decide(deciding as Recommendation, note)}
      />
      <NoteDialog
        open={reinstating}
        onOpenChange={setReinstating}
        title={`Reinstate ${vendorName}?`}
        description="The vendor returns to the accepted list. Say why the decision changed."
        confirmLabel="Reinstate the vendor"
        minLength={Math.max(10, minNote)}
        busy={busy}
        onConfirm={reinstate}
      />
    </>
  );
}
