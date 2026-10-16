# TenderFlow prototype — antd v6 port

Stack: antd 6.6.6, @ant-design/icons 6, React 19, react-router 7 (HashRouter), Vite + TypeScript. `npm i && npm run dev` (or `npm run build` → single-file `dist/index.html`).

## Screen → antd mapping
| Area | Components |
|---|---|
| App shell | `Layout` (hasSider) + `Sider` (collapsible, `breakpoint="lg"`), `Menu` (context-aware: global vs tender items), `Breadcrumb items`, `Dropdown` (user menu), `Popover` (jobs, notifications), `Drawer` (user profile, evidence, decision record, vendor details), `FloatButton` (prototype controls only — drop in production) |
| Tenders / vendors lists | `Card` + `Input.Search` + `Select` + `Table` |
| Create tender / vendor | `Form` (`useWatch`, `validateFields`, dependencies), `Checkbox.Group`, `DatePicker`, `Upload.Dragger`, `Steps orientation="vertical"` for readiness |
| Tender overview | `Statistic`, `Alert`, `Steps`, `Table`, `List` |
| Documents & vendors | `Table` with `expandable`, `Modal` + `Form` (pick vendor from registry) |
| Requirements | `Alert`, `Collapse items`, `Table`, `Descriptions items`, reason `Modal` |
| Screening | `Card`, `Modal` (Chair decision), `Drawer` |
| Evaluation | vendor `Select`, `Progress`, `Table`, `Modal` (grade change; blocking conflict declaration with `mask={{ closable:false }}`) |
| Report | `Segmented` index, `Statistic`, `Progress`, `Table`, `Collapse items`, `Drawer`, `Result` when locked |
| RFP / proposal viewer | centered `Modal` (width 1040) |

## v6 choices
- Theming only via `ConfigProvider theme` (tokens + `defaultAlgorithm`/`darkAlgorithm`) in `src/theme.ts`; CSS variables are the v6 default, so custom code uses `token` / `var(--ant-*)`. No Less, no `@ant-design/compatible`.
- `App` wrapper + `App.useApp()` for `message`/`modal`; no static `Modal.confirm`.
- v6 APIs used: `items` on Menu/Breadcrumb/Descriptions/Collapse/Steps, `Alert title`, `Drawer size`, `Modal destroyOnHidden` and `mask.closable`, `Space/Steps orientation`.
- RTL: `ConfigProvider direction` (toggle in prototype controls).
- antd `Anchor` is intentionally not used: it rewrites `location.hash`, which conflicts with HashRouter. Use BrowserRouter in production and you can swap the Segmented index for `Anchor`.

## Prototype limits
- Only "AMLock Migration to SAS" has full workflow screens; other tenders are list rows.
- Grades for vendors beyond SAS/ABG are placeholder values; the report ranking is still static SAS/ABG.
- All data is in-memory (`src/data.ts`, `src/store.tsx`); nothing persists.
