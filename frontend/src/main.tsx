import React from "react";
import { createRoot } from "react-dom/client";
import { AuthProvider } from "react-oidc-context";
import { App } from "./App";
import { oidcSettings } from "./auth";
import { createApiClient } from "./api/client";
import { loadConfig, type RuntimeConfig } from "./config";
import { TokenBridge } from "./app/TokenBridge";
import { getAccessToken } from "./lib/token";
import { setApiBase } from "./lib/base";
import "./styles/app.css";

/** The API client reads the access token held in memory by the OIDC library. */
function Root({ config }: { config: RuntimeConfig }) {
  setApiBase(config.apiBaseUrl);
  const api = React.useMemo(
    () => createApiClient(config.apiBaseUrl, getAccessToken),
    [config.apiBaseUrl],
  );
  return (
    <TokenBridge>
      <App api={api} />
    </TokenBridge>
  );
}

async function start() {
  const root = createRoot(document.getElementById("root")!);
  try {
    const config = await loadConfig();
    root.render(
      <React.StrictMode>
        <AuthProvider
          {...oidcSettings(config)}
          onSigninCallback={() => window.history.replaceState({}, document.title, "/")}
        >
          <Root config={config} />
        </AuthProvider>
      </React.StrictMode>,
    );
  } catch {
    root.render(<p role="alert">The application is not configured. Contact an administrator.</p>);
  }
}

void start();
