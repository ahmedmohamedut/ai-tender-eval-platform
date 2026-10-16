import {
  Alert,
  Card,
  Divider,
  Input,
  List,
  Progress,
  Radio,
  Select,
  Space,
  Typography,
} from "antd";
import { useEffect, useState } from "react";
import { Link, useLocation, useParams } from "react-router";
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
  const location = useLocation();
  const [registryId, setRegistryId] = useState<string>(
    (location.state as { pickVendor?: string } | null)?.pickVendor ?? "",
  );
  const [registryQ, setRegistryQ] = useState("");
  const [registry, setRegistry] = useState<{ id: string; name: string; inactive: boolean }[]>([]);
  useEffect(() => {
    if (!isAdmin) return;
    let live = true;
    unwrap(
      api.GET("/vendors", { params: { query: { q: registryQ || undefined, limit: 50 } } }),
    ).then(
      (r) =>
        live &&
        setRegistry(
          (r?.items ?? []).map((v) => ({
            id: v.id,
            name: v.name,
            inactive: v.status === "inactive",
          })),
        ),
      () => live && setRegistry([]),
    );
    return () => {
      live = false;
    };
  }, [api, isAdmin, registryQ]);

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
      setRegistryId("");
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
      <TenderHeader tender={tender.state.data} />
      <Typography.Title level={2} style={{ fontSize: 20 }}>
        Vendors
      </Typography.Title>
      {isAdmin && (
        <Card title="Add a vendor" style={{ marginBottom: 16 }}>
          <Field label="Choose from the vendor registry">
            {({ id }) => (
              <Space.Compact style={{ width: "100%" }}>
                <Select
                  id={id}
                  showSearch={{ filterOption: false, onSearch: setRegistryQ }}
                  value={registryId || undefined}
                  placeholder="Search the registry"
                  onChange={(v) => setRegistryId(v)}
                  style={{ flex: 1 }}
                  options={registry
                    .filter((v) => !items.some((tv) => tv.vendor.id === v.id))
                    .map((v) => ({
                      value: v.id,
                      disabled: v.inactive,
                      label: v.inactive ? `${v.name} (inactive)` : v.name,
                    }))}
                />
                <Button
                  variant="primary"
                  disabled={busy || !registryId}
                  onClick={() => void add(false, registryId)}
                >
                  Add from registry
                </Button>
              </Space.Compact>
            )}
          </Field>
          <Typography.Paragraph type="secondary">
            Not in the registry yet?{" "}
            <Link to="/vendors/new" state={{ returnTo: `/tenders/${tenderId}/vendors` }}>
              Create the vendor in the registry
            </Link>
            , or add it here by name.
          </Typography.Paragraph>
          <Divider />
          <Field label="Vendor name" error={error}>
            {({ id, describedBy, invalid }) => (
              <Input
                id={id}
                aria-describedby={describedBy}
                aria-invalid={invalid}
                status={invalid ? "error" : undefined}
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            )}
          </Field>
          <Field label="Website" help="Optional. Helps to spot a vendor that already exists.">
            {({ id, describedBy }) => (
              <Input
                id={id}
                type="url"
                aria-describedby={describedBy}
                value={website}
                onChange={(e) => setWebsite(e.target.value)}
              />
            )}
          </Field>
          {duplicates.length > 0 && (
            <Alert
              type="warning"
              showIcon
              role="alert"
              style={{ marginBottom: 16 }}
              title="A similar vendor already exists:"
              description={
                <>
                  <List
                    size="small"
                    dataSource={duplicates}
                    renderItem={(d) => (
                      <List.Item
                        actions={[
                          <Button key="use" disabled={busy} onClick={() => void add(false, d.id)}>
                            Use this vendor
                          </Button>,
                        ]}
                      >
                        {d.name}
                      </List.Item>
                    )}
                  />
                  <Button disabled={busy} onClick={() => void add(true)}>
                    Add as a new vendor anyway
                  </Button>
                </>
              }
            />
          )}
          <Button
            variant="primary"
            disabled={busy || name.trim() === ""}
            onClick={() => void add(false)}
          >
            Add vendor
          </Button>
        </Card>
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
    <Card
      style={{ marginBottom: 16 }}
      title={
        <Typography.Title level={3} style={{ fontSize: 16, margin: 0 }} id={`v-${tv.id}`}>
          {tv.vendor.name}{" "}
          {current && <StatusChip label={`Version ${current.version_no}`} tone="info" />}
        </Typography.Title>
      }
    >
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
          <Typography.Paragraph type="secondary">
            {current.files.length} file{current.files.length === 1 ? "" : "s"} in version{" "}
            {current.version_no}.
          </Typography.Paragraph>
          <List
            size="small"
            dataSource={current.files}
            renderItem={(f) => (
              <List.Item key={f.document_version_id}>
                <Space wrap>
                  <span>
                    {f.file_name} ({f.role === "main" ? "main file" : "annex"})
                  </span>
                  {(f.status === "ready" || f.status === null) && (
                    <OpenProposalFile
                      tenderVendorId={tv.id}
                      vendorName={tv.vendor.name}
                      documentVersionId={f.document_version_id}
                      fileName={f.file_name}
                      onDeclared={onChanged}
                    />
                  )}
                  {f.status !== null && (
                    <StatusChip
                      label={documentStatusLabel[f.status] ?? f.status}
                      tone={(documentStatusTone[f.status] ?? "neutral") as Tone}
                    />
                  )}
                  {f.status === "failed" && f.failure_reason && (
                    <Typography.Text type="secondary">
                      {failureText[f.failure_reason] ?? failureText.other}
                    </Typography.Text>
                  )}
                </Space>
              </List.Item>
            )}
          />
        </>
      ) : (
        versions.state.status === "ready" && (
          <Typography.Paragraph type="secondary">No proposal uploaded yet.</Typography.Paragraph>
        )
      )}
      <Typography.Paragraph type="secondary" role="note">
        Pricing and other financial documents are processed like any file. Mark them as annexes so
        that the main file stays the proposal itself.
      </Typography.Paragraph>
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
            <fieldset style={{ border: 0, padding: 0 }}>
              <legend>Main file</legend>
              <Radio.Group
                value={mainIndex}
                onChange={(e) => setMainIndex(e.target.value as number)}
                style={{ display: "flex", flexDirection: "column", gap: 4 }}
              >
                {files.map((f, i) => (
                  <Radio key={f.name + i} value={i}>
                    {f.name}
                  </Radio>
                ))}
              </Radio.Group>
            </fieldset>
          )}
          {current && (
            <Field label="Why a new version?" help="Shown in the history of this vendor.">
              {({ id, describedBy }) => (
                <Input
                  id={id}
                  aria-describedby={describedBy}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
              )}
            </Field>
          )}
          {error && <Alert type="error" showIcon role="alert" title={error} />}
          {progress !== null && (
            <Progress
              percent={Math.round(progress * 100)}
              aria-label={`Uploading for ${tv.vendor.name}`}
            />
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
        <details style={{ marginTop: 16 }}>
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
    </Card>
  );
}
