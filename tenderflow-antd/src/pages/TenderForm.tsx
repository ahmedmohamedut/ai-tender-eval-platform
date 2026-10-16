import { useMemo } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Alert, App, Button, Card, Checkbox, Col, DatePicker, Flex, Form, Input, Result, Row, Select, Space, Steps, Upload } from 'antd';
import { CheckCircleFilled, InboxOutlined, MinusCircleOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { useStore } from '../store';
import { committeePeople } from '../data';
import { PageHeader } from '../components/common';

interface Values { name?: string; desc?: string; due?: Dayjs; members?: string[]; chair?: string; rfp?: string[] }

export default function TenderForm() {
  const { id } = useParams();
  const s = useStore();
  const nav = useNavigate();
  const { message } = App.useApp();
  const [form] = Form.useForm<Values>();
  const existing = id ? s.tenders.find((t) => t.id === id) : undefined;
  const v = Form.useWatch([], form) as Values | undefined;

  const initial: Values = useMemo(() => existing ? { name: existing.name, desc: existing.desc, due: existing.due ? dayjs(existing.due) : undefined, members: existing.members, chair: existing.chair } : { members: [] }, [existing]);

  if (!s.isAdmin) return <Result status="403" title="Administrators only" subTitle="Switch to the Administrator role to create tenders." extra={<Button onClick={() => nav('/tenders')}>Back</Button>} />;

  const nameTaken = (n: string) => s.tenders.some((t) => t.id !== id && t.name.trim().toLowerCase() === n.trim().toLowerCase());
  const checks = [
    { label: 'Name', ok: !!v?.name?.trim() },
    { label: 'Committee (at least 2 members)', ok: (v?.members?.length ?? 0) >= 2 },
    { label: 'Chair selected', ok: !!v?.chair && !!v?.members?.includes(v.chair) },
    { label: 'RFP document', ok: !!v?.rfp?.length },
  ];
  const ready = checks.every((c) => c.ok);

  const save = (draft: boolean) => {
    const fields = draft ? ['name'] : ['name', 'members', 'chair'];
    form.validateFields(fields).then(() => {
      const val = form.getFieldsValue(true) as Values;
      const rec = { name: val.name!.trim(), desc: val.desc, due: val.due?.format('YYYY-MM-DD'), members: val.members ?? [], chair: val.chair, mc: val.members?.length ?? 0, rfp: val.rfp?.length ? 'RFP.pdf' : undefined, vendors: 0, status: (draft ? 'Draft' : ready ? 'Requirements' : 'Draft') as 'Draft' | 'Requirements' };
      if (existing) s.updateTender(existing.id, rec); else s.addTender(rec);
      message.success(draft ? 'Draft saved.' : ready ? 'Tender created — requirements extraction would start now.' : 'Tender saved as draft until the RFP is uploaded.');
      nav('/tenders');
    });
  };

  return (
    <>
      <PageHeader title={existing ? 'Continue tender setup' : 'Create tender'} subtitle="Name and committee are required; the RFP can be added later." />
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={16}>
          <Card>
            <Form form={form} layout="vertical" initialValues={initial} requiredMark="optional">
              <Form.Item name="name" label="Tender name" validateTrigger="onBlur" rules={[{ required: true, whitespace: true, message: 'Enter a tender name.' }, { validator: (_, val) => (val && nameTaken(val) ? Promise.reject(new Error('A tender with this name already exists.')) : Promise.resolve()) }]}><Input maxLength={120} showCount /></Form.Item>
              <Form.Item name="desc" label="Description"><Input.TextArea rows={3} /></Form.Item>
              <Form.Item name="due" label="Proposal due date"><DatePicker style={{ width: '100%' }} /></Form.Item>
              <Form.Item name="members" label="Committee members" rules={[{ type: 'array', min: 2, message: 'Select at least 2 members.' }]}><Checkbox.Group options={committeePeople} /></Form.Item>
              <Form.Item name="chair" label="Committee Chair" dependencies={['members']} rules={[{ required: true, message: 'Select a Chair.' }]}>
                <Select placeholder="Select from committee members" options={(v?.members ?? []).map((m) => ({ value: m, label: m }))} allowClear />
              </Form.Item>
              <Form.Item name="rfp" label="RFP document" valuePropName="fileList" getValueFromEvent={(e: { fileList: unknown[] }) => e.fileList.map((f) => (f as { name: string }).name)}>
                <Upload.Dragger beforeUpload={() => false} maxCount={1} accept=".pdf,.docx" onChange={(i) => form.setFieldValue('rfp', i.fileList.map((f) => f.name))} fileList={undefined}>
                  <p className="ant-upload-drag-icon"><InboxOutlined /></p><p className="ant-upload-text">Drop the RFP here, or click to browse</p><p className="ant-upload-hint">PDF or DOCX. Prototype: nothing is uploaded.</p>
                </Upload.Dragger>
              </Form.Item>
              <Flex justify="end" gap={8}><Button onClick={() => nav('/tenders')}>Cancel</Button><Button onClick={() => save(true)}>Save as draft</Button><Button type="primary" onClick={() => save(false)}>Create tender</Button></Flex>
            </Form>
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <Card title="Readiness">
            <Steps orientation="vertical" size="small" current={-1} items={checks.map((c) => ({ title: c.label, status: c.ok ? 'finish' : 'wait', icon: c.ok ? <CheckCircleFilled style={{ color: 'var(--ant-color-success)' }} /> : <MinusCircleOutlined /> }))} />
            <Alert style={{ marginTop: 16 }} showIcon type={ready ? 'success' : 'info'} title={ready ? 'Ready: the tender will start at Requirements.' : 'Incomplete: it will be stored as a Draft.'} />
          </Card>
        </Col>
      </Row>
    </>
  );
}
