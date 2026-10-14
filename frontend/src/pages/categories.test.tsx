import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { accessibilityViolations, fakeApi, renderPage } from "../test/helpers";
import { Categories } from "./Categories";

const cats = [
  { id: "c1", name: "Functional", sort_order: 1, is_active: true },
  { id: "c2", name: "Security", sort_order: 2, is_active: true },
  { id: "c3", name: "Legacy", sort_order: 3, is_active: false },
];

describe("Categories (UX-REQ-04)", () => {
  it("lists active and deactivated categories and is accessible", async () => {
    const api = fakeApi((call) => (call === "GET /categories" ? cats : undefined));
    const { container } = renderPage(<Categories />, { api });
    expect(await screen.findByRole("rowheader", { name: "Functional" })).toBeInTheDocument();
    expect(screen.getByText("Legacy")).toBeInTheDocument();
    expect(api.GET).toHaveBeenCalledWith(
      "/categories",
      expect.objectContaining({ params: { query: { include_inactive: true } } }),
    );
    expect(await accessibilityViolations(container)).toEqual([]);
  });

  it("adds a category with the next sort order", async () => {
    const api = fakeApi((call) => {
      if (call === "GET /categories") return cats;
      if (call === "POST /categories")
        return { id: "c4", name: "Hosting", sort_order: 4, is_active: true };
      return undefined;
    });
    renderPage(<Categories />, { api });
    await userEvent.click(await screen.findByRole("button", { name: "Add category" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Name (required)"), "Hosting");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add" }));
    await waitFor(() =>
      expect(api.POST).toHaveBeenCalledWith(
        "/categories",
        expect.objectContaining({ body: { name: "Hosting", sort_order: 4 } }),
      ),
    );
  });

  it("deactivates a category by sending its name with the change", async () => {
    const api = fakeApi((call) => {
      if (call === "GET /categories") return cats;
      if (call === "PATCH /categories/{categoryId}") return { ...cats[1], is_active: false };
      return undefined;
    });
    renderPage(<Categories />, { api });
    const row = (await screen.findByRole("rowheader", { name: "Security" })).closest("tr")!;
    await userEvent.click(within(row).getByRole("button", { name: "Deactivate" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Deactivate category" }));
    await waitFor(() =>
      expect(api.PATCH).toHaveBeenCalledWith(
        "/categories/{categoryId}",
        expect.objectContaining({
          params: { path: { categoryId: "c2" } },
          body: { name: "Security", is_active: false },
        }),
      ),
    );
  });

  it("swaps sort order with the neighbour when moved up", async () => {
    const api = fakeApi((call) => {
      if (call === "GET /categories") return cats;
      if (call === "PATCH /categories/{categoryId}") return cats[0];
      return undefined;
    });
    renderPage(<Categories />, { api });
    const row = (await screen.findByRole("rowheader", { name: "Security" })).closest("tr")!;
    await userEvent.click(within(row).getByRole("button", { name: "Move up" }));
    await waitFor(() => expect(api.PATCH).toHaveBeenCalledTimes(2));
    const bodies = api.PATCH.mock.calls.map((c) => (c[1] as { body: unknown }).body);
    expect(bodies).toContainEqual({ name: "Security", sort_order: 1 });
    expect(bodies).toContainEqual({ name: "Functional", sort_order: 2 });
  });

  it("says in words that the name is taken", async () => {
    const api = fakeApi((call) => {
      if (call === "GET /categories") return cats;
      if (call === "POST /categories")
        return { status: 409, error: { title: "x", status: 409, code: "already_exists" } };
      return undefined;
    });
    renderPage(<Categories />, { api });
    await userEvent.click(await screen.findByRole("button", { name: "Add category" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Name (required)"), "Security");
    await userEvent.click(within(dialog).getByRole("button", { name: "Add" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "A category with this name already exists.",
    );
  });
});
