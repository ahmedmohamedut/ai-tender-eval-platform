import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";
import { accessibilityViolations, fakeApi, makeMe, renderPage } from "../test/helpers";
import { Screening } from "./Screening";

const TENDER_ID = "22222222-2222-4222-8222-222222222222";
const TV = "33333333-3333-4333-8333-333333333333";
const TENDER = {
  id: TENDER_ID,
  name: "Cloud platform",
  status: "requirements",
  archived: false,
  committee_size: 2,
  vendor_count: 1,
  chair: { id: "11111111-1111-4111-8111-111111111111", display_name: "Casey Chair" },
  members: [],
  stage_dates: [],
  actions: {},
  next_step: null,
  row_version: 1,
};

function state(over: Record<string, unknown> = {}) {
  return {
    tender_vendor_id: TV,
    proposal_version: {
      id: "pv1",
      tender_vendor_id: TV,
      version_no: 1,
      status: "in_screening",
      uploaded_at: "2026-10-01T00:00:00Z",
      files: [
        {
          document_version_id: "dv1",
          file_name: "proposal.pdf",
          role: "main",
          status: "ready",
        },
      ],
    },
    checklist: [
      {
        item: {
          id: "i1",
          label: "Signed where required",
          sort_order: 1,
          is_default: true,
          is_active: true,
        },
        my_check: false,
      },
      {
        item: {
          id: "i2",
          label: "Within any page limit",
          sort_order: 2,
          is_default: true,
          is_active: true,
        },
        my_check: true,
      },
    ],
    recommendations: [
      {
        user: { id: "u2", display_name: "Morgan Member" },
        recommendation: "reject",
        reason: "Forms are missing",
      },
    ],
    my_recommendation: null,
    decision: null,
    prechecks: [
      { expected_section: "Pricing", found: false, note: "No section of the proposal covers it." },
      { expected_section: "Executive summary", found: true, note: "Executive summary (page 2)" },
    ],
    actions: {
      save_checks: { allowed: true },
      save_recommendation: { allowed: true },
      start_precheck: { allowed: true },
      confirm_decision: { allowed: false },
      reinstate: { allowed: false },
    },
    ...over,
  };
}

function handler(s: unknown, extra?: (call: string, init?: unknown) => unknown) {
  return (call: string, init?: unknown) => {
    if (extra) {
      const out = extra(call, init);
      if (out !== undefined) return out;
    }
    if (call === "GET /tenders/{tenderId}") return TENDER;
    if (call === "GET /tenders/{tenderId}/vendors") {
      return { items: [{ id: TV, vendor: { id: "v1", name: "Acme Cloud" } }], total: 1 };
    }
    if (call === "GET /tender-vendors/{tenderVendorId}/screening") return s;
    return undefined;
  };
}

function page() {
  return (
    <Routes>
      <Route path="/tenders/:tenderId/vendors/:tenderVendorId/screening" element={<Screening />} />
    </Routes>
  );
}

const ROUTE = `/tenders/${TENDER_ID}/vendors/${TV}/screening`;

describe("PG-14 Screening", () => {
  it("shows the checklist, the AI findings as text, and the committee's recommendations", async () => {
    const api = fakeApi(handler(state()));
    const { container } = renderPage(page(), { api, me: makeMe("member"), route: ROUTE });
    expect(
      await screen.findByRole("heading", { name: /Screening: Acme Cloud/ }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Signed where required")).not.toBeChecked();
    expect(screen.getByLabelText("Within any page limit")).toBeChecked();
    const table = screen.getByRole("table");
    expect(within(table).getByText("Not found")).toBeInTheDocument();
    expect(within(table).getByText("Found")).toBeInTheDocument();
    expect(screen.getByText(/Morgan Member:/)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Confirm the decision" })).not.toBeInTheDocument();
    expect(await accessibilityViolations(container)).toEqual([]);
  });

  it("saves the ticked items and a recommendation that needs a reason to reject", async () => {
    const calls: { call: string; body: unknown }[] = [];
    const api = fakeApi(
      handler(state(), (call, init) => {
        if (call.startsWith("PUT ")) {
          calls.push({ call, body: (init as { body: unknown }).body });
          return state();
        }
        return undefined;
      }),
    );
    renderPage(page(), { api, me: makeMe("member"), route: ROUTE });
    await userEvent.click(await screen.findByLabelText("Signed where required"));
    await userEvent.click(screen.getByRole("button", { name: "Save checklist" }));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.call).toBe("PUT /tender-vendors/{tenderVendorId}/screening/checks");
    expect(calls[0]!.body).toEqual({
      checks: [
        { checklist_item_id: "i1", is_checked: true, note: null },
        { checklist_item_id: "i2", is_checked: true, note: null },
      ],
    });
    await userEvent.click(screen.getByLabelText("Reject"));
    const save = screen.getByRole("button", { name: "Save recommendation" });
    expect(save).toBeDisabled();
    await userEvent.type(screen.getByLabelText("Reason (required)"), "The forms are missing");
    expect(save).toBeEnabled();
    await userEvent.click(save);
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1]!.body).toEqual({
      recommendation: "reject",
      reason: "The forms are missing",
    });
  });

  it("lets only the Chair decide, asks for a note to reject and sends the key", async () => {
    const posts: { call: string; body: unknown }[] = [];
    const chairState = state({
      actions: {
        save_checks: { allowed: true },
        save_recommendation: { allowed: true },
        start_precheck: { allowed: true },
        confirm_decision: { allowed: true },
        reinstate: { allowed: false },
      },
    });
    const api = fakeApi(
      handler(chairState, (call, init) => {
        if (call.startsWith("POST ")) {
          posts.push({ call, body: (init as { body: unknown }).body });
          return state({
            decision: {
              decision: "reject",
              decided_by: { id: "c", display_name: "Casey Chair" },
              decided_at: "2026-10-02T00:00:00Z",
              is_reinstatement: false,
            },
          });
        }
        return undefined;
      }),
    );
    renderPage(page(), { api, me: makeMe("member"), route: ROUTE });
    await userEvent.click(await screen.findByRole("button", { name: "Reject" }));
    const dialog = await screen.findByRole("dialog", { name: /Reject Acme Cloud/ });
    const confirm = within(dialog).getByRole("button", { name: "Reject the vendor" });
    expect(confirm).toBeDisabled();
    await userEvent.type(
      within(dialog).getByLabelText("Note (required)"),
      "The mandatory certificates are missing",
    );
    await userEvent.click(confirm);
    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0]!.call).toBe("POST /tender-vendors/{tenderVendorId}/screening/decision");
    expect(posts[0]!.body).toEqual({
      decision: "reject",
      reason: "The mandatory certificates are missing",
    });
  });
});
