import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { App, Button, Checkbox, Empty, Flex, Modal, Space, Tag, Typography, theme } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import { useStore } from '../store';
import { AMLOCK, proposalSections, rfpSections } from '../data';
import { Highlight, Paper } from './common';

/**
 * One document viewer for both the RFP and vendor proposals.
 * Rendered once in the app shell and opened from anywhere via `store.openViewer(...)`.
 * antd pieces: Modal (centered, custom footer), Checkbox, Tag, Typography.
 */
export function ViewerModal() {
  const s = useStore();
  const { modal } = App.useApp();
  const nav = useNavigate();
  const { token } = theme.useToken();
  const v = s.viewer;
  const [sel, setSel] = useState(0);
  useEffect(() => { if (v) setSel(v.kind === 'rfp' ? 3 : 2); }, [v]);
  if (!v) return <Modal open={false} />;

  const isRfp = v.kind === 'rfp';
  const vendor = v.vendor ? s.getVendor(v.vendor) : undefined;
  const tv = s.tenderVendors.find((t) => t.vid === v.vendor);
  const secs = isRfp
    ? rfpSections.map(([n, title, p, intro, hl, end]) => ({ n, title, p, intro, hl, end, restricted: false }))
    : proposalSections(vendor?.name ?? 'The vendor');
  const c = secs[Math.min(sel, secs.length - 1)];
  const title = isRfp ? 'RFP viewer · AMLock Migration RFP v1' : `Proposal viewer · ${vendor?.name ?? v.vendor}`;
  const sub = isRfp
    ? (s.isFinal ? 'Extraction is unavailable after finalization unless the tender is reopened.' : 'Tick sections to include in AI extraction; click a section name to preview it.')
    : `${tv?.doc ?? 'Proposal'} · v1 · read-only`;

  const canEval = !!tv && tv.screening === 'Accepted' && s.canEvaluate && !s.isFinal;
  return (
    <Modal
      open centered width={1040} onCancel={s.closeViewer} destroyOnHidden
      title={<div><Typography.Title level={5} style={{ margin: 0 }}>{title}</Typography.Title><Typography.Text type="secondary" style={{ fontWeight: 400, fontSize: 12 }}>{sub}</Typography.Text></div>}
      styles={{ body: { padding: 0 } }}
      footer={
        <Flex justify="space-between" align="center" gap={12} wrap>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {isRfp ? (s.isFinal ? 'Baseline is locked.' : `${s.checked.length} section(s) selected for extraction.`) : 'Prototype text is illustrative; highlights resolve to the stored passage in production.'}
          </Typography.Text>
          <Space>
            <Button onClick={s.closeViewer}>Close</Button>
            {isRfp && !s.isFinal && <Button type="primary" disabled={!s.checked.length} onClick={() => { s.closeViewer(); modal.confirm({ title: 'Start requirement extraction?', content: `Extract AI suggestions from ${s.checked.length} selected section(s). This starts a background job; suggestions need human review.`, okText: 'Start extraction' }); }}>Start extraction</Button>}
            {!isRfp && vendor && <Button onClick={() => { s.closeViewer(); nav(`/vendors/${vendor.id}`); }}>Vendor profile</Button>}
            {!isRfp && canEval && <Button type="primary" onClick={() => { s.closeViewer(); nav(`/tenders/${AMLOCK}/evaluation?vendor=${v.vendor}`); }}>Evaluate this proposal</Button>}
          </Space>
        </Flex>
      }
    >
      <Flex style={{ height: 'min(62vh, 560px)' }}>
        <div style={{ width: 310, flex: 'none', overflow: 'auto', padding: 12, borderInlineEnd: `1px solid ${token.colorSplit}` }}>
          <Typography.Text type="secondary" strong style={{ display: 'block', padding: '0 8px 8px' }}>{isRfp ? 'Document outline' : 'Proposal outline'}</Typography.Text>
          {secs.map((x, i) => (
            <Flex key={x.n} align="center" gap={8} onClick={() => setSel(i)}
              style={{ padding: '6px 8px', borderRadius: token.borderRadius, cursor: 'pointer', background: i === sel ? token.colorPrimaryBg : undefined }}>
              {isRfp && <Checkbox checked={s.checked.includes(x.n)} disabled={s.isFinal} onClick={(e) => e.stopPropagation()} onChange={(e) => s.toggleSection(x.n, e.target.checked)} aria-label={`Select section ${x.n} for extraction`} />}
              <span style={{ flex: 1 }}><b>{x.n}.</b> {x.title}</span>
              {isRfp ? (+x.n <= 3 ? <Tag color="success" style={{ margin: 0 }}>Extracted</Tag> : s.checked.includes(x.n) ? <Tag color="processing" style={{ margin: 0 }}>Selected</Tag> : <Tag style={{ margin: 0 }}>Not selected</Tag>)
                : x.restricted && <Tag color="warning" style={{ margin: 0 }}>Restricted</Tag>}
            </Flex>
          ))}
        </div>
        <Flex vertical align="center" gap={14} style={{ flex: 1, overflow: 'auto', padding: 22, background: token.colorFillQuaternary }}>
          <Flex justify="space-between" style={{ width: '100%', maxWidth: 560 }}>
            <Typography.Text strong>{isRfp ? 'RFP' : `${vendor?.name} proposal`} · section {c.n}</Typography.Text>
            <Typography.Text type="secondary">Page {c.p} of {isRfp ? 48 : 42}</Typography.Text>
          </Flex>
          {c.restricted ? (
            <Paper minHeight={400}><Empty image={<LockOutlined style={{ fontSize: 32 }} />} description={<><b>Commercial proposal is restricted</b><br />Financial material stays hidden during technical evaluation unless you have permission.</>} /></Paper>
          ) : (
            <Paper minHeight={400}><b>{c.n}. {c.title.toUpperCase()}</b><br /><br />{c.intro}<br /><br /><Highlight>{c.hl}</Highlight><br /><br />{c.end}</Paper>
          )}
        </Flex>
      </Flex>
    </Modal>
  );
}
