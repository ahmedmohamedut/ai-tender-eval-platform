import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, App, Avatar, Button, Card, Col, Collapse, Descriptions, Drawer, Flex, Form, Input, Modal, Progress, Result, Row, Segmented, Select, Space, Statistic, Table, Tag, Typography, theme } from 'antd';
import { CheckOutlined, DownloadOutlined, LockOutlined, PrinterOutlined, TeamOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { useStore } from '../store';
import { AMLOCK, reqs } from '../data';
import { GradeTag, PageHeader, gradeOptions } from '../components/common';
import { EvidenceDrawer } from '../components/EvidenceDrawer';
import type { GradeKey } from '../types';

const ranking = [{ v: 'SAS', score: 64.21, comp: 42.31, fails: 1, dist: '11 full · 14 partial · 1 non' }, { v: 'ABG', score: 62.71, comp: 46.15, fails: 2, dist: '12 full · 12 partial · 2 non' }];

export default function Report() {
  const s = useStore();
  const nav = useNavigate();
  const { message } = App.useApp();
  const { token } = theme.useToken();
  const [vf, setVf] = useState<'all' | 'SAS' | 'ABG'>('all');
  const [gf, setGf] = useState<GradeKey | 'all'>('all');
  const [evidence, setEvidence] = useState<{ id: string; vendor: string } | null>(null);
  const [vendorPanel, setVendorPanel] = useState<string | null>(null);
  const [reopen, setReopen] = useState(false);
  const [method, setMethod] = useState(false);
  const [form] = Form.useForm();

  if (!s.isFinal) {
    return (
      <>
        <PageHeader title="Final report" subtitle="Results stay gated until all evaluations are submitted and the Chair finalizes." />
        <Result icon={<LockOutlined />} title="Final report is not yet available" subTitle="In-progress member grades remain protected."
          extra={<Button type="primary" onClick={() => nav(`/tenders/${AMLOCK}/overview`)}>Back to overview</Button>} />
      </>
    );
  }

  const rows = reqs.filter((r) => gf === 'all' || r.grade === gf || r.other === gf);
  const jump = (id: string) => document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  const exportCsv = () => {
    const blob = new Blob(['Vendor,Final score,Mandatory failures\nSAS,64.21%,1\nABG,62.71%,2\n'], { type: 'text/csv;charset=utf-8' });
    const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = 'prototype-vendor-summary.csv'; a.click(); URL.revokeObjectURL(a.href);
    message.success('Prototype CSV exported — not an official report.');
  };
  const vp = ranking.find((r) => r.v === vendorPanel);

  return (
    <>
      <PageHeader title="Final evaluation report" subtitle="Locked report v1 · Finalized 15 Oct 2026, 4:30 PM · By Member 02"
        extra={<Space wrap><Button type="primary" icon={<DownloadOutlined />} onClick={() => window.print()}>Export PDF</Button><Button onClick={exportCsv}>Export CSV (demo)</Button><Button icon={<PrinterOutlined />} onClick={() => window.print()}>Print</Button></Space>} />

      {/* Sticky mini-index. (antd's <Anchor> rewrites location.hash, which would fight HashRouter, so this uses scrollIntoView.) */}
      <div style={{ position: 'sticky', top: 56, zIndex: 5, background: token.colorBgLayout, padding: '8px 0', marginBottom: 12 }}>
        <Segmented onChange={(v) => jump(String(v))} options={[{ value: 'summary', label: 'Summary' }, { value: 'ranking', label: 'Vendors' }, { value: 'matrix', label: 'Requirements' }, { value: 'governance', label: 'Governance & audit' }]} />
      </div>

      <div id="summary">
        <Alert showIcon type="warning" title="Mandatory requirement failures · Review before procurement decision"
          description="Both vendors have mandatory failures (SAS: 1; ABG: 2). A failure is flagged, not automatically excluded from the weighted ranking. The final score is not a procurement award decision." />
      </div>
      <Row gutter={[16, 16]} style={{ margin: '16px 0' }}>
        {[['Ranked vendors', '2', 'Both evaluated', <TeamOutlined key="i" />], ['Requirements', '26', 'Baseline v1', <UnorderedListOutlined key="i" />], ['Final disagreements', '2', 'Resolved by Chair', <CheckOutlined key="i" />], ['Report version', 'v1', 'Locked record', <LockOutlined key="i" />]].map(([t, v, h, i]) => (
          <Col key={String(t)} xs={24} sm={12} xl={6}><Card><Statistic title={t} value={v as string} prefix={i} /><Typography.Text type="secondary" style={{ fontSize: 12 }}>{h}</Typography.Text></Card></Col>
        ))}
      </Row>

      <Card id="ranking" title="Vendor comparison" extra={<Button onClick={() => setMethod(true)}>How scoring works</Button>}>
        <Typography.Paragraph type="secondary">Final scores ranked by the configured weighted methodology.</Typography.Paragraph>
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={12}>
            <Flex vertical gap={12}>
              {ranking.map((r, i) => (
                <Card key={r.v} size="small">
                  <Flex justify="space-between" align="center" gap={12}>
                    <Space><Avatar style={{ background: i ? token.colorFillSecondary : token.colorPrimaryBg, color: token.colorText }}>{i + 1}</Avatar>
                      <div><Typography.Text strong>{r.v}</Typography.Text><br /><Typography.Text type="secondary" style={{ fontSize: 12 }}>Rank {i + 1} · {r.fails} mandatory {r.fails === 1 ? 'failure' : 'failures'}</Typography.Text></div></Space>
                    <Space><Typography.Title level={4} style={{ margin: 0 }}>{r.score}%</Typography.Title><Button size="small" onClick={() => setVendorPanel(r.v)}>Details</Button></Space>
                  </Flex>
                  <Progress percent={r.score} showInfo={false} style={{ marginBottom: 0 }} />
                </Card>
              ))}
            </Flex>
          </Col>
          <Col xs={24} lg={12}>
            <Typography.Text strong>Score comparison</Typography.Text>
            {ranking.map((r) => <Flex key={r.v} align="center" gap={12}><span style={{ width: 48 }}>{r.v}</span><Progress percent={r.score} style={{ flex: 1 }} /></Flex>)}
            <Descriptions size="small" column={1} style={{ marginTop: 8 }} items={ranking.map((r) => ({ key: r.v, label: `Compliance · ${r.v}`, children: <b>{r.comp}%</b> }))} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>Rank and compliance are different metrics; failure counts remain visible.</Typography.Text>
          </Col>
        </Row>
      </Card>

      <Card id="matrix" title="Requirement results" style={{ marginTop: 16 }} extra={<Tag>26 total · 8 shown</Tag>}>
        <Typography.Paragraph type="secondary">Select any grade to inspect the AI suggestion, human review, final decision and citation.</Typography.Paragraph>
        <Flex gap={8} wrap style={{ marginBottom: 12 }}>
          <Select value={vf} onChange={setVf} style={{ minWidth: 150 }} options={[{ value: 'all', label: 'Both vendors' }, { value: 'SAS', label: 'SAS only' }, { value: 'ABG', label: 'ABG only' }]} />
          <Select value={gf} onChange={setGf} style={{ minWidth: 190 }} options={[{ value: 'all', label: 'All grades' }, ...gradeOptions]} />
          <Button onClick={() => { setVf('all'); setGf('all'); }}>Clear filters</Button>
        </Flex>
        <Table size="small" rowKey="id" pagination={false} dataSource={rows} columns={[
          { title: 'ID', dataIndex: 'id', width: 100, render: (v) => <a onClick={() => setEvidence({ id: v, vendor: 'SAS' })}>{v}</a> },
          { title: 'Requirement', render: (_, r) => <>{r.name}<br /><Typography.Text type="secondary" style={{ fontSize: 12 }}>{r.priority} · {r.cat}</Typography.Text></> },
          ...(vf !== 'ABG' ? [{ title: 'SAS', width: 200, render: (_: unknown, r: (typeof reqs)[number]) => <a onClick={() => setEvidence({ id: r.id, vendor: 'SAS' })}><GradeTag grade={r.grade} /></a> }] : []),
          ...(vf !== 'SAS' ? [{ title: 'ABG', width: 200, render: (_: unknown, r: (typeof reqs)[number]) => <a onClick={() => setEvidence({ id: r.id, vendor: 'ABG' })}><GradeTag grade={r.other} /></a> }] : []),
        ]} />
      </Card>

      <div id="governance" style={{ marginTop: 16 }}>
        <Typography.Title level={4}>Governance & audit</Typography.Title>
        <Collapse items={[
          { key: 'scr', label: <Space>Proposal screening outcomes <Tag color="success">2 accepted</Tag></Space>, children: <><Typography.Paragraph>Both SAS and ABG passed proposal screening, with committee recommendations and the Chair decision on record.</Typography.Paragraph><Button onClick={() => nav(`/tenders/${AMLOCK}/screening`)}>Review screening records</Button></> },
          { key: 'his', label: <Space>Committee sign-off and decision history <Tag>2 disagreements resolved</Tag></Space>, children: <><Typography.Paragraph>Submitting members, Chair justifications for final disagreements and finalization events. Protected grades are never exposed to unauthorized roles.</Typography.Paragraph>{s.isAdmin && <Button onClick={() => nav('/audit')}>Open audit trail</Button>}</> },
          { key: 'met', label: <Space>Scoring methodology and report metadata <Tag>Baseline v1</Tag></Space>, children: <Descriptions size="small" column={{ xs: 1, md: 2 }} items={[{ key: 'w', label: 'Weighted scoring', children: 'Fully = 1, Partially = 0.5, Non-compliant = 0. Mandatory failures remain flagged.' }, { key: 'v', label: 'Source and version', children: 'Baseline v1 · Report v1 · AI assistance used · model identifier and validation metadata belong here.' }]} /> },
        ]} />
      </div>

      <Alert style={{ marginTop: 16 }} showIcon type="success" icon={<LockOutlined />} title="Finalized record · Read-only"
        description="The report is an immutable version. Reopening requires a reason and produces a new report version. Export is allowed; direct editing is not."
        action={s.isChair ? <Button onClick={() => { form.resetFields(); setReopen(true); }}>Reopen controls</Button> : undefined} />

      <EvidenceDrawer reqId={evidence?.id ?? null} vendor={evidence?.vendor ?? 'SAS'} onClose={() => setEvidence(null)} />

      <Drawer open={!!vp} onClose={() => setVendorPanel(null)} size={460} title={`${vp?.v} · Vendor details`}>
        {vp && <Flex vertical gap={16}>
          <Alert showIcon type="warning" title="Mandatory failures" description={`${vp.fails} mandatory requirement${vp.fails > 1 ? 's' : ''} failed. Does not automatically exclude the vendor.`} />
          <Row gutter={12}><Col span={12}><Card size="small"><Statistic title="Final score" value={`${vp.score}%`} /></Card></Col><Col span={12}><Card size="small"><Statistic title="Compliance" value={`${vp.comp}%`} /></Card></Col></Row>
          <Descriptions bordered size="small" column={1} items={[{ key: 'd', label: 'Grade distribution', children: vp.dist }]} />
          <Button type="primary" onClick={() => { setVendorPanel(null); jump('matrix'); }}>View requirement results</Button>
        </Flex>}
      </Drawer>

      <Modal open={method} onCancel={() => setMethod(false)} footer={<Button onClick={() => setMethod(false)}>Close</Button>} title="Scoring methodology">
        <Typography.Paragraph>Fully compliant = 1, Partially compliant = 0.5, Non-compliant = 0. Mandatory failures remain visible and do not automatically remove ranking.</Typography.Paragraph>
      </Modal>

      <Modal title="Reopen finalized report?" open={reopen} onCancel={() => setReopen(false)} okText="Reopen" destroyOnHidden
        onOk={() => form.validateFields().then(() => { setReopen(false); message.success('Reopen recorded locally — no server record was changed.'); })}>
        <Typography.Paragraph type="secondary">The existing Report v1 remains immutable. Reopening starts a new version and requires a justification.</Typography.Paragraph>
        <Form form={form} layout="vertical"><Form.Item name="reason" label="Reason for reopening" rules={[{ required: true, min: 10, message: 'Add a justification of at least 10 characters.' }]}><Input.TextArea rows={3} /></Form.Item></Form>
      </Modal>
    </>
  );
}
