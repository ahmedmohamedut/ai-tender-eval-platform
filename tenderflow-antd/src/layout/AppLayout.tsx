import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Outlet, useLocation, useMatch, useNavigate } from 'react-router-dom';
import {
  App, Avatar, Badge, Breadcrumb, Button, Descriptions, Drawer, Dropdown, Flex, FloatButton, Input, Layout, List, Menu,
  Modal, Popover, Progress, Segmented, Select, Space, Tag, Tooltip, Typography, theme, version,
  type MenuProps,
} from 'antd';
import {
  ApartmentOutlined, AuditOutlined, BankOutlined, BarChartOutlined, BellOutlined, CheckCircleFilled, ClockCircleOutlined,
  FileSearchOutlined, FolderOpenOutlined, HomeOutlined, LockOutlined, LogoutOutlined, MenuFoldOutlined, MenuUnfoldOutlined,
  ProfileOutlined, SafetyCertificateOutlined, SearchOutlined, SettingOutlined, TagsOutlined, TeamOutlined, UserOutlined, ArrowLeftOutlined,
} from '@ant-design/icons';
import { useStore } from '../store';
import { AMLOCK, jobsData, notificationsData, reqs } from '../data';
import { TenderStatusTag } from '../components/common';
import { ViewerModal } from '../components/ViewerModal';
import type { Role } from '../types';

const { Header, Sider, Content, Footer } = Layout;

/* ---------- Sidebar ---------- */
function SideNav() {
  const s = useStore();
  const nav = useNavigate();
  const { token } = theme.useToken();
  const loc = useLocation();
  const tm = useMatch('/tenders/:tid/:page');
  const inTender = !!tm && tm.params.tid !== 'new' && tm.params.tid !== 'draft';

  const mark = (text: string, node?: ReactNode) => (
    <Flex justify="space-between" align="center" gap={8}><span>{text}</span>{node}</Flex>
  );
  const ok = <CheckCircleFilled style={{ color: token.colorSuccess }} />;

  const tenderItems: MenuProps['items'] = [
    { key: 'overview', icon: <HomeOutlined />, label: 'Overview' },
    { type: 'group', label: 'Setup', children: [
      { key: 'documents', icon: <FolderOpenOutlined />, label: mark('Documents & vendors', ok) },
      { key: 'requirements', icon: <FileSearchOutlined />, label: mark('Requirements', s.isFinal ? ok : <Tag style={{ margin: 0 }}>v1</Tag>) },
    ] },
    { type: 'group', label: 'Evaluate', children: [
      { key: 'screening', icon: <SafetyCertificateOutlined />, label: mark('Screening', ok) },
      ...(s.canEvaluate ? [{ key: 'evaluation', icon: <ProfileOutlined />, label: mark('Evaluation', s.isFinal ? ok : <Tag color="processing" style={{ margin: 0 }}>{(s.reviewed.SAS ?? []).length}/{reqs.length}</Tag>) }] : []),
    ] },
    { type: 'group', label: 'Decide', children: [
      {
        key: 'report', icon: <BarChartOutlined />, disabled: !s.isFinal,
        label: s.isFinal ? 'Final report' : (
          <Tooltip title="Unlocks when the Chair finalizes the evaluation" placement="right">
            {mark('Final report', <LockOutlined />)}
          </Tooltip>
        ),
      },
    ] },
  ];

  const globalItems: MenuProps['items'] = [
    { type: 'group', label: 'Workspace', children: [
      { key: '/tenders', icon: <ApartmentOutlined />, label: 'All tenders' },
      { key: '/vendors', icon: <BankOutlined />, label: 'All vendors' },
    ] },
    ...(s.isAdmin ? [{ type: 'group' as const, label: 'Administration', children: [
      { key: '/users', icon: <TeamOutlined />, label: 'Users' },
      { key: '/categories', icon: <TagsOutlined />, label: 'Categories' },
      { key: '/audit', icon: <AuditOutlined />, label: 'Audit trail' },
    ] }] : s.role === 'Observer' ? [{ type: 'group' as const, label: 'Administration', children: [{ key: '/audit', icon: <AuditOutlined />, label: 'Audit trail' }] }] : []),
  ];

  const tender = s.tenders.find((t) => t.id === tm?.params.tid);
  const globalKey = ['/tenders', '/vendors', '/users', '/categories', '/audit'].find((k) => loc.pathname.startsWith(k));

  return (
    <>
      <Flex align="center" gap={10} style={{ height: 56, padding: s.collapsed ? 0 : '0 20px', justifyContent: s.collapsed ? 'center' : 'flex-start', borderBottom: `1px solid ${token.colorSplit}` }}>
        <Avatar shape="square" style={{ background: token.colorPrimaryBg, color: token.colorPrimary, fontWeight: 800 }}>T</Avatar>
        {!s.collapsed && <div style={{ lineHeight: 1.2 }}><Typography.Text strong>TenderFlow</Typography.Text><br /><Typography.Text type="secondary" style={{ fontSize: 10, letterSpacing: 0.7, textTransform: 'uppercase' }}>AI evaluation platform</Typography.Text></div>}
      </Flex>
      {inTender && (
        <div style={{ padding: s.collapsed ? '12px 0' : '12px 16px 8px', textAlign: s.collapsed ? 'center' : 'start' }}>
          {s.collapsed ? (
            <Tooltip title="All tenders" placement="right"><Button type="text" icon={<ArrowLeftOutlined />} onClick={() => nav('/tenders')} /></Tooltip>
          ) : (
            <>
              <Button type="link" size="small" icon={<ArrowLeftOutlined />} style={{ padding: 0 }} onClick={() => nav('/tenders')}>All tenders</Button>
              <Typography.Title level={5} style={{ margin: '8px 0 6px' }}>{tender?.name ?? 'Tender'}</Typography.Title>
              {tender && <TenderStatusTag status={tender.status} />}
            </>
          )}
        </div>
      )}
      <Menu
        mode="inline" inlineCollapsed={s.collapsed} style={{ borderInlineEnd: 0 }}
        items={inTender ? tenderItems : globalItems}
        selectedKeys={inTender ? [tm!.params.page!] : globalKey ? [globalKey] : []}
        onClick={({ key }) => nav(inTender ? `/tenders/${tm!.params.tid}/${key}` : key)}
      />
    </>
  );
}

/* ---------- Header pieces ---------- */
function Crumbs() {
  const loc = useLocation();
  const s = useStore();
  const nav = useNavigate();
  const parts = loc.pathname.split('/').filter(Boolean);
  const pageNames: Record<string, string> = { overview: 'Overview', documents: 'Documents & vendors', requirements: 'Requirements', screening: 'Screening', evaluation: 'Evaluation', report: 'Final report' };
  const items: { title: ReactNode }[] = [];
  const link = (t: string, to: string) => ({ title: <a onClick={(e) => { e.preventDefault(); nav(to); }} href={`#${to}`}>{t}</a> });
  if (parts[0] === 'tenders') {
    items.push(link('All tenders', '/tenders'));
    if (parts[1] === 'new') items.push({ title: 'New tender' });
    else if (parts[1] === 'draft') items.push({ title: 'Edit draft tender' });
    else if (parts[1]) { items.push({ title: s.tenders.find((t) => t.id === parts[1])?.name ?? parts[1] }); if (parts[2]) items.push({ title: pageNames[parts[2]] ?? parts[2] }); }
  } else if (parts[0] === 'vendors') {
    items.push(link('All vendors', '/vendors'));
    if (parts[1] === 'new') items.push({ title: 'New vendor' });
    else if (parts[1]) { items.push({ title: s.getVendor(parts[1])?.name ?? parts[1] }); if (parts[2] === 'edit') items.push({ title: 'Edit' }); }
  } else items.push({ title: 'TenderFlow' }, { title: parts[0] ? parts[0][0].toUpperCase() + parts[0].slice(1) : 'Workspace' });
  return <Breadcrumb items={items} />;
}

function JobsPopover() {
  const nav = useNavigate();
  const [open, setOpen] = useState(false);
  const content = (
    <div style={{ width: 340 }}>
      <List size="small" dataSource={jobsData} renderItem={(j) => (
        <List.Item>
          <List.Item.Meta title={<Flex justify="space-between"><span>{j.job}</span><Tag color={j.state === 'Running' ? 'processing' : 'success'}>{j.state}</Tag></Flex>}
            description={<>{j.tender}{j.state === 'Running' && <Progress percent={j.progress} size="small" />}</>} />
        </List.Item>
      )} />
      <Button block size="small" onClick={() => { setOpen(false); nav('/jobs'); }}>View all jobs</Button>
    </div>
  );
  return (
    <Popover trigger="click" open={open} onOpenChange={setOpen} placement="bottomRight" title="Background jobs" content={content}>
      <Badge count={1} size="small" offset={[-2, 4]}><Button type="text" icon={<ClockCircleOutlined />} aria-label="Jobs" /></Badge>
    </Popover>
  );
}

function NotificationsPopover() {
  const nav = useNavigate();
  const s = useStore();
  const [open, setOpen] = useState(false);
  const content = (
    <div style={{ width: 340 }}>
      <List size="small" dataSource={notificationsData} renderItem={(n) => (
        <List.Item style={{ cursor: 'pointer' }} onClick={() => { setOpen(false); if (n.phase) s.setPhase(n.phase); nav(n.to); }}>
          <List.Item.Meta avatar={<Badge dot={n.unread} status="processing" />} title={n.title} description={n.desc} />
        </List.Item>
      )} />
      <Button block size="small" onClick={() => { setOpen(false); nav('/notifications'); }}>View all notifications</Button>
    </div>
  );
  return (
    <Popover trigger="click" open={open} onOpenChange={setOpen} placement="bottomRight" title="Notifications" content={content}>
      <Badge count={2} size="small" offset={[-2, 4]}><Button type="text" icon={<BellOutlined />} aria-label="Notifications" /></Badge>
    </Popover>
  );
}

function UserMenu() {
  const s = useStore();
  const nav = useNavigate();
  const [profile, setProfile] = useState(false);
  const email = `demo.${s.role.toLowerCase()}@organization.com`;
  return (
    <>
      <Dropdown trigger={['click']} placement="bottomRight" menu={{
        items: [
          { key: 'who', disabled: true, label: <div><Typography.Text strong>Demo {s.role}</Typography.Text><br /><Typography.Text type="secondary" style={{ fontSize: 12 }}>{email}</Typography.Text></div> },
          { type: 'divider' },
          { key: 'profile', icon: <UserOutlined />, label: 'User profile' },
          { key: 'logout', icon: <LogoutOutlined />, danger: true, label: 'Logout' },
        ],
        onClick: ({ key }) => { if (key === 'profile') setProfile(true); if (key === 'logout') nav('/signed-out'); },
      }}>
        <Avatar style={{ cursor: 'pointer' }} aria-label="Account menu">{s.role[0]}</Avatar>
      </Dropdown>
      <Drawer title="User profile" size={420} open={profile} onClose={() => setProfile(false)}>
        <Flex gap={12} align="center" style={{ marginBottom: 20 }}>
          <Avatar size={48}>{s.role[0]}</Avatar>
          <div><Typography.Title level={5} style={{ margin: 0 }}>Demo {s.role}</Typography.Title><Typography.Text type="secondary">{email}</Typography.Text></div>
        </Flex>
        <Descriptions column={1} bordered size="small" items={[
          { key: 'role', label: 'Role', children: s.role },
          { key: 'tr', label: 'Current tender role', children: s.role === 'Chair' ? 'Committee Chair' : s.role === 'Member' ? 'Committee member' : '—' },
          { key: 'st', label: 'Status', children: <Tag color="success">Active</Tag> },
        ]} />
        <Typography.Paragraph type="secondary" style={{ marginTop: 16 }}>Identity, password and session settings are managed by the platform identity provider.</Typography.Paragraph>
      </Drawer>
    </>
  );
}

/* ---------- Command palette (⌘K) ---------- */
function CommandPalette() {
  const s = useStore();
  const nav = useNavigate();
  const [q, setQ] = useState('');
  type Item = { key: string; title: string; sub: string; run: () => void };
  const items = useMemo<Item[]>(() => {
    const all: Item[] = [
      ...(s.isAdmin ? [{ key: 'a1', title: 'Create tender', sub: 'Action', run: () => nav('/tenders/new') }, { key: 'a2', title: 'Add vendor', sub: 'Action', run: () => nav('/vendors/new') }] : []),
      ...s.tenders.map((t) => ({ key: `t${t.id}`, title: t.name, sub: `Tender · ${t.status}`, run: () => nav(t.status === 'Draft' ? `/tenders/draft/${t.id}` : `/tenders/${t.id}/overview`) })),
      ...s.vendors.map((v) => ({ key: `v${v.id}`, title: `${v.name} · ${v.legal}`, sub: `Vendor · ${v.country}`, run: () => nav(`/vendors/${v.id}`) })),
      ...reqs.map((r) => ({ key: r.id, title: `${r.id} · ${r.name}`, sub: `Requirement · ${r.cat}`, run: () => nav(`/tenders/${AMLOCK}/requirements?req=${r.id}`) })),
      { key: 'p1', title: 'Final report', sub: 'Page', run: () => nav(`/tenders/${AMLOCK}/report`) },
      { key: 'p2', title: 'Jobs', sub: 'Page', run: () => nav('/jobs') },
      { key: 'p3', title: 'Notifications', sub: 'Page', run: () => nav('/notifications') },
    ];
    const t = q.trim().toLowerCase();
    return (t ? all.filter((i) => `${i.title} ${i.sub}`.toLowerCase().includes(t)) : all.slice(0, 6)).slice(0, 8);
  }, [q, s.tenders, s.vendors, s.isAdmin, nav]);
  const go = (i?: Item) => { if (!i) return; s.setSearchOpen(false); setQ(''); i.run(); };
  return (
    <Modal open={s.searchOpen} onCancel={() => s.setSearchOpen(false)} footer={null} closable={false} destroyOnHidden width={560} style={{ top: 96 }} afterOpenChange={(o) => o && setQ('')}>
      <Input autoFocus size="large" prefix={<SearchOutlined />} allowClear placeholder="Search tenders, vendors, requirements (REQ-001)…" value={q}
        onChange={(e) => setQ(e.target.value)} onPressEnter={() => go(items[0])} />
      <List style={{ marginTop: 12 }} dataSource={items} locale={{ emptyText: 'No matches' }} renderItem={(i) => (
        <List.Item onClick={() => go(i)} style={{ cursor: 'pointer' }}><List.Item.Meta title={i.title} description={i.sub} /></List.Item>
      )} />
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>Enter opens the first result · Esc closes</Typography.Text>
    </Modal>
  );
}

/* ---------- Prototype-only controls (not part of the product UI) ---------- */
function DevControls() {
  const s = useStore();
  return (
    <Popover trigger="click" placement="topRight" title="Prototype controls" content={
      <Flex vertical gap={10} style={{ width: 240 }}>
        <div><Typography.Text type="secondary">Role</Typography.Text>
          <Select style={{ width: '100%' }} value={s.role} onChange={(r: Role) => s.setRole(r)} options={(['Administrator', 'Member', 'Chair', 'Observer'] as Role[]).map((r) => ({ value: r, label: r }))} /></div>
        <div><Typography.Text type="secondary">Stage</Typography.Text>
          <Segmented block value={s.phase} onChange={(v) => s.setPhase(v as 'evaluation' | 'finalized')} options={[{ value: 'evaluation', label: 'In evaluation' }, { value: 'finalized', label: 'Finalized' }]} /></div>
        <div><Typography.Text type="secondary">Theme</Typography.Text>
          <Segmented block value={s.themeMode} onChange={(v) => s.setThemeMode(v as 'light' | 'dark')} options={[{ value: 'light', label: 'Light' }, { value: 'dark', label: 'Dark' }]} /></div>
        <div><Typography.Text type="secondary">Direction</Typography.Text>
          <Segmented block value={s.direction} onChange={(v) => s.setDirection(v as 'ltr' | 'rtl')} options={[{ value: 'ltr', label: 'LTR' }, { value: 'rtl', label: 'RTL' }]} /></div>
      </Flex>
    }>
      <FloatButton icon={<SettingOutlined />} tooltip="Prototype controls" style={{ insetBlockEnd: 72 }} />
    </Popover>
  );
}

/* ---------- Shell ---------- */
export default function AppLayout() {
  const s = useStore();
  const { token } = theme.useToken();
  const { message } = App.useApp();

  useEffect(() => {
    const h = (e: KeyboardEvent) => { if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); s.setSearchOpen(true); } };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [s]);

  return (
    <Layout hasSider style={{ minHeight: '100vh' }}>
      <Sider width={248} collapsedWidth={64} collapsed={s.collapsed} trigger={null} breakpoint="lg" onBreakpoint={(b) => s.setCollapsed(b)}
        style={{ borderInlineEnd: `1px solid ${token.colorSplit}`, position: 'sticky', top: 0, height: '100vh', overflow: 'auto' }}>
        <SideNav />
      </Sider>
      <Layout>
        <Header style={{ position: 'sticky', top: 0, zIndex: 10, borderBottom: `1px solid ${token.colorSplit}`, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
          <Flex align="center" gap={8} style={{ minWidth: 0 }}>
            <Button type="text" aria-label="Toggle navigation" aria-expanded={!s.collapsed} icon={s.collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />} onClick={() => s.setCollapsed(!s.collapsed)} />
            <Crumbs />
          </Flex>
          <Space size={4}>
            <Button icon={<SearchOutlined />} onClick={() => s.setSearchOpen(true)} style={{ minWidth: 240, justifyContent: 'flex-start', color: token.colorTextTertiary }}>
              Search…<span style={{ marginInlineStart: 'auto' }}><Typography.Text keyboard>⌘K</Typography.Text></span>
            </Button>
            <JobsPopover />
            <NotificationsPopover />
            <UserMenu />
          </Space>
        </Header>
        <Content style={{ padding: 24, maxWidth: 1540, width: '100%', marginInline: 'auto' }}><Outlet /></Content>
        <Footer style={{ borderTop: `1px solid ${token.colorSplit}`, background: token.colorBgContainer, padding: '14px 24px' }}>
          <Flex justify="space-between" align="center" wrap gap={12}>
            <Typography.Text type="secondary">© 2026 TenderFlow · AI evaluation platform</Typography.Text>
            <Space size="large">{['Help center', 'Privacy', 'Terms', 'Support'].map((l) => <Typography.Link key={l} type="secondary" onClick={() => message.info(`${l} — preview link, not connected.`)}>{l}</Typography.Link>)}</Space>
            <Typography.Text type="secondary">Prototype v3 · antd {version} · illustrative data only</Typography.Text>
          </Flex>
        </Footer>
      </Layout>
      <CommandPalette />
      <ViewerModal />
      <DevControls />
    </Layout>
  );
}
