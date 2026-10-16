import { useRef, useState } from "react";
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
import { unwrap, useApi, ApiError } from "../lib/api";
import {
  docTypeLabel,
  documentStatusLabel,
  documentStatusTone,
  failureText,
  type Tone,
} from "../lib/labels";
import { uploadForm, type UploadHandle } from "../lib/upload";
import { usePush } from "../lib/usePush";
import { useAsync } from "../lib/useAsync";
import { TenderHeader, useTender } from "./TenderHome";
import type { components } from "../api/generated/schema";

type Doc = components["schemas"]["Document"];
type Version = components["schemas"]["DocumentVersion"];

const TENDER_TYPES = ["rfp", "brd", "addendum", "other"] as const;

function VersionStatus({ v }: { v: Version }) {
  return (
    <>
      <StatusChip
        label={documentStatusLabel[v.status] ?? v.status}
        tone={(documentStatusTone[v.status] ?? "neutral") as Tone}
      />
      {v.status === "failed" && v.failure_reason && (
        <p className="help" role="note">
          {failureText[v.failure_reason] ?? failureText.other}
        </p>
      )}
    </>
  );
}

interface Pending {
  name: string;
  fraction: number;
  handle?: UploadHandle;
  error?: string;
}

/** PG-04 Documents (T1 list with an upload zone): the RFP, requirements documents and addenda. */
export function Documents() {
  const { tenderId } = useParams();
  const api = useApi();
  const announce = useAnnounce();
  const { me } = useSession();
  const isAdmin = me.user.role === "admin";
  const tender = useTender(tenderId);
  const list = useAsync(
    () =>
      unwrap(
        api.GET("/tenders/{tenderId}/documents", { params: { path: { tenderId: tenderId! } } }),
      ),
    [api, tenderId],
  );
  usePush(tenderId, list.reload, ["document.updated", "job.updated"]);

  const [docType, setDocType] = useState<string>("rfp");
  const [title, setTitle] = useState("");
  const [parent, setParent] = useState("");
  const [pending, setPending] = useState<Pending | null>(null);
  const [fieldError, setFieldError] = useState<string | undefined>();
  const [dragging, setDragging] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  if (tender.state.status === "loading" || list.state.status === "loading") {
    return <Loading label="Loading documents" />;
  }
  if (tender.state.status === "error")
    return <ErrorNotice error={tender.state.error} onRetry={tender.reload} />;
  if (list.state.status === "error")
    return <ErrorNotice error={list.state.error} onRetry={list.reload} />;
  const docs: Doc[] = list.state.data.items;
  const rfp = docs.find((d) => d.doc_type === "rfp");

  function start(path: string, file: File, fields: Record<string, string>) {
    const form = new FormData();
    for (const [k, v] of Object.entries(fields)) if (v) form.append(k, v);
    form.append("file", file); // the file comes last: fields are read before it is streamed
    const handle = uploadForm(
      path,
      form,
      (fraction) => setPending((p) => (p ? { ...p, fraction } : p)),
      crypto.randomUUID(),
    );
    setPending({ name: file.name, fraction: 0, handle });
    handle.done.then(
      () => {
        setPending(null);
        announce(`${file.name} was uploaded and is being read`);
        list.reload();
      },
      (e: unknown) => {
        const msg =
          e instanceof ApiError
            ? e.code === "file_too_large"
              ? "The file is larger than the upload limit."
              : e.code === "unsupported_format"
                ? "Only PDF and Word (.docx) files can be uploaded."
                : e.code === "rfp_exists"
                  ? "This tender already has an RFP. Upload a new version of it instead."
                  : e.message
            : e instanceof Error
              ? e.message
              : "The upload failed";
        setPending({ name: file.name, fraction: 0, error: msg });
      },
    );
  }

  function pick(file: File | undefined) {
    if (!file) return;
    setFieldError(undefined);
    if (docType === "addendum" && !parent) {
      setFieldError("Choose the RFP this addendum belongs to.");
      return;
    }
    start(`/tenders/${tenderId}/documents`, file, {
      doc_type: docType,
      title,
      parent_document_id: docType === "addendum" ? parent : "",
    });
  }

  async function retry(v: Version) {
    try {
      await unwrap(
        api.POST("/document-versions/{documentVersionId}/retry", {
          params: { path: { documentVersionId: v.id } },
        }),
      );
      announce("Processing was started again");
      list.reload();
    } catch (e) {
      announce(e instanceof Error ? e.message : "The retry failed");
    }
  }

  return (
    <>
      <TenderHeader tender={tender.state.data} />
      <h2>Documents</h2>
      {isAdmin && (
        <section className="card" aria-labelledby="upload-h">
          <h3 id="upload-h">Add a document</h3>
          <Field label="Type">
            {({ id }) => (
              <select id={id} value={docType} onChange={(e) => setDocType(e.target.value)}>
                {TENDER_TYPES.map((t) => (
                  <option key={t} value={t} disabled={t === "rfp" && Boolean(rfp)}>
                    {docTypeLabel[t]}
                    {t === "rfp" && rfp ? " (already uploaded)" : ""}
                  </option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Title" help="Leave empty to use the file name.">
            {({ id, describedBy }) => (
              <input
                id={id}
                aria-describedby={describedBy}
                value={title}
                onChange={(e) => setTitle(e.target.value)}
              />
            )}
          </Field>
          {docType === "addendum" && (
            <Field label="Belongs to" error={fieldError}>
              {({ id, describedBy, invalid }) => (
                <select
                  id={id}
                  aria-describedby={describedBy}
                  aria-invalid={invalid}
                  value={parent}
                  onChange={(e) => setParent(e.target.value)}
                >
                  <option value="">Choose the RFP</option>
                  {docs
                    .filter((d) => d.doc_type === "rfp")
                    .map((d) => (
                      <option key={d.id} value={d.id}>
                        {d.title}
                      </option>
                    ))}
                </select>
              )}
            </Field>
          )}
          <div
            className={`dropzone ${dragging ? "over" : ""}`.trim()}
            onDragOver={(e) => {
              e.preventDefault();
              setDragging(true);
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={(e) => {
              e.preventDefault();
              setDragging(false);
              pick(e.dataTransfer.files[0]);
            }}
          >
            <p>Drop a PDF or Word file here, or</p>
            <input
              ref={input}
              type="file"
              className="visually-hidden"
              id="document-file"
              accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
              onChange={(e) => {
                pick(e.target.files?.[0]);
                e.target.value = "";
              }}
            />
            <label htmlFor="document-file" className="btn primary">
              Choose a file
            </label>
          </div>
          {pending && (
            <div role="status" aria-live="polite">
              <p>
                {pending.error ? (
                  <>
                    <strong>{pending.name}</strong> could not be uploaded. {pending.error}
                  </>
                ) : (
                  <>
                    Uploading <strong>{pending.name}</strong>: {Math.round(pending.fraction * 100)}%
                  </>
                )}
              </p>
              {!pending.error && (
                <progress
                  max={1}
                  value={pending.fraction}
                  aria-label={`Uploading ${pending.name}`}
                />
              )}
              {pending.error ? (
                <Button onClick={() => setPending(null)}>Dismiss</Button>
              ) : (
                <Button onClick={() => pending.handle?.cancel()}>Cancel upload</Button>
              )}
            </div>
          )}
        </section>
      )}
      {docs.length === 0 ? (
        <EmptyState title="No documents yet">
          {isAdmin
            ? "Upload the RFP to start. Requirements are proposed from it."
            : "The administrator has not uploaded documents for this tender yet."}
        </EmptyState>
      ) : (
        <div className="table-wrap">
          <table>
            <caption>Documents of this tender</caption>
            <thead>
              <tr>
                <th scope="col">Title</th>
                <th scope="col">Type</th>
                <th scope="col">Version</th>
                <th scope="col">Status</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              {docs.map((d) => {
                const v = d.current_version;
                return (
                  <tr key={d.id}>
                    <th scope="row">{d.title}</th>
                    <td>{docTypeLabel[d.doc_type] ?? d.doc_type}</td>
                    <td>
                      {v.version_no}
                      {d.version_count > 1 ? ` of ${d.version_count}` : ""}
                    </td>
                    <td>
                      <VersionStatus v={v} />
                    </td>
                    <td>
                      {v.status === "ready" && (
                        <Link to={`/documents/${v.id}/view`} className="btn">
                          Open
                        </Link>
                      )}{" "}
                      {isAdmin && v.status === "failed" && !isFileCause(v.failure_reason) && (
                        <Button onClick={() => void retry(v)}>Try again</Button>
                      )}
                      {isAdmin && (
                        <NewVersion
                          doc={d}
                          onStart={(file) => start(`/documents/${d.id}/versions`, file, {})}
                        />
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function isFileCause(reason?: string | null): boolean {
  return (
    reason === "password_protected" ||
    reason === "no_selectable_text" ||
    reason === "unsupported_format"
  );
}

function NewVersion({ doc, onStart }: { doc: Doc; onStart: (f: File) => void }) {
  const id = `nv-${doc.id}`;
  return (
    <>
      <input
        id={id}
        type="file"
        className="visually-hidden"
        accept=".pdf,.docx"
        onChange={(e) => {
          const f = e.target.files?.[0];
          e.target.value = "";
          if (f) onStart(f);
        }}
      />
      <label htmlFor={id} className="btn">
        New version
      </label>
    </>
  );
}
