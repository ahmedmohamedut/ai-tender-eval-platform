import { useEffect, useRef, useState } from "react";
import { Button, useAnnounce } from "./ui";
import { unwrap, useApi } from "../lib/api";
import { getApiBase } from "../lib/base";
import { getAccessToken } from "../lib/token";

type Phase =
  | { kind: "idle" }
  | { kind: "pending"; id: string }
  | { kind: "ready"; id: string; fileName: string }
  | { kind: "failed"; message: string };

const POLL_MS = 1000;
const GIVE_UP_MS = 5 * 60 * 1000;

/**
 * Starts an export, follows it until the file is ready and offers the download (UX-RPT-09,
 * UX-REQ-15). The file is fetched through the API with the session token: no storage links.
 */
export function ExportButton({
  label,
  start,
  disabled,
}: {
  label: string;
  start: () => Promise<{ id: string }>;
  disabled?: boolean;
}) {
  const api = useApi();
  const announce = useAnnounce();
  const [phase, setPhase] = useState<Phase>({ kind: "idle" });
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  async function follow(id: string) {
    const began = Date.now();
    while (alive.current && Date.now() - began < GIVE_UP_MS) {
      await new Promise((r) => setTimeout(r, POLL_MS));
      try {
        const e = await unwrap(
          api.GET("/exports/{exportId}", { params: { path: { exportId: id } } }),
        );
        if (!alive.current) return;
        if (e.status === "ready") {
          setPhase({ kind: "ready", id, fileName: e.file_name ?? "export" });
          announce("The export is ready");
          return;
        }
        if (e.status === "failed") {
          setPhase({ kind: "failed", message: "The export failed. Try again." });
          return;
        }
      } catch {
        if (alive.current) setPhase({ kind: "failed", message: "The export could not be read." });
        return;
      }
    }
    if (alive.current) setPhase({ kind: "failed", message: "The export is taking too long." });
  }

  async function begin() {
    try {
      const e = await start();
      setPhase({ kind: "pending", id: e.id });
      void follow(e.id);
    } catch (e) {
      setPhase({
        kind: "failed",
        message: e instanceof Error ? e.message : "The export could not be started.",
      });
    }
  }

  async function download(id: string, fileName: string) {
    const token = getAccessToken();
    const res = await fetch(`${getApiBase()}/exports/${id}/file`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!res.ok) {
      setPhase({
        kind: "failed",
        message:
          res.status === 409
            ? "The file has expired. Export again to get a new copy."
            : "The file could not be downloaded.",
      });
      return;
    }
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement("a");
    a.href = url;
    a.download = fileName;
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <span className="export-button">
      {phase.kind === "ready" ? (
        <Button
          type="button"
          variant="primary"
          onClick={() => void download(phase.id, phase.fileName)}
        >
          Download {phase.fileName}
        </Button>
      ) : (
        <Button
          type="button"
          onClick={() => void begin()}
          disabled={disabled || phase.kind === "pending"}
        >
          {phase.kind === "pending" ? "Preparing the file" : label}
        </Button>
      )}
      {phase.kind === "failed" && (
        <span role="alert" className="help">
          {" "}
          {phase.message}
        </span>
      )}
    </span>
  );
}
