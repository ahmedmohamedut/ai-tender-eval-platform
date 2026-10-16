import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import type { Phase, Role, Tender, TenderVendor, Vendor, ScreeningStatus } from './types';
import { AMLOCK, seedTenders, seedTenderVendors, seedVendors } from './data';

export type ViewerState = { kind: 'rfp' | 'proposal'; vendor?: string } | null;
export type ConflictState = 'declared' | 'excluded';

interface Store {
  role: Role; setRole: (r: Role) => void;
  phase: Phase; setPhase: (p: Phase) => void;
  isFinal: boolean; isAdmin: boolean; isChair: boolean; canEvaluate: boolean;

  vendors: Vendor[]; getVendor: (id: string) => Vendor | undefined;
  addVendor: (v: Omit<Vendor, 'id'>) => string; updateVendor: (id: string, patch: Partial<Vendor>) => void;

  tenders: Tender[]; addTender: (t: Omit<Tender, 'id' | 'last' | 'created'>) => void;
  updateTender: (id: string, patch: Partial<Tender>) => void;

  tenderVendors: TenderVendor[];
  addTenderVendor: (vid: string) => void;
  setScreening: (vid: string, status: ScreeningStatus, note?: string) => void;

  reviewed: Record<string, string[]>; markReviewed: (vid: string, reqId: string) => void;
  conflict: Record<string, ConflictState>; declareConflict: (vid: string, has: boolean) => void;

  viewer: ViewerState; openViewer: (v: NonNullable<ViewerState>) => void; closeViewer: () => void;
  checked: string[]; toggleSection: (n: string, on: boolean) => void;

  collapsed: boolean; setCollapsed: (b: boolean) => void;
  themeMode: 'light' | 'dark'; setThemeMode: (m: 'light' | 'dark') => void;
  direction: 'ltr' | 'rtl'; setDirection: (d: 'ltr' | 'rtl') => void;
  searchOpen: boolean; setSearchOpen: (b: boolean) => void;
}

const Ctx = createContext<Store | null>(null);
export const useStore = () => {
  const s = useContext(Ctx);
  if (!s) throw new Error('StoreProvider missing');
  return s;
};

export function StoreProvider({ children, themeMode, setThemeMode, direction, setDirection }: {
  children: ReactNode; themeMode: 'light' | 'dark'; setThemeMode: (m: 'light' | 'dark') => void;
  direction: 'ltr' | 'rtl'; setDirection: (d: 'ltr' | 'rtl') => void;
}) {
  const [role, setRole] = useState<Role>('Administrator');
  const [phase, setPhase] = useState<Phase>('evaluation');
  const [vendors, setVendors] = useState<Vendor[]>(seedVendors);
  const [rawTenders, setTenders] = useState<Tender[]>(seedTenders);
  const [tenderVendors, setTenderVendors] = useState<TenderVendor[]>(seedTenderVendors);
  const [reviewed, setReviewed] = useState<Record<string, string[]>>({ SAS: ['REQ-001', 'REQ-002', 'REQ-003'], ABG: ['REQ-001'], _baseline: ['REQ-001', 'REQ-002', 'REQ-003'] });
  const [conflict, setConflict] = useState<Record<string, ConflictState>>({});
  const [viewer, setViewer] = useState<ViewerState>(null);
  const [checked, setChecked] = useState<string[]>(['4', '5']);
  const [collapsed, setCollapsed] = useState(false);
  const [searchOpen, setSearchOpen] = useState(false);

  const isFinal = phase === 'finalized';

  const addVendor = useCallback((v: Omit<Vendor, 'id'>) => {
    const base = v.name.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '').toUpperCase().slice(0, 14) || 'VENDOR';
    let id = base; let n = 2;
    setVendors((cur) => {
      while (cur.some((x) => x.id === id)) id = `${base}-${n++}`;
      return [...cur, { ...v, id }];
    });
    return id;
  }, []);

  const value = useMemo<Store>(() => ({
    role, setRole, phase, setPhase, isFinal,
    isAdmin: role === 'Administrator', isChair: role === 'Chair', canEvaluate: role === 'Member' || role === 'Chair',
    vendors, getVendor: (id) => vendors.find((v) => v.id === id),
    addVendor, updateVendor: (id, patch) => setVendors((c) => c.map((v) => (v.id === id ? { ...v, ...patch } : v))),
    // AMLock mirrors the stage switch and the live vendor list; other rows are plain data.
    tenders: rawTenders.map((t) => (t.id === AMLOCK ? { ...t, status: isFinal ? 'Finalized' : 'Evaluation', vendors: tenderVendors.length } : t)),
    addTender: (t) => setTenders((c) => [{ ...t, id: `t${Date.now()}`, last: '16 Oct 2026', created: true }, ...c]),
    updateTender: (id, patch) => setTenders((c) => c.map((t) => (t.id === id ? { ...t, ...patch, last: '16 Oct 2026' } : t))),
    tenderVendors,
    addTenderVendor: (vid) => setTenderVendors((c) => (c.some((x) => x.vid === vid) ? c : [...c, { vid, screening: 'Pending', eval: 'Not started', doc: `${vendors.find((v) => v.id === vid)?.name ?? vid} Proposal.pdf` }])),
    setScreening: (vid, status, note) => setTenderVendors((c) => c.map((x) => (x.vid === vid ? { ...x, screening: status, decisionNote: note } : x))),
    reviewed, markReviewed: (vid, reqId) => setReviewed((c) => ({ ...c, [vid]: Array.from(new Set([...(c[vid] ?? []), reqId])) })),
    conflict, declareConflict: (vid, has) => setConflict((c) => ({ ...c, [vid]: has ? 'excluded' : 'declared' })),
    viewer, openViewer: setViewer, closeViewer: () => setViewer(null),
    checked, toggleSection: (n, on) => setChecked((c) => (on ? Array.from(new Set([...c, n])) : c.filter((x) => x !== n))),
    collapsed, setCollapsed, themeMode, setThemeMode, direction, setDirection, searchOpen, setSearchOpen,
  }), [role, phase, isFinal, vendors, rawTenders, tenderVendors, reviewed, conflict, viewer, checked, collapsed, themeMode, direction, searchOpen, addVendor, setThemeMode, setDirection]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
