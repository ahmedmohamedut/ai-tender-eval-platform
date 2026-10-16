import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Alert, App, Button, Card, Col, Descriptions, Empty, Flex, Form, Input, Modal, Progress, Radio, Row, Select, Space, Table, Tag, Typography } from 'antd';
import { useStore } from '../store';
import { AMLOCK, reqs } from '../data';
import { GradeTag, Highlight, PageHeader, Paper, gradeOptions } from '../components/common';
import { EvidenceDrawer } from '../components/EvidenceDrawer';
import type { GradeKey, Requirement } from '../types';

/** Placeholder grades for vendors beyond the two with seeded data. */
const gradeOf = (vid: string, r: Requirement): GradeKey =>
  vid === 'SAS' ? r.grade : vid === 'ABG' ? r.other : (['full', 'partial', 'non', 'partial'] as GradeKey[])[(r.p + vid.length) % 4];

export default function Evaluation() {
  const s = useStore();
  const nav = useNavigate();
  const { message, modal } = App.useApp();
  const [sp, setSp] = useSearchParams();
  const accepted = s.tenderVendors.filter((t) => t.screening === 'Accepted');
  const [vid, setVid] = useState(sp.get('vendor') ?? accepted[0]?.vid ?? 'SAS');
  const [selId, setSelId] = useState('REQ-001');
  const [q, setQ] = useState('');
  const [gf, setGf] = useState<GradeKey | 'all'>('all');
  const [overrides, setOverrides] = useState<Record<string, GradeKey>>({});
  const [overrideOpen, setOverrideOpen] = useState(false);
  const [evidenceId, setEvidenceId] = useState<string | null>(null);
  const [form] = Form.useForm<{ grade: GradeKey; reason: string }>();
  const [conflictForm] = Form.useForm<{ has: boolean; note?: string }>();
  const hasConflict = Form.useWatch('has', conflictForm);

  useEffect(() => { const v = sp.get('vendor'); if (v) setVid(v); }, [sp]);

  const vendorName = s.getVendor(vid)?.name ?? vid;
  const sel = reqs.find((r) => r.id === selId) ?? reqs[0];
  const done = s.reviewed[vid] ?? [];
  const decided = (id: string) => s.isFinal || done.includes(id);
  const effective = (r: Requirement) => overrides[`${vid}:${r.id}`] ?? gradeOf(vid, r);
  const rows = useMemo(() => reqs.filter((r) => `${r.id} ${r.name}`.toLowerCase().includes(q.toLowerCase())).filter((r) => gf === 'all' || gradeOf(vid, r) === gf), [q, gf, vid]);
  const needsDecl = s.canEvaluate && !s.isFinal && !s.conflict[vid];

  if (!s.canEvaluate) return <><PageHeader title="Evaluation" /><Alert showIcon type="info" title="Only committee members evaluate proposals" description="Switch to the Member or Chair role to see this screen." /></>;

  const header = (
    <PageHeader title="Evaluation" subtitle="Requirements on the left; AI rationale and the cited proposal passage beside them."
      extra={
        <Space>
          <Typography.Text type="secondary">Vendor</Typography.Text>
          <Select style={{ minWidth: 260 }} value={vid} aria-label="Vendor to evaluate" onChange={(v) => { setVid(v); setSp({ vendor: v }, { replace: true }); }}
            options={s.tenderVendors.map((t) => {
              const ok = t.screening === 'Accepted';
              const st = !ok ? 'awaiting screening' : s.isFinal && t.eval === 'In progress' ? 'Submitted' : t.eval;
              return { value: t.vid, disabled: !ok, label: `${s.getVendor(t.vid)?.name ?? t.vid} · ${st}` };
            })} />
        </Space>
      } />
  );

  if (s.conflict[vid] === 'excluded') {
    return <>{header}<Alert showIcon type="warning" title={`You are excluded from evaluating ${vendorName}`} description="Your declared conflict was recorded. The Chair will be notified and can reassign this vendor." /></>;
  }

  return (
    <>
      {header}
      <Flex gap={24} align="center" wrap style={{ marginBottom: 16 }}>
        <div style={{ minWidth: 220 }}>
          <Typography.Text type="secondary">Review progress</Typography.Text>
          <Progress percent={s.isFinal ? 100 : Math.round((done.length / reqs.length) * 100)} format={() => (s.isFinal ? '26 / 26' : `${done.length} / ${reqs.length}`)} />
        </div>
        <Tag>{s.isFinal ? 'Score 64.21%' : 'Score — complete review to calculate'}</Tag><Tag color="warning">2 need clarification</Tag><Tag>3 overridden</Tag>
      </Flex>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={14}>
          <Card title={`Requirements · ${vendorName}`} extra={<Tag color={s.isFinal ? 'success' : 'processing'}>{s.isFinal ? 'Submitted · read-only' : 'In progress'}</Tag>}
            actions={[<Flex key="a" justify="space-between" align="center" style={{ padding: '0 16px' }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>{s.isFinal ? 'Your evaluation has been submitted and locked.' : 'Review every requirement before submission.'}</Typography.Text>
              <Button type="primary" disabled={s.isFinal} onClick={() => modal.confirm({ title: 'Submit your evaluation?', content: "After submission your evaluation becomes read-only. Other members' grades become visible only when the submission rules allow.", okText: 'Submit', onOk: () => message.success('Submitted locally — no server record was changed.') })}>Submit evaluation</Button>
            </Flex>]}>
            <Flex gap={8} wrap style={{ marginBottom: 12 }}>
              <Input.Search allowClear placeholder="Search requirements" style={{ maxWidth: 260 }} value={q} onChange={(e) => setQ(e.target.value)} />
              <Select value={gf} style={{ minWidth: 190 }} onChange={setGf} options={[{ value: 'all', label: 'All AI grades' }, ...gradeOptions]} />
            </Flex>
            <Table size="small" rowKey="id" pagination={false} dataSource={rows} scroll={{ y: 420 }}
              onRow={(r) => ({ onClick: () => setSelId(r.id), style: { cursor: 'pointer' } })} rowClassName={(r) => (r.id === sel.id ? 'ant-table-row-selected' : '')}
              columns={[
                { title: 'ID', dataIndex: 'id', width: 90, render: (v) => <a>{v}</a> },
                { title: 'Requirement', dataIndex: 'name' },
                { title: 'AI suggestion', width: 190, render: (_, r) => <GradeTag grade={gradeOf(vid, r)} /> },
                { title: 'My decision', width: 150, render: (_, r) => overrides[`${vid}:${r.id}`] ? <Tag color="purple">Changed</Tag> : <Tag color={decided(r.id) ? 'success' : 'default'}>{decided(r.id) ? 'Agreed' : 'Not reviewed'}</Tag> },
              ]} />
          </Card>
        </Col>
        <Col xs={24} xl={10}>
          <Card title={`${sel.id} · Evidence`} extra={<Tag color="purple">AI suggestion</Tag>}>
            <Typography.Text strong>{sel.name}</Typography.Text>
            <div style={{ margin: '12px 0' }}><GradeTag grade={effective(sel)} /></div>
            <Typography.Text type="secondary">AI rationale</Typography.Text>
            <Typography.Paragraph>The response includes related controls. The provided excerpt does not verify every acceptance criterion, so evidence should be reviewed before agreement.</Typography.Paragraph>
            <Flex justify="space-between" style={{ marginBottom: 8 }}><Typography.Text strong>{vendorName} proposal · p. {sel.p}</Typography.Text><Button type="link" size="small" onClick={() => setEvidenceId(sel.id)}>Open full passage ↗</Button></Flex>
            <Flex justify="center"><Paper minHeight={150}>Our solution includes safeguards and operational procedures.<br /><br /><Highlight>{sel.name} is addressed through the documented security controls and periodic review process.</Highlight><br /><br />Additional details are provided in supporting appendices.</Paper></Flex>
            <Typography.Text type="secondary" style={{ display: 'block', margin: '16px 0 8px' }}>Your decision</Typography.Text>
            {s.isFinal ? <Space><GradeTag grade={effective(sel)} /><Tag color="success">Submitted</Tag></Space> : (
              <Space wrap>
                <Button type="primary" onClick={() => { s.markReviewed(vid, sel.id); message.success(`${sel.id}: agreed with AI (local demo).`); }}>Agree with AI</Button>
                <Button onClick={() => { form.setFieldsValue({ grade: effective(sel), reason: '' }); setOverrideOpen(true); }}>Change grade</Button>
                <Button onClick={() => message.info(`Flag added to ${sel.id} (local demo).`)}>Needs clarification</Button>
              </Space>
            )}
          </Card>
        </Col>
      </Row>

      <EvidenceDrawer reqId={evidenceId} vendor={vid} onClose={() => setEvidenceId(null)} />

      <Modal title={`Change grade · ${sel.id}`} open={overrideOpen} onCancel={() => setOverrideOpen(false)} okText="Save change" destroyOnHidden
        onOk={() => form.validateFields().then((v) => { setOverrides((o) => ({ ...o, [`${vid}:${sel.id}`]: v.grade })); s.markReviewed(vid, sel.id); setOverrideOpen(false); message.success('Grade change recorded locally.'); })}>
        <Typography.Paragraph type="secondary">The existing AI value stays visible in history. Explain the change before saving.</Typography.Paragraph>
        <Form form={form} layout="vertical">
          <Form.Item name="grade" label="New grade"><Select options={gradeOptions} /></Form.Item>
          <Form.Item name="reason" label="Justification" rules={[{ required: true, min: 10, message: 'Add a justification of at least 10 characters.' }]}><Input.TextArea rows={3} placeholder="Explain the evidence or correction…" /></Form.Item>
        </Form>
      </Modal>

      {/* Blocking gate: the first time a member opens a vendor's proposal. v6: `mask.closable` replaces `maskClosable`. */}
      <Modal title={`Conflict declaration · ${vendorName}`} open={needsDecl} closable={false} mask={{ closable: false }} keyboard={false} okText="Declare and continue" cancelText="Cancel"
        onCancel={() => nav(`/tenders/${AMLOCK}/overview`)} destroyOnHidden
        onOk={() => conflictForm.validateFields().then((v) => { s.declareConflict(vid, !!v.has); message.info(v.has ? `Conflict recorded — you are excluded from ${vendorName}.` : `Declaration recorded for ${vendorName}.`); conflictForm.resetFields(); })}>
        <Typography.Paragraph type="secondary">Confirm whether you have a conflict of interest related to this vendor. The declaration is recorded before you access the proposal.</Typography.Paragraph>
        <Form form={conflictForm} layout="vertical" initialValues={{ has: false }}>
          <Form.Item name="has"><Radio.Group><Space orientation="vertical"><Radio value={false}>I have no conflict of interest</Radio><Radio value={true}>I have a conflict of interest (exclusion required)</Radio></Space></Radio.Group></Form.Item>
          {hasConflict && <Form.Item name="note" label="Declaration note" rules={[{ required: true, min: 10, message: 'Explain the conflict (at least 10 characters).' }]}><Input.TextArea rows={3} /></Form.Item>}
        </Form>
      </Modal>
      {!rows.length && <Empty />}
    </>
  );
}
