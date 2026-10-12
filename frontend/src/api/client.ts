import createClient from "openapi-fetch";
import type { paths } from "./generated/schema";

/**
 * The typed REST client. Types come from api/openapi.yaml through `npm run api`
 * (generated, not committed), so a request or response type is never written by hand.
 */
export function createApiClient(baseUrl: string, getToken: () => string | undefined) {
  const client = createClient<paths>({ baseUrl });
  client.use({
    onRequest({ request }) {
      const token = getToken();
      if (token) {
        request.headers.set("Authorization", `Bearer ${token}`);
      }
      return request;
    },
  });
  return client;
}
