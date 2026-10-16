import { Modal } from "antd";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useAuth } from "react-oidc-context";
import { useNavigate } from "react-router";
import { ApiError, unwrap, type ApiClient } from "../lib/api";
import { idleState, shouldRenewToken, type IdleState } from "../lib/idle";
import { Loading } from "../components/ui";
import type { Me } from "../lib/types";

interface SessionValue {
  me: Me;
  reloadMe: () => void;
  signOut: () => void;
}

const SessionContext = createContext<SessionValue | null>(null);

/** For tests and stories: a session without Keycloak. */
export function SessionTestProvider({ me, children }: { me: Me; children: ReactNode }) {
  return (
    <SessionContext.Provider value={{ me, reloadMe: () => undefined, signOut: () => undefined }}>
      {children}
    </SessionContext.Provider>
  );
}

export function useSession(): SessionValue {
  const v = useContext(SessionContext);
  if (!v) {
    throw new Error("useSession needs a SessionProvider");
  }
  return v;
}

/**
 * Sign-in (PG-01) is Keycloak's; this gate sends a visitor there, loads `GET /me` once the token
 * exists, refuses an uninvited or deactivated account with words, and keeps the idle clock
 * (UX-AUTH-04). Nothing is stored in the browser: the token lives in memory.
 */
export function SessionProvider({ api, children }: { api: ApiClient; children: ReactNode }) {
  const auth = useAuth();
  const [me, setMe] = useState<Me | null>(null);
  const [failure, setFailure] = useState<ApiError | null>(null);
  const [version, setVersion] = useState(0);

  const navigate = useNavigate();

  // The memory-only session is gone after a reload: sign in again through the Keycloak session
  // and come back to the page the person was on, not to the home page.
  useEffect(() => {
    if (!auth.isLoading && !auth.isAuthenticated && !auth.error) {
      const returnTo = `${window.location.pathname}${window.location.search}`;
      void auth.signinRedirect({ state: { returnTo } });
    }
  }, [auth]);

  // Once per sign-in: the state is kept by the library for the whole session, and following it
  // again on every navigation would bounce the person back to where they came in.
  const returnTo = (auth.user?.state as { returnTo?: string } | undefined)?.returnTo;
  const followed = useRef(false);
  useEffect(() => {
    if (auth.isAuthenticated && !followed.current) {
      followed.current = true;
      if (returnTo && returnTo !== "/") {
        navigate(returnTo, { replace: true });
      }
    }
  }, [auth.isAuthenticated, returnTo, navigate]);

  useEffect(() => {
    if (!auth.isAuthenticated) {
      return;
    }
    let cancelled = false;
    unwrap(api.GET("/me")).then(
      (data) => !cancelled && setMe(data),
      (e: unknown) =>
        !cancelled &&
        setFailure(
          e instanceof ApiError ? e : new ApiError(0, { title: "", status: 0, code: "error" }),
        ),
    );
    return () => {
      cancelled = true;
    };
  }, [api, auth.isAuthenticated, version]);

  const signOut = useCallback(() => void auth.signoutRedirect(), [auth]);

  if (auth.error) {
    return (
      <div role="alert" className="card" style={{ margin: 24 }}>
        <h1>Sign-in failed</h1>
        <p>The sign-in could not be completed. Try again.</p>
        <button type="button" className="btn primary" onClick={() => void auth.signinRedirect()}>
          Sign in
        </button>
      </div>
    );
  }
  if (!auth.isAuthenticated) {
    return <Loading label="Taking you to sign in" />;
  }
  if (failure) {
    return <Refusal error={failure} onSignOut={signOut} />;
  }
  if (!me) {
    return <Loading label="Loading your workspace" />;
  }
  return (
    <SessionContext.Provider value={{ me, reloadMe: () => setVersion((v) => v + 1), signOut }}>
      {children}
      <IdleGuard
        idleTimeoutSeconds={me.client_config.session_idle_timeout_seconds}
        warningSeconds={me.client_config.session_warning_seconds}
      />
    </SessionContext.Provider>
  );
}

function Refusal({ error, onSignOut }: { error: ApiError; onSignOut: () => void }) {
  const text =
    error.code === "not_invited"
      ? "This account has not been invited to the platform. Ask an administrator to invite you."
      : error.code === "user_deactivated"
        ? "This account is deactivated. Ask an administrator to reactivate it."
        : "The platform could not load your account. Try again, or contact an administrator.";
  return (
    <div className="card" style={{ margin: 24, maxWidth: 560 }}>
      <h1>You cannot use the platform with this account</h1>
      <p role="alert">{text}</p>
      {error.problem.trace_id && (
        <p className="help">
          Reference <span className="mono">{error.problem.trace_id}</span>
        </p>
      )}
      <button type="button" className="btn" onClick={onSignOut}>
        Sign out
      </button>
    </div>
  );
}

const ACTIVITY_EVENTS = ["pointerdown", "keydown", "wheel", "touchstart"] as const;

/** Warns two minutes before the realm's idle timeout and renews the token only while active. */
function IdleGuard({
  idleTimeoutSeconds,
  warningSeconds,
}: {
  idleTimeoutSeconds: number;
  warningSeconds: number;
}) {
  const auth = useAuth();
  const last = useRef(0);
  const [state, setState] = useState<IdleState>({ phase: "active", secondsLeft: 0 });

  const touch = useCallback(() => {
    last.current = Date.now();
  }, []);

  useEffect(() => {
    last.current = Date.now();
  }, []);

  useEffect(() => {
    ACTIVITY_EVENTS.forEach((e) => window.addEventListener(e, touch, { passive: true }));
    return () => ACTIVITY_EVENTS.forEach((e) => window.removeEventListener(e, touch));
  }, [touch]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      const now = Date.now();
      const s = idleState(now, last.current, idleTimeoutSeconds, warningSeconds);
      setState((prev) => (prev.phase === s.phase && prev.secondsLeft === s.secondsLeft ? prev : s));
      const expiresAt = auth.user?.expires_at ? auth.user.expires_at * 1000 : undefined;
      if (shouldRenewToken(expiresAt, now, s.phase)) {
        void auth.signinSilent();
      }
      if (s.phase === "expired") {
        void auth.signoutRedirect();
      }
    }, 1000);
    return () => window.clearInterval(timer);
  }, [auth, idleTimeoutSeconds, warningSeconds]);

  const stay = () => {
    touch();
    setState({ phase: "active", secondsLeft: 0 });
    void auth.signinSilent(); // keeps the realm's idle clock running
  };

  return (
    <Modal
      open={state.phase === "warning"}
      title="Session ending"
      closable={false}
      keyboard={false}
      mask={{ closable: false }}
      okText="Stay signed in"
      cancelButtonProps={{ style: { display: "none" } }}
      onOk={stay}
    >
      You will be signed out in{" "}
      {Math.ceil(state.secondsLeft / 60) > 1
        ? `${Math.ceil(state.secondsLeft / 60)} minutes`
        : `${state.secondsLeft} seconds`}
      .
    </Modal>
  );
}
