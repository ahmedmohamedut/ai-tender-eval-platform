import React from "react";
import { createRoot } from "react-dom/client";
import { AuthProvider } from "react-oidc-context";
import { App } from "./App";
import { oidcSettings } from "./auth";
import { loadConfig } from "./config";

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
          <App />
        </AuthProvider>
      </React.StrictMode>,
    );
  } catch {
    root.render(<p role="alert">The application is not configured. Contact an administrator.</p>);
  }
}

void start();
