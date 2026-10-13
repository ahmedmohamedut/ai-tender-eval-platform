import { Link, NavLink, useParams } from "react-router";
import { EmptyState, ErrorNotice, LinkButton, Loading, StatusChip } from "../components/ui";
import { ApiError, unwrap, useApi } from "../lib/api";
import { TENDER_STAGES, tenderStatusLabel, tenderStatusTone } from "../lib/labels";
import type { Tender } from "../lib/types";
import { useAsync } from "../lib/useAsync";
import { useSession } from "../app/Session";

/** The tender header band shared by every screen inside a tender (UX-GEN-08). */
export function TenderHeader({
  tender,
  active,
}: {
  tender: Tender;
  active: "overview" | "progress" | "documents" | "vendors" | "requirements";
}) {
  const { me } = useSession();
  const isAdmin = me.user.role === "admin";
  const stageIndex = TENDER_STAGES.indexOf(tender.status);
  const step = tender.next_step;
  return (
    <header>
      <p className="help">
        <Link to={isAdmin ? "/tenders" : "/my-tenders"}>
          {isAdmin ? "All tenders" : "My tenders"}
        </Link>{" "}
        / {tender.name}
      </p>
      <h1>
        {tender.name}{" "}
        <StatusChip
          label={tenderStatusLabel[tender.status]}
          tone={tenderStatusTone[tender.status]}
        />
      </h1>
      <ol className="stepper" aria-label="Stage">
        {TENDER_STAGES.map((s, i) => (
          <li key={s} aria-current={i === stageIndex ? "step" : undefined}>
            {tenderStatusLabel[s]}
          </li>
        ))}
      </ol>
      {step && (
        <div className="next-step" role="note">
          <span>{step.text}</span>
        </div>
      )}
      <ul className="tabs" aria-label="Tender sections">
        <li>
          <NavLink
            to={`/tenders/${tender.id}`}
            end
            aria-current={active === "overview" ? "page" : undefined}
          >
            Overview
          </NavLink>
        </li>
        {isAdmin && (
          <li>
            <NavLink
              to={`/tenders/${tender.id}/progress`}
              aria-current={active === "progress" ? "page" : undefined}
            >
              Progress
            </NavLink>
          </li>
        )}
        <li>
          <NavLink
            to={`/tenders/${tender.id}/documents`}
            aria-current={active === "documents" ? "page" : undefined}
          >
            Documents
          </NavLink>
        </li>
        <li>
          <NavLink
            to={`/tenders/${tender.id}/requirements`}
            aria-current={active === "requirements" ? "page" : undefined}
          >
            Requirements
          </NavLink>
        </li>
        <li>
          <NavLink
            to={`/tenders/${tender.id}/vendors`}
            aria-current={active === "vendors" ? "page" : undefined}
          >
            Vendors
          </NavLink>
        </li>
      </ul>
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
      <TenderHeader tender={t} active="overview" />
      <section className="card" aria-labelledby="committee-h">
        <h2 id="committee-h">Committee</h2>
        <ul>
          {(t.members ?? []).map((m) => (
            <li key={m.user.id}>
              {m.user.display_name}
              {m.is_chair && <StatusChip label="Chair" tone="info" />}
            </li>
          ))}
        </ul>
        {t.description && <p>{t.description}</p>}
        {t.due_date && <p>Evaluation due {t.due_date}</p>}
      </section>
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
    <div className="card" style={{ maxWidth: 560 }}>
      <h1>You do not have access to this tender</h1>
      <p>Ask the administrator to add you.</p>
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
    </div>
  );
}
