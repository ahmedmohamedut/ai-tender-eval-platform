import {
  ApartmentOutlined,
  ArrowLeftOutlined,
  AuditOutlined,
  BankOutlined,
  BarChartOutlined,
  FileSearchOutlined,
  FolderOpenOutlined,
  HomeOutlined,
  LockOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  ProfileOutlined,
  SafetyCertificateOutlined,
  TagsOutlined,
  TeamOutlined,
  CheckCircleFilled,
} from "@ant-design/icons";
import {
  Avatar,
  Breadcrumb,
  Button,
  Flex,
  Layout,
  Menu,
  Space,
  Tag,
  Tooltip,
  Typography,
  theme,
} from "antd";
import { useState, type ReactNode } from "react";
import { Link, Outlet, useLocation, useMatch } from "react-router";
import { StatusChip } from "../components/ui";
import { unwrap, useApi } from "../lib/api";
import { tenderStatusLabel, tenderStatusTone } from "../lib/labels";
import type { Tender } from "../lib/types";
import { useAsync } from "../lib/useAsync";
import { JobsPopover, NotificationsPopover, SearchBox, UserMenu } from "./HeaderControls";
import { useSession } from "./Session";

const { Header, Sider, Footer } = Layout;

type Role = "admin" | "member" | "observer";
type MenuItems = NonNullable<Parameters<typeof Menu>[0]["items"]>;

/** One navigation entry: where it goes and which paths it stands for. */
interface Entry {
  key: string;
  to: string;
  icon: ReactNode;
  label: ReactNode;
  matches: (path: string) => boolean;
  disabled?: boolean;
}

const starts = (prefix: string) => (p: string) => p === prefix || p.startsWith(`${prefix}/`);

function link(e: Entry) {
  return e.disabled ? e.label : <Link to={e.to}>{e.label}</Link>;
}

function globalMenu(role: Role): { groups: { title: string; entries: Entry[] }[] } {
  const workspace: Entry[] =
    role === "member"
      ? [
          {
            key: "my",
            to: "/my-tenders",
            icon: <ApartmentOutlined />,
            label: "My tenders",
            matches: (p) => starts("/my-tenders")(p) || starts("/tenders")(p),
          },
        ]
      : [
          {
            key: "all",
            to: "/tenders",
            icon: <ApartmentOutlined />,
            label: "All tenders",
            matches: starts("/tenders"),
          },
        ];
  if (role === "admin") {
    workspace.push({
      key: "vendors",
      to: "/vendors",
      icon: <BankOutlined />,
      label: "All vendors",
      matches: starts("/vendors"),
    });
  }
  const admin: Entry[] =
    role === "admin"
      ? [
          {
            key: "users",
            to: "/users",
            icon: <TeamOutlined />,
            label: "Users",
            matches: starts("/users"),
          },
          {
            key: "categories",
            to: "/categories",
            icon: <TagsOutlined />,
            label: "Categories",
            matches: starts("/categories"),
          },
          {
            key: "audit",
            to: "/audit",
            icon: <AuditOutlined />,
            label: "Audit trail",
            matches: starts("/audit"),
          },
        ]
      : role === "observer"
        ? [
            {
              key: "audit",
              to: "/audit",
              icon: <AuditOutlined />,
              label: "Audit trail",
              matches: starts("/audit"),
            },
          ]
        : [];
  return {
    groups: [
      { title: "Workspace", entries: workspace },
      ...(admin.length ? [{ title: "Administration", entries: admin }] : []),
    ],
  };
}

function tenderMenu(id: string, role: Role, tender: Tender | null) {
  const base = `/tenders/${id}`;
  const done = <CheckCircleFilled style={{ color: "var(--ant-color-success)" }} />;
  const row = (text: string, node?: ReactNode) => (
    <Flex justify="space-between" align="center" gap={8}>
      <span>{text}</span>
      {node}
    </Flex>
  );
  const reportOpen = tender?.status === "consolidation" || tender?.status === "finalized";
  const final = tender?.status === "finalized";
  const entries: { title?: string; entries: Entry[] }[] = [
    {
      entries: [
        {
          key: "overview",
          to: base,
          icon: <HomeOutlined />,
          label: "Overview",
          matches: (p) => p === base || starts(`${base}/progress`)(p),
        },
      ],
    },
    {
      title: "Setup",
      entries: [
        {
          key: "documents",
          to: `${base}/documents`,
          icon: <FolderOpenOutlined />,
          label: "Documents & vendors",
          matches: starts(`${base}/documents`),
        },
        {
          key: "requirements",
          to: `${base}/requirements`,
          icon: <FileSearchOutlined />,
          label: row(
            "Requirements",
            tender?.baseline_version ? (
              <Tag style={{ margin: 0 }}>v{tender.baseline_version}</Tag>
            ) : undefined,
          ),
          matches: (p) =>
            (starts(`${base}/requirements`)(p) && !p.endsWith("/compare")) ||
            starts(`${base}/baseline`)(p),
        },
      ],
    },
    {
      title: "Evaluate",
      entries: [
        {
          key: "screening",
          to: `${base}/screening`,
          icon: <SafetyCertificateOutlined />,
          label: "Screening",
          matches: (p) =>
            starts(`${base}/screening`)(p) ||
            (starts(`${base}/vendors`)(p) && p.endsWith("/screening")),
        },
        ...(role === "admin"
          ? []
          : [
              {
                key: "evaluation",
                to: `${base}/evaluation`,
                icon: <ProfileOutlined />,
                label: "Evaluation",
                matches: (p: string) => starts(`${base}/evaluation`)(p) || p.endsWith("/compare"),
              },
            ]),
      ],
    },
    {
      title: "Decide",
      entries: [
        {
          key: "report",
          to: `${base}/report`,
          icon: <BarChartOutlined />,
          disabled: !reportOpen,
          label: reportOpen ? (
            row("Final report", final ? done : undefined)
          ) : (
            <Tooltip
              title="Unlocks when the committee has submitted every evaluation"
              placement="right"
            >
              {row("Final report", <LockOutlined />)}
            </Tooltip>
          ),
          matches: starts(`${base}/report`),
        },
      ],
    },
  ];
  return { groups: entries };
}

/** The tender the person is inside, taken from the address; its name and stage come from the API. */
function useTenderScope() {
  const api = useApi();
  const match = useMatch("/tenders/:tenderId/*");
  const id = match?.params.tenderId;
  const scoped = id && id !== "new" ? id : undefined;
  const { state } = useAsync(
    async () =>
      scoped
        ? await unwrap(api.GET("/tenders/{tenderId}", { params: { path: { tenderId: scoped } } }))
        : null,
    [api, scoped],
  );
  return { id: scoped, tender: state.status === "ready" ? state.data : null };
}

const PAGE_NAMES: Record<string, string> = {
  progress: "Overview",
  documents: "Documents & vendors",
  requirements: "Requirements",
  baseline: "Baseline and scoring",
  screening: "Screening",
  evaluation: "Evaluation",
  report: "Final report",
  edit: "Edit tender",
};

function Crumbs({ tenderName, mine }: { tenderName?: string; mine: boolean }) {
  const { pathname } = useLocation();
  const parts = pathname.split("/").filter(Boolean);
  const top: Record<string, string> = {
    jobs: "Jobs",
    users: "Users",
    categories: "Categories",
    audit: "Audit trail",
    notifications: "Notifications",
    "my-tenders": "My tenders",
  };
  const items: { title: ReactNode }[] = [];
  const tendersLabel = mine ? "My tenders" : "All tenders";
  if (parts[0] === "tenders") {
    items.push({
      title: parts[1] ? (
        <Link to={mine ? "/my-tenders" : "/tenders"}>{tendersLabel}</Link>
      ) : (
        tendersLabel
      ),
    });
    if (parts[1] === "new") items.push({ title: "New tender" });
    else if (parts[1]) {
      items.push({
        title: parts[2] ? (
          <Link to={`/tenders/${parts[1]}`}>{tenderName ?? "Tender"}</Link>
        ) : (
          (tenderName ?? "Tender")
        ),
      });
      if (parts[2]) items.push({ title: PAGE_NAMES[parts[2]] ?? parts[2] });
    }
  } else if (parts[0] === "vendors") {
    items.push({ title: parts[1] ? <Link to="/vendors">All vendors</Link> : "All vendors" });
    if (parts[1] === "new") items.push({ title: "New vendor" });
    else if (parts[1]) items.push({ title: parts[2] === "edit" ? "Edit vendor" : "Vendor" });
  } else if (parts[0]) {
    items.push({ title: "TenderFlow" }, { title: top[parts[0]] ?? parts[0] });
  }
  return items.length ? <Breadcrumb items={items} /> : null;
}

/** Top bar, left navigation and the main landmark (docs/design/02-ux-design.md, App shell). */
export function Shell() {
  const { me } = useSession();
  const { token } = theme.useToken();
  const { pathname } = useLocation();
  const [collapsed, setCollapsed] = useState(false);
  const scope = useTenderScope();
  const role = me.user.role;
  const inTender = Boolean(scope.id);

  const groups = inTender
    ? tenderMenu(scope.id!, role, scope.tender).groups
    : globalMenu(role).groups;
  const all = groups.flatMap((g) => g.entries);
  const selected = all.find((e) => e.matches(pathname))?.key;
  const menuItems = groups.map((g, i): MenuItems[number] => {
    const children = g.entries.map((e) => ({
      key: e.key,
      icon: e.icon,
      disabled: e.disabled,
      label: link(e),
    }));
    return "title" in g && g.title
      ? { type: "group", key: `g${i}`, label: g.title, children }
      : { type: "group", key: `g${i}`, children };
  });

  return (
    <Layout hasSider style={{ minHeight: "100vh" }}>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <Sider
        width={248}
        collapsedWidth={64}
        collapsed={collapsed}
        trigger={null}
        breakpoint="lg"
        onBreakpoint={setCollapsed}
        style={{
          borderInlineEnd: `1px solid ${token.colorSplit}`,
          position: "sticky",
          top: 0,
          height: "100vh",
          overflow: "auto",
        }}
      >
        <Flex
          align="center"
          gap={10}
          style={{
            height: 56,
            padding: collapsed ? 0 : "0 20px",
            justifyContent: collapsed ? "center" : "flex-start",
            borderBottom: `1px solid ${token.colorSplit}`,
          }}
        >
          <Avatar
            shape="square"
            style={{ background: token.colorPrimaryBg, color: token.colorPrimary, fontWeight: 800 }}
          >
            T
          </Avatar>
          {!collapsed && (
            <div style={{ lineHeight: 1.2 }}>
              <Typography.Text strong>TenderFlow</Typography.Text>
              <br />
              <Typography.Text
                type="secondary"
                style={{ fontSize: 11, letterSpacing: 0.7, textTransform: "uppercase" }}
              >
                AI evaluation platform
              </Typography.Text>
            </div>
          )}
        </Flex>
        {inTender && (
          <div
            style={{
              padding: collapsed ? "12px 0" : "12px 16px 8px",
              textAlign: collapsed ? "center" : "start",
            }}
          >
            {collapsed ? (
              <Tooltip title="All tenders" placement="right">
                <Link to={role === "member" ? "/my-tenders" : "/tenders"} aria-label="All tenders">
                  <ArrowLeftOutlined />
                </Link>
              </Tooltip>
            ) : (
              <>
                <Link to={role === "member" ? "/my-tenders" : "/tenders"}>
                  <ArrowLeftOutlined /> {role === "member" ? "My tenders" : "All tenders"}
                </Link>
                <Typography.Title level={5} style={{ margin: "8px 0 6px" }}>
                  {scope.tender?.name ?? "Tender"}
                </Typography.Title>
                {scope.tender && (
                  <StatusChip
                    label={tenderStatusLabel[scope.tender.status]}
                    tone={tenderStatusTone[scope.tender.status]}
                  />
                )}
              </>
            )}
          </div>
        )}
        <nav aria-label="Main">
          <Menu
            mode="inline"
            inlineCollapsed={collapsed}
            selectedKeys={selected ? [selected] : []}
            style={{ borderInlineEnd: 0 }}
            items={menuItems}
          />
        </nav>
      </Sider>
      <Layout>
        <Header
          style={{
            position: "sticky",
            top: 0,
            zIndex: 10,
            borderBottom: `1px solid ${token.colorSplit}`,
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            gap: 12,
          }}
        >
          <Flex align="center" gap={8} style={{ minWidth: 0 }}>
            <Button
              type="text"
              aria-label="Toggle navigation"
              aria-expanded={!collapsed}
              icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => setCollapsed(!collapsed)}
            />
            <Crumbs tenderName={scope.tender?.name} mine={role === "member"} />
          </Flex>
          <Space size={4}>
            <SearchBox />
            <JobsPopover />
            <NotificationsPopover />
            <UserMenu />
          </Space>
        </Header>
        <div style={{ flex: 1, padding: 24, maxWidth: 1540, width: "100%", marginInline: "auto" }}>
          <main id="main" tabIndex={-1} style={{ outline: "none" }}>
            <Outlet />
          </main>
        </div>
        <Footer
          style={{
            borderTop: `1px solid ${token.colorSplit}`,
            background: token.colorBgContainer,
            padding: "14px 24px",
          }}
        >
          <Typography.Text type="secondary">
            © 2026 TenderFlow · AI evaluation platform
          </Typography.Text>
        </Footer>
      </Layout>
    </Layout>
  );
}
