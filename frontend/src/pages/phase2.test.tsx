import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";
import {
  accessibilityViolations,
  choose,
  fakeApi,
  makeMe,
  optionsOf,
  renderPage,
} from "../test/helpers";
import { parseFrames } from "../lib/events";
import { highlightBoxes, snippetParts, startPage } from "../lib/viewer";
import { Documents } from "./Documents";
import { Jobs } from "./Jobs";
import { Vendors } from "./Vendors";

const TENDER_ID = "22222222-2222-4222-8222-222222222222";
const TENDER = {
  id: TENDER_ID,
  name: "Cloud platform",
  status: "draft",
  archived: false,
  committee_size: 2,
  vendor_count: 1,
  chair: { id: "c1", display_name: "Casey Chair" },
  members: [],
  stage_dates: [],
  actions: {},
  next_step: null,
  row_version: 1,
};

function version(over: Record<string, unknown> = {}) {
  return {
    id: "v1",
    document_id: "d1",
    version_no: 1,
    file_name: "rfp.pdf",
    file_format: "pdf",
    size_bytes: 1000,
    status: "ready",
    page_count: 12,
    uploaded_at: "2026-10-01T10:00:00Z",
    ...over,
  };
}

function doc(title: string, v: Record<string, unknown>, type = "rfp") {
  return {
    id: `d-${title}`,
    tender_id: TENDER_ID,
    doc_type: type,
    title,
    current_version: version(v),
    version_count: 1,
  };
}

function at(ui: React.ReactElement, path: string) {
  return (
    <Routes>
      <Route path={path} element={ui} />
    </Routes>
  );
}

describe("PG-04 Documents", () => {
  const handler = (items: unknown[]) => (call: string) => {
    if (call === "GET /tenders/{tenderId}") return TENDER;
    if (call === "GET /tenders/{tenderId}/documents")
      return { items, next_cursor: null, total: items.length };
    if (call === "GET /tenders/{tenderId}/vendors") return { items: [], total: 0 };
    return undefined;
  };

  it("shows each document with its status as text and a failure in plain words", async () => {
    const api = fakeApi(
      handler([
        doc("Cloud RFP", { status: "ready" }),
        doc("Scan", { status: "failed", failure_reason: "no_selectable_text" }, "other"),
      ]),
    );
    const { container } = renderPage(at(<Documents />, "/tenders/:tenderId/documents"), {
      api,
      route: `/tenders/${TENDER_ID}/documents`,
    });
    const table = await screen.findByRole("table");
    expect(within(table).getByText("Ready")).toBeInTheDocument();
    expect(within(table).getByText("Failed")).toBeInTheDocument();
    expect(
      within(table).getByText(/no selectable text. Upload a text-based copy/i),
    ).toBeInTheDocument();
    expect(within(table).getByRole("button", { name: /^Open / })).toBeInTheDocument();
    expect(await accessibilityViolations(container)).toEqual([]);
  });

  it("offers the upload to an administrator only, and not a second RFP", async () => {
    const api = fakeApi(handler([doc("Cloud RFP", {})]));
    renderPage(at(<Documents />, "/tenders/:tenderId/documents"), {
      api,
      route: `/tenders/${TENDER_ID}/documents`,
    });
    await userEvent.click(await screen.findByRole("button", { name: "Upload document" }));
    expect(await screen.findByText("Choose a file")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("combobox", { name: "Type" }));
    const rfp = (
      await screen.findByText(/RFP \(already uploaded\)/, {
        selector: ".ant-select-item-option-content",
      })
    ).closest(".ant-select-item-option");
    expect(rfp).toHaveClass("ant-select-item-option-disabled");
    expect(screen.getByText("Choose a file")).toBeInTheDocument();
  });

  it("gives a member the list and no upload controls", async () => {
    const api = fakeApi(handler([doc("Cloud RFP", {})]));
    renderPage(at(<Documents />, "/tenders/:tenderId/documents"), {
      api,
      me: makeMe("member"),
      route: `/tenders/${TENDER_ID}/documents`,
    });
    expect(await screen.findByRole("table")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Upload document" })).not.toBeInTheDocument();
    expect(screen.queryByText("New version")).not.toBeInTheDocument();
  });

  it("explains the empty state differently to an administrator and a member", async () => {
    const api = fakeApi(handler([]));
    renderPage(at(<Documents />, "/tenders/:tenderId/documents"), {
      api,
      route: `/tenders/${TENDER_ID}/documents`,
    });
    expect(await screen.findByText(/Upload the RFP to start/)).toBeInTheDocument();
  });
});

describe("PG-17 Job center", () => {
  const job = (over: Record<string, unknown>) => ({
    id: "j1",
    tender_id: TENDER_ID,
    job_type: "document_processing",
    status: "running",
    progress_percent: 40,
    current_step: "parse",
    target_label: "Cloud RFP (version 1)",
    started_by: { id: "u1", display_name: "Alex Admin" },
    can_cancel: true,
    can_retry: false,
    queued_at: "2026-10-01T10:00:00Z",
    steps: [],
    ...over,
  });

  it("lists jobs with progress and a cancel action where allowed", async () => {
    const api = fakeApi((call) =>
      call === "GET /jobs"
        ? {
            items: [
              job({}),
              job({
                id: "j2",
                status: "failed",
                can_cancel: false,
                failure: { code: "timeout", message: "x", suggested_actions: ["retry"] },
              }),
            ],
            total: 2,
          }
        : call === "POST /jobs/{jobId}/cancel"
          ? job({ status: "cancelled", can_cancel: false })
          : undefined,
    );
    const { container } = renderPage(<Jobs />, { api, me: makeMe("member") });
    expect((await screen.findAllByText("Reading a document", { selector: "th" })).length).toBe(2);
    expect(screen.getByText("Running")).toBeInTheDocument();
    expect(screen.queryByText(/took longer than allowed/)).not.toBeInTheDocument();
    expect(screen.getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "Work",
      "On",
      "Status",
      "Started",
      "Last updated",
      "Actions",
    ]);
    expect(screen.getAllByRole("button", { name: "View job details" })).toHaveLength(2);
    expect(screen.getAllByRole("button", { name: "Cancel" })).toHaveLength(1);
    expect(await accessibilityViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() =>
      expect(api.POST).toHaveBeenCalledWith(
        "/jobs/{jobId}/cancel",
        expect.objectContaining({ params: { path: { jobId: "j1" } } }),
      ),
    );
  });

  it("opens the details of a job with its steps and activity", async () => {
    const failed = job({
      id: "j2",
      status: "failed",
      can_cancel: false,
      updated_at: "2026-10-01T10:05:00Z",
      started_at: "2026-10-01T10:00:30Z",
      failure: {
        code: "llm_unavailable",
        message: "The language model did not answer. Try again in a few minutes.",
        detail:
          "The embedding model returned vectors of 1024 values but 2560 are configured. Set EMBEDDING_DIMENSIONS to 1024 and use a new QDRANT_COLLECTION.",
        suggested_actions: ["retry"],
      },
      steps: [
        { step_no: 1, name: "fetch", status: "completed", started_at: "2026-10-01T10:00:30Z" },
        { step_no: 2, name: "parse", status: "failed", started_at: "2026-10-01T10:00:31Z" },
      ],
      events: [
        { at: "2026-10-01T10:00:30Z", level: "info", message: "The job started" },
        { at: "2026-10-01T10:05:00Z", level: "error", step: "parse", message: "The job failed: x" },
      ],
    });
    const api = fakeApi((call) =>
      call === "GET /jobs"
        ? { items: [failed], total: 1 }
        : call === "GET /jobs/{jobId}"
          ? failed
          : undefined,
    );
    renderPage(<Jobs />, { api, me: makeMe("member") });
    await userEvent.click(await screen.findByRole("button", { name: "View job details" }));
    const dialog = await screen.findByRole("dialog");
    expect(await within(dialog).findByText("The job started")).toBeInTheDocument();
    expect(within(dialog).getByText("Parse")).toBeInTheDocument();
    expect(within(dialog).getByText(/did not answer/)).toBeInTheDocument();
    expect(within(dialog).getByText(/1024 values but 2560 are configured/)).toBeInTheDocument();
    expect(api.GET).toHaveBeenCalledWith(
      "/jobs/{jobId}",
      expect.objectContaining({ params: { path: { jobId: "j2" } } }),
    );
  });

  it("filters to active jobs through the API", async () => {
    const api = fakeApi(() => ({ items: [], total: 0 }));
    renderPage(<Jobs />, { api });
    await screen.findByText("No jobs");
    await userEvent.click(screen.getByLabelText(/Only jobs that are waiting or running/));
    await waitFor(() =>
      expect(api.GET).toHaveBeenLastCalledWith(
        "/jobs",
        expect.objectContaining({ params: { query: { active: true } } }),
      ),
    );
  });
});

describe("PG-05 Vendors", () => {
  const tv = {
    id: "tv1",
    tender_id: TENDER_ID,
    vendor: { id: "v9", name: "Lumen Ltd", row_version: 1 },
    file_count: 2,
    proposal_version_no: 1,
    status: "uploaded",
    actions: { upload_proposal: { allowed: true } },
  };
  const proposal = {
    id: "pv1",
    tender_vendor_id: "tv1",
    version_no: 1,
    status: "uploaded",
    uploaded_at: "2026-10-01T10:00:00Z",
    files: [
      { document_version_id: "a", file_name: "technical.pdf", role: "main", status: "ready" },
      {
        document_version_id: "b",
        file_name: "pricing.pdf",
        role: "annex",
        status: "failed",
        failure_reason: "password_protected",
      },
    ],
  };
  const handler = (call: string) => {
    if (call === "GET /tenders/{tenderId}") return TENDER;
    if (call === "GET /tenders/{tenderId}/vendors") return { items: [tv], total: 1 };
    if (call === "GET /tender-vendors/{tenderVendorId}/proposal-versions") return [proposal];
    return undefined;
  };

  it("shows the vendor, its files with roles and statuses, and the reasons", async () => {
    const api = fakeApi(handler);
    const { container } = renderPage(at(<Vendors />, "/tenders/:tenderId/vendors"), {
      api,
      route: `/tenders/${TENDER_ID}/vendors`,
    });
    // the proposals start collapsed
    const expand = await screen.findByRole("button", { name: "Expand row" });
    expect(screen.queryByText(/technical.pdf/)).not.toBeInTheDocument();
    await userEvent.click(expand);
    expect(await screen.findByText(/technical.pdf \(main file\)/)).toBeInTheDocument();
    expect(screen.getByText(/pricing.pdf \(annex\)/)).toBeInTheDocument();
    expect(screen.getByText(/password protected/i)).toBeInTheDocument();
    expect(screen.getByText(/Pricing and other financial documents/)).toBeInTheDocument();
    expect(await accessibilityViolations(container)).toEqual([]);
  });

  it("offers Open for a file whose record is closed until the member declares (PG-13)", async () => {
    const closed = {
      ...proposal,
      files: [{ document_version_id: "a", file_name: "Main file", role: "main", status: null }],
    };
    const api = fakeApi((call) =>
      call === "GET /tender-vendors/{tenderVendorId}/proposal-versions" ? [closed] : handler(call),
    );
    renderPage(at(<Vendors />, "/tenders/:tenderId/vendors"), {
      api,
      route: `/tenders/${TENDER_ID}/vendors`,
    });
    await userEvent.click(await screen.findByRole("button", { name: "Expand row" }));
    expect(await screen.findByText(/Main file \(main file\)/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Open Main file" })).toBeInTheDocument();
    expect(screen.queryByText(/failed|processing|uploading/i)).not.toBeInTheDocument();
  });

  it("asks before adding a vendor that looks like an existing one", async () => {
    const api = fakeApi((call) => {
      if (call === "POST /tenders/{tenderId}/vendors") {
        return {
          status: 409,
          error: {
            title: "A similar vendor already exists",
            status: 409,
            code: "possible_duplicate",
            matches: [{ id: "v9", name: "Lumen Ltd" }],
          },
        };
      }
      return handler(call);
    });
    renderPage(at(<Vendors />, "/tenders/:tenderId/vendors"), {
      api,
      route: `/tenders/${TENDER_ID}/vendors`,
    });
    await userEvent.click(await screen.findByRole("button", { name: "Add vendor to tender" }));
    await userEvent.type(await screen.findByLabelText("Vendor name"), "LUMEN Limited");
    await userEvent.click(screen.getByRole("button", { name: "Add vendor" }));
    expect(await screen.findByText("A similar vendor already exists:")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Use this vendor" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add as a new vendor anyway" })).toBeInTheDocument();
  });
});

describe("PG-05 adding a vendor from the registry", () => {
  it("offers active registry vendors and adds the chosen one by id", async () => {
    const api = fakeApi((call) => {
      if (call === "GET /vendors")
        return {
          items: [
            { id: "v1", name: "Lumen Systems", status: "active", row_version: 1 },
            { id: "v2", name: "Dormant Co", status: "inactive", row_version: 1 },
          ],
          total: 2,
        };
      if (call === "POST /tenders/{tenderId}/vendors") return { id: "tv1" };
      if (call === "GET /tenders/{tenderId}") return TENDER;
      if (call === "GET /tenders/{tenderId}/vendors") return { items: [], total: 0 };
      return undefined;
    });
    renderPage(at(<Vendors />, "/tenders/:tenderId/vendors"), {
      api,
      route: `/tenders/${TENDER_ID}/vendors`,
    });
    await userEvent.click(await screen.findByRole("button", { name: "Add vendor to tender" }));
    await screen.findByLabelText("Choose from the vendor registry");
    expect(await optionsOf("Choose from the vendor registry")).toEqual([
      "Lumen Systems",
      "Dormant Co (inactive)",
    ]);
    await choose("Choose from the vendor registry", "Lumen Systems");
    fireEvent.click(await screen.findByRole("button", { name: "Add from registry" }));
    await waitFor(() =>
      expect(api.POST).toHaveBeenCalledWith(
        "/tenders/{tenderId}/vendors",
        expect.objectContaining({ body: { vendor_id: "v1" } }),
      ),
    );
  });
});

describe("push stream and viewer helpers", () => {
  it("parses frames, ids and comments", () => {
    const text =
      ':connected\n\nid:12\nevent:job.updated\ndata:{"type":"job.updated","tender_id":"t"}\n\nid:13\nevent:resync\ndata:{}\n\nid:14\nevent:par';
    const { events, rest } = parseFrames(text);
    expect(events.map((e) => [e.id, e.type])).toEqual([
      ["12", "job.updated"],
      ["13", "resync"],
    ]);
    expect(events[0].data.tender_id).toBe("t");
    expect(rest).toBe("id:14\nevent:par");
  });

  it("scales passage boxes to the zoom and ignores malformed ones", () => {
    expect(
      highlightBoxes(
        [
          [72, 100, 300, 112],
          [1, 2],
        ],
        2,
      ),
    ).toEqual([{ left: 144, top: 200, width: 456, height: 24 }]);
  });

  it("opens at the passage page, then ?page=, clamped to the document", () => {
    expect(startPage(7, "3", 20)).toBe(7);
    expect(startPage(undefined, "3", 20)).toBe(3);
    expect(startPage(undefined, "99", 20)).toBe(20);
    expect(startPage(undefined, null, 20)).toBe(1);
  });

  it("splits a search snippet at the marks without any HTML", () => {
    expect(snippetParts("data is «encrypted» at rest")).toEqual([
      { text: "data is ", hit: false },
      { text: "encrypted", hit: true },
      { text: " at rest", hit: false },
    ]);
  });
});

describe("result messages", () => {
  it("shows a message at the top, announces it, and removes it after a few seconds", async () => {
    const { act, render } = await import("@testing-library/react");
    const { AnnounceProvider, TOAST_MS, useAnnounce } = await import("../components/ui");
    const { vi } = await import("vitest");
    vi.useFakeTimers();
    function Trigger() {
      const announce = useAnnounce();
      return (
        <button type="button" onClick={() => announce("Tender saved.")}>
          save
        </button>
      );
    }
    const view = render(
      <AnnounceProvider>
        <Trigger />
      </AnnounceProvider>,
    );
    act(() => view.getByText("save").click());
    act(() => {
      vi.advanceTimersByTime(30);
    });
    const status = view.getByRole("status");
    expect(status).toHaveTextContent("Tender saved.");
    expect(within(status).getByRole("button", { name: "Dismiss message" })).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(TOAST_MS + 50);
    });
    expect(view.getByRole("status")).toHaveTextContent("");
    // and it can be dismissed at once
    act(() => view.getByText("save").click());
    act(() => {
      vi.advanceTimersByTime(30);
    });
    act(() => view.getByRole("button", { name: "Dismiss message" }).click());
    expect(view.getByRole("status")).toHaveTextContent("");
    vi.useRealTimers();
  });
});
