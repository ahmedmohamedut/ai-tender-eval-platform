import { useNavigate, useParams } from 'react-router-dom';
import { App, Button, Card, Descriptions, Result, Space, Table, Tag } from 'antd';
import { EditOutlined } from '@ant-design/icons';
import { useStore } from '../store';
import { AMLOCK, otherTenderVendors } from '../data';
import { PageHeader, TenderStatusTag } from '../components/common';

export default function VendorDetail() {
  const { id = '' } = useParams();
  const s = useStore();
  const nav = useNavigate();
  const { message, modal } = App.useApp();
  const v = s.getVendor(id);
  if (!v) return <Result status="404" title="Vendor not found" extra={<Button onClick={() => nav('/vendors')}>All vendors</Button>} />;

  const part = s.tenders.filter((t) => (t.id === AMLOCK ? s.tenderVendors.some((x) => x.vid === id) : otherTenderVendors[t.id]?.includes(id)));
  const toggle = () => modal.confirm({
    title: v.status === 'Active' ? `Deactivate ${v.name}?` : `Reactivate ${v.name}?`,
    content: v.status === 'Active' ? 'Inactive vendors cannot be added to new tenders. Existing tenders are unaffected.' : 'The vendor becomes selectable for new tenders again.',
    onOk: () => { s.updateVendor(v.id, { status: v.status === 'Active' ? 'Inactive' : 'Active' }); message.success('Vendor updated.'); },
  });
  return (
    <>
      <PageHeader title={<Space>{v.name}<Tag color={v.status === 'Active' ? 'success' : 'default'}>{v.status}</Tag></Space>} subtitle={v.legal}
        extra={s.isAdmin && <Space><Button icon={<EditOutlined />} onClick={() => nav(`/vendors/${v.id}/edit`)}>Edit</Button><Button danger={v.status === 'Active'} onClick={toggle}>{v.status === 'Active' ? 'Deactivate' : 'Reactivate'}</Button></Space>} />
      <Card title="Vendor information">
        <Descriptions bordered size="small" column={{ xs: 1, md: 2 }} items={[
          { key: 'reg', label: 'Registration no.', children: v.reg }, { key: 'c', label: 'Country', children: v.country },
          { key: 'i', label: 'Industry', children: v.industry }, { key: 'w', label: 'Website', children: v.website },
          { key: 'ct', label: 'Contact', children: v.contact }, { key: 'e', label: 'Email', children: v.email },
          { key: 'p', label: 'Phone', children: v.phone }, { key: 'a', label: 'Address', children: v.address },
          { key: 'n', label: 'Notes', span: 'filled', children: v.notes || '—' },
        ]} />
      </Card>
      <Card title="Tender participation" style={{ marginTop: 16 }}>
        <Table rowKey="id" size="small" pagination={false} dataSource={part} locale={{ emptyText: 'Not part of any tender yet.' }} columns={[
          { title: 'Tender', dataIndex: 'name', render: (n, t) => <a onClick={() => nav(`/tenders/${t.id}/overview`)}>{n}</a> },
          { title: 'Status', dataIndex: 'status', render: (st) => <TenderStatusTag status={st} /> },
        ]} />
      </Card>
    </>
  );
}
