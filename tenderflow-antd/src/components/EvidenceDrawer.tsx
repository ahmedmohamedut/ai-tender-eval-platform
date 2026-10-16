import { Descriptions, Drawer, Flex, Typography } from 'antd';
import { reqs } from '../data';
import { GradeTag, Highlight, Paper } from './common';

/** Cited decision panel shared by the Evaluation and Final report screens. antd v6: Drawer uses `size`, not `width`. */
export function EvidenceDrawer({ reqId, vendor, onClose }: { reqId: string | null; vendor: string; onClose: () => void }) {
  const r = reqs.find((x) => x.id === reqId);
  return (
    <Drawer open={!!r} onClose={onClose} size={520} title={r ? `${r.id} · Evidence & final decision` : ''} destroyOnHidden>
      {r && (
        <Flex vertical gap={20}>
          <div>
            <Typography.Text type="secondary" style={{ letterSpacing: 1, textTransform: 'uppercase', fontSize: 11 }}>Requirement</Typography.Text>
            <Typography.Title level={5} style={{ margin: '4px 0' }}>{r.name}</Typography.Title>
            <Typography.Text type="secondary">Mandatory · {r.cat}</Typography.Text>
          </div>
          <Descriptions bordered size="small" column={1} items={[
            { key: 'g', label: `Final ${vendor} grade`, children: <GradeTag grade={vendor === 'ABG' ? r.other : r.grade} /> },
          ]} />
          <div>
            <Typography.Title level={5}>AI rationale</Typography.Title>
            <Typography.Paragraph type="secondary">The proposal evidence supports some or all acceptance criteria. A human reviewed this suggestion before it became final.</Typography.Paragraph>
          </div>
          <div>
            <Typography.Title level={5}>Source citation</Typography.Title>
            <Paper minHeight={150}>{vendor} proposal — p. {r.p}<br /><br /><Highlight>{r.name} is addressed through documented operational controls.</Highlight><br /><br />Supporting documentation includes procedures and implementation details.</Paper>
          </div>
          <div>
            <Typography.Title level={5}>Decision history</Typography.Title>
            <Typography.Paragraph type="secondary">AI suggestion → Member review → Final grade (Chair decision where disagreement existed). Justifications are retained in audit history.</Typography.Paragraph>
          </div>
        </Flex>
      )}
    </Drawer>
  );
}
