import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Card, Flex, Input, Select, Table, Tooltip } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useStore } from '../store';
import { PageHeader, TenderStatusTag } from '../components/common';
import type { Tender, TenderStatus } from '../types';

export default function TendersList() {
  const s = useStore();
  const nav = useNavigate();
  const [q, setQ] = useState('');
  const [st, setSt] = useState<TenderStatus | 'all'>('all');
  const rows = useMemo(() => s.tenders.filter((t) => t.name.toLowerCase().includes(q.toLowerCase()) && (st === 'all' || t.status === st)), [s.tenders, q, st]);
  const nextLabel = (t: Tender) => (t.status === 'Finalized' ? 'View report' : t.status === 'Draft' ? 'Continue setup' : t.status === 'Evaluation' ? 'Open evaluation' : 'Review requirements');
  const go = (t: Tender) => nav(t.status === 'Draft' ? `/tenders/draft/${t.id}` : t.status === 'Finalized' ? `/tenders/${t.id}/report` : `/tenders/${t.id}/overview`);

  return (
    <>
      <PageHeader title="All tenders" subtitle="Open a tender to see its workspace in the left navigation."
        extra={<Tooltip title={s.isAdmin ? '' : 'Only administrators can create tenders'}><Button type="primary" icon={<PlusOutlined />} disabled={!s.isAdmin} onClick={() => nav('/tenders/new')}>Create tender</Button></Tooltip>} />
      <Card>
        <Flex gap={8} wrap style={{ marginBottom: 12 }}>
          <Input.Search allowClear placeholder="Search tenders" style={{ maxWidth: 280 }} value={q} onChange={(e) => setQ(e.target.value)} />
          <Select value={st} onChange={setSt} style={{ minWidth: 160 }} options={[{ value: 'all', label: 'All statuses' }, ...(['Draft', 'Requirements', 'Evaluation', 'Finalized'] as const).map((v) => ({ value: v, label: v }))]} />
        </Flex>
        <Table<Tender> rowKey="id" dataSource={rows} pagination={false} scroll={{ x: 640 }} columns={[
          { title: 'Tender', dataIndex: 'name', render: (v, t) => <a onClick={() => go(t)}>{v}</a> },
          { title: 'Status', dataIndex: 'status', width: 140, render: (v) => <TenderStatusTag status={v} /> },
          { title: 'Members', dataIndex: 'mc', width: 100, render: (v, t) => v ?? t.members?.length ?? '—' },
          { title: 'Vendors', dataIndex: 'vendors', width: 100, render: (v) => v ?? '—' },
          { title: 'Last activity', dataIndex: 'last', width: 140 },
          { title: '', width: 170, render: (_, t) => <Button size="small" onClick={() => go(t)}>{nextLabel(t)}</Button> },
        ]} />
      </Card>
    </>
  );
}
