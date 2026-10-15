import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { accessibilityViolations, fakeApi, makeMe, renderPage } from "../test/helpers";
import { Notifications } from "./Notifications";
import { ChairPanel } from "./ChairPanel";

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
