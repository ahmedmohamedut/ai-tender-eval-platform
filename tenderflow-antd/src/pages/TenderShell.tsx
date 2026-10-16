import { Outlet, useLocation, useNavigate, useParams } from 'react-router-dom';
import { App, Button, Flex, Result, Space, Typography } from 'antd';
import { ApartmentOutlined, ArrowRightOutlined, FileTextOutlined, TeamOutlined } from '@ant-design/icons';
import { useStore } from '../store';
import { AMLOCK } from '../data';
import { PageHeaderless, TenderStatusTag } from '../components/common';

/** Wraps every /tenders/:tid/* page: compact header (title, status, one next-step action) + the routed page. */
export default function TenderShell() {
  const { tid } = useParams();
  const s = useStore();
  const nav = useNavigate();
  const loc = useLocation();
  const { message } = App.useApp();
  const tender = s.tenders.find((t) => t.id === tid);
  const page = loc.pathname.split('/')[3];

  if (!tender) return <Result status="404" title="Tender not found" extra={<Button type="primary" onClick={() => nav('/tenders')}>Back to all tenders</Button>} />;
  if (tid !== AMLOCK) {
    return (
      <Result status="info" title={tender.name}
        subTitle="The full workflow screens are only built for “AMLock Migration to SAS” in this prototype."
        extra={<Button type="primary" onClick={() => nav('/tenders')}>Back to all tenders</Button>} />
    );
  }

  const next = s.isFinal
    ? { label: 'Open final report', run: () => nav(`/tenders/${AMLOCK}/report`), hide: page === 'report' }
    : s.canEvaluate
      ? { label: 'Continue evaluation · 1 outstanding', run: () => nav(`/tenders/${AMLOCK}/evaluation`), hide: page === 'evaluation' }
      : s.isAdmin ? { label: 'Send reminder', run: () => message.info('Demo only: no reminder was sent.'), hide: false } : null;

  return (
    <>
      <PageHeaderless>
        <Flex justify="space-between" align="center" gap={16} wrap>
          <div>
            <Space size={12} align="center">
              <Typography.Title level={4} style={{ margin: 0 }}>{tender.name}</Typography.Title>
              <TenderStatusTag status={tender.status} />
            </Space>
            <div>
              <Space size="large" wrap>
                <Typography.Text type="secondary"><TeamOutlined /> Committee: 2 members</Typography.Text>
                <Typography.Text type="secondary"><ApartmentOutlined /> Baseline v1</Typography.Text>
                <Typography.Text type="secondary"><FileTextOutlined /> {s.isFinal ? 'Report v1 · 15 Oct 2026' : 'Active evaluation'}</Typography.Text>
              </Space>
            </div>
          </div>
          {next && !next.hide && <Button type="primary" icon={<ArrowRightOutlined />} iconPlacement="end" onClick={next.run}>{next.label}</Button>}
        </Flex>
      </PageHeaderless>
      <Outlet />
    </>
  );
}
