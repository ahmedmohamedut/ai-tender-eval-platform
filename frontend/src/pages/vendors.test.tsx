import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";
import { accessibilityViolations, fakeApi, makeMe, renderPage } from "../test/helpers";
import { VendorDetail, VendorForm, VendorsList } from "./VendorRegistry";

const VENDOR = {
  id: "33333333-3333-4333-8333-333333333333",
  name: "Lumen Systems",
  legal_name: "Lumen Systems Ltd",
  registration_no: "REG-1",
  country: "Egypt",
  industry: "Technology",
  website: "https://lumen.example",
  contact_person: "Sam Seller",
  email: "sam@lumen.example",
  phone: "+20 100 000 0000",
  address: "1 Test Street",
  description: "Notes",
  status: "active",
  row_version: 4,
  created_at: "2026-10-01T10:00:00Z",
};

const at = (ui: React.ReactElement, path: string) => (
  <Routes>
    <Route path={path} element={ui} />
    <Route path="*" element={<p>elsewhere</p>} />
  </Routes>
);

describe("PG-05 Vendor registry", () => {
  it("lists the vendors to an administrator and refuses everyone else with words", async () => {
    const api = fakeApi((call) =>
      call === "GET /vendors" ? { items: [VENDOR], total: 1 } : undefined,
    );
    const { container, unmount } = renderPage(<VendorsList />, { api });
    expect(await screen.findByRole("link", { name: "Lumen Systems" })).toBeInTheDocument();
    expect(screen.getByText("Technology")).toBeInTheDocument();
    expect(within(screen.getByRole("table")).getByText("Active")).toBeInTheDocument();
    expect(await accessibilityViolations(container)).toEqual([]);
    unmount();

    const other = fakeApi(() => undefined);
    renderPage(<VendorsList />, { api: other, me: makeMe("member") });
    expect(await screen.findByText("Administrators only")).toBeInTheDocument();
    expect(other.GET).not.toHaveBeenCalled();
  });

  it("deactivates a vendor with the loaded version after a confirmation", async () => {
    const api = fakeApi((call) => {
      if (call === "GET /vendors/{vendorId}") return VENDOR;
      if (call === "PATCH /vendors/{vendorId}")
        return { ...VENDOR, status: "inactive", row_version: 5 };
      return undefined;
    });
    const { container } = renderPage(at(<VendorDetail />, "/vendors/:vendorId"), {
      api,
      route: `/vendors/${VENDOR.id}`,
    });
    expect(await screen.findByRole("heading", { name: /Lumen Systems/ })).toBeInTheDocument();
    expect(screen.getByText("REG-1")).toBeInTheDocument();
    expect(await accessibilityViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole("button", { name: "Deactivate" }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Deactivate vendor" }));
    await waitFor(() => expect(api.PATCH).toHaveBeenCalled());
    expect(api.PATCH).toHaveBeenCalledWith(
      "/vendors/{vendorId}",
      expect.objectContaining({
        params: { path: { vendorId: VENDOR.id }, header: { "If-Match": '"4"' } },
        body: { status: "inactive" },
      }),
    );
  });

  it("warns about a possible duplicate and creates the vendor only when confirmed", async () => {
    let posts = 0;
    const api = fakeApi((call) => {
      if (call !== "POST /vendors") return undefined;
      posts += 1;
      return posts === 1
        ? {
            status: 409,
            error: {
              title: "x",
              status: 409,
              code: "possible_duplicate",
              matches: [{ id: VENDOR.id, name: "Lumen Systems" }],
            },
          }
        : { ...VENDOR, id: "44444444-4444-4444-8444-444444444444" };
    });
    renderPage(at(<VendorForm />, "/vendors/new"), { api, route: "/vendors/new" });
    await userEvent.type(await screen.findByLabelText(/Vendor name/), "LUMEN Limited");
    await userEvent.click(screen.getByRole("button", { name: "Create vendor" }));
    expect(await screen.findByText("A similar vendor already exists")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Create it anyway" }));
    await waitFor(() => expect(api.POST).toHaveBeenCalledTimes(2));
    expect(api.POST).toHaveBeenLastCalledWith(
      "/vendors",
      expect.objectContaining({
        body: expect.objectContaining({ name: "LUMEN Limited", confirm_duplicate: true }),
      }),
    );
  });
});
