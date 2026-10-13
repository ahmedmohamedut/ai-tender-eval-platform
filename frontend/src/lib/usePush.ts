import { useEffect, useRef } from "react";
import { openEventStream, type PushEvent } from "./events";
import { getAccessToken } from "./token";
import { getApiBase } from "./base";

/** Calls `refresh` when something the page shows may have changed (events are hints). */
export function usePush(tenderId: string | undefined, refresh: () => void, types?: string[]) {
  const latest = useRef(refresh);
  useEffect(() => {
    latest.current = refresh;
  });
  const key = types?.join(",") ?? "";
  useEffect(() => {
    const wanted = key ? key.split(",") : null;
    const stop = openEventStream({
      baseUrl: getApiBase(),
      getToken: getAccessToken,
      tenderId,
      onEvent: (e: PushEvent) => {
        if (!wanted || wanted.includes(e.type)) latest.current();
      },
      onResync: () => latest.current(),
    });
    return stop;
  }, [tenderId, key]);
}
