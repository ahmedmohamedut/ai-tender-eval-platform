import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";
import { Shell } from "../app/Shell";
import {
  accessibilityViolations,
  choose,
  fakeApi,
  makeMe,
  optionsOf,
  renderPage,
} from "../test/helpers";
import { TenderForm } from "./TenderForm";
import { TenderHome } from "./TenderHome";
import { TendersList } from "./TendersList";
import { Users } from "./Users";

const TENDER = {
  id: "22222222-2222-4222-8222-222222222222",
  name: "Cloud platform",
  reference_no: "REF-1",
  description: "Hosting",
  status: "draft",
  archived: false,
  committee_size: 2,
  vendor_count: 0,
  chair: { id: "c1", display_name: "Casey Chair" },
  last_activity_at: "2026-10-01T10:00:00Z",
  due_date: "2027-03-01",
  my_pending_tasks: [],
  opened_at: null,
  stage_dates: [],
  members: [
    { user: { id: "c1", display_name: "Casey Chair" }, is_chair: true, status: "active" },
    { user: { id: "m1", display_name: "Morgan Member" }, is_chair: false, status: "active" },
  ],
  next_step: { text: "Upload the RFP and open the tender to the committee", target: "documents" },
  actions: { open: { allowed: false, reason: "Upload the RFP and choose a committee first" } },
  row_version: 3,
};

describe("PG-02 All tenders", () => {
  it("lists tenders with status as text and an icon, and is accessible", async () => {
    const api = fakeApi((call) =>
      call === "GET /tenders" ? { items: [TENDER], next_cursor: null, total: 1 } : undefined,
    );
    const { container } = renderPage(<TendersList />, { api });
    expect(await screen.findByRole("link", { name: "Cloud platform" })).toBeInTheDocument();
    const table = screen.getByRole("table");
    expect(within(table).getByText("Draft")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Create tender" })).toBeInTheDocument();
    expect(await accessibilityViolations(container)).toEqual([]);
  });

  it("shows the empty state with one primary action to an administrator", async () => {
    const api = fakeApi(() => ({ items: [], total: 0 }));
    renderPage(<TendersList />, { api });
    expect(await screen.findByText("No tenders yet")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Create tender" }).length).toBeGreaterThan(0);
  });

  it("tells a member who is not assigned what happens next (PG-08)", async () => {
    const api = fakeApi(() => ({ items: [], total: 0 }));
    renderPage(<TendersList mine />, { api, me: makeMe("member", "Morgan Member") });
    expect(await screen.findByText(/You are not assigned to any tender yet/)).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Create tender" })).not.toBeInTheDocument();
  });

  it("searches and asks the API only for the tenders the caller may see", async () => {
    const api = fakeApi(() => ({ items: [], total: 0 }));
    renderPage(<TendersList mine />, { api, me: makeMe("member") });
    await screen.findByText(/not assigned/);
    expect(api.GET).toHaveBeenCalledWith(
      "/tenders",
      expect.objectContaining({ params: { query: expect.objectContaining({ mine: true }) } }),
    );
    await userEvent.type(screen.getByLabelText("Search tenders"), "cloud");
    await waitFor(() =>
      expect(api.GET).toHaveBeenLastCalledWith(
        "/tenders",
        expect.objectContaining({ params: { query: expect.objectContaining({ q: "cloud" }) } }),
      ),
    );
  });

  it("shows a failure with a retry, never a blank page", async () => {
    const api = fakeApi(() => ({
      status: 500,
      error: {
        title: "Something went wrong",
        status: 500,
        code: "internal_error",
        trace_id: "abc",
      },
    }));
    renderPage(<TendersList />, { api });
    expect(await screen.findByRole("alert")).toHaveTextContent("Something went wrong");
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});

describe("PG-03 Create tender", () => {
  const lookup = {
    items: [
      { id: "c1", display_name: "Casey Chair" },
      { id: "m1", display_name: "Morgan Member" },
    ],
  };

  it("shows the server's field errors next to the fields and keeps what was typed", async () => {
    const api = fakeApi((call) => {
      if (call === "GET /users/lookup") return lookup;
      if (call === "POST /tenders") {
        return {
          status: 409,
          error: { title: "x", status: 409, code: "name_taken", trace_id: "t" },
        };
      }
      return undefined;
    });
    const { container } = renderPage(<TenderForm />, { api, route: "/tenders/new" });
    await screen.findByLabelText("Casey Chair");
    await userEvent.type(screen.getByLabelText(/Name \(required\)/), "Cloud platform");
    await userEvent.click(screen.getByLabelText("Casey Chair"));
    await choose(/Chair \(required\)/, "Casey Chair");
    await userEvent.click(screen.getByRole("button", { name: "Create tender" }));
    const nameField = screen.getByLabelText(/Name \(required\)/);
    await waitFor(() => expect(nameField).toHaveAttribute("aria-invalid", "true"));
    expect(screen.getByText(/A tender with this name already exists/)).toBeInTheDocument();
    expect(nameField).toHaveValue("Cloud platform");
    expect(await accessibilityViolations(container)).toEqual([]);
    expect(api.POST).toHaveBeenCalledWith(
      "/tenders",
      expect.objectContaining({
        body: expect.objectContaining({
          member_ids: ["c1"],
          chair_id: "c1",
          name: "Cloud platform",
        }),
      }),
    );
  });

  it("only offers the chosen members as Chair", async () => {
    const api = fakeApi((call) => (call === "GET /users/lookup" ? lookup : undefined));
    renderPage(<TenderForm />, { api, route: "/tenders/new" });
    await screen.findByLabelText("Morgan Member");
    expect(await optionsOf(/Chair \(required\)/)).not.toContain("Morgan Member");
    await userEvent.click(screen.getByLabelText("Morgan Member"));
    expect(await optionsOf(/Chair \(required\)/)).toContain("Morgan Member");
  });
});

describe("PG-03 Edit tender", () => {
  it("explains why the tender cannot be opened yet and stays focusable", async () => {
    const api = fakeApi((call) => {
      if (call === "GET /tenders/{tenderId}") return TENDER;
      if (call === "GET /tenders/{tenderId}/readiness")
        return {
          ready: false,
          items: [
            { key: "rfp_uploaded", label: "RFP uploaded", done: false },
            { key: "committee_chosen", label: "Committee chosen", done: true },
          ],
        };
      if (call === "GET /users/lookup") return { items: [] };
      return undefined;
    });
    const { container } = renderPage(
      <Routes>
        <Route path="/tenders/:tenderId/edit" element={<TenderForm />} />
      </Routes>,
      { api, route: `/tenders/${TENDER.id}/edit` },
    );
    const open = await screen.findByRole("button", { name: "Open to committee" });
    expect(open).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByText("Upload the RFP and choose a committee first")).toBeInTheDocument();
    expect(await screen.findByText(/RFP uploaded/)).toBeInTheDocument();
    expect(await accessibilityViolations(container)).toEqual([]);
  });

  it("saves with the version the editor loaded (If-Match)", async () => {
    const api = fakeApi((call) => {
      if (call === "GET /tenders/{tenderId}") return TENDER;
      if (call === "GET /tenders/{tenderId}/readiness") return { ready: false, items: [] };
      if (call === "GET /users/lookup") return { items: [] };
      if (call === "PATCH /tenders/{tenderId}") return { ...TENDER, row_version: 4 };
      return undefined;
    });
    renderPage(
      <Routes>
        <Route path="/tenders/:tenderId/edit" element={<TenderForm />} />
        <Route path="/tenders/:tenderId" element={<p>Tender overview page</p>} />
      </Routes>,
      { api, route: `/tenders/${TENDER.id}/edit` },
    );
    const name = await screen.findByLabelText(/Name \(required\)/);
    await userEvent.clear(name);
    await userEvent.type(name, "Renamed");
    await userEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(api.PATCH).toHaveBeenCalled());
    // saving returns to the overview the page was opened from
    expect(await screen.findByText("Tender overview page")).toBeInTheDocument();
    expect(api.PATCH).toHaveBeenCalledWith(
      "/tenders/{tenderId}",
      expect.objectContaining({
        params: { path: { tenderId: TENDER.id }, header: { "If-Match": '"3"' } },
        body: expect.objectContaining({ name: "Renamed" }),
      }),
    );
  });
});

describe("PG-03 Edit tender, leaving without saving", () => {
  it("offers a way back to the overview inside the page", async () => {
    const api = fakeApi((call) => {
      if (call === "GET /tenders/{tenderId}") return TENDER;
      if (call === "GET /tenders/{tenderId}/readiness") return { ready: false, items: [] };
      if (call === "GET /users/lookup") return { items: [] };
      return undefined;
    });
    renderPage(
      <Routes>
        <Route path="/tenders/:tenderId/edit" element={<TenderForm />} />
      </Routes>,
      { api, route: `/tenders/${TENDER.id}/edit` },
    );
    expect(await screen.findByRole("link", { name: "Back to tender overview" })).toHaveAttribute(
      "href",
      `/tenders/${TENDER.id}`,
    );
  });
});

describe("PG-09 Tender home", () => {
  it("shows the stage, the next step and the committee", async () => {
    const api = fakeApi((call) => (call === "GET /tenders/{tenderId}" ? TENDER : undefined));
    const { container } = renderPage(
      <Routes>
        <Route path="/tenders/:tenderId" element={<TenderHome />} />
      </Routes>,
      { api, route: `/tenders/${TENDER.id}` },
    );
    expect(
      await screen.findByRole("heading", { level: 1, name: /Cloud platform/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Upload the RFP and open the tender to the committee"),
    ).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Stage" })).toBeInTheDocument();
    expect(screen.getByText("Morgan Member")).toBeInTheDocument();
    expect(await accessibilityViolations(container)).toEqual([]);
  });

  it("names who to ask when access is refused (UX-ROL-04)", async () => {
    const api = fakeApi(() => ({
      status: 403,
      error: {
        title: "x",
        status: 403,
        code: "no_access",
        access_contacts: [{ name: "Ada Admin", email: "ada@example.test", role: "admin" }],
      },
    }));
    renderPage(
      <Routes>
        <Route path="/tenders/:tenderId" element={<TenderHome />} />
      </Routes>,
      { api, route: `/tenders/${TENDER.id}`, me: makeMe("member") },
    );
    expect(await screen.findByText("You do not have access to this tender")).toBeInTheDocument();
    expect(screen.getByText(/Ada Admin \(ada@example.test\)/)).toBeInTheDocument();
  });
});

describe("PG-07 Users", () => {
  const user = {
    id: "u1",
    email: "pat@example.test",
    display_name: "Pat Person",
    role: "member",
    status: "invited",
    time_zone: null,
    last_sign_in_at: null,
    created_at: "2026-01-01T00:00:00Z",
    row_version: 2,
  };

  it("lists users with labels, offers resend for an invited user, and is accessible", async () => {
    const api = fakeApi((call) =>
      call === "GET /users" ? { items: [user], total: 1 } : undefined,
    );
    const { container } = renderPage(<Users />, { api });
    expect(await screen.findByRole("rowheader", { name: "Pat Person" })).toBeInTheDocument();
    const table = screen.getByRole("table");
    expect(within(table).getByText("Committee member")).toBeInTheDocument();
    expect(within(table).getByText("Invited")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Resend invitation" })).toBeInTheDocument();
    expect(await accessibilityViolations(container)).toEqual([]);
  });

  it("changes a role with the row's version", async () => {
    const api = fakeApi((call) => {
      if (call === "GET /users") return { items: [user], total: 1 };
      if (call === "PATCH /users/{userId}") return { ...user, role: "observer", row_version: 3 };
      return undefined;
    });
    renderPage(<Users />, { api });
    await userEvent.click(await screen.findByRole("button", { name: "Change role" }));
    const dialog = await screen.findByRole("dialog");
    await choose("Role", "Observer", dialog);
    await userEvent.click(within(dialog).getByRole("button", { name: "Change role" }));
    await waitFor(() => expect(api.PATCH).toHaveBeenCalled());
    expect(api.PATCH).toHaveBeenCalledWith(
      "/users/{userId}",
      expect.objectContaining({
        params: { path: { userId: "u1" }, header: { "If-Match": '"2"' } },
        body: { role: "observer" },
      }),
    );
  });

  it("shows the problem in words when the server refuses", async () => {
    const api = fakeApi((call) => {
      if (call === "GET /users") return { items: [{ ...user, status: "active" }], total: 1 };
      if (call === "POST /users/{userId}/deactivate")
        return { status: 409, error: { title: "x", status: 409, code: "last_administrator" } };
      return undefined;
    });
    renderPage(<Users />, { api });
    await userEvent.click(await screen.findByRole("button", { name: "Deactivate" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Deactivate user" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "There must be at least one active administrator.",
    );
  });
});

describe("App shell", () => {
  const tree = (
    <Routes>
      <Route element={<Shell />}>
        <Route index element={<p>Home</p>} />
      </Route>
    </Routes>
  );

  it("offers an administrator tenders and users, with a skip link and one main landmark", async () => {
    const { container } = renderPage(tree, { api: fakeApi(() => undefined) });
    expect(screen.getByRole("link", { name: "Skip to content" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Tenders" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Users" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Categories" })).toBeInTheDocument();
    expect(screen.getAllByRole("main")).toHaveLength(1);
    expect(await accessibilityViolations(container)).toEqual([]);
  });

  it("hides what a committee member can never use (UX-ROL-03)", () => {
    renderPage(tree, { api: fakeApi(() => undefined), me: makeMe("member", "Morgan Member") });
    expect(screen.getByRole("link", { name: "My tenders" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Users" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Categories" })).not.toBeInTheDocument();
  });
});
