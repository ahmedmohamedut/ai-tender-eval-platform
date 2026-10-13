/**
 * The push stream (PI 5.10, UX-GEN-06): a fetch-based reader, because EventSource cannot send an
 * Authorization header. It reconnects with Last-Event-ID and a fresh token; a `resync` event, or a
 * reconnect after a long gap, tells the page to refetch what it shows.
 */
export interface PushEvent {
  id: string | null;
  type: string;
  data: {
    type: string;
    tender_id?: string;
    object_type?: string;
    object_id?: string;
    data?: unknown;
  };
}

export interface StreamOptions {
  baseUrl: string;
  getToken: () => string | undefined;
  tenderId?: string;
  onEvent: (e: PushEvent) => void;
  /** The server cannot replay: refetch what is on screen. */
  onResync: () => void;
  fetchImpl?: typeof fetch;
  /** Delay before reconnecting, in ms (tests shorten it). */
  retryMs?: number;
}

/** Parses SSE text frames. Exported for tests. */
export function parseFrames(buffer: string): { events: PushEvent[]; rest: string } {
  const events: PushEvent[] = [];
  const frames = buffer.split(/\r?\n\r?\n/);
  const rest = frames.pop() ?? "";
  for (const frame of frames) {
    let id: string | null = null;
    let type = "message";
    const data: string[] = [];
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith(":")) continue;
      const i = line.indexOf(":");
      const field = i < 0 ? line : line.slice(0, i);
      const value = i < 0 ? "" : line.slice(i + 1).replace(/^ /, "");
      if (field === "id") id = value;
      else if (field === "event") type = value;
      else if (field === "data") data.push(value);
    }
    if (data.length === 0 && type === "message") continue;
    let parsed: PushEvent["data"] = { type };
    try {
      parsed = data.length ? JSON.parse(data.join("\n")) : { type };
    } catch {
      parsed = { type };
    }
    events.push({ id, type, data: parsed });
  }
  return { events, rest };
}

export function openEventStream(opts: StreamOptions): () => void {
  const doFetch = opts.fetchImpl ?? fetch.bind(globalThis);
  let stopped = false;
  let lastId: string | null = null;
  let controller: AbortController | null = null;

  async function run(): Promise<void> {
    while (!stopped) {
      controller = new AbortController();
      try {
        const q = opts.tenderId ? `?tender_id=${encodeURIComponent(opts.tenderId)}` : "";
        const headers: Record<string, string> = { Accept: "text/event-stream" };
        const token = opts.getToken();
        if (token) headers.Authorization = `Bearer ${token}`;
        if (lastId) headers["Last-Event-ID"] = lastId;
        const res = await doFetch(`${opts.baseUrl}/events${q}`, {
          headers,
          signal: controller.signal,
        });
        if (!res.ok || !res.body) throw new Error(`events ${res.status}`);
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const { events, rest } = parseFrames(buffer);
          buffer = rest;
          for (const e of events) {
            if (e.id) lastId = e.id;
            if (e.type === "resync") opts.onResync();
            else opts.onEvent(e);
          }
        }
      } catch {
        // fall through to the reconnect delay
      }
      if (stopped) return;
      await new Promise((r) => setTimeout(r, opts.retryMs ?? 2000));
    }
  }
  void run();
  return () => {
    stopped = true;
    controller?.abort();
  };
}
