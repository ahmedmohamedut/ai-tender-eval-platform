import { useNavigate } from 'react-router-dom';
import { Alert, Button, Card, Progress, Result, Table, Tag } from 'antd';
import { useStore } from '../store';
import { auditData, categoriesData, jobsData, notificationsData, usersData } from '../data';
import { PageHeader } from '../components/common';

export function Jobs() {
  const nav = useNavigate();
  return (<><PageHeader title="Jobs" subtitle="Background processing across tenders." /><Card>
    <Table rowKey="id" pagination={false} dataSource={jobsData} columns={[
      { title: 'Job', dataIndex: 'job' }, { title: 'Tender', dataIndex: 'tender' },
      { title: 'State', dataIndex: 'state', render: (v) => <Tag color={v === 'Completed' ? 'success' : 'processing'}>{v}</Tag> },
      { title: 'Progress', dataIndex: 'progress', width: 180, render: (v) => <Progress percent={v} size="small" /> },
      { title: 'Started', dataIndex: 'started' },
      { title: '', render: (_, r) => r.to ? <Button size="small" onClick={() => nav(r.to)}>Open</Button> : null },
    ]} /></Card></>);
}

export function Notifications() {
  const nav = useNavigate();
  const s = useStore();
  return (<><PageHeader title="Notifications" /><Card>
    <Table rowKey="id" pagination={false} showHeader={false} dataSource={notificationsData} columns={[
      { dataIndex: 'title', render: (v, r) => <><b>{v}</b> {r.unread && <Tag color="processing">New</Tag>}<br /><span style={{ opacity: 0.65 }}>{r.desc}</span></> },
      { width: 100, render: (_, r) => <Button size="small" onClick={() => { if (r.phase) s.setPhase(r.phase); nav(r.to); }}>Open</Button> },
    ]} /></Card></>);
}

function AdminOnly({ children }: { children: React.ReactNode }) {
  const s = useStore();
  return s.isAdmin ? <>{children}</> : <Result status="403" title="Administrators only" />;
}

export function Users() {
  return (<AdminOnly><PageHeader title="Users" /><Card><Table rowKey="id" pagination={false} dataSource={usersData} columns={[
    { title: 'User', dataIndex: 'user' }, { title: 'Role', dataIndex: 'role' },
    { title: 'Status', dataIndex: 'status', render: (v) => <Tag color="success">{v}</Tag> }, { title: 'Last active', dataIndex: 'last' }]} /></Card></AdminOnly>);
}

export function Categories() {
  return (<AdminOnly><PageHeader title="Categories" /><Card><Table rowKey="id" pagination={false} dataSource={categoriesData} columns={[
    { title: 'Order', dataIndex: 'order', width: 80 }, { title: 'Category', dataIndex: 'category' }, { title: 'Requirements', dataIndex: 'count' },
    { title: 'Status', dataIndex: 'status', render: (v) => <Tag color="success">{v}</Tag> }]} /></Card></AdminOnly>);
}

export function Audit() {
  const s = useStore();
  if (!s.isAdmin && s.role !== 'Observer') return <Result status="403" title="Not available for your role" />;
  return (<><PageHeader title="Audit trail" /><Alert showIcon type="info" style={{ marginBottom: 16 }} title="Read-only record of significant actions." />
    <Card><Table rowKey="id" pagination={false} scroll={{ x: 720 }} dataSource={auditData} columns={[
      { title: 'Date', dataIndex: 'date' }, { title: 'Actor', dataIndex: 'actor' }, { title: 'Object', dataIndex: 'object' },
      { title: 'Action', dataIndex: 'action' }, { title: 'Reason', dataIndex: 'reason' }]} /></Card></>);
}

export function SignedOut() {
  const nav = useNavigate();
  return <Result status="success" title="You have signed out" subTitle="Prototype only — no session was ended." extra={<Button type="primary" onClick={() => nav('/tenders')}>Sign in again</Button>} />;
}
