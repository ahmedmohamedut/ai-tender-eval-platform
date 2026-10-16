import { theme, type ThemeConfig } from "antd";

export type Mode = "light" | "dark";

/**
 * The one place that decides how the product looks (docs/design/02-ux-design.md, Visual
 * foundations). antd v6 renders these tokens as CSS variables, so nothing else needs colour.
 * Every text, control and status colour is spelled out as a hex value, because antd's own
 * alpha-based defaults cannot be checked against WCAG 2.2 AA; theme.test.ts recomputes the
 * ratios for both modes.
 */
const palette = {
  light: {
    text: "#1d2939",
    textSecondary: "#475467",
    placeholder: "#667085",
    layout: "#f7f8fa",
    container: "#ffffff",
    elevated: "#ffffff",
    border: "#667085",
    divider: "#d0d5dd",
    primary: "#175cd3",
    onPrimary: "#ffffff",
    selectedBg: "#eff8ff",
    success: ["#067647", "#ecfdf3"],
    warning: ["#b54708", "#fffaeb"],
    error: ["#b42318", "#fef3f2"],
    info: ["#175cd3", "#eff8ff"],
    ai: ["#6941c6", "#f4f3ff"],
  },
  dark: {
    text: "#f2f4f7",
    textSecondary: "#cfd4dc",
    placeholder: "#98a2b3",
    layout: "#0c111d",
    container: "#101828",
    elevated: "#1d2939",
    border: "#667085",
    divider: "#344054",
    primary: "#84adff",
    onPrimary: "#0b1220",
    selectedBg: "#102a56",
    success: ["#47cd89", "#053321"],
    warning: ["#fdb022", "#3b2a07"],
    error: ["#f97066", "#55160c"],
    info: ["#84adff", "#102a56"],
    ai: ["#b692f6", "#27115f"],
  },
} as const;

export const palettes = palette;

export function buildTheme(mode: Mode): ThemeConfig {
  const p = palette[mode];
  return {
    algorithm: mode === "dark" ? theme.darkAlgorithm : theme.defaultAlgorithm,
    token: {
      colorPrimary: p.primary,
      colorLink: p.primary,
      colorTextLightSolid: p.onPrimary,
      colorInfo: p.info[0],
      colorInfoBg: p.info[1],
      colorSuccess: p.success[0],
      colorSuccessBg: p.success[1],
      colorWarning: p.warning[0],
      colorWarningBg: p.warning[1],
      colorError: p.error[0],
      colorErrorBg: p.error[1],
      colorText: p.text,
      colorTextHeading: p.text,
      colorTextSecondary: p.textSecondary,
      colorTextTertiary: p.textSecondary,
      colorTextDescription: p.textSecondary,
      colorTextPlaceholder: p.placeholder,
      colorBgLayout: p.layout,
      colorBgContainer: p.container,
      colorBgElevated: p.elevated,
      colorBorder: p.border,
      colorBorderSecondary: p.divider,
      colorSplit: p.divider,
      borderRadius: 8,
      fontSize: 14,
      controlHeight: 36,
      fontFamily: "Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif",
    },
    components: {
      Layout: {
        headerHeight: 56,
        headerPadding: "0 24px",
        headerBg: p.container,
        siderBg: p.container,
        bodyBg: p.layout,
      },
      Menu: {
        itemBorderRadius: 8,
        itemMarginInline: 8,
        groupTitleFontSize: 12,
        itemSelectedBg: p.selectedBg,
        itemSelectedColor: p.primary,
      },
      Table: { cellPaddingBlock: 12, headerBg: p.layout },
      Card: { headerFontSize: 16 },
      Statistic: { contentFontSize: 26 },
    },
  };
}
