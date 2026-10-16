import {
  BellOutlined,
  ClockCircleOutlined,
  LogoutOutlined,
  MoonOutlined,
  SunOutlined,
  SearchOutlined,
  UserOutlined,
} from "@ant-design/icons";
import {
  Avatar,
  Badge,
  Button,
  Descriptions,
  Drawer,
  Dropdown,
  Flex,
  Input,
  List,
  Modal,
  Popover,
  Progress,
  Tooltip,
  Tag,
  Typography,
  theme,
} from "antd";
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router";
import { StatusChip } from "../components/ui";
import { unwrap, useApi } from "../lib/api";
import { jobStatusLabel, jobTypeLabel, roleLabel, userStatusLabel } from "../lib/labels";
import { useAsync } from "../lib/useAsync";
import { usePush } from "../lib/usePush";
import { useAppearance } from "./Appearance";
import { useSession } from "./Session";

/** Jobs in the top bar: the running work at a glance, with the full list one click away. */
export function JobsPopover() {
  const api = useApi();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const list = useAsync(
    () => unwrap(api.GET("/jobs", { params: { query: { active: true, limit: 5 } } })),
    [api],
  );
  usePush(undefined, list.reload, ["job.updated"]);
  const jobs = list.state.status === "ready" ? (list.state.data?.items ?? []) : [];
  const content = (
    <div style={{ width: 340 }}>
      <List
        size="small"
        locale={{ emptyText: "No jobs are running." }}
        dataSource={jobs}
        renderItem={(j) => (
          <List.Item>
            <div style={{ width: "100%" }}>
              <Flex justify="space-between" gap={8}>
                <Typography.Text strong>{jobTypeLabel[j.job_type] ?? j.job_type}</Typography.Text>
                <Tag color="processing" style={{ margin: 0 }}>
                  {jobStatusLabel[j.status] ?? j.status}
                </Tag>
              </Flex>
              <Typography.Text type="secondary">{j.target_label ?? ""}</Typography.Text>
              {(j.status === "running" || j.status === "queued") && (
                <Progress
                  percent={j.progress_percent ?? 0}
                  size="small"
                  aria-label="Job progress"
                />
              )}
            </div>
          </List.Item>
        )}
      />
      <Button
        block
        size="small"
        onClick={() => {
          setOpen(false);
          void navigate("/jobs");
        }}
      >
        View all jobs
      </Button>
    </div>
  );
  return (
    <Popover
      trigger="click"
      open={open}
      onOpenChange={setOpen}
      placement="bottomRight"
      title="Background jobs"
      content={content}
    >
      <Badge count={jobs.length} size="small" offset={[-2, 4]}>
        <Button
          type="text"
          icon={<ClockCircleOutlined />}
          aria-label={jobs.length > 0 ? `Jobs, ${jobs.length} running` : "Jobs"}
        />
      </Badge>
    </Popover>
  );
}

/** Notifications in the top bar: the newest few, opened in place, with the unread count. */
export function NotificationsPopover() {
  const api = useApi();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const unread = useAsync(() => unwrap(api.GET("/notifications/unread-count")), [api]);
  const list = useAsync(
    () => unwrap(api.GET("/notifications", { params: { query: { limit: 5 } } })),
    [api],
  );
  const reload = () => {
    unread.reload();
    list.reload();
  };
  usePush(undefined, reload, ["notification.created"]);
  const count = unread.state.status === "ready" ? (unread.state.data?.count ?? 0) : 0;
  const items = list.state.status === "ready" ? (list.state.data?.items ?? []) : [];

  async function openOne(id: string, link?: string | null) {
    setOpen(false);
    try {
      await unwrap(
        api.POST("/notifications/{notificationId}/read", {
          params: { path: { notificationId: id } },
        }),
      );
      reload();
    } catch {
      /* reading is a convenience; the link still opens */
    }
    if (link) void navigate(link);
  }

  const content = (
    <div style={{ width: 340 }}>
      <List
        size="small"
        locale={{ emptyText: "Nothing new." }}
        dataSource={items}
        renderItem={(n) => {
          const isNew = n.read_at === null || n.read_at === undefined;
          return (
            <List.Item style={{ cursor: "pointer" }} onClick={() => void openOne(n.id, n.link)}>
              <div>
                <Typography.Text strong>{n.title}</Typography.Text>{" "}
                {isNew && <StatusChip label="New" tone="info" />}
                {n.body && (
                  <div>
                    <Typography.Text type="secondary">{n.body}</Typography.Text>
                  </div>
                )}
              </div>
            </List.Item>
          );
        }}
      />
      <Button
        block
        size="small"
        onClick={() => {
          setOpen(false);
          void navigate("/notifications");
        }}
      >
        View all notifications
      </Button>
    </div>
  );
  return (
    <Popover
      trigger="click"
      open={open}
      onOpenChange={setOpen}
      placement="bottomRight"
      title="Notifications"
      content={content}
    >
      <Badge count={count} size="small" offset={[-2, 4]}>
        <Button
          type="text"
          icon={<BellOutlined />}
          aria-label={count > 0 ? `Notifications, ${count} unread` : "Notifications"}
        />
      </Badge>
    </Popover>
  );
}

/** The round avatar, the account menu and the User profile drawer. */
export function UserMenu() {
  const { me, signOut } = useSession();
  const [profile, setProfile] = useState(false);
  const u = me.user;
  const initial = (u.display_name || u.email).trim().charAt(0).toUpperCase();
  return (
    <>
      <Dropdown
        trigger={["click"]}
        placement="bottomRight"
        menu={{
          items: [
            {
              key: "who",
              disabled: true,
              label: (
                <div>
                  <Typography.Text strong>{u.display_name}</Typography.Text>
                  <br />
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {u.email}
                  </Typography.Text>
                </div>
              ),
            },
            { type: "divider" },
            { key: "profile", icon: <UserOutlined />, label: "User profile" },
            { type: "divider" },
            { key: "out", icon: <LogoutOutlined />, danger: true, label: "Sign out" },
          ],
          onClick: ({ key }) => {
            if (key === "profile") setProfile(true);
            if (key === "out") signOut();
          },
        }}
      >
        <Button
          type="text"
          shape="circle"
          aria-label={`Account menu for ${u.display_name}`}
          style={{ padding: 0 }}
        >
          <Avatar>{initial}</Avatar>
        </Button>
      </Dropdown>
      <Drawer title="User profile" size={420} open={profile} onClose={() => setProfile(false)}>
        <Flex gap={12} align="center" style={{ marginBottom: 20 }}>
          <Avatar size={48}>{initial}</Avatar>
          <div>
            <Typography.Title level={5} style={{ margin: 0 }}>
              {u.display_name}
            </Typography.Title>
            <Typography.Text type="secondary">{u.email}</Typography.Text>
          </div>
        </Flex>
        <Descriptions
          column={1}
          bordered
          size="small"
          items={[
            { key: "role", label: "Role", children: roleLabel[u.role] },
            {
              key: "status",
              label: "Status",
              children: <StatusChip label={userStatusLabel[u.status]} tone="success" />,
            },
            { key: "tz", label: "Time zone", children: u.time_zone },
            {
              key: "last",
              label: "Last sign-in",
              children: u.last_sign_in_at ? new Date(u.last_sign_in_at).toLocaleString() : "—",
            },
          ]}
        />
        <Typography.Paragraph type="secondary" style={{ marginTop: 16 }}>
          Identity, password and session settings are managed by the platform identity provider.
        </Typography.Paragraph>
      </Drawer>
    </>
  );
}

interface Hit {
  key: string;
  title: string;
  sub: string;
  to: string;
}

/** The search button and its ⌘K dialog: tenders by name, plus the places a person goes often. */
export function SearchBox() {
  const api = useApi();
  const navigate = useNavigate();
  const { me } = useSession();
  const { token } = theme.useToken();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [tenders, setTenders] = useState<{ id: string; name: string; status: string }[]>([]);
  const isAdmin = me.user.role === "admin";

  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setOpen(true);
      }
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, []);

  useEffect(() => {
    if (!open) return;
    let live = true;
    const timer = setTimeout(() => {
      unwrap(api.GET("/tenders", { params: { query: { q: q || undefined, limit: 6 } } })).then(
        (r) =>
          live &&
          setTenders((r?.items ?? []).map((t) => ({ id: t.id, name: t.name, status: t.status }))),
        () => live && setTenders([]),
      );
    }, 200);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [api, open, q]);

  const hits = useMemo<Hit[]>(() => {
    const actions: Hit[] = [
      ...(isAdmin
        ? [
            { key: "a1", title: "Create tender", sub: "Action", to: "/tenders/new" },
            { key: "a2", title: "Add vendor", sub: "Action", to: "/vendors/new" },
          ]
        : []),
      { key: "p1", title: "Jobs", sub: "Page", to: "/jobs" },
      { key: "p2", title: "Notifications", sub: "Page", to: "/notifications" },
    ];
    const needle = q.trim().toLowerCase();
    const matching = needle
      ? actions.filter((a) => `${a.title} ${a.sub}`.toLowerCase().includes(needle))
      : actions;
    return [
      ...tenders.map((t) => ({
        key: `t${t.id}`,
        title: t.name,
        sub: `Tender · ${t.status}`,
        to: `/tenders/${t.id}`,
      })),
      ...matching,
    ].slice(0, 8);
  }, [q, tenders, isAdmin]);

  const go = (h?: Hit) => {
    if (!h) return;
    setOpen(false);
    setQ("");
    void navigate(h.to);
  };

  return (
    <>
      <Button
        icon={<SearchOutlined />}
        onClick={() => setOpen(true)}
        style={{ minWidth: 240, justifyContent: "flex-start", color: token.colorTextSecondary }}
      >
        Search…
        <span style={{ marginInlineStart: "auto" }}>
          <Typography.Text keyboard>⌘K</Typography.Text>
        </span>
      </Button>
      <Modal
        open={open}
        title="Search"
        onCancel={() => setOpen(false)}
        footer={null}
        destroyOnHidden
        width={560}
        style={{ top: 96 }}
      >
        <Input
          autoFocus
          size="large"
          prefix={<SearchOutlined />}
          allowClear
          aria-label="Search tenders and pages"
          placeholder="Search tenders, pages and actions…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onPressEnter={() => go(hits[0])}
        />
        <List
          style={{ marginTop: 12 }}
          dataSource={hits}
          locale={{ emptyText: "No matches" }}
          renderItem={(h) => (
            <List.Item style={{ cursor: "pointer" }} onClick={() => go(h)}>
              <div>
                <Typography.Text strong>{h.title}</Typography.Text>
                <br />
                <Typography.Text type="secondary">{h.sub}</Typography.Text>
              </div>
            </List.Item>
          )}
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          Enter opens the first result · Esc closes
        </Typography.Text>
      </Modal>
    </>
  );
}

/** Light or dark: a moon while the theme is dark, a sun while it is light. */
export function ThemeToggle() {
  const { mode, setMode } = useAppearance();
  const dark = mode === "dark";
  const label = dark ? "Switch to light mode" : "Switch to dark mode";
  return (
    <Tooltip title={label}>
      <Button
        type="text"
        icon={dark ? <MoonOutlined /> : <SunOutlined />}
        aria-label={label}
        onClick={() => setMode(dark ? "light" : "dark")}
      />
    </Tooltip>
  );
}
