import {
  CheckCircleFilled,
  CloseCircleFilled,
  ExclamationCircleFilled,
  InfoCircleFilled,
  MinusCircleOutlined,
  StarFilled,
} from "@ant-design/icons";
import { Alert, Button as AntButton, Card, Input, Modal, Spin, Tag, Typography } from "antd";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type HTMLAttributes,
  type ReactNode,
} from "react";
import { useNavigate, type LinkProps } from "react-router";
import type { Tone } from "../lib/labels";
import { ApiError } from "../lib/api";
import { problemMessage } from "../lib/labels";

/** A button that stays focusable when it cannot be used, and says why (UX-ROL-03). */
export function Button({
  variant,
  disabledReason,
  children,
  ...rest
}: Omit<ButtonHTMLAttributes<HTMLButtonElement>, "color"> & {
  variant?: "primary" | "danger";
  disabledReason?: string;
  loading?: boolean;
}) {
  const reasonId = useId();
  const blocked = Boolean(disabledReason);
  const inert = blocked || Boolean(rest.disabled);
  const { disabled, type, onClick, ...other } = rest;
  return (
    <span>
      <AntButton
        {...other}
        htmlType={type ?? "button"}
        disabled={disabled}
        type={variant === "primary" ? "primary" : "default"}
        danger={variant === "danger"}
        aria-disabled={inert ? true : undefined}
        aria-describedby={blocked ? reasonId : rest["aria-describedby"]}
        onClick={inert ? (e) => e.preventDefault() : onClick}
        style={inert ? { opacity: 0.65, cursor: "not-allowed", ...rest.style } : rest.style}
      >
        {children}
      </AntButton>
      {blocked && (
        <Typography.Text id={reasonId} type="secondary" style={{ marginInlineStart: 8 }}>
          {disabledReason}
        </Typography.Text>
      )}
    </span>
  );
}

/** A link that looks like a button. It is a real anchor, so it opens in a new tab when asked to. */
export function LinkButton({
  variant,
  to,
  children,
  state,
  replace,
}: Pick<LinkProps, "to" | "state" | "replace" | "children"> & { variant?: "primary" }) {
  const navigate = useNavigate();
  const href = typeof to === "string" ? to : `${to.pathname ?? ""}${to.search ?? ""}`;
  return (
    <AntButton
      href={href}
      type={variant === "primary" ? "primary" : "default"}
      onClick={(e) => {
        if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
        e.preventDefault();
        void navigate(to, { state, replace });
      }}
    >
      {children}
    </AntButton>
  );
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
    <div style={{ marginBottom: 16 }}>
      <label htmlFor={id} style={{ display: "block", fontWeight: 500, marginBottom: 4 }}>
        {label}
      </label>
      {children({ id, describedBy, invalid: Boolean(error) })}
      {help && (
        <Typography.Text id={helpId} type="secondary" style={{ display: "block", fontSize: 12 }}>
          {help}
        </Typography.Text>
      )}
      {error && (
        <Typography.Text id={errorId} type="danger" style={{ display: "block", fontSize: 12 }}>
          {error}
        </Typography.Text>
      )}
    </div>
  );
}

const TONES: Record<Tone, { color: string; icon: ReactNode }> = {
  ai: { color: "purple", icon: <StarFilled /> },
  neutral: { color: "default", icon: <MinusCircleOutlined /> },
  success: { color: "success", icon: <CheckCircleFilled /> },
  warning: { color: "warning", icon: <ExclamationCircleFilled /> },
  error: { color: "error", icon: <CloseCircleFilled /> },
  info: { color: "processing", icon: <InfoCircleFilled /> },
};

/** Colour never carries the meaning alone: an icon and the label always accompany it. */
export function StatusChip({ label, tone = "neutral" }: { label: string; tone?: Tone }) {
  const t = TONES[tone];
  return (
    <Tag
      color={t.color}
      icon={
        <span aria-hidden="true" style={{ marginInlineEnd: 2 }}>
          {t.icon}
        </span>
      }
      style={{ margin: 0 }}
    >
      {label}
    </Tag>
  );
}

/** A titled card: the one container for the groups of a page (title is a level 3 heading). */
export function Section({
  title,
  extra,
  children,
}: {
  title: ReactNode;
  extra?: ReactNode;
  children: ReactNode;
}) {
  return (
    <Card
      style={{ marginBottom: 16 }}
      extra={extra}
      title={
        <Typography.Title level={3} style={{ fontSize: 16, margin: 0 }}>
          {title}
        </Typography.Title>
      }
    >
      {children}
    </Card>
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
    <Card style={{ textAlign: "center" }}>
      <Typography.Title level={2} style={{ fontSize: 18 }}>
        {title}
      </Typography.Title>
      {children && <Typography.Paragraph type="secondary">{children}</Typography.Paragraph>}
      {action}
    </Card>
  );
}

export function Loading({ label = "Loading" }: { label?: string }) {
  return (
    <div role="status" aria-live="polite" style={{ padding: 16 }}>
      <Spin size="small" /> <span style={{ marginInlineStart: 8 }}>{label}</span>
    </div>
  );
}

export function ErrorNotice({ error, onRetry }: { error: Error; onRetry?: () => void }) {
  const api = error instanceof ApiError ? error : undefined;
  const text = api ? problemMessage(api.code, api.problem.title) : "Something went wrong.";
  return (
    <Alert
      type="error"
      showIcon
      role="alert"
      title={text}
      description={
        api?.problem.trace_id ? (
          <>
            Reference <Typography.Text code>{api.problem.trace_id}</Typography.Text>
          </>
        ) : undefined
      }
      action={onRetry ? <Button onClick={onRetry}>Try again</Button> : undefined}
    />
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
    <Modal
      open={open}
      title={title}
      destroyOnHidden
      onCancel={() => onOpenChange(false)}
      cancelText="Cancel"
      okText={confirmLabel}
      okButtonProps={{ danger, disabled: busy }}
      onOk={onConfirm}
    >
      <Typography.Paragraph>{description}</Typography.Paragraph>
      {children}
    </Modal>
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
    <Modal
      open={open}
      title={title}
      destroyOnHidden
      afterClose={() => setNote("")}
      onCancel={() => onOpenChange(false)}
      cancelText="Cancel"
      okText={confirmLabel}
      okButtonProps={{
        danger,
        disabled: busy || short,
        "aria-disabled": busy || short,
      }}
      onOk={() => {
        if (!short) {
          onConfirm(note.trim());
        }
      }}
    >
      <Typography.Paragraph>{description}</Typography.Paragraph>
      {children}
      <label htmlFor={id} style={{ display: "block", fontWeight: 500, marginBottom: 4 }}>
        Note (required)
      </label>
      <Input.TextArea
        id={id}
        rows={3}
        value={note}
        aria-describedby={`${id}-help`}
        onChange={(e) => setNote(e.target.value)}
      />
      <Typography.Text id={`${id}-help`} type="secondary" style={{ fontSize: 12 }}>
        {short
          ? `At least ${minLength} characters, so the history says why. ${note.trim().length} so far.`
          : "The note is kept in the history."}
      </Typography.Text>
    </Modal>
  );
}

/**
 * Row headers for antd tables: a column whose `onCell` is `rowHeader` renders `<th scope="row">`,
 * so assistive technology reads the row's subject before its values. Pass `rowHeaderComponents`
 * to the table's `components`.
 */
export const rowHeader = () => ({ "data-row-header": true }) as object;

function RowHeaderCell(props: HTMLAttributes<HTMLElement> & { "data-row-header"?: boolean }) {
  const { "data-row-header": isHeader, ...rest } = props;
  return isHeader ? <th {...rest} scope="row" /> : <td {...rest} />;
}
export const rowHeaderComponents = { body: { cell: RowHeaderCell } };

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
      <div
        role="status"
        aria-live="polite"
        style={
          message
            ? { position: "fixed", top: 64, insetInlineEnd: 24, zIndex: 2000, maxWidth: 420 }
            : {
                position: "absolute",
                width: 1,
                height: 1,
                overflow: "hidden",
                clip: "rect(0 0 0 0)",
              }
        }
      >
        {message && (
          <Alert
            role="none"
            type="success"
            showIcon
            closable={{ closeIcon: true, "aria-label": "Dismiss message" }}
            onClose={clear}
            title={message}
          />
        )}
      </div>
    </AnnounceContext.Provider>
  );
}

export function useAnnounce(): Announce {
  return useContext(AnnounceContext);
}
