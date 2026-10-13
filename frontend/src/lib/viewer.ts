/** Geometry of the viewer's highlight (UX-DOC-06): boxes are PDF points, origin top left. */
export interface Box {
  left: number;
  top: number;
  width: number;
  height: number;
}

export function highlightBoxes(bbox: number[][], scale: number): Box[] {
  return bbox
    .filter((r) => r.length === 4)
    .map(([x0, y0, x1, y1]) => ({
      left: x0 * scale,
      top: y0 * scale,
      width: Math.max(0, (x1 - x0) * scale),
      height: Math.max(0, (y1 - y0) * scale),
    }));
}

/** The page to open: a passage deep link wins, then ?page=, then the first page. */
export function startPage(
  passagePage: number | undefined,
  pageParam: string | null,
  pageCount: number,
): number {
  const fromParam = pageParam ? Number.parseInt(pageParam, 10) : NaN;
  const wanted = passagePage ?? (Number.isFinite(fromParam) ? fromParam : 1);
  return Math.min(Math.max(1, wanted), Math.max(1, pageCount));
}

/** Marks in a search snippet are « and »: split them so the page never injects HTML. */
export function snippetParts(snippet: string): { text: string; hit: boolean }[] {
  const parts: { text: string; hit: boolean }[] = [];
  let hit = false;
  for (const piece of snippet.split(/[«»]/)) {
    if (piece) parts.push({ text: piece, hit });
    hit = !hit;
  }
  return parts;
}
