import { Card, Checkbox, Flex, List, Typography } from "antd";
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
      <Typography.Title level={1} style={{ fontSize: 24, marginTop: 0 }}>
        Notifications
      </Typography.Title>
      <Flex gap={16} align="center" style={{ marginBottom: 16 }}>
        <Checkbox checked={unreadOnly} onChange={(e) => setUnreadOnly(e.target.checked)}>
          Only unread
        </Checkbox>
        <Button type="button" onClick={() => void readAll()}>
          Mark all as read
        </Button>
      </Flex>
      {items.length === 0 ? (
        <EmptyState title="Nothing new">
          You are told here when something needs your attention.
        </EmptyState>
      ) : (
        <Card styles={{ body: { padding: 0 } }}>
          <List
            dataSource={items}
            renderItem={(n) => {
              const unread = n.read_at === null || n.read_at === undefined;
              return (
                <List.Item
                  key={n.id}
                  actions={[
                    n.link ? (
                      <Link key="open" to={n.link} onClick={() => void read(n.id)}>
                        Open
                      </Link>
                    ) : null,
                    unread ? (
                      <Button key="read" type="button" onClick={() => void read(n.id)}>
                        Mark as read
                      </Button>
                    ) : null,
                  ].filter(Boolean)}
                >
                  <div>
                    <Typography.Text strong>{n.title}</Typography.Text>{" "}
                    {unread ? <StatusChip label="Unread" tone="info" /> : null}
                    <div>
                      <Typography.Text type="secondary">
                        {n.tender && typeof n.tender === "object" && "name" in n.tender
                          ? String(n.tender.name)
                          : ""}{" "}
                        · {new Date(n.created_at).toLocaleString()}
                      </Typography.Text>
                    </div>
                    {n.body && <div>{n.body}</div>}
                  </div>
                </List.Item>
              );
            }}
          />
        </Card>
      )}
      <Card title="Email" style={{ marginTop: 16 }}>
        <Typography.Paragraph type="secondary">
          By default you are told in the app only. Choose the notifications you also want by email.
        </Typography.Paragraph>
        {prefs.state.status === "ready" && (
          <Flex vertical gap={8}>
            {prefs.state.data.items.map((p) => (
              <Checkbox
                key={p.notification_type}
                checked={p.email_enabled}
                onChange={(e) => void setEmail(p.notification_type, e.target.checked)}
              >
                {p.notification_type.replace(/_/g, " ")}
              </Checkbox>
            ))}
          </Flex>
        )}
      </Card>
    </>
  );
}
