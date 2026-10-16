import { useNavigate } from 'react-router-dom';
import { Alert, App, Button, Card, Col, List, Row, Statistic, Steps, Table, Tag, Typography } from 'antd';
import { CheckOutlined, FileTextOutlined, OrderedListOutlined, TeamOutlined } from '@ant-design/icons';
import { useStore } from '../store';
import { AMLOCK } from '../data';
import { PageHeader } from '../components/common';

export default function Overview() {
  const s = useStore();
  const nav = useNavigate();
  const { message } = App.useApp();
  const base = `/tenders/${AMLOCK}`;
  const accepted = s.tenderVendors.filter((t) => t.screening === 'Accepted').length;

  const stat = (title: string, value: string | number, hint: string, icon: React.ReactNode) => (
    <Col xs={24} sm={12} xl={6}>
      <Card><Statistic title={title} value={value} prefix={icon} /><Typography.Text type="secondary" style={{ fontSize: 12 }}>{hint}</Typography.Text></Card>
    </Col>
  );

  // Progress board: statuses only, never the grades themselves.
  const memberStatus = (vid: string, member: 1 | 2) => {
    const tv = s.tenderVendors.find((t) => t.vid === vid)!;
    if (tv.screening !== 'Accepted' || tv.eval === 'Not started') return 'Not started';
    if (s.isFinal) return 'Submitted';
    return vid === 'ABG' && member === 2 ? 'In progress' : 'Submitted';
  };
  const statusTag = (v: string) => <Tag color={v === 'Submitted' ? 'success' : v === 'In progress' ? 'warning' : 'default'}>{v}</Tag>;

  const action = s.isFinal
    ? { type: 'success' as const, title: 'The committee evaluation is finalized', desc: 'Results are locked in report version 1. Open rankings, mandatory failures and cited evidence.', btn: <Button type="primary" onClick={() => nav(`${base}/report`)}>View report</Button>, tag: <Tag color="success">Report ready</Tag> }
    : s.canEvaluate
      ? { type: 'warning' as const, title: 'Your evaluation is outstanding', desc: 'Continue your remaining vendor evaluation.', btn: <Button type="primary" onClick={() => nav(`${base}/evaluation`)}>Continue evaluation</Button>, tag: <Tag color="warning">Needs attention</Tag> }
      : { type: 'info' as const, title: 'One member evaluation is outstanding', desc: 'The report unlocks once every member has submitted.', btn: s.isAdmin ? <Button type="primary" onClick={() => message.info('Demo only: no reminder was sent.')}>Send reminder</Button> : undefined, tag: <Tag color="processing">In progress</Tag> };

  return (
    <>
      <PageHeader title="Overview" subtitle="Where this tender stands and what happens next." />
      <Row gutter={[16, 16]}>
        {stat('Documents', 1 + s.tenderVendors.length, 'RFP and vendor proposals', <FileTextOutlined />)}
        {stat('Vendors', s.tenderVendors.length, `${accepted} accepted at screening`, <TeamOutlined />)}
        {stat('Requirements', 26, 'Baseline v1 confirmed', <OrderedListOutlined />)}
        {stat('Evaluations', s.isFinal ? 'Complete' : '3 of 4', 'Member submissions', <CheckOutlined />)}
      </Row>
      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} xl={16}>
          <Card title="Next action" extra={action.tag}>
            <Alert showIcon type={action.type} title={action.title} description={action.desc} action={action.btn} />
          </Card>
          {(s.isChair || s.isAdmin) && (
            <Card title="Progress · vendor × member" style={{ marginTop: 16 }} extra={!s.isFinal && <Button size="small" onClick={() => message.info('Demo only: no reminder was sent.')}>Send reminder (demo)</Button>}>
              <Table size="small" pagination={false} rowKey="vid" dataSource={s.tenderVendors}
                columns={[
                  { title: 'Vendor', render: (_, r) => s.getVendor(r.vid)?.name ?? r.vid },
                  { title: 'Member 01', render: (_, r) => statusTag(memberStatus(r.vid, 1)) },
                  { title: 'Member 02', render: (_, r) => statusTag(memberStatus(r.vid, 2)) },
                  { title: 'Outstanding', render: (_, r) => { const n = [1, 2].filter((m) => memberStatus(r.vid, m as 1 | 2) !== 'Submitted').length; return n ? <Tag color="warning">{n} member{n > 1 ? 's' : ''}</Tag> : '—'; } },
                ]} />
              <Typography.Paragraph type="secondary" style={{ margin: '12px 0 0', fontSize: 12 }}>Status only — independent grades stay hidden until each vendor's submissions are complete.</Typography.Paragraph>
            </Card>
          )}
        </Col>
        <Col xs={24} xl={8}>
          <Card title="Committee" extra={<Tag>2 members</Tag>}>
            <List dataSource={['Member 01 · Committee member', 'Member 02 · Committee Chair']} renderItem={(m) => <List.Item extra={<Tag color="success">Submitted</Tag>}>{m}</List.Item>} />
          </Card>
          <Card title="Workflow" style={{ marginTop: 16 }}>
            <Steps orientation="vertical" size="small" current={s.isFinal ? 6 : 3}
              items={['Draft', 'Requirements', 'Screening', 'Evaluation', 'Consolidation', 'Finalized'].map((title) => ({ title }))} />
          </Card>
        </Col>
      </Row>
    </>
  );
}
