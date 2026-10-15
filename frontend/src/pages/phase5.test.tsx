import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { accessibilityViolations, fakeApi, makeMe, renderPage } from "../test/helpers";
import { Audit } from "./Audit";
import { Notifications } from "./Notifications";
import { ChairPanel } from "./ChairPanel";
import { Report } from "./Report";
import { Route, Routes } from "react-router";

const T = "22222222-2222-4222-8222-222222222222";
const TV = "33333333-3333-4333-8333-333333333333";
const U1 = "55555555-5555-4555-8555-555555555555";
const ME = "66666666-6666-4666-8666-666666666666";

describe("PG-18 Notification center", () => {
  it("lists notifications, marks one as read and saves an email choice", async () => {
    const calls: string[] = [];
    const api = fakeApi((call, init) => {
      calls.push(call);
      if (call === "GET /notifications")
        return {
          data: {
            items: [
              {
                id: "n1",
                notification_type: "report_finalized",
                title: "The report was finalized",
                created_at: "2026-10-15T10:00:00Z",
                read_at: null,
                link: `/tenders/${T}/report`,
                tender: { id: T, name: "Cloud platform" },
              },
            ],
            next_cursor: null,
          },
        };
      if (call === "GET /notification-preferences")
        return {
          data: {
            items: [{ notification_type: "report_finalized", email_enabled: false }],
          },
        };
      if (call === "PUT /notification-preferences") {
        expect((init as { body: unknown }).body).toEqual({
          items: [{ notification_type: "report_finalized", email_enabled: true }],
        });
        return {
          data: { items: [{ notification_type: "report_finalized", email_enabled: true }] },
        };
      }
      return { data: undefined };
    });
    const { container } = renderPage(<Notifications />, { api, me: makeMe("member") });
    expect(await screen.findByText("The report was finalized")).toBeInTheDocument();
    expect(screen.getByText("Unread")).toBeInTheDocument();
    expect(await accessibilityViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole("button", { name: "Mark as read" }));
    await waitFor(() => expect(calls).toContain("POST /notifications/{notificationId}/read"));
    await userEvent.click(screen.getByRole("checkbox", { name: /report finalized/ }));
    await waitFor(() => expect(calls).toContain("PUT /notification-preferences"));
  });
});

describe("Chair panel", () => {
  function progress() {
    return {
      data: {
        vendors: [{ tender_vendor_id: TV, name: "Acme Cloud" }],
        members: [{ id: U1, display_name: "Mia Member" }],
        cells: [
          {
            tender_vendor_id: TV,
            user_id: U1,
            status: "submitted",
            excluded: false,
            member_evaluation_id: "ev1",
          },
        ],
      },
    };
  }

  it("lets the Chair reopen an evaluation with a reason and shows nobody's grades", async () => {
    const posts: { path: string; body: unknown }[] = [];
    const api = fakeApi((call, init) => {
      if (call === "GET /tenders/{tenderId}/report/progress") return progress();
      if (call.startsWith("POST /member-evaluations")) {
        posts.push({ path: call, body: (init as { body: unknown }).body });
        return { data: {} };
      }
      return { data: undefined };
    });
    const me = makeMe("member");
    me.user.id = ME;
    const { container } = renderPage(<ChairPanel tenderId={T} isChair />, { api, me });
    expect(await screen.findByText("Submitted")).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole("button", { name: "Reopen Mia Member's evaluation of Acme Cloud" }),
    );
    const confirm = screen.getByRole("button", { name: "Reopen evaluation" });
    expect(confirm).toBeDisabled();
    await userEvent.type(screen.getByLabelText("Note (required)"), "The pricing was misread");
    expect(await accessibilityViolations(container)).toEqual([]);
    await userEvent.click(confirm);
    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0].path).toBe("POST /member-evaluations/{memberEvaluationId}/reopen");
    expect(posts[0].body).toEqual({ reason: "The pricing was misread" });
  });

  it("shows the administrator the matrix without controls", async () => {
    const api = fakeApi((call) =>
      call === "GET /tenders/{tenderId}/report/progress" ? progress() : { data: undefined },
    );
    renderPage(<ChairPanel tenderId={T} isChair={false} />, { api, me: makeMe("admin") });
    expect(await screen.findByText("Submitted")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Reopen/ })).not.toBeInTheDocument();
  });

  it("renders nothing for a member who is not the Chair", async () => {
    const api = fakeApi((call) =>
      call === "GET /tenders/{tenderId}/report/progress"
        ? { status: 403, error: { title: "Forbidden", code: "forbidden", status: 403 } }
        : { data: undefined },
    );
    renderPage(<ChairPanel tenderId={T} isChair={false} />, { api, me: makeMe("member") });
    await waitFor(() => expect(api.GET).toHaveBeenCalled());
    await waitFor(() =>
      expect(screen.queryByText("Loading the committee's progress")).not.toBeInTheDocument(),
    );
    expect(
      screen.queryByRole("heading", { name: "Evaluations by member" }),
    ).not.toBeInTheDocument();
  });
});

describe("PG-19 Consolidated report", () => {
  const R1 = "77777777-7777-4777-8777-777777777777";
  const header = (over: Record<string, unknown> = {}) => ({
    tender_id: T,
    tender_name: "Cloud platform",
    version_no: 1,
    status: "in_progress",
    baseline_version: 2,
    scoring_model: {
      points_fully: 1,
      points_partially: 0.5,
      points_non: 0,
      mandatory_rule: "flag_only",
      weights: [],
      locked: true,
    },
    committee: [{ id: U1, display_name: "Mia Member" }],
    disagreement_count: 1,
    ai_model_versions: ["stub 1"],
    actions: {
      finalize: { allowed: false, reason: "1 disagreements still need a final grade" },
      export: { allowed: true },
    },
    ...over,
  });
  const tender = {
    id: T,
    name: "Cloud platform",
    status: "consolidation",
    chair: { id: ME, display_name: "Chair Person" },
    actions: {},
    row_version: 1,
  };
  const handler = (extra: (call: string, init?: Record<string, unknown>) => unknown) =>
    fakeApi((call, init) => {
      const out = extra(call, init);
      if (out !== undefined) return out;
      switch (call) {
        case "GET /tenders/{tenderId}":
          return { data: tender };
        case "GET /tenders/{tenderId}/report":
          return { data: header() };
        case "GET /tenders/{tenderId}/report/overview":
          return {
            data: {
              vendors: [
                {
                  tender_vendor_id: TV,
                  vendor_name: "Acme Cloud",
                  available: true,
                  outcome: "evaluated",
                  rank: 1,
                  final_score: 25,
                  compliance_percent: 0,
                  mandatory_failures: 1,
                  fails_mandatory: true,
                  counts: { fully_compliant: 0, partially_compliant: 1, non_compliant: 1 },
                  agreement_level: 50,
                  awaiting: [],
                },
              ],
            },
          };
        case "GET /tenders/{tenderId}/report/matrix":
          return {
            data: {
              vendors: [{ tender_vendor_id: TV, name: "Acme Cloud" }],
              rows: [
                {
                  requirement: {
                    id: R1,
                    display_no: "REQ-001",
                    text: "Encrypt data at rest",
                    category: "",
                    importance: "mandatory",
                  },
                  cells: [{ tender_vendor_id: TV, available: true, has_disagreement: true }],
                },
              ],
            },
          };
        case "GET /tenders/{tenderId}/report/matrix/{requirementId}/{tenderVendorId}":
          return {
            data: {
              requirement: {
                id: R1,
                display_no: "REQ-001",
                text: "Encrypt data at rest",
                category: "",
                importance: "mandatory",
              },
              tender_vendor_id: TV,
              final_grade: null,
              member_grades: [
                {
                  member: { id: U1, display_name: "Mia Member" },
                  grade: "fully_compliant",
                  review_state: "agreed",
                },
                {
                  member: { id: "other", display_name: "Omar Member" },
                  grade: "partially_compliant",
                  review_state: "overridden",
                  note: "Backups only",
                },
              ],
              ai: null,
            },
          };
        case "GET /tenders/{tenderId}/report/versions":
          return { data: [{ version_no: 1, status: "in_progress" }] };
        case "GET /tenders/{tenderId}/report/screening-outcomes":
          return { data: [] };
        case "GET /tenders/{tenderId}/report/appendix":
          return {
            data: {
              clarification_lists: [],
              override_stats: [{ tender_vendor_id: TV, overridden: 1, total: 2, percent: 50 }],
              ai_model_versions: [],
              audit_summary: { events: 0 },
            },
          };
        case "GET /tenders/{tenderId}/report/progress":
          return { status: 403, error: { title: "Forbidden", code: "forbidden", status: 403 } };
        default:
          return { data: undefined };
      }
    });

  function chair() {
    const me = makeMe("member");
    me.user.id = ME;
    return me;
  }

  function page() {
    return (
      <Routes>
        <Route path="/tenders/:tenderId/report" element={<Report />} />
      </Routes>
    );
  }

  it("shows the Chair the status, ranking and a disabled finalize with its reason", async () => {
    const api = handler(() => undefined);
    const { container } = renderPage(page(), {
      api,
      me: chair(),
      route: `/tenders/${T}/report`,
    });
    expect(await screen.findByText("Report version 1")).toBeInTheDocument();
    expect(screen.getAllByText("Acme Cloud", { selector: "th" }).length).toBeGreaterThan(0);
    expect(screen.getByText(/1 failed \(flag\)/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Finalize report" })).toBeDisabled();
    expect(screen.getByText(/1 disagreements still need a final grade/)).toBeInTheDocument();
    expect(await accessibilityViolations(container)).toEqual([]);
  });

  it("records a final grade only with a grade and a justification", async () => {
    const puts: unknown[] = [];
    const api = handler((call, init) => {
      if (call === "PUT /tenders/{tenderId}/report/grades/{tenderVendorId}/{requirementId}") {
        puts.push((init as { body: unknown }).body);
        return { data: {} };
      }
      return undefined;
    });
    renderPage(page(), { api, me: chair(), route: `/tenders/${T}/report` });
    await userEvent.click(
      await screen.findByRole("button", { name: /Open REQ-001 for Acme Cloud/ }),
    );
    expect(await screen.findByText("Backups only")).toBeInTheDocument();
    const record = screen.getByRole("button", { name: "Record final grade" });
    expect(record).toBeDisabled();
    await userEvent.click(screen.getByRole("radio", { name: "Partially compliant" }));
    await userEvent.type(
      screen.getByLabelText("Justification (required)"),
      "Backups only is partial coverage",
    );
    await userEvent.click(record);
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).toEqual({
      final_grade: "partially_compliant",
      justification: "Backups only is partial coverage",
    });
  });

  it("changes a recorded decision with its version, shows the earlier ones, and reloads on a stale save", async () => {
    const puts: { header: unknown }[] = [];
    let stale = true;
    const api = handler((call, init) => {
      if (call === "GET /tenders/{tenderId}/report/matrix/{requirementId}/{tenderVendorId}")
        return {
          data: {
            requirement: {
              id: "r1",
              display_no: "REQ-001",
              text: "Encrypt data",
              importance: "mandatory",
              category: "",
            },
            tender_vendor_id: TV,
            final_grade: "non_compliant",
            basis: "chair_decision",
            justification: "On second reading nothing is encrypted.",
            decided_by: { id: ME, display_name: "Chair Person" },
            decided_at: "2026-10-15T10:00:00Z",
            row_version: 2,
            history: [
              {
                final_grade: "partially_compliant",
                justification: "Backups only is partial coverage.",
                decided_by: { id: ME, display_name: "Chair Person" },
                decided_at: "2026-10-15T09:00:00Z",
                superseded_at: "2026-10-15T10:00:00Z",
              },
            ],
            member_grades: [
              {
                member: { id: U1, display_name: "Mia Member" },
                grade: "fully_compliant",
                review_state: "agreed",
              },
              {
                member: { id: ME, display_name: "Chair Person" },
                grade: "non_compliant",
                review_state: "overridden",
              },
            ],
          },
        };
      if (call === "PUT /tenders/{tenderId}/report/grades/{tenderVendorId}/{requirementId}") {
        puts.push({ header: (init as { params: { header: unknown } }).params.header });
        if (stale) {
          stale = false;
          return {
            status: 412,
            error: { title: "Changed", code: "precondition_failed", status: 412 },
          };
        }
        return { data: {} };
      }
      return undefined;
    });
    renderPage(page(), { api, me: chair(), route: `/tenders/${T}/report` });
    await userEvent.click(
      await screen.findByRole("button", { name: /Open REQ-001 for Acme Cloud/ }),
    );
    expect(await screen.findByText("Backups only is partial coverage.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("radio", { name: "Fully compliant" }));
    await userEvent.type(
      screen.getByLabelText("Justification (required)"),
      "The clarification shows full coverage",
    );
    await userEvent.click(screen.getByRole("button", { name: "Record final grade" }));
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0].header).toEqual({ "If-Match": '"2"' });
    // refused as stale: the cell reloads and the form stays for another try
    await userEvent.click(screen.getByRole("button", { name: "Record final grade" }));
    await waitFor(() => expect(puts).toHaveLength(2));
  });

  it("lists what is missing when finalizing is refused", async () => {
    const api = handler((call) => {
      if (call === "GET /tenders/{tenderId}/report")
        return {
          data: header({ actions: { finalize: { allowed: true }, export: { allowed: true } } }),
        };
      if (call === "POST /tenders/{tenderId}/report/finalize")
        return {
          status: 422,
          error: {
            title: "The report is not ready to be finalized",
            code: "precheck_failed",
            status: 422,
            failures: [
              {
                code: "disagreements_undecided",
                message: "Acme Cloud: 1 disagreements have no final grade",
              },
            ],
          },
        };
      return undefined;
    });
    renderPage(page(), { api, me: chair(), route: `/tenders/${T}/report` });
    await userEvent.click(await screen.findByRole("button", { name: "Finalize report" }));
    await userEvent.click(screen.getAllByRole("button", { name: "Finalize report" }).at(-1)!);
    expect(
      await screen.findByText("Acme Cloud: 1 disagreements have no final grade"),
    ).toBeInTheDocument();
  });

  it("tells a member that an unfinalized report is not available", async () => {
    const api = handler((call) =>
      call === "GET /tenders/{tenderId}/report"
        ? { status: 404, error: { title: "Not found", code: "not_found", status: 404 } }
        : undefined,
    );
    renderPage(page(), { api, me: makeMe("member"), route: `/tenders/${T}/report` });
    expect(await screen.findByText("The report is not available yet")).toBeInTheDocument();
  });
});

describe("PG-20 Audit trail", () => {
  it("filters the trail, pages it and switches to the access log", async () => {
    const queries: Record<string, unknown>[] = [];
    const api = fakeApi((call, init) => {
      const query = (init as { params?: { query?: Record<string, unknown> } })?.params?.query ?? {};
      if (call === "GET /tenders")
        return { data: { items: [{ id: T, name: "Cloud platform" }], next_cursor: null } };
      if (call === "GET /audit-events") {
        queries.push(query);
        return {
          data: {
            items: [
              {
                id: "a1",
                occurred_at: "2026-10-15T10:00:00Z",
                actor: { id: U1, display_name: "Mia Member" },
                actor_role: "member",
                source: "person",
                object_type: "report",
                action: "report_finalized",
                note: null,
              },
            ],
            next_cursor: null,
          },
        };
      }
      if (call === "GET /document-access-events")
        return {
          data: {
            items: [
              {
                id: "x1",
                user: { id: U1, display_name: "Mia Member" },
                document_version_id: "dv-1",
                opened_at: "2026-10-15T09:00:00Z",
                watermark_shown: true,
              },
            ],
            next_cursor: null,
          },
        };
      return { data: undefined };
    });
    const { container } = renderPage(<Audit />, { api, me: makeMe("admin") });
    expect(await screen.findByText("Report finalized", { selector: "th" })).toBeInTheDocument();
    expect(await accessibilityViolations(container)).toEqual([]);
    await userEvent.selectOptions(screen.getByLabelText("Tender"), T);
    await userEvent.click(screen.getByRole("button", { name: "Search" }));
    await waitFor(() => expect(queries.some((q) => q.tender_id === T)).toBe(true));
    await userEvent.click(screen.getByRole("tab", { name: "Proposal access" }));
    expect(await screen.findByText("Shown")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Export CSV" })).not.toBeInTheDocument();
  });
});
