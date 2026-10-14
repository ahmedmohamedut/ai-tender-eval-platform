import { useState } from "react";
import { Link, useParams } from "react-router";
import {
  Button,
  EmptyState,
  ErrorNotice,
  Field,
  Loading,
  StatusChip,
  useAnnounce,
} from "../components/ui";
import { useSession } from "../app/Session";
import { OpenProposalFile } from "../components/OpenProposalFile";
import { ApiError, unwrap, useApi } from "../lib/api";
import { documentStatusLabel, documentStatusTone, failureText, type Tone } from "../lib/labels";
import { uploadForm } from "../lib/upload";
import { usePush } from "../lib/usePush";
import { useAsync } from "../lib/useAsync";
import { TenderHeader, useTender } from "./TenderHome";
import type { components } from "../api/generated/schema";

type TenderVendor = components["schemas"]["TenderVendor"];
type ProposalVersion = components["schemas"]["ProposalVersion"];

/** PG-05 Vendors and proposals (T1 list): add vendors, upload proposal files, see versions. */
export function Vendors() {
  const { tenderId } = useParams();
  const api = useApi();
  const announce = useAnnounce();
  const { me } = useSession();
  const isAdmin = me.user.role === "admin";
  const tender = useTender(tenderId);
  const list = useAsync(
    () =>
      unwrap(api.GET("/tenders/{tenderId}/vendors", { params: { path: { tenderId: tenderId! } } })),
    [api, tenderId],
  );
  usePush(tenderId, list.reload, ["document.updated", "job.updated", "tender.changed"]);

  const [name, setName] = useState("");
  const [website, setWebsite] = useState("");
  const [duplicates, setDuplicates] = useState<{ id: string; name: string }[]>([]);
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);

  if (tender.state.status === "loading" || list.state.status === "loading") {
    return <Loading label="Loading vendors" />;
  }
  if (tender.state.status === "error")
    return <ErrorNotice error={tender.state.error} onRetry={tender.reload} />;
  if (list.state.status === "error")
    return <ErrorNotice error={list.state.error} onRetry={list.reload} />;
  const items: TenderVendor[] = list.state.data.items;

  async function add(confirm: boolean, existingId?: string) {
    setBusy(true);
    setError(undefined);
    try {
      const body = existingId
        ? { vendor_id: existingId }
        : {
            new_vendor: {
              name: name.trim(),
              website: website.trim() || null,
              confirm_duplicate: confirm,
            },
          };
      await unwrap(
        api.POST("/tenders/{tenderId}/vendors", {
          params: { path: { tenderId: tenderId! } },
          body,
        }),
      );
      setName("");
      setWebsite("");
      setDuplicates([]);
      announce("The vendor was added to the tender");
      list.reload();
    } catch (e) {
      if (e instanceof ApiError && e.code === "possible_duplicate") {
        const matches = (e.problem as unknown as { matches?: { id: string; name: string }[] })
          .matches;
        setDuplicates(matches ?? []);
      } else if (e instanceof ApiError && e.code === "already_exists") {
        setError("This vendor is already part of the tender.");
      } else {
        setError(e instanceof Error ? e.message : "The vendor could not be added");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <TenderHeader tender={tender.state.data} active="vendors" />
      <h2>Vendors</h2>
      {isAdmin && (
        <section className="card" aria-labelledby="add-vendor-h">
          <h3 id="add-vendor-h">Add a vendor</h3>
          <Field label="Vendor name" error={error}>
            {({ id, describedBy, invalid }) => (
              <input
                id={id}
                aria-describedby={describedBy}
                aria-invalid={invalid}
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            )}
          </Field>
          <Field label="Website" help="Optional. Helps to spot a vendor that already exists.">
            {({ id, describedBy }) => (
              <input
                id={id}
                type="url"
                aria-describedby={describedBy}
                value={website}
                onChange={(e) => setWebsite(e.target.value)}
              />
            )}
          </Field>
          {duplicates.length > 0 && (
            <div role="alert" className="next-step">
              <p>A similar vendor already exists:</p>
              <ul>
                {duplicates.map((d) => (
                  <li key={d.id}>
                    {d.name}{" "}
                    <Button disabled={busy} onClick={() => void add(false, d.id)}>
                      Use this vendor
                    </Button>
                  </li>
                ))}
              </ul>
              <Button disabled={busy} onClick={() => void add(true)}>
                Add as a new vendor anyway
              </Button>
            </div>
          )}
          <Button
            variant="primary"
            disabled={busy || name.trim() === ""}
            onClick={() => void add(false)}
          >
            Add vendor
          </Button>
        </section>
      )}
      {items.length === 0 ? (
        <EmptyState title="No vendors yet">
          {isAdmin
            ? "Add the vendors that responded, then upload their proposals."
            : "The administrator has not added vendors to this tender yet."}
        </EmptyState>
      ) : (
        items.map((tv) => (
          <VendorCard
            key={tv.id}
            tv={tv}
            tenderId={tenderId!}
            isMember={me.user.role === "member"}
            canUpload={tv.actions?.upload_proposal?.allowed === true}
            onChanged={list.reload}
          />
        ))
      )}
    </>
  );
}

function VendorCard({
  tv,
  tenderId,
  isMember,
  canUpload,
  onChanged,
}: {
  tv: TenderVendor;
  tenderId: string;
  isMember: boolean;
  canUpload: boolean;
  onChanged: () => void;
}) {
  const api = useApi();
  const announce = useAnnounce();
  const versions = useAsync(
    () =>
      unwrap(
        api.GET("/tender-vendors/{tenderVendorId}/proposal-versions", {
          params: { path: { tenderVendorId: tv.id } },
        }),
      ),
    [api, tv.id, tv.proposal_version_no, tv.file_count],
  );
  const [files, setFiles] = useState<File[]>([]);
  const [mainIndex, setMainIndex] = useState(0);
  const [reason, setReason] = useState("");
  const [progress, setProgress] = useState<number | null>(null);
  const [error, setError] = useState<string | undefined>();

  function submit() {
    setError(undefined);
    const form = new FormData();
    // Roles first, so a wrong one is refused before any file is stored.
    files.forEach((_, i) => form.append("file_roles", i === mainIndex ? "main" : "annex"));
    if (reason.trim()) form.append("change_reason", reason.trim());
    files.forEach((f) => form.append("files", f));
    setProgress(0);
    uploadForm(
      `/tender-vendors/${tv.id}/proposal-versions`,
      form,
      (f) => setProgress(f),
      crypto.randomUUID(),
    ).done.then(
      () => {
        setProgress(null);
        setFiles([]);
        setReason("");
        announce(`Proposal files of ${tv.vendor.name} were uploaded`);
        versions.reload();
        onChanged();
      },
      (e: unknown) => {
        setProgress(null);
        setError(
          e instanceof ApiError && e.code === "unsupported_format"
            ? "Only PDF and Word (.docx) files can be uploaded."
            : e instanceof Error
              ? e.message
              : "The upload failed",
        );
      },
    );
  }

  const current: ProposalVersion | undefined =
    versions.state.status === "ready" ? versions.state.data[0] : undefined;

  return (
    <section className="card" aria-labelledby={`v-${tv.id}`}>
      <h3 id={`v-${tv.id}`}>
        {tv.vendor.name}{" "}
        {current && <StatusChip label={`Version ${current.version_no}`} tone="info" />}
      </h3>
      {versions.state.status === "loading" && <Loading label="Loading proposal versions" />}
      {current ? (
        <>
          {isMember && (
            <p>
              <Link to={`/tenders/${tenderId}/vendors/${tv.id}/screening`}>
                Screen this proposal
              </Link>{" "}
              · <Link to={`/tenders/${tenderId}/vendors/${tv.id}/evaluation`}>Evaluate</Link>
            </p>
          )}
          <p className="help">
            {current.files.length} file{current.files.length === 1 ? "" : "s"} in version{" "}
            {current.version_no}.
          </p>
          <ul>
            {current.files.map((f) => (
              <li key={f.document_version_id}>
                {f.file_name} ({f.role === "main" ? "main file" : "annex"}){" "}
                {f.status === "ready" && (
                  <OpenProposalFile
                    tenderVendorId={tv.id}
                    vendorName={tv.vendor.name}
                    documentVersionId={f.document_version_id}
                    fileName={f.file_name}
                    onDeclared={onChanged}
                  />
                )}{" "}
                <StatusChip
                  label={documentStatusLabel[f.status] ?? f.status}
                  tone={(documentStatusTone[f.status] ?? "neutral") as Tone}
                />
                {f.status === "failed" && f.failure_reason && (
                  <span className="help">
                    {" "}
                    {failureText[f.failure_reason] ?? failureText.other}
                  </span>
                )}
              </li>
            ))}
          </ul>
        </>
      ) : (
        versions.state.status === "ready" && <p className="help">No proposal uploaded yet.</p>
      )}
      <p className="help" role="note">
        Pricing and other financial documents are processed like any file. Mark them as annexes so
        that the main file stays the proposal itself.
      </p>
      {canUpload && (
        <div>
          <Field
            label={current ? "Upload a new version" : "Upload the proposal"}
            help="PDF or Word files. Choose which one is the main file."
          >
            {({ id, describedBy }) => (
              <input
                id={id}
                aria-describedby={describedBy}
                type="file"
                multiple
                accept=".pdf,.docx"
                onChange={(e) => {
                  setFiles(Array.from(e.target.files ?? []));
                  setMainIndex(0);
                }}
              />
            )}
          </Field>
          {files.length > 1 && (
            <fieldset>
              <legend>Main file</legend>
              {files.map((f, i) => (
                <label key={f.name + i} style={{ display: "block" }}>
                  <input
                    type="radio"
                    name={`main-${tv.id}`}
                    checked={mainIndex === i}
                    onChange={() => setMainIndex(i)}
                  />{" "}
                  {f.name}
                </label>
              ))}
            </fieldset>
          )}
          {current && (
            <Field label="Why a new version?" help="Shown in the history of this vendor.">
              {({ id, describedBy }) => (
                <input
                  id={id}
                  aria-describedby={describedBy}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
              )}
            </Field>
          )}
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
          {progress !== null && (
            <progress max={1} value={progress} aria-label={`Uploading for ${tv.vendor.name}`} />
          )}
          <Button
            variant="primary"
            disabled={files.length === 0 || progress !== null}
            onClick={submit}
          >
            Upload
          </Button>
        </div>
      )}
      {versions.state.status === "ready" && versions.state.data.length > 1 && (
        <details>
          <summary>Earlier versions</summary>
          <ul>
            {versions.state.data.slice(1).map((v) => (
              <li key={v.id}>
                Version {v.version_no}, {new Date(v.uploaded_at).toLocaleDateString()}
                {v.change_reason ? `: ${v.change_reason}` : ""}
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}
