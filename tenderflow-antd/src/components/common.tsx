import { Flex, Tag, Typography, theme } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined, MinusCircleOutlined } from '@ant-design/icons';
import type { ReactNode } from 'react';
import type { GradeKey, TenderStatus } from '../types';

/** Page title block used by every screen: Title + subtitle on the start side, actions on the end side. */
export function PageHeader({ title, subtitle, extra }: { title: ReactNode; subtitle?: ReactNode; extra?: ReactNode }) {
  return (
    <Flex justify="space-between" align="flex-start" gap={16} wrap style={{ marginBottom: 20 }}>
      <div>
        <Typography.Title level={3} style={{ margin: 0 }}>{title}</Typography.Title>
        {subtitle && <Typography.Text type="secondary">{subtitle}</Typography.Text>}
      </div>
      {extra}
    </Flex>
  );
}

const gradeMeta: Record<GradeKey, { label: string; color: string; icon: ReactNode }> = {
  full: { label: 'Fully compliant', color: 'success', icon: <CheckCircleOutlined /> },
  partial: { label: 'Partially compliant', color: 'warning', icon: <MinusCircleOutlined /> },
  non: { label: 'Non-compliant', color: 'error', icon: <CloseCircleOutlined /> },
};
export const gradeOptions = (Object.keys(gradeMeta) as GradeKey[]).map((k) => ({ value: k, label: gradeMeta[k].label }));
export function GradeTag({ grade }: { grade: GradeKey }) {
  const m = gradeMeta[grade];
  return <Tag color={m.color} icon={m.icon}>{m.label}</Tag>;
}

const statusColor: Record<TenderStatus, string> = { Finalized: 'success', Evaluation: 'processing', Requirements: 'warning', Draft: 'default' };
export const TenderStatusTag = ({ status }: { status: TenderStatus }) => <Tag color={statusColor[status]}>{status}</Tag>;

/** Prototype "document page": uses tokens so it follows light/dark automatically. */
export function Paper({ children, minHeight = 300 }: { children: ReactNode; minHeight?: number }) {
  const { token } = theme.useToken();
  return (
    <div style={{ width: '100%', maxWidth: 560, minHeight, padding: 32, background: token.colorBgContainer, color: token.colorTextSecondary, borderRadius: token.borderRadiusLG, boxShadow: token.boxShadowTertiary, fontSize: 12, lineHeight: 1.9 }}>
      {children}
    </div>
  );
}
export function Highlight({ children }: { children: ReactNode }) {
  const { token } = theme.useToken();
  return <mark style={{ background: token.colorWarningBg, color: token.colorText, padding: '1px 4px', borderRadius: 4 }}>{children}</mark>;
}

/** Divider-bottomed block used for the compact tender header. */
export function PageHeaderless({ children }: { children: ReactNode }) {
  const { token } = theme.useToken();
  return <div style={{ marginBottom: 20, paddingBottom: 16, borderBottom: `1px solid ${token.colorSplit}` }}>{children}</div>;
}
