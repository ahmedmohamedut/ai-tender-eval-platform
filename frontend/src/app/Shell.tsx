import {
  ApartmentOutlined,
  AuditOutlined,
  BankOutlined,
  BarChartOutlined,
  ClockCircleOutlined,
  FileProtectOutlined,
  FileSearchOutlined,
  FolderOpenOutlined,
  HomeOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  ProfileOutlined,
  TagsOutlined,
  TeamOutlined,
  UserOutlined,
  DashboardOutlined,
} from "@ant-design/icons";
import { Avatar, Breadcrumb, Button, Dropdown, Flex, Layout, Menu, Switch, Typography } from "antd";
import { useState, type ReactNode } from "react";
import { Link, Outlet, useLocation, useMatch } from "react-router";
import { NotificationBell } from "../components/NotificationBell";
import { StatusChip } from "../components/ui";
import { unwrap, useApi } from "../lib/api";
import { roleLabel, tenderStatusLabel, tenderStatusTone } from "../lib/labels";
import { useAsync } from "../lib/useAsync";
import { useAppearance } from "./Appearance";
import { useSession } from "./Session";

const { Header, Sider } = Layout;

interface Item {
  key: string;
  icon: ReactNode;
  label: string;
}

/** The left navigation of one role (docs/design/02-ux-design.md, App shell). */
function globalItems(role: "admin" | "member" | "observer"): Item[] {
  if (role === "admin") {
    return [
      { key: "/tenders", icon: <ApartmentOutlined />, label: "Tenders" },
      { key: "/vendors", icon: <BankOutlined />, label: "Vendors" },
      { key: "/jobs", icon: <ClockCircleOutlined />, label: "Jobs" },
      { key: "/users", icon: <TeamOutlined />, label: "Users" },
      { key: "/categories", icon: <TagsOutlined />, label: "Categories" },
      { key: "/audit", icon: <AuditOutlined />, label: "Audit trail" },
    ];
  }
  if (role === "member") {
    return [
      { key: "/my-tenders", icon: <ApartmentOutlined />, label: "My tenders" },
      { key: "/jobs", icon: <ClockCircleOutlined />, label: "Jobs" },
    ];
  }
  return [
    { key: "/tenders", icon: <ApartmentOutlined />, label: "Tenders" },
    { key: "/audit", icon: <AuditOutlined />, label: "Audit trail" },
  ];
}

const TENDER_PAGES: { slug: string; icon: ReactNode; label: string }[] = [
  { slug: "", icon: <HomeOutlined />, label: "Overview" },
  { slug: "progress", icon: <DashboardOutlined />, label: "Progress" },
  { slug: "documents", icon: <FolderOpenOutlined />, label: "Documents" },
  { slug: "vendors", icon: <BankOutlined />, label: "Vendors and proposals" },
  { slug: "requirements", icon: <FileSearchOutlined />, label: "Requirements" },
  { slug: "baseline", icon: <FileProtectOutlined />, label: "Baseline" },
  { slug: "evaluation", icon: <ProfileOutlined />, label: "Evaluation" },
  { slug: "report", icon: <BarChartOutlined />, label: "Report" },
];

/** The tender the person is inside, taken from the address; the name comes from the API. */
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

function Crumbs({ tenderName }: { tenderName?: string }) {
  const { pathname } = useLocation();
  const parts = pathname.split("/").filter(Boolean);
  const names: Record<string, string> = {
    tenders: "Tenders",
    "my-tenders": "My tenders",
    vendors: "Vendors",
    jobs: "Jobs",
    users: "Users",
    categories: "Categories",
    audit: "Audit trail",
    notifications: "Notifications",
  };
  const items: { title: ReactNode }[] = [];
  if (parts[0] === "tenders") {
    items.push({ title: <Link to="/tenders">Tenders</Link> });
    if (parts[1] === "new") items.push({ title: "New tender" });
    else if (parts[1]) {
      items.push({
        title: parts[2] ? (
          <Link to={`/tenders/${parts[1]}`}>{tenderName ?? "Tender"}</Link>
        ) : (
          (tenderName ?? "Tender")
        ),
      });
      const page = TENDER_PAGES.find((p) => p.slug === parts[2]);
      if (parts[2]) items.push({ title: page?.label ?? parts[2] });
    }
  } else if (parts[0] === "vendors") {
    items.push({ title: <Link to="/vendors">Vendors</Link> });
    if (parts[1] === "new") items.push({ title: "New vendor" });
    else if (parts[1]) items.push({ title: parts[2] === "edit" ? "Edit vendor" : "Vendor" });
  } else if (parts[0]) {
    items.push({ title: names[parts[0]] ?? parts[0] });
  }
  return items.length ? <Breadcrumb items={items} /> : null;
}

/** Top bar, left navigation and the main landmark (docs/design/02-ux-design.md, App shell). */
export function Shell() {
  const { me, signOut } = useSession();
  const { mode, direction, setMode, setDirection } = useAppearance();
  const { pathname } = useLocation();
  const [collapsed, setCollapsed] = useState(false);
  const scope = useTenderScope();
  const role = me.user.role;

  const inTender = Boolean(scope.id);
  const items: Item[] = inTender
    ? TENDER_PAGES.filter(
        (p) =>
          (p.slug !== "progress" || role === "admin") &&
          (p.slug !== "evaluation" || role !== "admin") &&
          p.slug !== "baseline",
      ).map((p) => ({
        key: `/tenders/${scope.id}${p.slug ? `/${p.slug}` : ""}`,
        icon: p.icon,
        label: p.label,
      }))
    : globalItems(role);
  const selected =
    items
      .filter((i) => pathname === i.key || pathname.startsWith(`${i.key}/`))
      .sort((a, b) => b.key.length - a.key.length)[0]?.key ?? "";

  return (
    <Layout hasSider style={{ minHeight: "100vh" }}>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <Sider
        width={240}
        collapsedWidth={64}
        collapsible
        trigger={null}
        collapsed={collapsed}
        breakpoint="lg"
        onBreakpoint={setCollapsed}
        style={{ borderInlineEnd: "1px solid var(--ant-color-split)" }}
      >
        <Flex
          align="center"
          gap={10}
          style={{
            height: 56,
            padding: collapsed ? 0 : "0 20px",
            justifyContent: collapsed ? "center" : "flex-start",
          }}
        >
          <Avatar shape="square" style={{ fontWeight: 700 }}>
            T
          </Avatar>
          {!collapsed && <Typography.Text strong>AI Tender Evaluation</Typography.Text>}
        </Flex>
        {inTender && !collapsed && (
          <div style={{ padding: "8px 16px 12px" }}>
            <Link to="/tenders">All tenders</Link>
            <Typography.Title level={5} style={{ margin: "8px 0 6px" }}>
              {scope.tender?.name ?? "Tender"}
            </Typography.Title>
            {scope.tender && (
              <StatusChip
                label={tenderStatusLabel[scope.tender.status]}
                tone={tenderStatusTone[scope.tender.status]}
              />
            )}
          </div>
        )}
        <nav aria-label="Main">
          <Menu
            mode="inline"
            selectedKeys={[selected]}
            style={{ borderInlineEnd: 0 }}
            items={items.map((i) => ({
              key: i.key,
              icon: i.icon,
              label: <Link to={i.key}>{i.label}</Link>,
            }))}
          />
        </nav>
      </Sider>
      <Layout>
        <Header
          style={{
            display: "flex",
            alignItems: "center",
            gap: 12,
            borderBottom: "1px solid var(--ant-color-split)",
          }}
        >
          <Button
            type="text"
            aria-label={collapsed ? "Expand navigation" : "Collapse navigation"}
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed(!collapsed)}
          />
          <Crumbs tenderName={scope.tender?.name} />
          <span style={{ flex: 1 }} />
          <NotificationBell />
          <Dropdown
            trigger={["click"]}
            placement="bottomRight"
            menu={{
              items: [
                {
                  key: "who",
                  disabled: true,
                  label: `${me.user.email} · ${roleLabel[role]}`,
                },
                { type: "divider" },
                {
                  key: "dark",
                  label: (
                    <Flex justify="space-between" gap={16}>
                      Dark mode
                      <Switch size="small" checked={mode === "dark"} aria-label="Dark mode" />
                    </Flex>
                  ),
                },
                {
                  key: "rtl",
                  label: (
                    <Flex justify="space-between" gap={16}>
                      Right-to-left
                      <Switch
                        size="small"
                        checked={direction === "rtl"}
                        aria-label="Right-to-left"
                      />
                    </Flex>
                  ),
                },
                { type: "divider" },
                { key: "out", icon: <LogoutOutlined />, danger: true, label: "Sign out" },
              ],
              onClick: ({ key }) => {
                if (key === "dark") setMode(mode === "dark" ? "light" : "dark");
                if (key === "rtl") setDirection(direction === "rtl" ? "ltr" : "rtl");
                if (key === "out") signOut();
              },
            }}
          >
            <Button type="text" aria-label={`Account menu for ${me.user.display_name}`}>
              <UserOutlined /> {me.user.display_name}
            </Button>
          </Dropdown>
        </Header>
        <div style={{ flex: 1 }}>
          <main id="main" tabIndex={-1} style={{ padding: 24, outline: "none" }}>
            <Outlet />
          </main>
        </div>
      </Layout>
    </Layout>
  );
}
