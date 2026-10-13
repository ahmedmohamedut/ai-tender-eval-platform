import { useEffect, useRef, useState } from "react";
import { Field } from "../components/ui";
import { Link, useParams, useSearchParams } from "react-router";
import * as pdfjs from "pdfjs-dist";
import workerSrc from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import {
  Button,
  ConfirmDialog,
  ErrorNotice,
  Loading,
  StatusChip,
  useAnnounce,
} from "../components/ui";
import { useSession } from "../app/Session";
import { usePush } from "../lib/usePush";
import { ApiError } from "../lib/api";
import { unwrap, useApi } from "../lib/api";
import { getApiBase } from "../lib/base";
import { getAccessToken } from "../lib/token";
import { useAsync } from "../lib/useAsync";
import { highlightBoxes, snippetParts, startPage } from "../lib/viewer";

pdfjs.GlobalWorkerOptions.workerSrc = workerSrc;

/** PG-10 (document part): outline, pages, search, zoom, text selection, passage highlight. */
export function Viewer() {
  const { versionId } = useParams();
  const [params, setParams] = useSearchParams();
  const api = useApi();
  const passageId = params.get("passage");

  const outline = useAsync(
    () =>
      unwrap(
        api.GET("/document-versions/{documentVersionId}/outline", {
          params: { path: { documentVersionId: versionId! } },
        }),
      ),
    [api, versionId],
  );
  const pages = useAsync(
    () =>
      unwrap(
        api.GET("/document-versions/{documentVersionId}/pages", {
          params: { path: { documentVersionId: versionId! } },
        }),
      ),
    [api, versionId],
  );
  // Where "back" goes: the documents of the tender this version belongs to.
  const origin = useAsync(async () => {
    const v = await unwrap(
      api.GET("/document-versions/{documentVersionId}", {
        params: { path: { documentVersionId: versionId! } },
      }),
    );
    const d = await unwrap(
      api.GET("/documents/{documentId}", { params: { path: { documentId: v.document_id } } }),
    );
    return { tenderId: d.tender_id, title: d.title };
  }, [api, versionId]);
  const back =
    origin.state.status === "ready"
      ? `/tenders/${origin.state.data.tenderId}/documents`
      : "/tenders";
  const backLabel =
    origin.state.status === "ready" ? "Back to tender documents" : "Back to tenders";

  const { me } = useSession();
  const announce = useAnnounce();
  const isMember = me.user.role === "member";
  const tenderId = origin.state.status === "ready" ? origin.state.data.tenderId : null;
  const coverage = useAsync(
    () =>
      tenderId && isMember
        ? unwrap(
            api.GET("/tenders/{tenderId}/extractions/coverage", {
              params: { path: { tenderId }, query: { document_version_id: versionId } },
            }),
          )
        : Promise.resolve(null),
    [api, tenderId, versionId, isMember],
  );
  usePush(tenderId ?? undefined, coverage.reload, ["job.updated", "requirements.changed"]);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [rangeFrom, setRangeFrom] = useState("");
  const [rangeTo, setRangeTo] = useState("");
  const [confirmExtract, setConfirmExtract] = useState(false);
  const [overlap, setOverlap] = useState(false);

  const passage = useAsync(
    () =>
      passageId
        ? unwrap(api.GET("/passages/{passageId}", { params: { path: { passageId } } }))
        : Promise.resolve(null),
    [api, passageId],
  );
  const [page, setPage] = useState<number | null>(null);
  const [zoom, setZoom] = useState(1.2);
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<{ passage_id: string; page_no: number; snippet: string }[]>([]);

  const count = pages.state.status === "ready" ? pages.state.data.length : 0;
  const passageState = passage.state;
  const passageReady = passageState.status === "ready" || passageState.status === "error";
  const current =
    page ??
    (pages.state.status === "ready" && passageReady
      ? startPage(
          passageState.status === "ready" ? passageState.data?.page_no : undefined,
          params.get("page"),
          count,
        )
      : null);

  const backLink = (
    <p className="viewer-back">
      <Link to={back} className="btn">
        {backLabel}
      </Link>
    </p>
  );
  if (pages.state.status === "loading" || outline.state.status === "loading") {
    return (
      <>
        {backLink}
        <Loading label="Opening the document" />
      </>
    );
  }
  if (pages.state.status === "error")
    return (
      <>
        {backLink}
        <ErrorNotice error={pages.state.error} onRetry={pages.reload} />
      </>
    );
  if (outline.state.status === "error")
    return (
      <>
        {backLink}
        <ErrorNotice error={outline.state.error} onRetry={outline.reload} />
      </>
    );
  const sections = outline.state.data.sections;
  const highlighted = passageState.status === "ready" ? passageState.data : null;

  async function search() {
    if (!query.trim()) return;
    const r = await unwrap(
      api.GET("/document-versions/{documentVersionId}/search", {
        params: { path: { documentVersionId: versionId! }, query: { q: query } },
      }),
    );
    setHits(r.hits);
  }

  function go(n: number) {
    setPage(Math.min(Math.max(1, n), count));
  }

  const from = rangeFrom ? Number(rangeFrom) : null;
  const to = rangeTo ? Number(rangeTo) : null;
  const rangeOk =
    (from === null && to === null) ||
    (from !== null && from >= 1 && from <= count && (to === null || (to >= from && to <= count)));
  const selectedPages = [...picked].reduce((n, id) => {
    const sec = sections.find((x) => x.id === id);
    return n + (sec ? sec.end_page - sec.start_page + 1 : 0);
  }, 0);
  const hasSelection = picked.size > 0 || from !== null;

  async function startExtraction(acknowledge: boolean) {
    try {
      await unwrap(
        api.POST("/tenders/{tenderId}/extractions", {
          params: {
            path: { tenderId: tenderId! },
            header: { "Idempotency-Key": crypto.randomUUID() },
          },
          body: {
            scope: [
              {
                document_version_id: versionId!,
                section_ids: [...picked],
                page_from: from,
                page_to: to,
              },
            ],
            acknowledge_overlap: acknowledge,
          },
        }),
      );
      announce("Extraction started. You can leave this page; it continues in the background.");
      setConfirmExtract(false);
      setOverlap(false);
      setPicked(new Set());
      setRangeFrom("");
      setRangeTo("");
      coverage.reload();
    } catch (e) {
      setConfirmExtract(false);
      if (e instanceof ApiError && e.code === "job_overlap") {
        setOverlap(true);
      } else {
        announce(e instanceof Error ? e.message : "The extraction could not be started");
      }
    }
  }

  return (
    <div className="viewer">
      <h1 className="visually-hidden">Document viewer</h1>
      <p className="viewer-back">
        <Link to={back} className="btn">
          {backLabel}
        </Link>
        {origin.state.status === "ready" && (
          <span className="help"> {origin.state.data.title}</span>
        )}
      </p>
      <aside aria-label="Document navigation" className="viewer-side">
        <form
          role="search"
          onSubmit={(e) => {
            e.preventDefault();
            void search();
          }}
        >
          <label htmlFor="doc-search">Search this document</label>
          <input id="doc-search" value={query} onChange={(e) => setQuery(e.target.value)} />
          <Button type="submit" onClick={() => void search()}>
            Search
          </Button>
        </form>
        {hits.length > 0 && (
          <ol aria-label="Search results" className="hits">
            {hits.map((h) => (
              <li key={h.passage_id}>
                <button
                  type="button"
                  className="linklike"
                  onClick={() => {
                    setParams({ passage: h.passage_id });
                    setPage(h.page_no);
                  }}
                >
                  Page {h.page_no}:{" "}
                  {snippetParts(h.snippet).map((p, i) =>
                    p.hit ? <mark key={i}>{p.text}</mark> : <span key={i}>{p.text}</span>,
                  )}
                </button>
              </li>
            ))}
          </ol>
        )}
        {isMember && (
          <section aria-label="Extract requirements" className="card">
            <h2>Extract requirements</h2>
            <p className="help">
              Tick sections or give a page range. {picked.size} section(s)
              {selectedPages ? `, ${selectedPages} pages` : ""} selected.
            </p>
            <Field label="From page">
              {({ id }) => (
                <input
                  id={id}
                  type="number"
                  min={1}
                  max={count}
                  value={rangeFrom}
                  onChange={(e) => setRangeFrom(e.target.value)}
                />
              )}
            </Field>
            <Field label="To page" error={rangeOk ? undefined : "Use pages inside the document"}>
              {({ id, describedBy, invalid }) => (
                <input
                  id={id}
                  aria-describedby={describedBy}
                  aria-invalid={invalid}
                  type="number"
                  min={1}
                  max={count}
                  value={rangeTo}
                  onChange={(e) => setRangeTo(e.target.value)}
                />
              )}
            </Field>
            <Button
              variant="primary"
              disabledReason={
                !hasSelection
                  ? "Choose sections or pages first"
                  : !rangeOk
                    ? "Fix the page range"
                    : undefined
              }
              onClick={() => setConfirmExtract(true)}
            >
              Start extraction
            </Button>
            <Button
              onClick={() => {
                setPicked(new Set());
                setRangeFrom("");
                setRangeTo("");
              }}
            >
              Clear selection
            </Button>
          </section>
        )}
        <nav aria-label="Contents">
          {sections.length === 0 ? (
            <p className="help">This document has no outline. Use the page controls.</p>
          ) : (
            <ul className="outline">
              {sections.map((s) => {
                const cov =
                  coverage.state.status === "ready" && coverage.state.data
                    ? coverage.state.data.items.find((c) => c.section_id === s.id)
                    : undefined;
                return (
                  <li key={s.id} style={{ paddingLeft: (s.level - 1) * 12 }}>
                    {isMember && (
                      <input
                        type="checkbox"
                        aria-label={`Extract from ${s.title}`}
                        checked={picked.has(s.id)}
                        onChange={(e) => {
                          const next = new Set(picked);
                          if (e.target.checked) next.add(s.id);
                          else next.delete(s.id);
                          setPicked(next);
                        }}
                      />
                    )}{" "}
                    <button type="button" className="linklike" onClick={() => go(s.start_page)}>
                      {s.title}
                    </button>
                    {cov && (
                      <>
                        {" "}
                        <StatusChip
                          label={cov.status === "running" ? "Running" : "Extracted"}
                          tone={cov.status === "running" ? "info" : "success"}
                        />
                        <span className="visually-hidden">
                          {cov.by ? ` by ${cov.by.display_name}` : ""}
                        </span>
                      </>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </nav>
      </aside>
      <section aria-label="Document" className="viewer-main">
        <div className="viewer-toolbar" role="toolbar" aria-label="Page controls">
          <Button onClick={() => go((current ?? 1) - 1)} disabled={(current ?? 1) <= 1}>
            Previous page
          </Button>
          <span aria-live="polite">
            Page {current ?? 1} of {count}
          </span>
          <Button onClick={() => go((current ?? 1) + 1)} disabled={(current ?? 1) >= count}>
            Next page
          </Button>
          <Button onClick={() => setZoom((z) => Math.max(0.6, z - 0.2))}>Zoom out</Button>
          <Button onClick={() => setZoom((z) => Math.min(3, z + 0.2))}>Zoom in</Button>
        </div>
        <ConfirmDialog
          open={confirmExtract}
          onOpenChange={setConfirmExtract}
          title="Start extraction"
          description={`Extract requirements from ${picked.size} section(s)${
            from !== null ? `, pages ${from} to ${to ?? count}` : ""
          }. The AI proposes; you review every requirement before it counts.`}
          confirmLabel="Start extraction"
          onConfirm={() => void startExtraction(false)}
        />
        <ConfirmDialog
          open={overlap}
          onOpenChange={setOverlap}
          title="Someone is already extracting here"
          description="Part of this selection is being extracted by another member. Extracting again may propose duplicates, which are flagged."
          confirmLabel="Extract anyway"
          onConfirm={() => void startExtraction(true)}
        />
        {current !== null && (
          <PdfPage
            versionId={versionId!}
            pageNo={current}
            zoom={zoom}
            highlight={highlighted && highlighted.page_no === current ? highlighted.bbox : null}
          />
        )}
      </section>
    </div>
  );
}

const docCache = new Map<string, Promise<pdfjs.PDFDocumentProxy>>();

function loadDocument(versionId: string): Promise<pdfjs.PDFDocumentProxy> {
  let doc = docCache.get(versionId);
  if (!doc) {
    const token = getAccessToken();
    doc = pdfjs.getDocument({
      url: `${getApiBase()}/document-versions/${versionId}/render`,
      httpHeaders: token ? { Authorization: `Bearer ${token}` } : {},
      // The backend answers ranges, so page 200 of a long file opens without the whole file.
      rangeChunkSize: 262144,
    }).promise;
    docCache.set(versionId, doc);
  }
  return doc;
}

function PdfPage({
  versionId,
  pageNo,
  zoom,
  highlight,
}: {
  versionId: string;
  pageNo: number;
  zoom: number;
  highlight: number[][] | null;
}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const textLayer = useRef<HTMLDivElement>(null);
  const box = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState<{ w: number; h: number } | null>(null);
  const [error, setError] = useState<Error | null>(null);

  useEffect(() => {
    let cancelled = false;
    let task: pdfjs.RenderTask | null = null;
    (async () => {
      try {
        const doc = await loadDocument(versionId);
        const page = await doc.getPage(pageNo);
        const viewport = page.getViewport({ scale: zoom });
        if (cancelled || !canvas.current) return;
        const ctx = canvas.current.getContext("2d");
        if (!ctx) return;
        canvas.current.width = Math.floor(viewport.width);
        canvas.current.height = Math.floor(viewport.height);
        setSize({ w: viewport.width, h: viewport.height });
        task = page.render({ canvasContext: ctx, canvas: canvas.current, viewport });
        await task.promise;
        if (textLayer.current && !cancelled) {
          textLayer.current.replaceChildren();
          const layer = new pdfjs.TextLayer({
            textContentSource: page.streamTextContent(),
            container: textLayer.current,
            viewport,
          });
          await layer.render();
        }
      } catch (e) {
        if (!cancelled && !(e instanceof Error && e.name === "RenderingCancelledException")) {
          setError(e instanceof Error ? e : new Error(String(e)));
        }
      }
    })();
    return () => {
      cancelled = true;
      task?.cancel();
    };
  }, [versionId, pageNo, zoom]);

  useEffect(() => {
    box.current?.scrollIntoView?.({ block: "center", behavior: "smooth" });
  }, [highlight, size]);

  if (error) return <ErrorNotice error={error} />;
  const boxes = highlight ? highlightBoxes(highlight, zoom) : [];
  return (
    <div
      className="pdf-page"
      style={{ width: size?.w, height: size?.h, position: "relative" }}
      role="group"
      aria-label={`Page ${pageNo}`}
    >
      <canvas ref={canvas} />
      <div ref={textLayer} className="textLayer" />
      {boxes.map((b, i) => (
        <div
          key={i}
          ref={i === 0 ? box : undefined}
          className="passage-highlight"
          data-testid="passage-highlight"
          style={{ left: b.left, top: b.top, width: b.width, height: b.height }}
        />
      ))}
    </div>
  );
}
