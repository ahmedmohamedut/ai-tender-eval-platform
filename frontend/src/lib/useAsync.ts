import { useEffect, useRef, useState } from "react";
import { ApiError } from "./api";

export type AsyncState<T> =
  | { status: "loading" }
  | { status: "error"; error: ApiError | Error }
  | { status: "ready"; data: T };

/**
 * Loads on mount and whenever `deps` change; `reload` refetches. The answer is stored with the
 * request it belongs to, so a state that does not match the current request reads as loading and
 * a slow response never overwrites a fresher one.
 */
export function useAsync<T>(load: () => Promise<T>, deps: unknown[]) {
  const [tick, setTick] = useState(0);
  const [answer, setAnswer] = useState<{ id: string; value: AsyncState<T> } | null>(null);
  const loader = useRef(load);
  useEffect(() => {
    loader.current = load;
  });

  // Object dependencies (the API client) are stable for the session and compare by kind.
  const key = JSON.stringify(deps.map((d) => (typeof d === "object" && d !== null ? "object" : d)));
  const id = `${key}#${tick}`;

  useEffect(() => {
    let current = true;
    loader.current().then(
      (data) => {
        if (current) setAnswer({ id, value: { status: "ready", data } });
      },
      (error: unknown) => {
        if (current) {
          setAnswer({
            id,
            value: {
              status: "error",
              error: error instanceof Error ? error : new Error(String(error)),
            },
          });
        }
      },
    );
    return () => {
      current = false;
    };
  }, [id]);

  const state: AsyncState<T> = answer && answer.id === id ? answer.value : { status: "loading" };
  return { state, reload: () => setTick((t) => t + 1) };
}
