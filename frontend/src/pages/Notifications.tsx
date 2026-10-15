import { useState } from "react";
import { Link } from "react-router";
import {
  Button,
  EmptyState,
  ErrorNotice,
  Loading,
  StatusChip,
  useAnnounce,
} from "../components/ui";
import { unwrap, useApi } from "../lib/api";
import { useAsync } from "../lib/useAsync";
import { usePush } from "../lib/usePush";

/** PG-18 Notification center: a person's own notifications and their email choices (UX-NOT-02). */
export function Notifications() {
  const api = useApi();
  const announce = useAnnounce();
  const [unreadOnly, setUnreadOnly] = useState(false);
  const list = useAsync(
    () =>
      unwrap(api.GET("/notifications", { params: { query: { unread: unreadOnly || undefined } } })),
    [api, unreadOnly],
  );
  const prefs = useAsync(() => unwrap(api.GET("/notification-preferences")), [api]);
  usePush(undefined, list.reload, ["notification.created"]);

  async function read(id: string) {
    await unwrap(
      api.POST("/notifications/{notificationId}/read", {
        params: { path: { notificationId: id } },
      }),
    );
    list.reload();
  }

  async function readAll() {
    await unwrap(api.POST("/notifications/read-all", { params: { query: {} } }));
    announce("All notifications are marked as read");
    list.reload();
  }

  async function setEmail(type: string, enabled: boolean) {
    if (prefs.state.status !== "ready") return;
    const items = prefs.state.data.items.map((p) =>
      p.notification_type === type ? { ...p, email_enabled: enabled } : p,
    );
    await unwrap(api.PUT("/notification-preferences", { body: { items } }));
    announce("Your email choice is saved");
    prefs.reload();
  }

  if (list.state.status === "loading") return <Loading label="Loading notifications" />;
  if (list.state.status === "error")
    return <ErrorNotice error={list.state.error} onRetry={list.reload} />;
  const items = list.state.data.items;
  return (
    <>
      <h1>Notifications</h1>
      <p>
        <label>
          <input
            type="checkbox"
            checked={unreadOnly}
            onChange={(e) => setUnreadOnly(e.target.checked)}
          />{" "}
          Only unread
        </label>{" "}
        <Button type="button" onClick={() => void readAll()}>
          Mark all as read
        </Button>
      </p>
      {items.length === 0 ? (
        <EmptyState title="Nothing new">
          You are told here when something needs your attention.
        </EmptyState>
      ) : (
        <ul className="notification-list">
          {items.map((n) => (
            <li key={n.id}>
              <strong>{n.title}</strong>{" "}
              {n.read_at === null || n.read_at === undefined ? (
                <StatusChip label="Unread" tone="info" />
              ) : null}
              <div className="help">
                {n.tender && typeof n.tender === "object" && "name" in n.tender
                  ? String(n.tender.name)
                  : ""}{" "}
                · {new Date(n.created_at).toLocaleString()}
              </div>
              {n.body && <p>{n.body}</p>}
              <p>
                {n.link && (
                  <Link to={n.link} onClick={() => void read(n.id)}>
                    Open
                  </Link>
                )}{" "}
                {(n.read_at === null || n.read_at === undefined) && (
                  <button type="button" className="link" onClick={() => void read(n.id)}>
                    Mark as read
                  </button>
                )}
              </p>
            </li>
          ))}
        </ul>
      )}
      <h2>Email</h2>
      <p className="help">
        By default you are told in the app only. Choose the notifications you also want by email.
      </p>
      {prefs.state.status === "ready" && (
        <ul className="plain">
          {prefs.state.data.items.map((p) => (
            <li key={p.notification_type}>
              <label>
                <input
                  type="checkbox"
                  checked={p.email_enabled}
                  onChange={(e) => void setEmail(p.notification_type, e.target.checked)}
                />{" "}
                {p.notification_type.replace(/_/g, " ")}
              </label>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
