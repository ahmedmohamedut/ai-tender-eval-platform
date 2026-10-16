import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { accessibilityViolations, fakeApi, makeMe, renderPage } from "../test/helpers";
import { OpenProposalFile } from "../components/OpenProposalFile";
import { ViewerContext } from "../app/ViewerHost";

const TV = "33333333-3333-4333-8333-333333333333";
const DV = "44444444-4444-4444-8444-444444444444";

function page() {
  return <Harness />;
}

/** Stands in for the viewer window: it only records that a document was asked for. */
function Harness() {
  const [opened, setOpened] = useState<string | null>(null);
  return (
    <ViewerContext.Provider value={{ open: (v) => setOpened(v) }}>
      <OpenProposalFile
        tenderVendorId={TV}
        vendorName="Acme Cloud"
        documentVersionId={DV}
        fileName="proposal.pdf"
      />
      {opened === DV && <p>Viewer opened</p>}
    </ViewerContext.Provider>
  );
}

describe("PG-13 Conflict declaration", () => {
  it("asks a member who has not declared, then opens the file after a no-conflict declaration", async () => {
    const posts: unknown[] = [];
    const api = fakeApi((call, init) => {
      if (call === "GET /tender-vendors/{tenderVendorId}/declaration") return { data: undefined };
      if (call === "POST /tender-vendors/{tenderVendorId}/declaration") {
        posts.push((init as { body: unknown }).body);
        return { data: { has_conflict: false } };
      }
      return undefined;
    });
    const { container } = renderPage(page(), { api, me: makeMe("member"), route: "/" });
    await userEvent.click(screen.getByRole("button", { name: "Open proposal.pdf" }));
    await screen.findByRole("dialog", { name: /Before you open Acme Cloud/ });
    expect(await accessibilityViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole("button", { name: "Declare and continue" }));
    expect(await screen.findByText("Viewer opened")).toBeInTheDocument();
    expect(posts).toEqual([{ has_conflict: false, statement: null }]);
  });

  it("needs a description of a conflict and tells the member they are excluded", async () => {
    const posts: unknown[] = [];
    const api = fakeApi((call, init) => {
      if (call === "GET /tender-vendors/{tenderVendorId}/declaration") return { data: undefined };
      if (call === "POST /tender-vendors/{tenderVendorId}/declaration") {
        posts.push((init as { body: unknown }).body);
        return { data: { has_conflict: true } };
      }
      return undefined;
    });
    renderPage(page(), { api, me: makeMe("member"), route: "/" });
    await userEvent.click(screen.getByRole("button", { name: "Open proposal.pdf" }));
    await userEvent.click(await screen.findByLabelText(/I have a conflict of interest/));
    const submit = screen.getByRole("button", { name: "Declare and continue" });
    expect(submit).toBeDisabled();
    await userEvent.type(
      screen.getByLabelText("Describe the conflict (required)"),
      "I advised this vendor in 2025",
    );
    await userEvent.click(submit);
    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0]).toEqual({ has_conflict: true, statement: "I advised this vendor in 2025" });
    expect(screen.queryByText("Viewer opened")).not.toBeInTheDocument();
  });

  it("opens straight away after an earlier no-conflict declaration, and for the administrator", async () => {
    const api = fakeApi((call) => {
      if (call === "GET /tender-vendors/{tenderVendorId}/declaration") {
        return { data: { has_conflict: false } };
      }
      return undefined;
    });
    renderPage(page(), { api, me: makeMe("member"), route: "/" });
    await userEvent.click(screen.getByRole("button", { name: "Open proposal.pdf" }));
    expect(await screen.findByText("Viewer opened")).toBeInTheDocument();

    const adminApi = fakeApi(() => undefined);
    renderPage(page(), { api: adminApi, me: makeMe("admin"), route: "/" });
    const buttons = screen.getAllByRole("button", { name: "Open proposal.pdf" });
    await userEvent.click(buttons[buttons.length - 1]!);
    expect(adminApi.GET).not.toHaveBeenCalled();
  });
});
