import { useAuth } from "react-oidc-context";

/** Application shell. Screens arrive from Phase 1; this proves sign-in and the API path. */
export function App() {
  const auth = useAuth();

  if (auth.isLoading) {
    return <p role="status">Loading</p>;
  }
  if (auth.error) {
    return <p role="alert">Sign-in failed. Try again.</p>;
  }

  return (
    <main>
      <h1>AI Tender Evaluation Platform</h1>
      {auth.isAuthenticated ? (
        <>
          <p>Signed in as {auth.user?.profile.email ?? "unknown"}</p>
          <button type="button" onClick={() => void auth.signoutRedirect()}>
            Sign out
          </button>
        </>
      ) : (
        <button type="button" onClick={() => void auth.signinRedirect()}>
          Sign in
        </button>
      )}
    </main>
  );
}
