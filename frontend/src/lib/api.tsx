import { createContext, useContext, useMemo, type ReactNode } from "react";
import { createApiClient } from "../api/client";
import type { Problem } from "./types";

export type ApiClient = ReturnType<typeof createApiClient>;

/** A failed call: the status, the platform's code and the fields the server named. */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem;

  constructor(status: number, problem: Problem) {
    super(problem.title);
    this.status = status;
    this.problem = problem;
  }

  get code(): string {
    return this.problem.code;
  }
}

export function toApiError(status: number, body: unknown): ApiError {
  const p = (body ?? {}) as Partial<Problem>;
  return new ApiError(status, {
    title: p.title ?? "The request failed",
    status,
    code: p.code ?? "error",
    trace_id: p.trace_id,
    errors: p.errors,
    access_contacts: p.access_contacts,
  });
}

/** Unwraps an openapi-fetch result: the data, or an ApiError carrying the problem body. */
export async function unwrap<T>(
  result: Promise<{ data?: T; error?: unknown; response: { status: number } }>,
): Promise<T> {
  const r = await result;
  if (r.error !== undefined || r.data === undefined) {
    if (r.response.status >= 200 && r.response.status < 300) {
      return undefined as T; // 204 and similar: no body
    }
    throw toApiError(r.response.status, r.error);
  }
  return r.data;
}

const ApiContext = createContext<ApiClient | null>(null);

export function ApiProvider({ client, children }: { client: ApiClient; children: ReactNode }) {
  return <ApiContext.Provider value={client}>{children}</ApiContext.Provider>;
}

export function useApi(): ApiClient {
  const client = useContext(ApiContext);
  if (!client) {
    throw new Error("useApi needs an ApiProvider");
  }
  return client;
}

/** Builds the client once per token getter and base url. */
export function useApiClient(baseUrl: string, getToken: () => string | undefined): ApiClient {
  return useMemo(() => createApiClient(baseUrl, getToken), [baseUrl, getToken]);
}
