import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Alert, App, Button, Card, Form, Modal, Select, Space, Table, Tag, Typography, Upload } from 'antd';
import { FileOutlined, PlusOutlined, UploadOutlined } from '@ant-design/icons';
import { useStore } from '../store';
import { AMLOCK } from '../data';
import { PageHeader } from '../components/common';

export default function Documents() {
  const s = useStore();
  const nav = useNavigate();
  const loc = useLocation();
  const { message } = App.useApp();
  const [addOpen, setAddOpen] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [form] = Form.useForm<{ vendor?: string }>();
  const canManage = s.isAdmin && !s.isFinal;

  // Coming back from "Define a new vendor": reopen the picker with the new vendor preselected.
  useEffect(() => {
    const st = loc.state as { pickVendor?: string } | null;
    if (st?.pickVendor) { setAddOpen(true); form.setFieldValue('vendor', st.pickVendor); nav(loc.pathname, { replace: true, state: null }); }
  }, [loc, nav, form]);

  const used = s.tenderVendors.map((t) => t.vid);
  const available = s.vendors.filter((v) => v.status === 'Active' && !used.includes(v.id));

  return (
    <>
      <PageHeader title="Documents & vendors" subtitle="The RFP, each vendor and their proposal in one place."
        extra={canManage && <Space><Button icon={<UploadOutlined />} onClick={() => setUploadOpen(true)}>Upload document</Button><Button type="primary" icon={<PlusOutlined />} onClick={() => { form.resetFields(); setAddOpen(true); }}>Add vendor to tender</Button></Space>} />

      <Card title="RFP" extra={<Tag color="success">{s.isFinal ? 'Read-only' : 'Ready'}</Tag>}>
        <Table size="small" pagination={false} rowKey="n" dataSource={[{ n: 'AMLock Migration RFP.pdf' }]} columns={[
          { title: 'Document', render: (_, r) => <Typography.Text strong><FileOutlined /> {r.n}</Typography.Text> },
          { title: 'Type', render: () => 'RFP' }, { title: 'Version', render: () => 'v1' },
          { title: 'Processing', render: () => <Tag color="success">Ready</Tag> },
          { title: 'Action', render: () => <Button size="small" onClick={() => s.openViewer({ kind: 'rfp' })}>Open viewer</Button> },
        ]} />
      </Card>

      <Card title="Vendors & proposals" style={{ marginTop: 16 }}>
        <Table rowKey="vid" pagination={false} dataSource={s.tenderVendors}
          expandable={{
            defaultExpandedRowKeys: ['SAS'],
            expandedRowRender: (r) => (
              <Table size="small" pagination={false} rowKey="doc" dataSource={[r]} columns={[
                { title: 'Document', render: (_, x) => <Typography.Text strong><FileOutlined /> {x.doc}</Typography.Text> },
                { title: 'Version', render: () => 'v1' },
                { title: 'Processing', render: () => <Tag color="success">Ready</Tag> },
              ]} />
            ),
          }}
          columns={[
            { title: 'Vendor', render: (_, r) => <a onClick={() => nav(`/vendors/${r.vid}`)}>{s.getVendor(r.vid)?.name ?? r.vid}</a> },
            { title: 'Documents', render: () => '1 proposal' },
            { title: 'Screening', render: (_, r) => <Tag color={r.screening === 'Accepted' ? 'success' : r.screening === 'Rejected' ? 'error' : 'warning'}>{r.screening}</Tag> },
            { title: 'Evaluation', render: (_, r) => <Tag color={r.eval === 'Not started' ? 'default' : s.isFinal ? 'success' : 'processing'}>{s.isFinal && r.eval === 'In progress' ? 'Submitted' : r.eval}</Tag> },
            { title: 'Action', render: (_, r) => <Space><Button size="small" onClick={() => s.openViewer({ kind: 'proposal', vendor: r.vid })}>Open proposal</Button><Button size="small" onClick={() => nav(`/vendors/${r.vid}`)}>Vendor profile</Button></Space> },
          ]} />
      </Card>

      <Alert style={{ marginTop: 16 }} showIcon type="info" title="Versioning safeguards"
        description="Replacing a document requires a reason and shows affected requirements or evaluations first. Financial material stays hidden from technical scoring unless a user has permission." />

      <Modal title="Add vendor to this tender" open={addOpen} onCancel={() => setAddOpen(false)} okText="Add to tender" okButtonProps={{ disabled: !available.length }} destroyOnHidden
        onOk={() => form.validateFields().then(({ vendor }) => { s.addTenderVendor(vendor!); setAddOpen(false); message.success(`${s.getVendor(vendor!)?.name} added to this tender (local preview).`); })}>
        <Typography.Paragraph type="secondary">Choose from your defined vendors. Company details come from the vendor record.</Typography.Paragraph>
        <Form form={form} layout="vertical">
          {available.length ? (
            <>
              <Form.Item name="vendor" label="Vendor" rules={[{ required: true, message: 'Select a vendor' }]}>
                <Select placeholder="Select a vendor…" options={available.map((v) => ({ value: v.id, label: `${v.name} · ${v.country}` }))} />
              </Form.Item>
              <Form.Item label="Proposal document" extra="Optional — can be uploaded later."><Upload maxCount={1} beforeUpload={() => false} accept=".pdf,.docx"><Button icon={<UploadOutlined />}>Select file</Button></Upload></Form.Item>
            </>
          ) : <Alert type="warning" showIcon title="No available vendors" description="Every active vendor is already in this tender." />}
        </Form>
        <Typography.Text type="secondary">Vendor not in the list? <Button type="link" size="small" style={{ padding: 0 }} onClick={() => nav('/vendors/new', { state: { returnTo: `/tenders/${AMLOCK}/documents` } })}>Define a new vendor</Button></Typography.Text>
      </Modal>

      <Modal title="Upload document" open={uploadOpen} onCancel={() => setUploadOpen(false)} okText="Upload" destroyOnHidden onOk={() => { setUploadOpen(false); message.success('Document uploaded (local preview).'); }}>
        <Upload.Dragger maxCount={1} beforeUpload={() => false} accept=".pdf,.docx"><p style={{ margin: 0 }}><UploadOutlined /> Click or drag a file here</p></Upload.Dragger>
      </Modal>
    </>
  );
}
