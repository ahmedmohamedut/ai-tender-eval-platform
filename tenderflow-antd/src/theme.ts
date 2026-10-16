import { theme, type ThemeConfig } from 'antd';

/**
 * Single source of truth for look & feel. Everything visual in the app is driven by these
 * design tokens (antd v6 renders them as CSS variables by default), so re-branding or dark mode
 * never needs per-component CSS.
 */
export function buildTheme(mode: 'light' | 'dark'): ThemeConfig {
  const dark = mode === 'dark';
  return {
    algorithm: dark ? theme.darkAlgorithm : theme.defaultAlgorithm,
    token: {
      colorPrimary: '#245fc7',
      colorInfo: '#245fc7',
      colorSuccess: '#137d55',
      colorWarning: '#d48806',
      colorError: '#cf3a36',
      borderRadius: 8,
      fontSize: 14,
      fontFamily: "Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', sans-serif",
      ...(dark ? {} : { colorBgLayout: '#f5f7fb' }),
    },
    components: {
      Layout: {
        headerHeight: 56,
        headerPadding: '0 24px',
        ...(dark ? {} : { headerBg: '#ffffff', siderBg: '#ffffff' }),
      },
      Menu: {
        itemBorderRadius: 8,
        itemMarginInline: 8,
        groupTitleFontSize: 11,
        ...(dark ? {} : { itemSelectedBg: '#eaf2ff', itemSelectedColor: '#245fc7' }),
      },
      Table: { cellPaddingBlock: 12, ...(dark ? {} : { headerBg: '#f8fafc' }) },
      Card: { headerFontSize: 16 },
      Statistic: { contentFontSize: 26 },
    },
  };
}
