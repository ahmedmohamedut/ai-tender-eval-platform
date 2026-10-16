import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Card, Flex, Input, Select, Table, Tag, Tooltip } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useStore } from '../store';
import { PageHeader } from '../components/common';
import { industries } from '../data';
import type { Vendor } from '../types';

export default function VendorsList() {
  const s = useStore();
  const nav = useNavigate();
  const [q, setQ] = useState('');
  const [st, setSt] = useState<'all' | Vendor['status']>('all');
  const [ind, setInd] = useState('all');
  const rows = useMemo(() => s.vendors.filter((v) => `${v.name} ${v.legal} ${v.reg}`.toLowerCase().includes(q.toLowerCase()) && (st === 'all' || v.status === st) && (ind === 'all' || v.industry === ind)), [s.vendors, q, st, ind]);
  return (
    <>
      <PageHeader title="All vendors" subtitle="The vendor registry. Proposals are attached to vendors chosen from this list."
        extra={<Tooltip title={s.isAdmin ? '' : 'Only administrators can add vendors'}><Button type="primary" icon={<PlusOutlined />} disabled={!s.isAdmin} onClick={() => nav('/vendors/new')}>Create vendor</Button></Tooltip>} />
      <Card>
        <Flex gap={8} wrap style={{ marginBottom: 12 }}>
          <Input.Search allowClear placeholder="Search name, legal name or registration" style={{ maxWidth: 320 }} value={q} onChange={(e) => setQ(e.target.value)} />
          <Select value={st} onChange={setSt} style={{ minWidth: 140 }} options={[{ value: 'all', label: 'All statuses' }, { value: 'Active', label: 'Active' }, { value: 'Inactive', label: 'Inactive' }]} />
          <Select value={ind} onChange={setInd} style={{ minWidth: 220 }} options={[{ value: 'all', label: 'All industries' }, ...industries.map((i) => ({ value: i, label: i }))]} />
        </Flex>
        <Table<Vendor> rowKey="id" dataSource={rows} pagination={false} scroll={{ x: 760 }} columns={[
          { title: 'Vendor', dataIndex: 'name', render: (v, r) => <a onClick={() => nav(`/vendors/${r.id}`)}>{v}</a> },
          { title: 'Legal name', dataIndex: 'legal' },
          { title: 'Country', dataIndex: 'country' },
          { title: 'Industry', dataIndex: 'industry' },
          { title: 'Status', dataIndex: 'status', width: 110, render: (v) => <Tag color={v === 'Active' ? 'success' : 'default'}>{v}</Tag> },
        ]} />
      </Card>
    </>
  );
}
