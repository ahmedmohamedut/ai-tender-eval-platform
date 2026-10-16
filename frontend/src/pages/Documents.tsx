import { InboxOutlined } from "@ant-design/icons";
import { Alert, Card, Input, Progress, Select, Space, Table, Typography, Upload } from "antd";
import { useState } from "react";
import { useParams } from "react-router";
import {
  Button,
  EmptyState,
  ErrorNotice,
  Field,
  LinkButton,
  Loading,
  StatusChip,
  rowHeader,
  rowHeaderComponents,
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
import { SetupSwitch } from "../components/SetupSwitch";
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
        <Typography.Paragraph type="secondary" role="note" style={{ margin: "4px 0 0" }}>
          {failureText[v.failure_reason] ?? failureText.other}
        </Typography.Paragraph>
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
      <SetupSwitch tenderId={tenderId!} active="documents" />
      <Typography.Title level={2} style={{ fontSize: 20 }}>
        Documents
      </Typography.Title>
      {isAdmin && (
        <Card title="Add a document" style={{ marginBottom: 16 }}>
          <Field label="Type">
            {({ id }) => (
              <Select
                id={id}
                value={docType}
                onChange={(v) => setDocType(v)}
                style={{ minWidth: 260 }}
                options={TENDER_TYPES.map((t) => ({
                  value: t,
                  disabled: t === "rfp" && Boolean(rfp),
                  label: `${docTypeLabel[t]}${t === "rfp" && rfp ? " (already uploaded)" : ""}`,
                }))}
              />
            )}
          </Field>
          <Field label="Title" help="Leave empty to use the file name.">
            {({ id, describedBy }) => (
              <Input
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
                <Select
                  id={id}
                  aria-describedby={describedBy}
                  status={invalid ? "error" : undefined}
                  value={parent}
                  onChange={(v) => setParent(v)}
                  style={{ minWidth: 260 }}
                  options={[
                    { value: "", label: "Choose the RFP" },
                    ...docs
                      .filter((d) => d.doc_type === "rfp")
                      .map((d) => ({ value: d.id, label: d.title })),
                  ]}
                />
              )}
            </Field>
          )}
          <Upload.Dragger
            id="document-file"
            multiple={false}
            showUploadList={false}
            accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            beforeUpload={(file) => {
              pick(file);
              return false;
            }}
          >
            <p className="ant-upload-drag-icon">
              <InboxOutlined />
            </p>
            <p>Drop a PDF or Word file here, or</p>
            <Typography.Text strong>Choose a file</Typography.Text>
          </Upload.Dragger>
          {pending && (
            <div role="status" aria-live="polite" style={{ marginTop: 16 }}>
              {pending.error ? (
                <Alert
                  type="error"
                  showIcon
                  title={
                    <>
                      <strong>{pending.name}</strong> could not be uploaded. {pending.error}
                    </>
                  }
                  action={<Button onClick={() => setPending(null)}>Dismiss</Button>}
                />
              ) : (
                <>
                  <p>
                    Uploading <strong>{pending.name}</strong>: {Math.round(pending.fraction * 100)}%
                  </p>
                  <Progress
                    percent={Math.round(pending.fraction * 100)}
                    aria-label={`Uploading ${pending.name}`}
                  />
                  <Button onClick={() => pending.handle?.cancel()}>Cancel upload</Button>
                </>
              )}
            </div>
          )}
        </Card>
      )}
      {docs.length === 0 ? (
        <EmptyState title="No documents yet">
          {isAdmin
            ? "Upload the RFP to start. Requirements are proposed from it."
            : "The administrator has not uploaded documents for this tender yet."}
        </EmptyState>
      ) : (
        <Table<Doc>
          rowKey="id"
          components={rowHeaderComponents}
          dataSource={docs}
          pagination={false}
          scroll={{ x: "max-content" }}
          title={() => <Typography.Text strong>Documents of this tender</Typography.Text>}
          columns={[
            { title: "Title", onCell: rowHeader, render: (_, d) => d.title },
            { title: "Type", render: (_, d) => docTypeLabel[d.doc_type] ?? d.doc_type },
            {
              title: "Version",
              render: (_, d) =>
                `${d.current_version.version_no}${d.version_count > 1 ? ` of ${d.version_count}` : ""}`,
            },
            { title: "Status", render: (_, d) => <VersionStatus v={d.current_version} /> },
            {
              title: "Actions",
              render: (_, d) => {
                const v = d.current_version;
                return (
                  <Space wrap>
                    {v.status === "ready" && (
                      <LinkButton to={`/documents/${v.id}/view`}>Open</LinkButton>
                    )}
                    {isAdmin && v.status === "failed" && !isFileCause(v.failure_reason) && (
                      <Button onClick={() => void retry(v)}>Try again</Button>
                    )}
                    {isAdmin && (
                      <NewVersion
                        doc={d}
                        onStart={(file) => start(`/documents/${d.id}/versions`, file, {})}
                      />
                    )}
                  </Space>
                );
              },
            },
          ]}
        />
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
  return (
    <Upload
      accept=".pdf,.docx"
      showUploadList={false}
      aria-label={`New version of ${doc.title}`}
      beforeUpload={(file) => {
        onStart(file);
        return false;
      }}
    >
      <Button>New version</Button>
    </Upload>
  );
}
