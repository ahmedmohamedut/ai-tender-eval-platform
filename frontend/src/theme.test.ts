import { describe, expect, it } from "vitest";
import { contrast } from "./lib/contrast";
import { buildTheme, palettes, type Mode } from "./theme";

/** WCAG 2.2 AA for both modes (docs/design/02-ux-design.md, Visual foundations). */
describe.each<Mode>(["light", "dark"])("%s theme colours", (mode) => {
  const p = palettes[mode];
  const token = buildTheme(mode).token as Record<string, string>;

  it("text and secondary text reach 4.5:1 on the page, the surface and raised surfaces", () => {
    for (const bg of [p.layout, p.container, p.elevated]) {
      expect(contrast(token.colorText!, bg)).toBeGreaterThanOrEqual(4.5);
      expect(contrast(token.colorTextSecondary!, bg)).toBeGreaterThanOrEqual(4.5);
      expect(contrast(token.colorTextPlaceholder!, bg)).toBeGreaterThanOrEqual(4.5);
    }
  });

  it("the action colour carries its label, links and a 3:1 focus ring", () => {
    expect(contrast(token.colorPrimary!, token.colorTextLightSolid!)).toBeGreaterThanOrEqual(4.5);
    for (const bg of [p.layout, p.container, p.elevated]) {
      expect(contrast(token.colorLink!, bg)).toBeGreaterThanOrEqual(4.5);
    }
    expect(contrast(token.colorPrimary!, p.selectedBg)).toBeGreaterThanOrEqual(4.5);
  });

  it("control borders reach 3:1 and the divider stays decorative", () => {
    expect(contrast(token.colorBorder!, p.container)).toBeGreaterThanOrEqual(3);
    expect(contrast(token.colorBorder!, p.layout)).toBeGreaterThanOrEqual(3);
    expect(contrast(token.colorSplit!, p.container)).toBeLessThan(3);
  });

  it.each(["success", "warning", "error", "info", "ai"] as const)(
    "%s text reaches 4.5:1 on its tint and on the surface",
    (name) => {
      const [fg, tint] = p[name];
      expect(contrast(fg, tint)).toBeGreaterThanOrEqual(4.5);
      expect(contrast(fg, p.container)).toBeGreaterThanOrEqual(4.5);
    },
  );
});
