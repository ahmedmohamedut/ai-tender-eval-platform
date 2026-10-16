import { useState } from 'react';
import { Alert, App, Button, Card, Col, Descriptions, Drawer, Form, Input, List, Modal, Radio, Row, Space, Tag, Typography } from 'antd';
import { CheckOutlined, ClockCircleOutlined } from '@ant-design/icons';
import { useStore } from '../store';
import { PageHeader } from '../components/common';
import type { ScreeningStatus } from '../types';

const checklist = ['Technical proposal available', 'Required documents present', 'Accepted file format', 'Committee recommendations recorded'];

export default function Screening() {
  const s = useStore();
  const { message } = App.useApp();
  const [decide, setDecide] = useState<string | null>(null);
  const [record, setRecord] = useState<string | null>(null);
  const [form] = Form.useForm<{ decision: ScreeningStatus; reason?: string }>();
  const decision = Form.useWatch('decision', form);
  const nameOf = (vid: string) => s.getVendor(vid)?.name ?? vid;
  const rec = s.tenderVendors.find((t) => t.vid === record);

  return (
    <>
      <PageHeader title="Screening" subtitle="Check completeness and capture recommendations. The Chair makes the final accept or reject decision." />
      <Row gutter={[16, 16]}>
        {s.tenderVendors.map((t) => {
          const pending = t.screening === 'Pending';
          return (
            <Col key={t.vid} xs={24} lg={12}>
              <Card title={nameOf(t.vid)} extra={<Tag color={t.screening === 'Accepted' ? 'success' : t.screening === 'Rejected' ? 'error' : 'warning'}>{pending ? 'Pending decision' : t.screening}</Tag>}>
                <Typography.Text type="secondary">{t.doc} · Processing complete</Typography.Text>
                <List size="small" header={<Typography.Text strong>Screening checklist</Typography.Text>} dataSource={checklist} renderItem={(c, i) => {
                  const wait = pending && i === 3;
                  return <List.Item extra={<Tag color={wait ? 'warning' : 'success'}>{wait ? 'Pending' : 'Passed'}</Tag>}>{wait ? <ClockCircleOutlined /> : <CheckOutlined />} {c}</List.Item>;
                }} />
                <Space style={{ marginTop: 16 }}>
                  <Button onClick={() => s.openViewer({ kind: 'proposal', vendor: t.vid })}>Open proposal</Button>
                  {pending && s.isChair && !s.isFinal
                    ? <Button type="primary" onClick={() => { form.resetFields(); setDecide(t.vid); }}>Record Chair decision</Button>
                    : <Button type="primary" disabled={pending} onClick={() => setRecord(t.vid)}>View decision record</Button>}
                </Space>
              </Card>
            </Col>
          );
        })}
      </Row>
      <Alert style={{ marginTop: 16 }} showIcon type="info" title="Decision gate" description="Committee recommendations stay separate from the Chair's decision. Rejections need a justification and remain in the audit trail." />

      <Modal title={`Chair decision · ${decide ? nameOf(decide) : ''}`} open={!!decide} onCancel={() => setDecide(null)} okText="Record decision" destroyOnHidden
        onOk={() => form.validateFields().then((v) => { s.setScreening(decide!, v.decision, v.reason); message.success(`${nameOf(decide!)} ${v.decision.toLowerCase()} (local preview).`); setDecide(null); })}>
        <Form form={form} layout="vertical" initialValues={{ decision: 'Accepted' }}>
          <Form.Item name="decision" label="Decision"><Radio.Group options={[{ value: 'Accepted', label: 'Accept for evaluation' }, { value: 'Rejected', label: 'Reject' }]} /></Form.Item>
          <Form.Item name="reason" label="Justification" rules={[{ required: decision === 'Rejected', min: 10, message: 'A rejection needs a justification of at least 10 characters.' }]}>
            <Input.TextArea rows={3} placeholder={decision === 'Rejected' ? 'Required for a rejection' : 'Optional'} />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer title="Screening decision" size={460} open={!!rec} onClose={() => setRecord(null)}>
        {rec && <Descriptions bordered column={1} size="small" items={[
          { key: 'v', label: 'Vendor', children: nameOf(rec.vid) },
          { key: 'd', label: 'Decision', children: <Tag color={rec.screening === 'Accepted' ? 'success' : 'error'}>{rec.screening}</Tag> },
          { key: 'b', label: 'Decided by', children: 'Member 02 · Committee Chair' },
          { key: 'n', label: 'Justification', children: rec.decisionNote || 'Committee recommendations recorded; all checks passed.' },
        ]} />}
      </Drawer>
    </>
  );
}
