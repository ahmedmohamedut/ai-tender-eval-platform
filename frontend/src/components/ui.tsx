import * as Dialog from "@radix-ui/react-dialog";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type ReactNode,
} from "react";
import { Link, type LinkProps } from "react-router";
import type { Tone } from "../lib/labels";
import { ApiError } from "../lib/api";
import { problemMessage } from "../lib/labels";

/** A button that stays focusable when it cannot be used, and says why (UX-ROL-03). */
export function Button({
  variant,
  disabledReason,
  children,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "danger";
  disabledReason?: string;
}) {
  const reasonId = useId();
  const blocked = Boolean(disabledReason);
  return (
    <span>
      <button
        type="button"
        {...rest}
        className={`btn ${variant ?? ""} ${rest.className ?? ""}`.trim()}
        aria-disabled={blocked || rest.disabled ? true : undefined}
        aria-describedby={blocked ? reasonId : rest["aria-describedby"]}
        onClick={blocked || rest.disabled ? (e) => e.preventDefault() : rest.onClick}
      >
        {children}
      </button>
      {blocked && (
        <span
          id={reasonId}
          className="help"
          style={{ marginLeft: 8, color: "var(--text-secondary)" }}
        >
          {disabledReason}
        </span>
      )}
    </span>
  );
}

export function LinkButton({ variant, className, ...rest }: LinkProps & { variant?: "primary" }) {
  return <Link {...rest} className={`btn ${variant ?? ""} ${className ?? ""}`.trim()} />;
}

export function Field({
  label,
  help,
  error,
  children,
}: {
  label: string;
  help?: string;
  error?: string;
  children: (props: { id: string; describedBy?: string; invalid: boolean }) => ReactNode;
}) {
  const id = useId();
  const helpId = `${id}-help`;
  const errorId = `${id}-error`;
  const describedBy =
    [help ? helpId : null, error ? errorId : null].filter(Boolean).join(" ") || undefined;
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children({ id, describedBy, invalid: Boolean(error) })}
      {help && (
        <span id={helpId} className="help">
          {help}
        </span>
      )}
      {error && (
        <span id={errorId} className="error">
          {error}
        </span>
      )}
    </div>
  );
}

const ICONS: Record<Tone, string> = {
  ai: "✦",
  neutral: "○",
  success: "✓",
  warning: "◐",
  error: "!",
  info: "●",
};

/** Colour never carries the meaning alone: an icon and the label always accompany it. */
export function StatusChip({ label, tone = "neutral" }: { label: string; tone?: Tone }) {
  return (
    <span className={`chip ${tone === "neutral" ? "" : tone}`.trim()}>
      <span aria-hidden="true">{ICONS[tone]}</span>
      {label}
    </span>
  );
}

export function EmptyState({
  title,
  children,
  action,
}: {
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="empty">
      <h2>{title}</h2>
      {children && <p>{children}</p>}
      {action}
    </div>
  );
}

export function Loading({ label = "Loading" }: { label?: string }) {
  return (
    <p role="status" aria-live="polite">
      {label}
    </p>
  );
}

export function ErrorNotice({ error, onRetry }: { error: Error; onRetry?: () => void }) {
  const api = error instanceof ApiError ? error : undefined;
  const text = api ? problemMessage(api.code, api.problem.title) : "Something went wrong.";
  return (
    <div role="alert" className="card" style={{ borderColor: "var(--error)" }}>
      <p>
        <strong>{text}</strong>
      </p>
      {api?.problem.trace_id && (
        <p className="help">
          Reference <span className="mono">{api.problem.trace_id}</span>
        </p>
      )}
      {onRetry && <Button onClick={onRetry}>Try again</Button>}
    </div>
  );
}

/** A decision that changes state: says what happens, then asks (T7 dialog). */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  danger,
  onConfirm,
  busy,
  children,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  confirmLabel: string;
  danger?: boolean;
  onConfirm: () => void;
  busy?: boolean;
  children?: ReactNode;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog">
          <Dialog.Title asChild>
            <h2>{title}</h2>
          </Dialog.Title>
          <Dialog.Description>{description}</Dialog.Description>
          {children}
          <div className="dialog-actions">
            <Dialog.Close asChild>
              <button type="button" className="btn">
                Cancel
              </button>
            </Dialog.Close>
            <button
              type="button"
              className={`btn ${danger ? "danger" : "primary"}`}
              disabled={busy}
              onClick={onConfirm}
            >
              {confirmLabel}
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

/** A justification note (T7): the action, the note, its minimum length and a disabled reason. */
export function NoteDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  minLength,
  onConfirm,
  busy,
  danger,
  children,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  confirmLabel: string;
  minLength: number;
  onConfirm: (note: string) => void;
  busy?: boolean;
  danger?: boolean;
  children?: ReactNode;
}) {
  const [note, setNote] = useState("");
  const id = useId();
  const short = note.trim().length < minLength;
  return (
    <Dialog.Root
      open={open}
      onOpenChange={(o) => {
        if (!o) setNote("");
        onOpenChange(o);
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog">
          <Dialog.Title asChild>
            <h2>{title}</h2>
          </Dialog.Title>
          <Dialog.Description>{description}</Dialog.Description>
          {children}
          <div className="field">
            <label htmlFor={id}>Note (required)</label>
            <textarea
              id={id}
              rows={3}
              value={note}
              aria-describedby={`${id}-help`}
              onChange={(e) => setNote(e.target.value)}
            />
            <span id={`${id}-help`} className="help">
              {short
                ? `At least ${minLength} characters, so the history says why. ${note.trim().length} so far.`
                : "The note is kept in the history."}
            </span>
          </div>
          <div className="dialog-actions">
            <Dialog.Close asChild>
              <button type="button" className="btn">
                Cancel
              </button>
            </Dialog.Close>
            <button
              type="button"
              className={`btn ${danger ? "danger" : "primary"}`}
              disabled={busy || short}
              aria-disabled={busy || short}
              onClick={() => {
                if (!short) {
                  onConfirm(note.trim());
                }
              }}
            >
              {confirmLabel}
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

// ---- announcements: one polite live region for results (saved, invited, failed) ---------------

type Announce = (message: string) => void;
const AnnounceContext = createContext<Announce>(() => undefined);

/** How long a result message stays on screen (WCAG 2.2.1: it can also be dismissed). */
export const TOAST_MS = 3000;

export function AnnounceProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState("");
  const timers = useRef<{
    show?: ReturnType<typeof setTimeout>;
    hide?: ReturnType<typeof setTimeout>;
  }>({});
  const clear = useCallback(() => {
    clearTimeout(timers.current.show);
    clearTimeout(timers.current.hide);
    setMessage("");
  }, []);
  const announce = useCallback((m: string) => {
    clearTimeout(timers.current.show);
    clearTimeout(timers.current.hide);
    setMessage("");
    // Re-set on the next tick so the same message is read twice.
    timers.current.show = setTimeout(() => {
      setMessage(m);
      timers.current.hide = setTimeout(() => setMessage(""), TOAST_MS);
    }, 20);
  }, []);
  useEffect(
    () => () => {
      clearTimeout(timers.current.show);
      clearTimeout(timers.current.hide);
    },
    [],
  );
  return (
    <AnnounceContext.Provider value={announce}>
      {children}
      {/* One polite live region: it is also the visible message, at the top under the header. */}
      <div role="status" aria-live="polite" className={message ? "toast" : "visually-hidden"}>
        {message && (
          <>
            <span>{message}</span>
            <button
              type="button"
              className="toast-close"
              onClick={clear}
              aria-label="Dismiss message"
            >
              ×
            </button>
          </>
        )}
      </div>
    </AnnounceContext.Provider>
  );
}

export function useAnnounce(): Announce {
  return useContext(AnnounceContext);
}
