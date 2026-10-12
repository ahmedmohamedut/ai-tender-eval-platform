import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { contrast } from "../lib/contrast";

const css = readFileSync(`${process.cwd()}/src/styles/tokens.css`, "utf8");
const token = (name: string): string => {
  const m = new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})`).exec(css);
  if (!m) throw new Error(`token ${name} missing`);
  return m[1]!;
};

describe("colour tokens (docs/design/02-ux-design.md)", () => {
  const surface = token("surface");
  const page = token("page");

  it("body and secondary text reach 4.5:1 on the surface and the page", () => {
    for (const t of ["text-primary", "text-secondary"]) {
      expect(contrast(token(t), surface)).toBeGreaterThanOrEqual(4.5);
      expect(contrast(token(t), page)).toBeGreaterThanOrEqual(4.5);
    }
  });

  it("the action colour carries white text and a focus ring", () => {
    expect(contrast(token("action"), token("action-contrast"))).toBeGreaterThanOrEqual(4.5);
    expect(contrast(token("action"), surface)).toBeGreaterThanOrEqual(3);
    expect(contrast(token("action"), page)).toBeGreaterThanOrEqual(3);
  });

  it("control borders reach 3:1", () => {
    expect(contrast(token("border-control"), surface)).toBeGreaterThanOrEqual(3);
  });

  it.each(["success", "warning", "error", "info", "ai"])(
    "%s text reaches 4.5:1 on its tint",
    (name) => {
      expect(contrast(token(name), token(`${name}-tint`))).toBeGreaterThanOrEqual(4.5);
    },
  );

  it("the divider is decorative and never carries meaning", () => {
    expect(contrast(token("divider"), surface)).toBeLessThan(3);
  });
});
