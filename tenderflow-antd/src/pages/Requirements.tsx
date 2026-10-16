import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Alert, App, Button, Card, Col, Collapse, Descriptions, Flex, Form, Input, List, Modal, Row, Space, Table, Tag, Typography } from 'antd';
import { CheckCircleOutlined, ExportOutlined, LockOutlined } from '@ant-design/icons';
import { useStore } from '../store';
import { reqs } from '../data';
import { PageHeader } from '../components/common';

const checks = ['Every requirement reviewed', 'No unresolved critical flags', 'Every requirement categorized', 'Mandatory classifications validated', 'Scoring configuration reviewed'];

export default function Requirements() {
  const s = useStore();
  const { message, modal } = App.useApp();
  const [sp] = useSearchParams();
  const [selId, setSelId] = useState(sp.get('req') ?? 'REQ-001');
  const [q, setQ] = useState('');
  const [editOpen, setEditOpen] = useState(false);
  const [form] = Form.useForm<{ reason: string }>();
  const sel = reqs.find((r) => r.id === selId) ?? reqs[0];
  const reviewed = s.reviewed._baseline ?? [];
  const rows = reqs.filter((r) => `${r.id} ${r.name} ${r.cat}`.toLowerCase().includes(q.toLowerCase()));
  const isReviewed = (id: string) => s.isFinal || reviewed.includes(id);

  const confirmBaseline = () => modal.confirm({
    title: 'Confirm requirements baseline?',
    content: 'The reviewed requirements become an immutable Baseline v1. Reopening requires a reason and affected evaluations become Outdated.',
    okText: 'Confirm baseline', onOk: () => message.success('Baseline confirmed (local preview).'),
  });

  return (
    <>
      <PageHeader title="Requirements" subtitle="Review AI-extracted requirements with their citations. A confirmed baseline is read-only."
        extra={<Button icon={<ExportOutlined />} onClick={() => s.openViewer({ kind: 'rfp' })}>Open RFP</Button>} />
      <Space wrap style={{ marginBottom: 16 }}>
        <Tag>26 requirements</Tag><Tag>26 mandatory</Tag>
        <Tag color={s.isFinal ? 'success' : 'processing'}>{s.isFinal ? 'Baseline v1 · locked' : 'Baseline v1 · draft'}</Tag>
        <Tag>{s.isFinal ? 'Review complete' : `Reviewed ${reviewed.length} / 8 (example rows)`}</Tag>
      </Space>

      {s.isChair && (
        <>
          <Alert showIcon style={{ marginBottom: 12 }} type={s.isFinal ? 'success' : 'info'} icon={s.isFinal ? <LockOutlined /> : <CheckCircleOutlined />}
            title={s.isFinal ? 'Baseline v1 is confirmed and locked' : 'All 5 pre-checks passed — ready to confirm baseline'}
            description={s.isFinal ? 'Reopening creates an auditable new version and needs a justification.' : 'Only the Chair can confirm. Confirming makes the requirements read-only for every vendor evaluation.'}
            action={<Button type="primary" disabled={s.isFinal} onClick={confirmBaseline}>{s.isFinal ? 'Confirmed · Read-only' : 'Confirm baseline'}</Button>} />
          <Collapse style={{ marginBottom: 16 }} items={[{
            key: 'pre', label: 'Pre-checks and scoring methodology',
            children: (
              <Row gutter={24}>
                <Col xs={24} md={12}><List size="small" dataSource={checks} renderItem={(c) => <List.Item extra={<Tag color="success">Passed</Tag>}><CheckCircleOutlined /> {c}</List.Item>} /></Col>
                <Col xs={24} md={12}>
                  <Descriptions column={1} size="small" bordered items={[
                    { key: 'f', label: 'Fully compliant', children: '1.0' }, { key: 'p', label: 'Partially compliant', children: '0.5' }, { key: 'n', label: 'Non-compliant', children: '0.0' }]} />
                  <Typography.Paragraph type="secondary" style={{ marginTop: 8, fontSize: 12 }}>A failed mandatory requirement stays visible as a flag; it does not remove the vendor from the ranking.</Typography.Paragraph>
                </Col>
              </Row>
            ),
          }]} />
        </>
      )}

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={14}>
          <Card title="Requirement register" extra={<Tag color="blue">AI + manual changes</Tag>}>
            <Input.Search allowClear placeholder="Search requirement or ID" value={q} onChange={(e) => setQ(e.target.value)} style={{ marginBottom: 12, maxWidth: 320 }} />
            <Table size="small" rowKey="id" pagination={false} dataSource={rows}
              onRow={(r) => ({ onClick: () => setSelId(r.id), style: { cursor: 'pointer' } })}
              rowClassName={(r) => (r.id === sel.id ? 'ant-table-row-selected' : '')}
              columns={[
                { title: 'ID', dataIndex: 'id', render: (v) => <a>{v}</a> },
                { title: 'Requirement', dataIndex: 'name' },
                { title: 'Category', dataIndex: 'cat' },
                { title: 'Importance', dataIndex: 'priority', render: (v) => <Tag>{v}</Tag> },
                { title: 'State', render: (_, r) => <Tag color={isReviewed(r.id) ? 'success' : 'warning'}>{s.isFinal ? 'Confirmed' : isReviewed(r.id) ? 'Reviewed' : 'Not reviewed'}</Tag> },
              ]} />
          </Card>
        </Col>
        <Col xs={24} xl={10}>
          <Card title={`${sel.id} detail`} extra={<Tag color="purple">AI origin</Tag>}>
            <Typography.Title level={5} style={{ marginTop: 0 }}>{sel.name}</Typography.Title>
            <Descriptions column={1} size="small" items={[
              { key: 'c', label: 'Category', children: sel.cat },
              { key: 'i', label: 'Importance', children: <Tag>{sel.priority}</Tag> },
              { key: 'a', label: 'Acceptance criteria', children: 'Vendor response provides sufficient documented evidence for this requirement.' },
              { key: 's', label: 'Source evidence', children: <Button type="link" size="small" style={{ padding: 0 }} icon={<ExportOutlined />} onClick={() => s.openViewer({ kind: 'rfp' })}>RFP v1, p. {sel.p} — open in viewer</Button> },
            ]} />
            <Flex gap={8} wrap style={{ marginTop: 16 }}>
              <Button onClick={() => modal.info({ title: 'Requirement history', content: 'AI extracted requirement, human reviews and baseline confirmation are retained as immutable events.' })}>History</Button>
              <Button type="primary" disabled={s.isFinal} onClick={() => { s.markReviewed('_baseline', sel.id); message.success(`${sel.id} marked reviewed (local demo).`); }}>Mark reviewed</Button>
              <Button disabled={s.isFinal} onClick={() => { form.resetFields(); setEditOpen(true); }}>Edit with reason</Button>
            </Flex>
            {s.isFinal && <Typography.Paragraph type="secondary" style={{ marginTop: 12, fontSize: 12 }}>This confirmed baseline cannot be modified without a controlled reopen.</Typography.Paragraph>}
          </Card>
        </Col>
      </Row>

      <Modal title={`Edit requirement · ${sel.id}`} open={editOpen} onCancel={() => setEditOpen(false)} okText="Save change" destroyOnHidden
        onOk={() => form.validateFields().then(() => { setEditOpen(false); message.success('Change recorded locally — no server record was changed.'); })}>
        <Typography.Paragraph type="secondary">The existing AI value stays visible in history. Explain the change before saving.</Typography.Paragraph>
        <Form form={form} layout="vertical">
          <Form.Item name="reason" label="Justification" rules={[{ required: true, min: 10, message: 'Add a justification of at least 10 characters.' }]}><Input.TextArea rows={4} placeholder="Explain the evidence or correction…" /></Form.Item>
        </Form>
      </Modal>
    </>
  );
}
