import { useState } from "react";
import { Link, useParams } from "react-router";
import {
  Button,
  ConfirmDialog,
  ErrorNotice,
  Field,
  Loading,
  NoteDialog,
  StatusChip,
  useAnnounce,
} from "../components/ui";
import { useSession } from "../app/Session";
import { ApiError, unwrap, useApi } from "../lib/api";
import { baselineStatusLabel } from "../lib/labels";
import { usePush } from "../lib/usePush";
import { useAsync } from "../lib/useAsync";
import { TenderHeader, useTender } from "./TenderHome";

/** PG-12 Baseline and scoring (T6, the Chair's page): scoring model, pre-checks, confirm, reopen. */
export function Baseline() {
  const { tenderId } = useParams();
  const api = useApi();
  const announce = useAnnounce();
  const { me } = useSession();
  const minNote = me.client_config.min_note_length;
  const tender = useTender(tenderId);
  const path = { path: { tenderId: tenderId! } };
  const pre = useAsync(
    () => unwrap(api.GET("/tenders/{tenderId}/baselines/precheck", { params: path })),
    [api, tenderId],
  );
  const versions = useAsync(
    () => unwrap(api.GET("/tenders/{tenderId}/baselines", { params: path })),
    [api, tenderId],
  );
  const model = useAsync(
    () => unwrap(api.GET("/tenders/{tenderId}/scoring-model", { params: path })),
    [api, tenderId],
  );
  const cats = useAsync(() => unwrap(api.GET("/categories", { params: { query: {} } })), [api]);
  const reloadAll = () => {
    pre.reload();
    versions.reload();
    model.reload();
  };
  usePush(tenderId, reloadAll, ["requirements.changed", "baseline.changed"]);

  const [confirming, setConfirming] = useState(false);
  const [reopening, setReopening] = useState(false);
  const [draft, setDraft] = useState<{
    fully: string;
    partially: string;
    non: string;
    weights: Record<string, string>;
  } | null>(null);
  const [error, setError] = useState<string | undefined>();

  if ([tender, pre, versions, model, cats].some((a) => a.state.status === "loading")) {
    return <Loading label="Loading baseline and scoring" />;
  }
  for (const a of [tender, pre, versions, model, cats]) {
    if (a.state.status === "error")
      return <ErrorNotice error={a.state.error} onRetry={reloadAll} />;
  }
  if (
    tender.state.status !== "ready" ||
    pre.state.status !== "ready" ||
    versions.state.status !== "ready" ||
    model.state.status !== "ready" ||
    cats.state.status !== "ready"
  ) {
    return null;
  }
  const check = pre.state.data;
  const m = model.state.data;
  const live = versions.state.data.find((b) => b.status === "confirmed" || b.status === "reopened");
  const locked = m.locked;
  const isChair = tender.state.data.chair?.id === me.user.id;
  const active = cats.state.data.filter((c) => c.is_active);
  const d = draft ?? {
    fully: String(m.points_fully),
    partially: String(m.points_partially),
    non: String(m.points_non),
    weights: Object.fromEntries(
      active.map((c) => [c.id, String(m.weights.find((w) => w.category_id === c.id)?.weight ?? 1)]),
    ),
  };

  async function saveModel() {
    setError(undefined);
    try {
      await unwrap(
        api.PUT("/tenders/{tenderId}/scoring-model", {
          params: {
            path: { tenderId: tenderId! },
            header: { "If-Match": `"${m.row_version ?? 0}"` },
          },
          body: {
            points_fully: Number(d.fully),
            points_partially: Number(d.partially),
            points_non: Number(d.non),
            weights: Object.entries(d.weights).map(([category_id, w]) => ({
              category_id,
              weight: Number(w),
            })),
          },
        }),
      );
      announce("Scoring model saved");
      setDraft(null);
      reloadAll();
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 412
          ? "Someone changed the scoring model. Reload the page and try again."
          : e instanceof Error
            ? e.message
            : "The scoring model could not be saved",
      );
    }
  }

  async function confirm() {
    try {
      await unwrap(
        api.POST("/tenders/{tenderId}/baselines", {
          params: {
            path: { tenderId: tenderId! },
            header: { "Idempotency-Key": crypto.randomUUID() },
          },
        }),
      );
      announce(`Requirements confirmed as Baseline v${check.next_version_no}`);
      setConfirming(false);
      reloadAll();
    } catch (e) {
      setConfirming(false);
      setError(e instanceof Error ? e.message : "The requirements could not be confirmed");
      reloadAll();
    }
  }

  async function reopen(reason: string) {
    try {
      await unwrap(
        api.POST("/tenders/{tenderId}/baselines/current/reopen", {
          params: { path: { tenderId: tenderId! } },
          body: { reason },
        }),
      );
      announce("Requirements reopened");
      setReopening(false);
      reloadAll();
    } catch (e) {
      setReopening(false);
      setError(e instanceof Error ? e.message : "The requirements could not be reopened");
    }
  }

  return (
    <>
      <TenderHeader tender={tender.state.data} active="requirements" />
      <p>
        <Link to={`/tenders/${tenderId}/requirements`}>Back to requirements</Link>
      </p>
      <h2>Baseline and scoring</h2>
      {live && (
        <p className="banner">
          <span aria-hidden="true">🔒 </span>
          Baseline v{live.version_no}{" "}
          <StatusChip
            label={baselineStatusLabel[live.status]}
            tone={live.status === "confirmed" ? "success" : "warning"}
          />{" "}
          · confirmed {new Date(live.confirmed_at).toLocaleDateString()}
          {live.confirmed_by ? ` by ${live.confirmed_by.display_name}` : ""} ·{" "}
          {live.requirement_count} requirements
        </p>
      )}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      <section aria-labelledby="scoring-h" className="card">
        <h3 id="scoring-h">Scoring model {locked ? "(locked with the baseline)" : ""}</h3>
        <div className="filter-bar">
          <Field label="Points for fully compliant">
            {({ id }) => (
              <input
                id={id}
                type="number"
                min={0}
                step="0.5"
                disabled={locked || !isChair}
                value={d.fully}
                onChange={(e) => setDraft({ ...d, fully: e.target.value })}
              />
            )}
          </Field>
          <Field label="Points for partially compliant">
            {({ id }) => (
              <input
                id={id}
                type="number"
                min={0}
                step="0.5"
                disabled={locked || !isChair}
                value={d.partially}
                onChange={(e) => setDraft({ ...d, partially: e.target.value })}
              />
            )}
          </Field>
          <Field label="Points for non compliant">
            {({ id }) => (
              <input
                id={id}
                type="number"
                min={0}
                step="0.5"
                disabled={locked || !isChair}
                value={d.non}
                onChange={(e) => setDraft({ ...d, non: e.target.value })}
              />
            )}
          </Field>
        </div>
        <h4>Category weights</h4>
        <ul>
          {active.map((c) => (
            <li key={c.id}>
              <Field label={`Weight of ${c.name}`}>
                {({ id }) => (
                  <input
                    id={id}
                    type="number"
                    min={0.1}
                    step="0.1"
                    disabled={locked || !isChair}
                    value={d.weights[c.id] ?? "1"}
                    onChange={(e) =>
                      setDraft({ ...d, weights: { ...d.weights, [c.id]: e.target.value } })
                    }
                  />
                )}
              </Field>
            </li>
          ))}
        </ul>
        <p className="help">
          A mandatory requirement that is not met flags the vendor; it never removes it from the
          evaluation.
        </p>
        {isChair && !locked && (
          <Button
            variant="primary"
            disabledReason={draft ? undefined : "Nothing has changed"}
            onClick={() => void saveModel()}
          >
            Save scoring model
          </Button>
        )}
      </section>

      <section aria-labelledby="checks-h" className="card">
        <h3 id="checks-h">Checks before confirming</h3>
        <p>
          {check.counts.total} requirements: {check.counts.mandatory} mandatory,{" "}
          {check.counts.optional} optional.
        </p>
        {live && locked ? (
          <p>The requirements are confirmed.</p>
        ) : check.ready ? (
          <p>
            <span aria-hidden="true">✓ </span>Every requirement is reviewed, has a category and no
            open flag.
          </p>
        ) : (
          <ul>
            {check.failures.map((f) => (
              <li key={f.code}>
                <span aria-hidden="true">! </span>
                {f.message}
                {f.link ? (
                  <>
                    {" "}
                    <Link to={f.link}>Show them</Link>
                  </>
                ) : null}
              </li>
            ))}
          </ul>
        )}
        {isChair && !locked && (
          <Button
            variant="primary"
            disabledReason={check.ready ? undefined : "Resolve the checks above first"}
            onClick={() => setConfirming(true)}
          >
            Confirm requirements
          </Button>
        )}
        {isChair && live?.status === "confirmed" && (
          <Button onClick={() => setReopening(true)}>Reopen requirements</Button>
        )}
        {!isChair && <p className="help">Only the Chair confirms and reopens requirements.</p>}
      </section>

      {versions.state.data.length > 0 && (
        <section aria-labelledby="versions-h">
          <h3 id="versions-h">Versions</h3>
          <ul>
            {versions.state.data.map((b) => (
              <li key={b.id}>
                v{b.version_no} · {baselineStatusLabel[b.status]} · {b.requirement_count}{" "}
                requirements
                {b.reopen_reason ? ` · reopened: ${b.reopen_reason}` : ""}
              </li>
            ))}
          </ul>
        </section>
      )}

      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title="Confirm requirements"
        description={`Confirm ${check.counts.total} requirements? The list becomes Baseline v${check.next_version_no} and can no longer be edited. You can reopen it later with a reason; only affected evaluations will be marked Outdated.`}
        confirmLabel="Confirm requirements"
        onConfirm={() => void confirm()}
      />
      <NoteDialog
        open={reopening}
        onOpenChange={setReopening}
        title="Reopen requirements"
        description="Members can edit the list again. Confirming it again creates the next version."
        confirmLabel="Reopen requirements"
        minLength={minNote}
        onConfirm={(reason) => void reopen(reason)}
      />
    </>
  );
}
