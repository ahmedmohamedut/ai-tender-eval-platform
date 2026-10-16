import { CheckCircleFilled, InboxOutlined, MinusCircleOutlined } from "@ant-design/icons";
import {
  Alert,
  Card,
  Col,
  DatePicker,
  Flex,
  Form,
  Input,
  Progress,
  Row,
  Select,
  Space,
  Steps,
  Table,
  Typography,
  Upload,
  type UploadFile,
} from "antd";
import dayjs, { type Dayjs } from "dayjs";
import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import {
  Button,
  ConfirmDialog,
  ErrorNotice,
  Field,
  Loading,
  StatusChip,
  rowHeader,
  rowHeaderComponents,
  useAnnounce,
} from "../components/ui";
import { ApiError, toApiError, unwrap, useApi } from "../lib/api";
import { problemMessage } from "../lib/labels";
import type { Readiness, Tender, UserRef } from "../lib/types";
import { uploadForm } from "../lib/upload";
import { useAsync } from "../lib/useAsync";

type FieldErrors = Record<string, string>;

function fieldErrors(e: unknown): FieldErrors {
  const out: FieldErrors = {};
  if (e instanceof ApiError) {
    for (const f of e.problem.errors ?? []) {
      out[f.field] = f.message;
    }
    if (e.code === "name_taken") {
      out.name = problemMessage("name_taken", e.problem.title);
    }
  }
  return out;
}

/** PG-03 Create or edit tender (T2): one column, the readiness checklist beside it. */
export function TenderForm() {
  const { tenderId } = useParams();
  const editing = Boolean(tenderId);
  const api = useApi();
  const navigate = useNavigate();
  const announce = useAnnounce();

  const existing = useAsync(
    async () =>
      editing
        ? unwrap(api.GET("/tenders/{tenderId}", { params: { path: { tenderId: tenderId! } } }))
        : null,
    [api, tenderId],
  );

  if (existing.state.status === "loading") return <Loading label="Loading the tender" />;
  if (existing.state.status === "error") {
    return <ErrorNotice error={existing.state.error} onRetry={existing.reload} />;
  }
  return (
    <TenderFormBody
      key={existing.state.data?.id ?? "new"}
      tender={existing.state.data}
      onSaved={(t, message) => {
        announce(message);
        // A new tender goes on to its edit page (committee, readiness); a saved or opened one
        // returns to the overview it was edited from.
        navigate(editing ? `/tenders/${t.id}` : `/tenders/${t.id}/edit`);
      }}
      reload={existing.reload}
      api={api}
    />
  );
}

interface Values {
  name?: string;
  reference_no?: string;
  description?: string;
  due?: Dayjs | null;
  members?: string[];
  chair?: string;
  rfp?: UploadFile[];
}

const FIELD_OF: Record<string, keyof Values> = {
  name: "name",
  reference_no: "reference_no",
  description: "description",
  member_ids: "members",
  chair_id: "chair",
};

function TenderFormBody({
  tender,
  onSaved,
  reload,
  api,
}: {
  tender: Tender | null;
  onSaved: (t: Tender, message: string) => void;
  reload: () => void;
  api: ReturnType<typeof useApi>;
}) {
  const announce = useAnnounce();
  const navigate = useNavigate();
  const [form] = Form.useForm<Values>();
  const values = Form.useWatch([], form) as Values | undefined;
  const known = useRef(new Map<string, UserRef>());
  const [failure, setFailure] = useState<Error | null>(null);
  const [busy, setBusy] = useState(false);
  const [fraction, setFraction] = useState<number | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const archived = tender?.archived ?? false;
  const creating = tender === null;

  // What is still missing, as the person types (the server's own list is used once it exists).
  const chosen = values?.members ?? [];
  const checks = [
    { key: "name", label: "Name", done: Boolean(values?.name?.trim()) },
    { key: "members", label: "Committee members", done: chosen.length >= 1 },
    {
      key: "chair",
      label: "Chair selected",
      done: Boolean(values?.chair) && chosen.includes(values?.chair ?? ""),
    },
    { key: "rfp", label: "RFP document", done: (values?.rfp?.length ?? 0) > 0 },
  ];

  function showErrors(err: unknown): boolean {
    const found = fieldErrors(err);
    const list = Object.entries(found)
      .filter(([f]) => f in FIELD_OF)
      .map(([f, message]) => ({ name: FIELD_OF[f]!, errors: [message] }));
    if (list.length > 0) form.setFields(list);
    return list.length > 0;
  }

  async function create(v: Values) {
    setBusy(true);
    setFailure(null);
    let created: Tender;
    try {
      created = await unwrap(
        api.POST("/tenders", {
          body: {
            name: v.name!.trim(),
            reference_no: v.reference_no?.trim() || null,
            description: v.description?.trim() || null,
            member_ids: v.members ?? [],
            chair_id: v.chair!,
            evaluation_due_date: v.due ? v.due.format("YYYY-MM-DD") : null,
          },
        }),
      );
    } catch (err) {
      if (!showErrors(err)) setFailure(err as Error);
      setBusy(false);
      return;
    }
    const file = v.rfp?.[0]?.originFileObj;
    let note = `Tender ${created.name} created.`;
    if (file) {
      const body = new FormData();
      body.append("doc_type", "rfp");
      body.append("file", file); // the file comes last: fields are read before it is streamed
      try {
        await uploadForm(`/tenders/${created.id}/documents`, body, setFraction, crypto.randomUUID())
          .done;
        note = `Tender ${created.name} created and the RFP is being read.`;
      } catch (e) {
        note = `Tender ${created.name} created, but the RFP was not uploaded: ${
          e instanceof Error ? e.message : "the upload failed"
        }. Upload it from Documents & vendors.`;
      }
    }
    setBusy(false);
    setFraction(null);
    onSaved(created, note);
  }

  async function save(v: Values) {
    if (!tender) return;
    setBusy(true);
    setFailure(null);
    try {
      const due = v.due ? v.due.format("YYYY-MM-DD") : "";
      const updated = await unwrap(
        api.PATCH("/tenders/{tenderId}", {
          params: {
            path: { tenderId: tender.id },
            header: { "If-Match": `"${tender.row_version}"` },
          },
          body: {
            name: v.name!.trim(),
            reference_no: v.reference_no?.trim() || null,
            description: v.description?.trim() || null,
          },
        }),
      );
      if (due !== (tender.due_date ?? "")) {
        await unwrap(
          api.PUT("/tenders/{tenderId}/stage-dates", {
            params: { path: { tenderId: tender.id } },
            body: { items: due ? [{ stage: "evaluation", due_date: due }] : [] },
          }),
        );
      }
      onSaved(updated, "Tender saved.");
    } catch (err) {
      if (!showErrors(err)) setFailure(err as Error);
    } finally {
      setBusy(false);
    }
  }

  async function open() {
    if (!tender) return;
    setBusy(true);
    try {
      const opened = await unwrap(
        api.POST("/tenders/{tenderId}/open", { params: { path: { tenderId: tender.id } } }),
      );
      setConfirmOpen(false);
      onSaved(opened, "The tender is open to the committee.");
    } catch (err) {
      setConfirmOpen(false);
      setFailure(err as Error);
    } finally {
      setBusy(false);
    }
  }

  const openAction = tender?.actions?.open;

  return (
    <>
      {tender && (
        <p>
          <Link to={`/tenders/${tender.id}`}>Back to tender overview</Link>
        </p>
      )}
      <Typography.Title level={1} style={{ fontSize: 24, marginTop: 0, marginBottom: 4 }}>
        {tender ? "Edit tender" : "Create tender"}
      </Typography.Title>
      <Typography.Paragraph type="secondary">
        {creating
          ? "Name, committee and Chair are required; the RFP can be added now or later."
          : "Change the tender's details. The committee is managed below."}
      </Typography.Paragraph>
      {failure && <ErrorNotice error={failure} onRetry={reload} />}
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={16}>
          <Card>
            <Form<Values>
              form={form}
              layout="vertical"
              requiredMark="optional"
              disabled={archived || busy}
              scrollToFirstError
              initialValues={
                tender
                  ? {
                      name: tender.name,
                      reference_no: tender.reference_no ?? undefined,
                      description: tender.description ?? undefined,
                      due: tender.due_date ? dayjs(tender.due_date) : null,
                    }
                  : { members: [] }
              }
              onValuesChange={(changed, all) => {
                if ("members" in changed && all.chair && !(all.members ?? []).includes(all.chair)) {
                  form.setFieldValue("chair", undefined);
                }
              }}
              onFinish={(v) => void (creating ? create(v) : save(v))}
            >
              <Form.Item
                name="name"
                label="Tender name"
                rules={[{ required: true, whitespace: true, message: "Enter a tender name." }]}
              >
                <Input maxLength={120} showCount />
              </Form.Item>
              <Form.Item name="reference_no" label="Reference number">
                <Input />
              </Form.Item>
              <Form.Item name="description" label="Description">
                <Input.TextArea rows={3} />
              </Form.Item>
              <Form.Item name="due" label="Evaluation due date" extra="Optional. Reminders use it.">
                <DatePicker style={{ width: "100%" }} />
              </Form.Item>
              {creating && (
                <>
                  <Form.Item
                    name="members"
                    label="Committee members"
                    rules={[
                      {
                        type: "array",
                        required: true,
                        min: 1,
                        message: "Choose at least one committee member.",
                      },
                    ]}
                    extra="Active users with the committee member role. Type to search."
                  >
                    <MemberSelect api={api} known={known.current} />
                  </Form.Item>
                  <Form.Item
                    name="chair"
                    label="Committee Chair"
                    dependencies={["members"]}
                    rules={[{ required: true, message: "Choose the Chair from the members." }]}
                    extra="One of the chosen members."
                  >
                    <Select
                      allowClear
                      placeholder="Select from committee members"
                      options={chosen.map((id) => ({
                        value: id,
                        label: known.current.get(id)?.display_name ?? id,
                      }))}
                    />
                  </Form.Item>
                  <Form.Item
                    name="rfp"
                    label="RFP document"
                    valuePropName="fileList"
                    extra="Optional. PDF or Word. Requirements are proposed from it."
                    getValueFromEvent={(e: { fileList: UploadFile[] }) => e.fileList}
                  >
                    <Upload.Dragger
                      id="tender-rfp"
                      beforeUpload={() => false}
                      maxCount={1}
                      accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    >
                      <p className="ant-upload-drag-icon">
                        <InboxOutlined />
                      </p>
                      <p className="ant-upload-text">Drop the RFP here, or click to browse</p>
                      <p className="ant-upload-hint">PDF or DOCX</p>
                    </Upload.Dragger>
                  </Form.Item>
                </>
              )}
              {fraction !== null && (
                <Progress
                  percent={Math.round(fraction * 100)}
                  aria-label="Uploading the RFP"
                  style={{ marginBottom: 16 }}
                />
              )}
              <Flex justify="end" gap={8}>
                <Button onClick={() => void navigate(-1)}>Cancel</Button>
                <Button variant="primary" type="submit" disabled={busy || archived}>
                  {tender ? "Save changes" : "Create tender"}
                </Button>
              </Flex>
            </Form>
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <aside aria-label="Readiness">
            <ReadinessCard tender={tender} api={api} local={checks} />
            {tender && (
              <Button
                variant="primary"
                disabledReason={
                  openAction?.allowed === false ? (openAction.reason ?? undefined) : undefined
                }
                onClick={() => setConfirmOpen(true)}
              >
                Open to committee
              </Button>
            )}
          </aside>
        </Col>
      </Row>

      {tender && (
        <CommitteeEditor
          tender={tender}
          api={api}
          onChanged={() => {
            announce("Committee updated.");
            reload();
          }}
        />
      )}

      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title="Open to committee"
        description="Members are notified and see the tender from this moment. You can still edit the tender afterwards."
        confirmLabel="Open to committee"
        onConfirm={open}
        busy={busy}
      />
    </>
  );
}

/** What is still missing before the tender can open; every finished part is ticked. */
function ReadinessCard({
  tender,
  api,
  local,
}: {
  tender: Tender | null;
  api: ReturnType<typeof useApi>;
  local: { key: string; label: string; done: boolean }[];
}) {
  const state = useAsync(
    async (): Promise<Readiness | null> =>
      tender
        ? unwrap(
            api.GET("/tenders/{tenderId}/readiness", { params: { path: { tenderId: tender.id } } }),
          )
        : null,
    [api, tender?.id, tender?.row_version],
  );
  const server = state.state.status === "ready" ? state.state.data : null;
  const items = tender ? (server?.items ?? []) : local;
  const ready = items.length > 0 && items.every((i) => i.done);
  return (
    <Card title="Readiness" style={{ marginBottom: 16 }}>
      <Steps
        orientation="vertical"
        size="small"
        current={-1}
        items={items.map((i) => ({
          title: (
            <>
              {i.label}
              <span className="visually-hidden">{i.done ? " (done)" : " (missing)"}</span>
            </>
          ),
          status: i.done ? "finish" : "wait",
          icon: i.done ? (
            <CheckCircleFilled style={{ color: "var(--ant-color-success)" }} />
          ) : (
            <MinusCircleOutlined />
          ),
        }))}
      />
      <Alert
        style={{ marginTop: 16 }}
        showIcon
        type={ready ? "success" : "info"}
        title={
          tender
            ? ready
              ? "Ready to open to the committee."
              : "Not ready to open yet."
            : ready
              ? "Ready: the tender will start with its RFP."
              : "Incomplete: the tender is created as a draft."
        }
      />
    </Card>
  );
}

/** Committee members: a searchable multi select over the users who may sit on a committee. */
function MemberSelect({
  api,
  known,
  value,
  onChange,
  id,
}: {
  api: ReturnType<typeof useApi>;
  known: Map<string, UserRef>;
  value?: string[];
  onChange?: (v: string[]) => void;
  id?: string;
}) {
  const [q, setQ] = useState("");
  const [found, setFound] = useState<UserRef[]>([]);
  useEffect(() => {
    let live = true;
    const timer = setTimeout(
      () => {
        unwrap(
          api.GET("/users/lookup", { params: { query: { q: q || undefined, limit: 25 } } }),
        ).then(
          (r) => {
            const items = r?.items ?? [];
            items.forEach((u) => known.set(u.id, u));
            if (live) setFound(items);
          },
          () => live && setFound([]),
        );
      },
      q ? 200 : 0,
    );
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [api, q, known]);
  const options = useMemo(() => {
    const listed = new Set(found.map((u) => u.id));
    const kept = (value ?? [])
      .filter((v) => !listed.has(v))
      .map((v) => known.get(v))
      .filter((u): u is UserRef => Boolean(u));
    return [...found, ...kept].map((u) => ({ value: u.id, label: u.display_name }));
  }, [found, value, known]);
  return (
    <Select
      id={id}
      mode="multiple"
      allowClear
      value={value}
      onChange={onChange}
      options={options}
      showSearch={{ filterOption: false, onSearch: setQ }}
      placeholder="Search and add members…"
      notFoundContent="No matching users."
      maxTagCount="responsive"
      style={{ width: "100%" }}
    />
  );
}

function CommitteeEditor({
  tender,
  api,
  onChanged,
}: {
  tender: Tender;
  api: ReturnType<typeof useApi>;
  onChanged: () => void;
}) {
  const [removing, setRemoving] = useState<UserRef | null>(null);
  const [handling, setHandling] = useState<"kept_as_history" | "reassigned">("kept_as_history");
  const [target, setTarget] = useState("");
  const [note, setNote] = useState("");
  const [error, setError] = useState<Error | null>(null);
  const [adding, setAdding] = useState("");
  const [candidates, setCandidates] = useState<UserRef[]>([]);

  useEffect(() => {
    let live = true;
    unwrap(api.GET("/users/lookup", { params: { query: { limit: 50 } } })).then(
      (r) =>
        live &&
        setCandidates(
          r.items.filter((u) => !(tender.members ?? []).some((m) => m.user.id === u.id)),
        ),
      () => undefined,
    );
    return () => {
      live = false;
    };
  }, [api, tender.members]);

  const run = async (f: () => Promise<unknown>) => {
    setError(null);
    try {
      await f();
      onChanged();
    } catch (e) {
      setError(e as Error);
    }
  };

  return (
    <Card title="Committee" style={{ marginTop: 24 }}>
      {error && <ErrorNotice error={error instanceof ApiError ? error : toApiError(0, null)} />}
      <Table<NonNullable<Tender["members"]>[number]>
        rowKey={(m) => m.user.id}
        components={rowHeaderComponents}
        dataSource={tender.members ?? []}
        pagination={false}
        title={() => <Typography.Text strong>Committee members</Typography.Text>}
        columns={[
          { title: "Name", onCell: rowHeader, render: (_, m) => m.user.display_name },
          {
            title: "Role in this tender",
            render: (_, m) => (m.is_chair ? <StatusChip label="Chair" tone="info" /> : "Member"),
          },
          {
            title: "Actions",
            render: (_, m) =>
              !m.is_chair && (
                <Space>
                  <Button
                    onClick={() =>
                      run(() =>
                        unwrap(
                          api.PUT("/tenders/{tenderId}/chair", {
                            params: { path: { tenderId: tender.id } },
                            body: { user_id: m.user.id },
                          }),
                        ),
                      )
                    }
                  >
                    Make Chair
                  </Button>
                  <Button onClick={() => setRemoving(m.user)}>Remove</Button>
                </Space>
              ),
          },
        ]}
      />
      <Space align="end" style={{ marginTop: 16 }}>
        <div>
          <label htmlFor="add-member" style={{ display: "block", marginBottom: 4 }}>
            Add a member
          </label>
          <Select
            id="add-member"
            value={adding}
            onChange={(v) => setAdding(v)}
            style={{ minWidth: 240 }}
            options={[
              { value: "", label: "Choose a person" },
              ...candidates.map((c) => ({ value: c.id, label: c.display_name })),
            ]}
          />
        </div>
        <Button
          disabled={!adding}
          onClick={() =>
            run(async () => {
              await unwrap(
                api.POST("/tenders/{tenderId}/members", {
                  params: { path: { tenderId: tender.id } },
                  body: { user_id: adding },
                }),
              );
              setAdding("");
            })
          }
        >
          Add member
        </Button>
      </Space>

      <ConfirmDialog
        open={removing !== null}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={`Remove ${removing?.display_name ?? ""}`}
        description="Their work is kept as read-only history, or handed to another member."
        confirmLabel="Remove from committee"
        danger
        onConfirm={() => {
          const who = removing!;
          setRemoving(null);
          void run(() =>
            unwrap(
              api.POST("/tenders/{tenderId}/members/{userId}/remove", {
                params: { path: { tenderId: tender.id, userId: who.id } },
                body: {
                  handling,
                  reassign_to_user_id: handling === "reassigned" ? target : null,
                  note: note || null,
                },
              }),
            ),
          );
        }}
      >
        <Field label="What happens to their work">
          {({ id }) => (
            <Select
              id={id}
              value={handling}
              onChange={(v) => setHandling(v)}
              style={{ width: "100%" }}
              options={[
                { value: "kept_as_history", label: "Keep as read-only history" },
                { value: "reassigned", label: "Reassign to another member" },
              ]}
            />
          )}
        </Field>
        {handling === "reassigned" && (
          <Field label="Reassign to">
            {({ id }) => (
              <Select
                id={id}
                value={target}
                onChange={(v) => setTarget(v)}
                style={{ width: "100%" }}
                options={[
                  { value: "", label: "Choose a member" },
                  ...(tender.members ?? [])
                    .filter((m) => m.user.id !== removing?.id)
                    .map((m) => ({ value: m.user.id, label: m.user.display_name })),
                ]}
              />
            )}
          </Field>
        )}
        <Field label="Note">
          {({ id }) => (
            <Input.TextArea id={id} value={note} onChange={(e) => setNote(e.target.value)} />
          )}
        </Field>
      </ConfirmDialog>
    </Card>
  );
}
