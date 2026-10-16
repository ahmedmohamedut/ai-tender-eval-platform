import {
  Alert,
  Card,
  Col,
  Descriptions,
  Flex,
  Form,
  Input,
  Row,
  Select,
  Space,
  Table,
  Typography,
} from "antd";
import { useState } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router";
import { useSession } from "../app/Session";
import {
  Button,
  ConfirmDialog,
  EmptyState,
  ErrorNotice,
  Loading,
  LinkButton,
  StatusChip,
  rowHeader,
  rowHeaderComponents,
  useAnnounce,
} from "../components/ui";
import { ApiError, unwrap, useApi } from "../lib/api";
import type { components } from "../api/generated/schema";
import { useAsync } from "../lib/useAsync";

type Vendor = components["schemas"]["Vendor"];
type VendorInput = components["schemas"]["VendorInput"];

const INDUSTRIES = [
  "Banking and finance",
  "Government",
  "Healthcare",
  "Technology",
  "Telecommunications",
  "Energy",
  "Manufacturing",
  "Other",
];

const statusLabel = { active: "Active", inactive: "Inactive" } as const;

function AdminOnly() {
  return (
    <EmptyState title="Administrators only">
      The vendor registry is managed by administrators.
    </EmptyState>
  );
}

/** PG-05 (registry): every vendor the platform knows, reused across tenders (UX-VND-03). */
export function VendorsList() {
  const api = useApi();
  const { me } = useSession();
  const [q, setQ] = useState("");
  const [status, setStatus] = useState<"all" | "active" | "inactive">("all");
  const [industry, setIndustry] = useState("all");
  const isAdmin = me.user.role === "admin";
  const { state, reload } = useAsync(
    async () =>
      isAdmin
        ? unwrap(api.GET("/vendors", { params: { query: { q: q || undefined, limit: 100 } } }))
        : null,
    [api, q, isAdmin],
  );
  if (!isAdmin) return <AdminOnly />;
  const rows =
    state.status === "ready" && state.data
      ? state.data.items.filter(
          (v) =>
            (status === "all" || v.status === status) &&
            (industry === "all" || v.industry === industry),
        )
      : [];
  return (
    <>
      <Flex justify="space-between" align="flex-start" wrap gap={16}>
        <div>
          <Typography.Title level={1} style={{ fontSize: 24, marginTop: 0 }}>
            Vendors
          </Typography.Title>
          <Typography.Paragraph type="secondary">
            The vendor registry. Proposals are attached to vendors chosen from this list.
          </Typography.Paragraph>
        </div>
        <LinkButton to="/vendors/new" variant="primary">
          Create vendor
        </LinkButton>
      </Flex>
      <Card style={{ marginBottom: 16 }}>
        <Flex gap={16} wrap align="flex-end" role="search">
          <div style={{ flex: 1, minWidth: 240 }}>
            <label htmlFor="vendor-search" style={{ display: "block", marginBottom: 4 }}>
              Search vendors
            </label>
            <Input.Search
              id="vendor-search"
              allowClear
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="Name"
            />
          </div>
          <div>
            <label htmlFor="vendor-status" style={{ display: "block", marginBottom: 4 }}>
              Status
            </label>
            <Select
              id="vendor-status"
              value={status}
              onChange={(v) => setStatus(v)}
              style={{ minWidth: 160 }}
              options={[
                { value: "all", label: "All statuses" },
                { value: "active", label: "Active" },
                { value: "inactive", label: "Inactive" },
              ]}
            />
          </div>
          <div>
            <label htmlFor="vendor-industry" style={{ display: "block", marginBottom: 4 }}>
              Industry
            </label>
            <Select
              id="vendor-industry"
              value={industry}
              onChange={(v) => setIndustry(v)}
              style={{ minWidth: 200 }}
              options={[
                { value: "all", label: "All industries" },
                ...INDUSTRIES.map((i) => ({ value: i, label: i })),
              ]}
            />
          </div>
        </Flex>
      </Card>
      {state.status === "loading" && <Loading label="Loading vendors" />}
      {state.status === "error" && <ErrorNotice error={state.error} onRetry={reload} />}
      {state.status === "ready" &&
        (rows.length === 0 ? (
          <EmptyState title="No vendors match">Change the search or the filters.</EmptyState>
        ) : (
          <Table<Vendor>
            rowKey="id"
            components={rowHeaderComponents}
            dataSource={rows}
            pagination={false}
            scroll={{ x: "max-content" }}
            title={() => <Typography.Text strong>Vendors ({rows.length})</Typography.Text>}
            columns={[
              {
                title: "Vendor",
                onCell: rowHeader,
                render: (_, v) => <Link to={`/vendors/${v.id}`}>{v.name}</Link>,
              },
              { title: "Legal name", render: (_, v) => v.legal_name ?? "—" },
              { title: "Country", render: (_, v) => v.country ?? "—" },
              { title: "Industry", render: (_, v) => v.industry ?? "—" },
              {
                title: "Status",
                render: (_, v) => (
                  <StatusChip
                    label={statusLabel[v.status]}
                    tone={v.status === "active" ? "success" : "neutral"}
                  />
                ),
              },
            ]}
          />
        ))}
    </>
  );
}

/** One vendor of the registry with its profile; an administrator edits or deactivates it. */
export function VendorDetail() {
  const { vendorId } = useParams();
  const api = useApi();
  const announce = useAnnounce();
  const navigate = useNavigate();
  const { me } = useSession();
  const isAdmin = me.user.role === "admin";
  const [confirming, setConfirming] = useState(false);
  const [failure, setFailure] = useState<Error | null>(null);
  const { state, reload } = useAsync(
    async () =>
      isAdmin
        ? unwrap(api.GET("/vendors/{vendorId}", { params: { path: { vendorId: vendorId! } } }))
        : null,
    [api, vendorId, isAdmin],
  );
  if (!isAdmin) return <AdminOnly />;
  if (state.status === "loading") return <Loading label="Loading the vendor" />;
  if (state.status === "error") {
    return state.error instanceof ApiError && state.error.status === 404 ? (
      <EmptyState title="Vendor not found">This vendor does not exist or was removed.</EmptyState>
    ) : (
      <ErrorNotice error={state.error} onRetry={reload} />
    );
  }
  const v = state.data!;
  const active = v.status === "active";
  const toggle = async () => {
    setConfirming(false);
    try {
      await unwrap(
        api.PATCH("/vendors/{vendorId}", {
          params: { path: { vendorId: v.id }, header: { "If-Match": `"${v.row_version}"` } },
          body: { status: active ? "inactive" : "active" },
        }),
      );
      announce(active ? `${v.name} is deactivated.` : `${v.name} is active again.`);
      reload();
    } catch (e) {
      setFailure(e as Error);
    }
  };
  return (
    <>
      <Flex justify="space-between" align="flex-start" wrap gap={16}>
        <div>
          <Typography.Title level={1} style={{ fontSize: 24, marginTop: 0 }}>
            {v.name}{" "}
            <StatusChip label={statusLabel[v.status]} tone={active ? "success" : "neutral"} />
          </Typography.Title>
          {v.legal_name && (
            <Typography.Paragraph type="secondary">{v.legal_name}</Typography.Paragraph>
          )}
        </div>
        <Space>
          <LinkButton to={`/vendors/${v.id}/edit`}>Edit</LinkButton>
          <Button variant={active ? "danger" : undefined} onClick={() => setConfirming(true)}>
            {active ? "Deactivate" : "Reactivate"}
          </Button>
        </Space>
      </Flex>
      {failure && <ErrorNotice error={failure} />}
      <Card title="Vendor information">
        <Descriptions
          bordered
          size="small"
          column={{ xs: 1, md: 2 }}
          items={[
            { key: "reg", label: "Registration number", children: v.registration_no ?? "—" },
            { key: "country", label: "Country", children: v.country ?? "—" },
            { key: "industry", label: "Industry", children: v.industry ?? "—" },
            {
              key: "web",
              label: "Website",
              children: v.website ? (
                <a href={v.website} target="_blank" rel="noreferrer noopener">
                  {v.website}
                </a>
              ) : (
                "—"
              ),
            },
            { key: "contact", label: "Contact person", children: v.contact_person ?? "—" },
            { key: "email", label: "Email", children: v.email ?? "—" },
            { key: "phone", label: "Phone", children: v.phone ?? "—" },
            { key: "address", label: "Address", children: v.address ?? "—" },
            { key: "notes", label: "Notes", span: "filled", children: v.description ?? "—" },
          ]}
        />
      </Card>
      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={active ? `Deactivate ${v.name}` : `Reactivate ${v.name}`}
        description={
          active
            ? "An inactive vendor cannot be added to a new tender. Tenders it already belongs to are not changed."
            : "The vendor can be added to tenders again."
        }
        confirmLabel={active ? "Deactivate vendor" : "Reactivate vendor"}
        danger={active}
        onConfirm={() => void toggle()}
      />
      <Typography.Paragraph>
        <Button onClick={() => void navigate("/vendors")}>All vendors</Button>
      </Typography.Paragraph>
    </>
  );
}

interface FormValues {
  name: string;
  legal_name?: string;
  registration_no?: string;
  country?: string;
  industry?: string;
  website?: string;
  contact_person?: string;
  email?: string;
  phone?: string;
  address?: string;
  description?: string;
  status?: "active" | "inactive";
}

/** Create or edit a vendor (UX-VND-01). After creating from a tender, the person goes back to it. */
export function VendorForm() {
  const { vendorId } = useParams();
  const api = useApi();
  const announce = useAnnounce();
  const navigate = useNavigate();
  const location = useLocation();
  const { me } = useSession();
  const isAdmin = me.user.role === "admin";
  const returnTo = (location.state as { returnTo?: string } | null)?.returnTo;
  const [duplicates, setDuplicates] = useState<{ id: string; name: string }[]>([]);
  const [failure, setFailure] = useState<Error | null>(null);
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm<FormValues>();
  const existing = useAsync(
    async () =>
      isAdmin && vendorId
        ? unwrap(api.GET("/vendors/{vendorId}", { params: { path: { vendorId } } }))
        : null,
    [api, vendorId, isAdmin],
  );
  if (!isAdmin) return <AdminOnly />;
  if (existing.state.status === "loading") return <Loading label="Loading the vendor" />;
  if (existing.state.status === "error") {
    return <ErrorNotice error={existing.state.error} onRetry={existing.reload} />;
  }
  const current = existing.state.data;

  const submit = async (values: FormValues, confirmDuplicate = false) => {
    setBusy(true);
    setFailure(null);
    const clean = Object.fromEntries(
      Object.entries(values).map(([k, val]) => [
        k,
        typeof val === "string" ? val.trim() || null : val,
      ]),
    );
    try {
      if (current) {
        await unwrap(
          api.PATCH("/vendors/{vendorId}", {
            params: {
              path: { vendorId: current.id },
              header: { "If-Match": `"${current.row_version}"` },
            },
            body: clean as components["schemas"]["VendorUpdate"],
          }),
        );
        announce("Vendor updated.");
        void navigate(`/vendors/${current.id}`);
      } else {
        const created = await unwrap(
          api.POST("/vendors", {
            body: { ...clean, confirm_duplicate: confirmDuplicate } as VendorInput,
          }),
        );
        announce(`${created.name} was added to the registry.`);
        void navigate(returnTo ?? `/vendors/${created.id}`, {
          state: returnTo ? { pickVendor: created.id } : undefined,
        });
      }
    } catch (e) {
      if (e instanceof ApiError && e.code === "possible_duplicate") {
        const matches = (e.problem as unknown as { matches?: { id: string; name: string }[] })
          .matches;
        setDuplicates(matches ?? []);
      } else {
        setFailure(e as Error);
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Typography.Title level={1} style={{ fontSize: 24, marginTop: 0 }}>
        {current ? `Edit ${current.name}` : "Create vendor"}
      </Typography.Title>
      <Typography.Paragraph type="secondary">
        {returnTo
          ? "After saving you return to the tender and the vendor is preselected."
          : "Vendor information is reused across tenders."}
      </Typography.Paragraph>
      {failure && <ErrorNotice error={failure} />}
      {duplicates.length > 0 && (
        <Alert
          type="warning"
          showIcon
          role="alert"
          style={{ marginBottom: 16 }}
          title="A similar vendor already exists"
          description={
            <>
              <ul>
                {duplicates.map((d) => (
                  <li key={d.id}>
                    <Link to={`/vendors/${d.id}`}>{d.name}</Link>
                  </li>
                ))}
              </ul>
              <Button disabled={busy} onClick={() => form.submit()}>
                Create it anyway
              </Button>
            </>
          }
        />
      )}
      <Card>
        <Form<FormValues>
          form={form}
          layout="vertical"
          requiredMark="optional"
          initialValues={
            current
              ? {
                  name: current.name,
                  legal_name: current.legal_name ?? undefined,
                  registration_no: current.registration_no ?? undefined,
                  country: current.country ?? undefined,
                  industry: current.industry ?? undefined,
                  website: current.website ?? undefined,
                  contact_person: current.contact_person ?? undefined,
                  email: current.email ?? undefined,
                  phone: current.phone ?? undefined,
                  address: current.address ?? undefined,
                  description: current.description ?? undefined,
                  status: current.status,
                }
              : { status: "active" }
          }
          onFinish={(v) => void submit(v, duplicates.length > 0)}
        >
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                name="name"
                label="Vendor name"
                rules={[{ required: true, whitespace: true, message: "Enter the vendor name." }]}
              >
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="legal_name" label="Legal name">
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="registration_no" label="Registration number">
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="country" label="Country">
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="industry" label="Industry">
                <Select
                  allowClear
                  options={INDUSTRIES.map((i) => ({ value: i, label: i }))}
                  placeholder="Choose an industry"
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="website"
                label="Website"
                rules={[{ type: "url", message: "Enter an address that starts with https://." }]}
              >
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item name="contact_person" label="Primary contact">
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item
                name="email"
                label="Email"
                rules={[{ type: "email", message: "Enter a valid email address." }]}
              >
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item name="phone" label="Phone">
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24}>
              <Form.Item name="address" label="Address">
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24}>
              <Form.Item name="description" label="Notes">
                <Input.TextArea rows={3} />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item name="status" label="Status">
                <Select
                  options={[
                    { value: "active", label: "Active" },
                    { value: "inactive", label: "Inactive" },
                  ]}
                />
              </Form.Item>
            </Col>
          </Row>
          <Flex justify="end" gap={8}>
            <Button
              onClick={() =>
                void navigate(returnTo ?? (current ? `/vendors/${current.id}` : "/vendors"))
              }
            >
              Cancel
            </Button>
            <Button variant="primary" type="submit" disabled={busy}>
              {current ? "Save changes" : "Create vendor"}
            </Button>
          </Flex>
        </Form>
      </Card>
    </>
  );
}
